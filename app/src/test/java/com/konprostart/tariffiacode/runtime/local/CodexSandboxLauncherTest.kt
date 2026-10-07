package com.konprostart.tariffiacode.runtime.local

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CodexSandboxLauncherTest {
    @Test
    fun `default command uses the sandboxed workspace-write mode`() {
        val root = Files.createTempDirectory("codex-sandbox").toFile()
        try {
            val command =
                CodexSandboxLauncher.command(
                    runtime = mockRuntime(root),
                    workspaceHostDir = "/host/workspace",
                    arguments = listOf("app-server"),
                    fullAccess = false,
                )

            assertTrue(
                "default must sandbox Codex with workspace-write",
                command.contains("sandbox_mode=\"workspace-write\""),
            )
            assertFalse(
                "default must never grant danger-full-access",
                command.any { it.contains("danger-full-access") },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `full access is only granted when explicitly enabled`() {
        val root = Files.createTempDirectory("codex-sandbox").toFile()
        try {
            val command =
                CodexSandboxLauncher.command(
                    runtime = mockRuntime(root),
                    workspaceHostDir = "/host/workspace",
                    arguments = listOf("app-server"),
                    fullAccess = true,
                )

            assertTrue(command.contains("sandbox_mode=\"danger-full-access\""))
            assertFalse(command.contains("sandbox_mode=\"workspace-write\""))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sandbox mode is configured before the app-server subcommand`() {
        val root = Files.createTempDirectory("codex-sandbox").toFile()
        try {
            val command =
                CodexSandboxLauncher.command(
                    runtime = mockRuntime(root),
                    workspaceHostDir = "/host/workspace",
                    arguments = listOf("app-server"),
                    fullAccess = false,
                )

            // `codex -c key=value app-server`, not the reverse: after the subcommand Codex treats
            // the flag as an app-server option and the sandbox mode is not applied.
            assertTrue(command.indexOf("sandbox_mode=\"workspace-write\"") < command.indexOf("app-server"))
        } finally {
            root.deleteRecursively()
        }
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
        )
}
