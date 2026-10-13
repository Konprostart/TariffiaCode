package com.konprostart.tariffiacode.runtime.local

import android.content.Context
import com.konprostart.tariffiacode.runtime.AgentUserland
import com.konprostart.tariffiacode.runtime.LocalAgent
import java.io.File

/**
 * One agent's contribution to provisioning the sandbox.
 *
 * A provisioning pass runs three phases, and an agent takes part in whichever ones it needs:
 *
 *  1. [download] - before the Alpine rootfs is unpacked. An agent that fetches an archive it will
 *     unpack later stores it here.
 *  2. [provision] - once the rootfs exists and before the shared packages are installed. This is
 *     where an agent that needs a userland of its own builds it, and where one that ships a binary
 *     inside the rootfs puts it there.
 *  3. [install] - after the shared packages, in the registry's declared order. This is where the
 *     package-manager-driven agents (apk, npm) run, because they need those packages.
 *
 * The phases exist because that is the order the work genuinely has, not because of how the four
 * agents happen to divide today. An agent provisioned by pip or npm drops into phase 3 and needs no
 * new phase; one that needs glibc sets [AgentUserland] and gets its own root in phase 2.
 *
 * Implementations are registered per agent rather than chosen by a `when`, so adding one is a new
 * file and one registry entry instead of an edit inside a several-hundred-line installer.
 */
interface AgentInstaller {
    val agent: LocalAgent

    /** Fetches anything that must be on the device before the rootfs is unpacked. */
    suspend fun download(step: AgentInstallStep): Unit = Unit

    /** Acts on the rootfs before the shared packages go in. */
    suspend fun provision(step: AgentInstallStep): Unit = Unit

    /** Puts the agent in place, after the shared packages. */
    suspend fun install(step: AgentInstallStep): Unit = Unit
}

/**
 * What an [AgentInstaller] is given, for one agent, during one provisioning pass.
 *
 * [onProgress] is already bound to the agent, so an implementation cannot forget to attribute a
 * step to itself - which is how the setup guide ended up showing "Installing Claude Code" under an
 * OpenCode heading.
 */
class AgentInstallStep(
    val agent: LocalAgent,
    /**
     * Resolves a string resource to the user's language.
     *
     * Deliberately not a [android.content.Context]: an installer needs its own labels, nothing
     * more, and holding a Context here would make every phase untestable without a device.
     */
    val label: (Int) -> String,
    val runtimeDirectory: File,
    /** The staging environment; activation swaps it into place once every agent is done. */
    val stagingDirectory: File,
    /** The shared Alpine rootfs inside the staging environment. */
    val stagingRootfs: File,
    val abi: String,
    val commandSuite: EmbeddedCommandSuite.Paths,
    val accessCoordinator: LocalRuntimeAccessCoordinator,
    val httpClient: OkHttpClient,
    val downloader: VerifiedRuntimeDownloader,
    val cache: File,
    val manifest: LocalRuntimeManifest,
    val includeFullDevelopmentTools: Boolean,
    /** Reports a step as belonging to [agent]. */
    val onProgress: (Float, String) -> Unit,
    /**
     * Reports a step as belonging to no agent in particular.
     *
     * OpenCode is the case that needs this: its download and unpack are reported as part of
     * building the shared environment rather than as "installing OpenCode", because that is how the
     * setup guide has always presented them - the sandbox is built once and OpenCode is inside it.
     */
    val onSharedProgress: (Float, String) -> Unit,
) {
    /**
     * The rootfs this agent installs into.
     *
     * The shared Alpine rootfs, unless the agent declared a userland of its own and [provision] has
     * built it - see [userlandRootFor].
     */
    var installRoot: File = stagingRootfs
        private set

    /** Scratch space for this agent inside the staging environment, removed on failure with it. */
    val workDirectory: File get() = File(stagingDirectory, "${agent.id}-work")

    /** Records the rootfs [provision] built for an agent with its own userland. */
    fun useUserlandRoot(root: File) {
        installRoot = root
    }

    /** Remembers an artefact from one phase for a later one, e.g. an archive to unpack in [AgentInstaller.provision]. */
    private val artifacts = mutableMapOf<String, Any>()

    fun <T : Any> put(
        key: String,
        value: T,
    ) {
        artifacts[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? = artifacts[key] as? T

    companion object {
        /**
         * Where the agent with a userland of its own builds it, or null when it uses the shared one.
         *
         * Named after the agent, so the path an agent gets is a function of the agent rather than of
         * which branch happened to create it.
         */
        fun userlandRootFor(
            stagingDirectory: File,
            agent: LocalAgent,
        ): File? =
            when (agent.descriptor.userland) {
                AgentUserland.ALPINE_STAGING_ROOTFS -> null
                AgentUserland.DEBIAN_ROOTFS -> File(stagingDirectory, "${agent.id}-rootfs")
            }
    }
}

/**
 * The installers to run, in the order their install phase runs.
 *
 * Order matters and is therefore declared here rather than implied by declaration order: the setup
 * guide shows one panel per agent, and an install that reported its steps in a different order would
 * look like it was going backwards.
 */
class AgentInstallerRegistry(
    private val installers: List<AgentInstaller>,
) {
    init {
        val duplicates = installers.groupBy { it.agent }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Two installers registered for $duplicates" }
    }

    /** Installers for [agents], in registry order. Agents with no installer are simply absent. */
    fun forAgents(agents: Collection<LocalAgent>): List<AgentInstaller> = installers.filter { it.agent in agents }

    /** Every agent that has an installer registered, in registry order. */
    fun agents(): List<LocalAgent> = installers.map { it.agent }
}

/**
 * Downloads [url] to [destination], verifying it against [expectedSha256], reporting progress in
 * the half-open range [startProgress] to [endProgress].
 *
 * A cached file that no longer verifies is deleted and fetched again rather than trusted.
 */
internal suspend fun downloadVerified(
    downloader: VerifiedRuntimeDownloader,
    url: String,
    destination: File,
    expectedSha256: String,
    startProgress: Float,
    endProgress: Float,
    label: String,
    onProgress: (Float?, String) -> Unit,
) {
    if (destination.isFile) {
        runCatching { RuntimeArchive.verifySha256(destination, expectedSha256) }
            .onSuccess {
                onProgress(endProgress, label)
                return
            }
        destination.delete()
    }
    downloader.download(
        url = url,
        destination = destination,
        expectedSha256 = expectedSha256,
        onProgress = { fraction ->
            onProgress(
                fraction?.let {
                    startProgress + (endProgress - startProgress) * it.coerceIn(0f, 1f)
                },
                label,
            )
        },
    )
    onProgress(endProgress, label)
}

/** [downloadVerified] for a step, reporting through [onProgress] which defaults to the step's agent. */
internal suspend fun downloadRuntimeFile(
    step: AgentInstallStep,
    url: String,
    destination: File,
    expectedSha256: String,
    startProgress: Float,
    endProgress: Float,
    label: String,
    onProgress: (Float, String) -> Unit = step.onProgress,
) {
    downloadVerified(
        downloader = step.downloader,
        url = url,
        destination = destination,
        expectedSha256 = expectedSha256,
        startProgress = startProgress,
        endProgress = endProgress,
        label = label,
        onProgress = { progress, text -> onProgress(progress ?: startProgress, text) },
    )
}
