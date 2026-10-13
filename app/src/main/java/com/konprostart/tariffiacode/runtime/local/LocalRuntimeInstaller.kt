package com.konprostart.tariffiacode.runtime.local

import android.content.Context
import android.system.Os
import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.runtime.LocalAgent
import com.konprostart.tariffiacode.runtime.ProprietaryAgents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File

class LocalRuntimeInstaller(
    private val context: Context,
    private val runtimeDirectory: File,
    private val abi: String,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val manifestReader: LocalRuntimeManifestReader = LocalRuntimeManifestReader(context),
    private val downloader: VerifiedRuntimeDownloader = VerifiedRuntimeDownloader(httpClient),
    private val accessCoordinator: LocalRuntimeAccessCoordinator = LocalRuntimeAccessCoordinator(),
    /** Guest capabilities written into a rootfs whatever agents are installed - see [GuestCapabilities]. */
    private val guestCapabilities: GuestCapabilities = GuestCapabilities(context),
    /**
     * Which installers run, and in which order.
     *
     * Built here rather than from a shared default so there is exactly one production registry.
     */
    private val installers: AgentInstallerRegistry =
        AgentInstallerRegistry(
            listOf(
                OpenCodeAgentInstaller(),
                ClaudeCodeAgentInstaller(guestCapabilities),
                CodexAgentInstaller(),
                AntigravityAgentInstaller(context, guestCapabilities),
            ),
        ),
) {
    /**
     * Where Antigravity's userland lives inside [environment], or null when it has none.
     *
     * Derived from the agent's own id, so the path cannot drift from the one the installer created.
     */
    private fun antigravityRootfs(environment: File): File =
        AgentInstallStep.userlandRootFor(environment, LocalAgent.ANTIGRAVITY) ?: File(environment, "antigravity-rootfs")

    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    data class InstalledRuntime(
        val metadata: LocalRuntimeMetadata,
        val commandSuite: EmbeddedCommandSuite.Paths,
        val rootfs: File,
        /** Null when the sandbox was provisioned without the OpenCode agent. */
        val openCode: File?,
        /** Debian Bookworm rootfs for the glibc-linked official Antigravity binary. */
        val antigravityRootfs: File? = null,
    )

    /**
     * Provisions the shared Alpine sandbox and the requested [agents].
     *
     * Agents already recorded in the current install are carried over, so adding one never silently
     * removes another. The OpenCode binary — by far the largest download — is fetched only when
     * OpenCode is actually among the resulting agents.
     */
    suspend fun install(
        agents: Set<LocalAgent> = setOf(LocalAgent.OPEN_CODE),
        installFullDevelopmentTools: Boolean = false,
        /**
         * Progress, the step to show, and which agent that step belongs to - null for the shared
         * Alpine environment every agent runs in. One install provisions the whole selection, so
         * without the third argument the setup guide attributed every step to OpenCode and showed
         * "Installing Claude Code" underneath the OpenCode heading.
         */
        onProgress: (Float?, String, LocalAgent?) -> Unit = { _, _, _ -> },
    ): InstalledRuntime =
        withContext(Dispatchers.IO) {
            // The shared environment's own steps, which belong to no single agent.
            val onShared: (Float?, String) -> Unit = { progress, step -> onProgress(progress, step, null) }
            runtimeDirectory.mkdirs()
            onShared(0.02f, context.getString(R.string.install_step_preparing_command_env))
            val existingMetadata = installedMetadata()
            // Proprietary agents are dropped here when the build disables them (F-Droid), so no
            // download step below can ever be reached for them. In every other build this is the
            // unchanged selection-plus-carry-over set.
            val requestedAgents =
                ProprietaryAgents.requestedForInstall(
                    selected = agents,
                    existing = existingMetadata?.let { metadata -> LocalAgent.entries.filter(metadata::has) } ?: emptyList(),
                )
            // Runtimes created before this option existed already contain the full toolchain, and
            // adding another agent must not silently remove it by rebuilding a smaller rootfs.
            val includeFullDevelopmentTools =
                installFullDevelopmentTools || existingMetadata?.fullDevelopmentToolsInstalled == true
            require(requestedAgents.isNotEmpty()) { "At least one agent must be selected" }
            val commandSuite = EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
            val manifest = manifestReader.read()
            val architecture = manifest.architecture(abi)
            val cache = File(runtimeDirectory, "cache").apply { mkdirs() }
            val staging = File(runtimeDirectory, "environment.staging")
            val active = File(runtimeDirectory, "environment")
            val rollback = File(runtimeDirectory, "environment.rollback")
            accessCoordinator.write {
                recoverInterruptedRuntimeEnvironment(
                    active = active,
                    rollback = rollback,
                    topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
                )
            }
            staging.deleteRecursively()
            staging.mkdirs()

            try {
                val alpineArchive = File(cache, "alpine-${manifest.alpineVersion}-$abi.tar.gz")
                downloadVerified(
                    downloader = downloader,
                    url = architecture.alpineUrl,
                    destination = alpineArchive,
                    expectedSha256 = architecture.alpineSha256,
                    startProgress = 0.05f,
                    endProgress = 0.22f,
                    label = context.getString(R.string.install_step_downloading_alpine),
                    onProgress = { progress, step -> onShared(progress, step) },
                )
                val withOpenCode = LocalAgent.OPEN_CODE in requestedAgents
                val selected = installers.forAgents(requestedAgents)
                val steps =
                    selected.associate { installer ->
                        installer.agent to
                            AgentInstallStep(
                                agent = installer.agent,
                                label = { resource -> context.getString(resource) },
                                runtimeDirectory = runtimeDirectory,
                                stagingDirectory = staging,
                                stagingRootfs = File(staging, "rootfs"),
                                abi = abi,
                                commandSuite = commandSuite,
                                accessCoordinator = accessCoordinator,
                                httpClient = httpClient,
                                downloader = downloader,
                                cache = cache,
                                manifest = manifest,
                                includeFullDevelopmentTools = includeFullDevelopmentTools,
                                onProgress = { progress, step -> onProgress(progress, step, installer.agent) },
                                onSharedProgress = onShared,
                            )
                    }
                val rootfs = File(staging, "rootfs").apply { mkdirs() }
                provisionAgents(
                    installers = selected,
                    steps = steps,
                    afterDownloads = {
                        onShared(0.75f, context.getString(R.string.install_step_extracting_linux_env))
                        alpineArchive.inputStream().use { RuntimeArchive.extractTarGz(it, rootfs) }
                        // Checked here, before the provision phase, which can pull a whole second
                        // rootfs down: a missing launcher must not cost the user that download
                        // before the install is told it cannot possibly finish.
                        require(commandSuite.proot.isFile) { "PRoot launcher is unavailable" }
                    },
                    afterProvision = {
                        configureRootfs(rootfs, commandSuite)
                        // Credentials and agent config live under /root inside the rootfs. Activation swaps
                        // the whole environment directory, so without this the user is signed out of every
                        // agent whenever another one is added or the runtime is reinstalled.
                        carryOverHomeDirectory(File(active, "rootfs"), rootfs)
                        ensureTariffiaCodeAgentContext(rootfs, context)
                        onShared(
                            0.91f,
                            context.getString(
                                if (includeFullDevelopmentTools) {
                                    R.string.install_step_installing_dev_tools
                                } else {
                                    R.string.install_step_installing_runtime_tools
                                },
                            ),
                        )
                        installPackages(
                            rootfs = rootfs,
                            suite = commandSuite,
                            packages =
                                if (includeFullDevelopmentTools) {
                                    REQUIRED_RUNTIME_PACKAGES + OPTIONAL_DEVELOPMENT_PACKAGES
                                } else {
                                    REQUIRED_RUNTIME_PACKAGES
                                },
                        )
                    },
                )
                // Which agents provisioned a userland of their own, named by the agent rather than by a literal
                // path, so a second such agent has somewhere to go without another edit here.
                val userlandAgents = selected.filter { AgentInstallStep.userlandRootFor(staging, it.agent) != null }
                val antigravityRootfs = steps[LocalAgent.ANTIGRAVITY]?.takeIf { it in userlandAgents }?.installRoot

                val metadata =
                    LocalRuntimeMetadata(
                        version = if (withOpenCode) manifest.openCodeVersion else "",
                        port = manifest.port,
                        installedAt = System.currentTimeMillis(),
                        runtimeVersion = manifest.runtimeVersion,
                        abi = abi,
                        components = requestedAgents.map(LocalAgent::id).toSet(),
                        fullDevelopmentToolsInstalled = includeFullDevelopmentTools,
                        fullDebianDevelopmentToolsInstalled = includeFullDevelopmentTools && antigravityRootfs != null,
                    )
                File(staging, METADATA_FILE).writeText(json.encodeToString(metadata))
                onShared(0.96f, context.getString(R.string.install_step_activating_runtime))
                accessCoordinator.write {
                    activateRuntimeEnvironment(
                        active = active,
                        staging = staging,
                        rollback = rollback,
                        finalizeActivation = { activated ->
                            replaceFileAtomically(
                                source = File(activated, METADATA_FILE),
                                destination = File(runtimeDirectory, METADATA_FILE),
                            )
                        },
                    )
                }
                onShared(1f, context.getString(R.string.install_step_done))
                val activeRootfs = File(active, "rootfs")
                InstalledRuntime(
                    metadata,
                    commandSuite,
                    activeRootfs,
                    // Where each agent's executables ended up, from what the descriptor declares,
                    // rather than from which branch installed them.
                    if (withOpenCode) {
                        val binary = LocalAgent.OPEN_CODE.descriptor.binaryPaths.first()
                        // Explicitly relative: File(parent, child) concatenates, but that is a
                        // coincidence of the JDK rather than something to rely on for an absolute path.
                        File(activeRootfs, binary.removePrefix("/"))
                    } else {
                        null
                    },
                    AgentInstallStep
                        .userlandRootFor(active, LocalAgent.ANTIGRAVITY)
                        ?.takeIf { LocalAgent.ANTIGRAVITY in requestedAgents && it.isDirectory },
                )
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }

    fun recoverInterruptedActivation(): Boolean =
        accessCoordinator.write {
            recoverInterruptedRuntimeEnvironment(
                active = File(runtimeDirectory, "environment"),
                rollback = File(runtimeDirectory, "environment.rollback"),
                topLevelMetadata = File(runtimeDirectory, METADATA_FILE),
            )
        }

    /**
     * The provisioned sandbox, or null when no usable Linux environment exists yet.
     *
     * The OpenCode binary is no longer part of the liveness check: a Claude Code-only sandbox is a
     * complete, usable runtime and callers that need OpenCode check [InstalledRuntime.openCode].
     */
    fun installedRuntime(): InstalledRuntime? =
        accessCoordinator.write {
            val active = File(runtimeDirectory, "environment")
            normalizeRuntimeSymlinks(active)
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val rootfs = File(active, "rootfs")
            if (!metadataFile.isFile || !rootfs.isDirectory) return@write null
            val metadata =
                runCatching {
                    json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
                }.getOrNull() ?: return@write null
            val commandSuite =
                runCatching {
                    EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
                }.getOrNull() ?: return@write null
            InstalledRuntime(
                metadata,
                commandSuite,
                rootfs,
                File(rootfs, "usr/local/bin/opencode").takeIf { it.isFile },
                antigravityRootfs(active).takeIf { metadata.has(LocalAgent.ANTIGRAVITY) && it.isDirectory },
            )
        }?.also { installed ->
            ensureTariffiaCodeAgentContext(installed.rootfs, context)
            installed.antigravityRootfs?.let { ensureTariffiaCodeAgentContext(it, context) }
        }

    /** Metadata of the active install, without requiring the command suite to be extractable. */
    fun installedMetadata(): LocalRuntimeMetadata? =
        accessCoordinator.read {
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            if (!metadataFile.isFile) return@read null
            runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
        }

    /**
     * Realigns the persisted runtime port (`metadata.json`) with the port declared by the bundled
     * manifest, without rebuilding the runtime or touching the rootfs, workspace, sessions, or auth
     * state. Cheap and idempotent; callers run it before any runtime start so a stale port left by
     * an earlier build can never launch or connect. See [RuntimeMetadataPortMigration].
     */
    fun reconcilePersistedPortWithManifest() {
        accessCoordinator.write {
            val manifestPort = runCatching { manifestReader.read().port }.getOrNull() ?: return@write
            RuntimeMetadataPortMigration.reconcile(File(runtimeDirectory, METADATA_FILE), manifestPort)
        }
    }

    /** Records [agent] as provisioned, so a later reinstall keeps it. */
    fun recordAgent(agent: LocalAgent) {
        accessCoordinator.write {
            val metadataFile = File(runtimeDirectory, METADATA_FILE)
            val metadata =
                runCatching { json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText()) }.getOrNull()
                    ?: return@write
            metadataFile.writeText(json.encodeToString(metadata.with(agent)))
        }
    }

    /** Installs the optional toolchain into the active sandbox without rebuilding the runtime. */
    suspend fun installFullDevelopmentTools(onProgress: (Float?, String, LocalAgent?) -> Unit = { _, _, _ -> }): LocalRuntimeMetadata =
        withContext(Dispatchers.IO) {
            accessCoordinator.write {
                val active = File(runtimeDirectory, "environment")
                val rootfs = File(active, "rootfs")
                val metadataFile = File(runtimeDirectory, METADATA_FILE)
                require(rootfs.isDirectory && metadataFile.isFile) {
                    "The Linux environment is not installed"
                }
                val metadata =
                    json.decodeFromString<LocalRuntimeMetadata>(metadataFile.readText())
                if (metadata.hasFullDevelopmentTools()) return@write metadata

                val suite = EmbeddedCommandSuite(context, runtimeDirectory, abi).ensureInstalled()
                onProgress(null, context.getString(R.string.install_step_installing_dev_tools), null)
                if (!metadata.fullDevelopmentToolsInstalled) installPackages(rootfs, suite, OPTIONAL_DEVELOPMENT_PACKAGES)
                if (metadata.has(LocalAgent.ANTIGRAVITY) && !metadata.fullDebianDevelopmentToolsInstalled) {
                    val antigravityRootfs = antigravityRootfs(active)
                    require(antigravityRootfs.isDirectory) { "The Antigravity Linux environment is not installed" }
                    DebianRootfsInstaller(runtimeDirectory, abi, downloader, httpClient, suite)
                        .installFullDevelopmentTools(antigravityRootfs)
                }

                val updated =
                    metadata.copy(
                        fullDevelopmentToolsInstalled = true,
                        fullDebianDevelopmentToolsInstalled = metadata.has(LocalAgent.ANTIGRAVITY),
                    )
                val encoded = json.encodeToString(updated)
                File(active, METADATA_FILE).writeText(encoded)
                replaceFileAtomically(File(active, METADATA_FILE), metadataFile)
                onProgress(1f, context.getString(R.string.install_step_done), null)
                updated
            }
        }

    fun bundledOpenCodeVersion(): String = manifestReader.read().openCodeVersion

    /**
     * Brings the active sandbox's Antigravity up to the newest official release.
     *
     * The release is resolved at update time — GitHub's `latest`, with this build's pin only as the
     * fallback when the lookup fails — so users no longer wait for an app release to receive new
     * Antigravity versions. When the guest already runs the resolved release (or something newer,
     * which a pin-degraded lookup can report) the ~50 MB download is skipped. Going through
     * [install] for any of this would provision a whole new environment directory to replace one
     * file; [AntigravityInstaller] swaps `agy` in place instead, leaving the Debian rootfs and
     * every agent's credentials untouched.
     *
     * Not serialised against [install]: that one builds a staging directory and swaps it in, so the
     * worst a concurrent run can do is discard this binary, which the next update reinstates. The
     * controller that owns both actions runs one at a time.
     */
    suspend fun updateAntigravity(onProgress: (Float) -> Unit = {}): String {
        require(ProprietaryAgents.enabled) { "Antigravity is not available in this build" }
        val runtime = installedRuntime() ?: error("The Linux environment is not installed")
        val rootfs = runtime.antigravityRootfs ?: runtime.rootfs
        val release = resolveAntigravityRelease(abi, httpClient)
        val installed = AntigravityInstaller.installedVersion(rootfs)
        if (!shouldReplaceInstalledAntigravity(installed, release.version)) return installed!!
        return AntigravityInstaller(runtimeDirectory, downloader).installInto(rootfs, onProgress, release)
    }

    private fun carryOverHomeDirectory(
        currentRootfs: File,
        stagingRootfs: File,
    ) {
        val currentHome = File(currentRootfs, "root")
        if (!currentHome.isDirectory) return
        runCatching { currentHome.copyRecursively(File(stagingRootfs, "root"), overwrite = true) }
    }

    private fun installPackages(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
        packages: List<String>,
    ) {
        val prootTmp = File(runtimeDirectory, "proot-tmp").apply { mkdirs() }
        val apkCache = File(runtimeDirectory, "cache/apk").apply { mkdirs() }
        File(rootfs, "var/cache/apk").mkdirs()
        val command =
            listOf(
                suite.proot.absolutePath,
                "--kill-on-exit",
                "--link2symlink",
                "-0",
                "-r",
                rootfs.absolutePath,
                "-b",
                "/dev",
                "-b",
                "/proc",
                "-b",
                "/sys",
                "-b",
                "/system",
                "-b",
                "${apkCache.absolutePath}:/var/cache/apk",
                "-w",
                "/root",
                "/bin/sh",
                "-lc",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin " +
                    "/sbin/apk --cache-dir /var/cache/apk add ${packages.joinToString(" ")} && " +
                    "/usr/sbin/update-ca-certificates",
            )
        val installLog =
            File(runtimeDirectory, "logs/tool-install.log").apply {
                parentFile?.mkdirs()
                delete()
            }
        val process =
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(installLog))
                .apply {
                    environment().putAll(suite.environment())
                    environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                }
                .start()
        val completed = process.waitFor(15, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) {
            process.destroyForcibly()
            error("Development tool installation timed out. $PACKAGE_INSTALL_RETRY_HINT")
        }
        require(process.exitValue() == 0) {
            // The hint sits between the headline and the raw log, or a 4000-character tail scrolls
            // it off the screen the error is read on.
            "Unable to install runtime packages. $PACKAGE_INSTALL_RETRY_HINT\n\n" +
                "Last log lines:\n${installLog.readText().takeLast(4000)}"
        }
    }

    private fun configureRootfs(
        rootfs: File,
        suite: EmbeddedCommandSuite.Paths,
    ) {
        File(rootfs, "root").mkdirs()
        File(rootfs, "tmp").apply {
            mkdirs()
            setWritable(true, false)
            setExecutable(true, false)
        }
        File(rootfs, "workspace").mkdirs()
        File(rootfs, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
        File(rootfs, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost\n")
        File(rootfs, "etc/profile.d/android-code.sh").apply {
            parentFile?.mkdirs()
            writeText(
                "export HOME=/root\n" +
                    "export TMPDIR=/tmp\n" +
                    "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/system/bin:/system/xbin\n" +
                    "export JAVA_HOME=/usr/lib/jvm/java-17-openjdk\n" +
                    "export OPENCODE_CONFIG_DIR=/root/.config/opencode\n",
            )
        }
        File(rootfs, "root/.config/opencode").mkdirs()
        File(rootfs, "root/.local/share/opencode").mkdirs()
        // The compact Alpine archive intentionally omits a few SONAME symlinks. apk loads
        // libapk.so.3 by SONAME, so restore the link before installing agent dependencies.
        val libApk = File(rootfs, "usr/lib/libapk.so.3")
        if (!libApk.exists() && File(rootfs, "usr/lib/libapk.so.3.0.0").isFile) {
            Os.symlink("libapk.so.3.0.0", libApk.absolutePath)
        }
        guestCapabilities.installHelperScripts(rootfs)
        guestCapabilities.provisionBrowserMcp(rootfs)
        guestCapabilities.provisionScheduleMcp(rootfs)
        require(suite.proot.isFile) { "PRoot launcher is unavailable" }
    }

    /**
     * Re-seeds the guest MCP servers (browser + schedule) and agent registrations on runtimes that
     * were installed before the provisioning existed. Idempotent and safe to call on every start.
     */
    fun provisionGuestCapabilitiesForExistingInstall() {
        val active = File(runtimeDirectory, "environment")
        listOf(File(active, "rootfs"), antigravityRootfs(active))
            .filter(File::isDirectory)
            .forEach { rootfs ->
                guestCapabilities.installHelperScripts(rootfs)
                guestCapabilities.provisionBrowserMcp(rootfs)
                guestCapabilities.provisionScheduleMcp(rootfs)
                guestCapabilities.installClaudePermissionHook(rootfs)
            }
    }

    internal companion object {
        private const val METADATA_FILE = "metadata.json"

        /** Required by OpenCode and TariffiaCode's built-in Git, MCP, and Android-device features. */
        val REQUIRED_RUNTIME_PACKAGES =
            listOf(
                "bash",
                "git",
                "curl",
                "wget",
                "jq",
                "openssh-client",
                "ripgrep",
                "ca-certificates",
                "libstdc++",
                "android-tools",
                "python3",
                "py3-pillow",
            )

        /** Project-specific compilers, language SDKs, editors, and convenience utilities. */
        val OPTIONAL_DEVELOPMENT_PACKAGES =
            listOf(
                "tree",
                "file",
                "less",
                "nano",
                "vim",
                "github-cli",
                "openjdk17",
                "gradle",
                "py3-pip",
                "nodejs",
                "npm",
                "make",
                "cmake",
                "gcc",
                "g++",
                "musl-dev",
                "pkgconf",
                "patch",
                "zip",
                "unzip",
                "sqlite",
                "go",
                "gcompat",
                "util-linux",
            )
    }
}
