package com.konprostart.tariffiacode.runtime.local

import android.content.Context
import com.konprostart.tariffiacode.R
import com.konprostart.tariffiacode.runtime.LocalAgent

/**
 * Antigravity: a GitHub release tarball, into a Debian userland of its own.
 *
 * The only agent that declares [com.konprostart.tariffiacode.runtime.AgentUserland.DEBIAN_ROOTFS],
 * because the official binary is glibc-linked and the shared Alpine rootfs cannot run it. That makes
 * it the one agent whose work is split across both phases that touch a rootfs: [provision] builds
 * the Debian rootfs and gives it the guest capabilities the Alpine one already has, and [install]
 * unpacks the binary into it once the shared packages are in.
 *
 * Everything after this point installs into [AgentInstallStep.installRoot], which by then is the
 * Debian rootfs rather than the shared one - so the same install path serves an agent that has a
 * userland of its own and one that does not.
 */
class AntigravityAgentInstaller(
    private val context: Context,
    private val guestCapabilities: GuestCapabilities,
) : AgentInstaller {
    override val agent: LocalAgent = LocalAgent.ANTIGRAVITY

    override suspend fun provision(step: AgentInstallStep) {
        val destination =
            AgentInstallStep.userlandRootFor(step.stagingDirectory, agent)
                ?: error("Antigravity declares its own userland but none was planned for it")
        // Before the callback stream, which runs 0.90 up to 0.93: emitting it afterwards would make the
        // bar reach 93% and then snap back to 90%.
        step.onProgress(0.90f, step.label(R.string.install_step_preparing_antigravity_rootfs))
        val debianRootfs =
            DebianRootfsInstaller(step.runtimeDirectory, step.abi, step.downloader, step.httpClient, step.commandSuite).installInto(
                destination,
                installFullDevelopmentTools = step.includeFullDevelopmentTools,
            ) { progress ->
                step.onProgress(
                    0.90f + progress * 0.03f,
                    step.label(R.string.install_step_preparing_antigravity_rootfs),
                )
            }
        // From here on this agent installs into its own userland, not the shared Alpine rootfs.
        step.useUserlandRoot(debianRootfs)
        ensureTariffiaCodeAgentContext(debianRootfs, context)
        // The Debian rootfs has no trust store of its own, and every HTTPS call this agent makes -
        // its release check included - needs one.
        guestCapabilities.copyCaCertificates(step.stagingRootfs, debianRootfs)
        // Keep the Android-vision tool surface available to agy's Debian tool runner as well. The
        // scripts still fail closed when adb or Pillow is not installed; they are never silently
        // replaced by a fork.
        guestCapabilities.installHelperScripts(debianRootfs)
        guestCapabilities.provisionBrowserMcp(debianRootfs)
        guestCapabilities.provisionScheduleMcp(debianRootfs)
    }

    override suspend fun install(step: AgentInstallStep) {
        step.onProgress(0.94f, step.label(R.string.install_step_downloading_antigravity))
        val release = resolveAntigravityRelease(step.abi, step.httpClient)
        AntigravityInstaller(step.runtimeDirectory, step.downloader).installInto(
            step.installRoot,
            { progress ->
                step.onProgress(
                    0.94f + progress * 0.04f,
                    step.label(R.string.install_step_installing_antigravity),
                )
            },
            release,
        )
    }
}
