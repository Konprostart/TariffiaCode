package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.core.storage.DeviceStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pins the one part of the PRoot sandbox that is provable without running it: the allow-list of host
 * paths each launcher asks PRoot to bind.
 *
 * This does NOT prove PRoot enforces the allow-list at runtime - PRoot is a path translator, not an
 * access-control boundary (see docs/SECURITY_MODEL.md). It proves the app never *asks* for an
 * app-private or secret host path to be exposed, and that shared storage is exposed only as the
 * specific folders the user registered as projects, never as the whole `/sdcard`/`/storage` tree.
 */
class SandboxMountBoundaryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun resetDeviceStorage() {
        DeviceStorage.install { DeviceStorage.Mounts.None }
        DeviceStorage.installProjectPaths { emptyList() }
    }

    @Test
    fun `claude binds only the fixed allow-list of host paths`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")
        val workspace = File(runtimeDirectory, "workspace")
        val bridgeHost = File(runtimeDirectory, ClaudePermissionBridge.HOST_DIR_NAME)
        val rootfs = temporaryFolder.newFolder("rootfs")

        val command =
            ClaudeSandboxLauncher.command(
                runtime = mockRuntime(rootfs),
                workspaceHostDir = workspace,
                workingDirectory = "/workspace",
                arguments = emptyList(),
                pty = false,
            )

        assertEquals(rootfs.absolutePath, rootfsArgument(command))
        assertEquals(
            setOf("/dev", "/proc", "/sys", "/system", workspace.absolutePath, bridgeHost.absolutePath),
            binds(command).map { it.first }.toSet(),
        )
        assertEquals(
            setOf("/dev", "/proc", "/sys", "/system", "/workspace", ClaudePermissionBridge.GUEST_BRIDGE_PATH),
            binds(command).map { it.second }.toSet(),
        )
        assertNoSecretHostPath(command)
    }

    @Test
    fun `antigravity binds only the fixed allow-list of host paths`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")
        val workspace = File(runtimeDirectory, "workspace")
        val rootfs = temporaryFolder.newFolder("rootfs")

        val command =
            AntigravitySandboxLauncher.command(
                runtime = mockRuntime(rootfs),
                workspaceHostDir = workspace.absolutePath,
                arguments = emptyList(),
                pty = false,
            )

        assertEquals(rootfs.absolutePath, rootfsArgument(command))
        assertEquals(
            setOf("/dev", "/proc", "/sys", "/system", workspace.absolutePath),
            binds(command).map { it.first }.toSet(),
        )
        assertNoSecretHostPath(command)
    }

    @Test
    fun `codex binds only the fixed allow-list of host paths`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")
        val workspace = File(runtimeDirectory, "workspace")
        val rootfs = temporaryFolder.newFolder("rootfs")

        val command =
            CodexSandboxLauncher.command(
                runtime = mockRuntime(rootfs),
                workspaceHostDir = workspace.absolutePath,
                arguments = listOf("app-server"),
                fullAccess = false,
            )

        assertEquals(rootfs.absolutePath, rootfsArgument(command))
        assertEquals(
            setOf("/dev", "/proc", "/sys", "/system", workspace.absolutePath),
            binds(command).map { it.first }.toSet(),
        )
        assertNoSecretHostPath(command)
    }

    @Test
    fun `only a registered project folder is bound, never the whole storage tree`() {
        val runtimeDirectory = temporaryFolder.newFolder("runtime")
        val workspace = File(runtimeDirectory, "workspace")
        val rootfs = temporaryFolder.newFolder("rootfs")

        val before =
            ClaudeSandboxLauncher.command(mockRuntime(rootfs), workspace, "/workspace", emptyList(), pty = false)
        assertTrue(binds(before).none { it.second == "/sdcard" || it.second == "/storage" })

        val shared = temporaryFolder.newFolder("emulated", "0")
        DeviceStorage.install {
            DeviceStorage.Mounts(
                sharedStorage = shared,
                volumes = temporaryFolder.newFolder("storage"),
            )
        }
        File(shared, "Download/repo").mkdirs()
        DeviceStorage.installProjectPaths { listOf("/sdcard/Download/repo") }

        val after =
            ClaudeSandboxLauncher.command(mockRuntime(rootfs), workspace, "/workspace", emptyList(), pty = false)
        assertEquals(
            setOf(
                "/dev",
                "/proc",
                "/sys",
                "/system",
                "/workspace",
                ClaudePermissionBridge.GUEST_BRIDGE_PATH,
                "/sdcard/Download/repo",
            ),
            binds(after).map { it.second }.toSet(),
        )
        assertTrue(
            "the storage roots must never be bound",
            binds(after).none { it.second == "/sdcard" || it.second == "/storage" },
        )
    }

    private fun assertNoSecretHostPath(command: List<String>) {
        val forbidden = listOf("shared_prefs", "databases", "auth.json", "keystore", ".ssh")
        binds(command).forEach { (host, _) ->
            forbidden.forEach { needle ->
                assertTrue("sandbox must not bind a secret path: $host", !host.contains(needle))
            }
        }
    }

    /** The `-r` argument is the guest root; it must be the app-private rootfs, never `/`. */
    private fun rootfsArgument(command: List<String>): String = command[command.indexOf("-r") + 1]

    private fun binds(command: List<String>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        var index = 0
        while (index < command.size - 1) {
            if (command[index] == "-b") {
                val spec = command[index + 1]
                val separator = spec.indexOf(':')
                result +=
                    if (separator > 0 && spec.substring(separator + 1).startsWith("/")) {
                        spec.substring(0, separator) to spec.substring(separator + 1)
                    } else {
                        spec to spec
                    }
                index += 2
            } else {
                index++
            }
        }
        return result
    }

    private fun mockRuntime(rootfs: File): LocalRuntimeInstaller.InstalledRuntime =
        LocalRuntimeInstaller.InstalledRuntime(
            metadata = LocalRuntimeMetadata(version = "test", port = 0, installedAt = 0),
            commandSuite =
                EmbeddedCommandSuite.Paths(
                    home = rootfs,
                    tmp = rootfs,
                    nativeLibraryDirectory = rootfs,
                    proot = rootfs,
                    loader = rootfs,
                    loader32 = rootfs,
                ),
            rootfs = rootfs,
            openCode = null,
            antigravityRootfs = rootfs,
        )
}
