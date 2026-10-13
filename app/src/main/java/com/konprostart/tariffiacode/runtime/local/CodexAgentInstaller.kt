package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.runtime.LocalAgent

/**
 * Codex: an npm tarball, installed with the npm registry's own SHA-512 integrity check.
 *
 * Into the shared Alpine rootfs like every other agent here, so it is swapped in with the
 * environment and is not lost when a later install rebuilds the sandbox.
 */
class CodexAgentInstaller : AgentInstaller {
    override val agent: LocalAgent = LocalAgent.CODEX

    override suspend fun install(step: AgentInstallStep) {
        step.onProgress(0.935f, step.label(R.string.install_step_downloading_codex))
        CodexInstaller.install(
            rootfs = step.installRoot,
            abi = step.abi,
            runtimeDirectory = step.runtimeDirectory,
            accessCoordinator = step.accessCoordinator,
            httpClient = step.httpClient,
        )
    }
}
