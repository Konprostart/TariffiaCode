package com.konprostart.tariffiacode.runtime.local

import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.runtime.LocalAgent
import java.io.File

/**
 * OpenCode: an upstream tarball unpacked to a single binary in the shared Alpine rootfs.
 *
 * The only agent that downloads in the download phase, because its archive is by far the largest
 * and fetching it while the (small, fast) Alpine archive is being unpacked is what keeps the
 * install from stalling. The unpack then happens in the provision phase, before the shared packages,
 * so that a failure to unpack is reported before anything has been installed on top of it.
 */
class OpenCodeAgentInstaller : AgentInstaller {
    override val agent: LocalAgent = LocalAgent.OPEN_CODE

    override suspend fun download(step: AgentInstallStep) {
        val architecture = step.manifest.architecture(step.abi)
        val archive =
            File(step.cache, "opencode-${step.manifest.openCodeVersion}-${step.abi}.tar.gz").also {
                downloadRuntimeFile(
                    step = step,
                    url = architecture.openCodeUrl,
                    destination = it,
                    expectedSha256 = architecture.openCodeSha256,
                    startProgress = 0.24f,
                    endProgress = 0.72f,
                    label = step.label(R.string.install_step_downloading_opencode),
                    onProgress = step.onSharedProgress,
                )
            }
        step.put(ARCHIVE_KEY, archive)
    }

    override suspend fun provision(step: AgentInstallStep) {
        val archive = step.get(ARCHIVE_KEY) ?: return
        // Reported as shared environment work, not as "installing OpenCode" - see
        // AgentInstallStep.onSharedProgress.
        step.onSharedProgress(0.85f, step.label(R.string.install_step_extracting_opencode))
        val sourceBinary =
            step.workDirectory.apply { mkdirs() }.let { extractDir ->
                try {
                    archive.inputStream().use { RuntimeArchive.extractTarGz(it, extractDir) }
                    extractDir.walkTopDown().firstOrNull { it.isFile && it.name == BINARY_NAME }
                        ?: error("OpenCode archive did not contain the opencode binary")
                } finally {
                    extractDir.deleteRecursively()
                }
            }
        val binary = File(step.installRoot, "usr/local/bin/$BINARY_NAME")
        binary.parentFile?.mkdirs()
        sourceBinary.copyTo(binary, overwrite = true)
        require(binary.setExecutable(true, false) || binary.canExecute()) {
            "Unable to mark OpenCode executable"
        }
    }

    private companion object {
        const val ARCHIVE_KEY = "opencode-archive"
        const val BINARY_NAME = "opencode"
    }
}
