package com.yugahashimoto.andcode.startup

import android.content.Intent
import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAutoStartPolicyTest {
    @Test
    fun `restores an installed local runtime after a process losing event`() {
        assertTrue(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = null,
                trigger = RuntimeAutoStartTrigger.AppLaunch,
                localRuntimeIdleStopEnabled = false,
            ),
        )
        assertTrue(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = "local-android",
                trigger = RuntimeAutoStartTrigger.AppLaunch,
                localRuntimeIdleStopEnabled = false,
            ),
        )
    }

    @Test
    fun `does not start when onboarding is incomplete or runtime is not installed`() {
        assertFalse(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = false,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = null,
                trigger = RuntimeAutoStartTrigger.AppLaunch,
                localRuntimeIdleStopEnabled = false,
            ),
        )
        assertFalse(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.NotInstalled,
                selectedRuntimeId = null,
                trigger = RuntimeAutoStartTrigger.AppLaunch,
                localRuntimeIdleStopEnabled = false,
            ),
        )
    }

    @Test
    fun `does not take over an explicitly selected remote runtime`() {
        assertFalse(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = "remote-runtime",
                trigger = RuntimeAutoStartTrigger.AppLaunch,
                localRuntimeIdleStopEnabled = false,
            ),
        )
    }

    /**
     * Starting the runtime on boot only for the idle-stop watchdog to shut it down again minutes
     * later burns battery for nothing the user asked for - the schedule path and app launch both
     * already start it on demand once idle-stop is on.
     */
    @Test
    fun `skips the boot and package-replaced trigger when idle-stop is enabled`() {
        assertFalse(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = null,
                trigger = RuntimeAutoStartTrigger.BootOrPackageReplaced,
                localRuntimeIdleStopEnabled = true,
            ),
        )
    }

    /** With idle-stop off, a reboot recovery has to restore the runtime exactly as it did before. */
    @Test
    fun `restarts on boot when idle-stop is disabled`() {
        assertTrue(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = null,
                trigger = RuntimeAutoStartTrigger.BootOrPackageReplaced,
                localRuntimeIdleStopEnabled = false,
            ),
        )
    }

    /** Opening the app is always allowed to start the runtime, whatever the idle-stop setting says. */
    @Test
    fun `app launch starts the runtime regardless of idle-stop`() {
        assertTrue(
            shouldAutoStartLocalRuntime(
                onboardingCompleted = true,
                localRuntimeStatus = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                selectedRuntimeId = null,
                trigger = RuntimeAutoStartTrigger.AppLaunch,
                localRuntimeIdleStopEnabled = true,
            ),
        )
    }

    @Test
    fun `recognizes reboot and package replacement as runtime recovery triggers`() {
        assertTrue(isRuntimeAutoStartBroadcast(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(isRuntimeAutoStartBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertFalse(isRuntimeAutoStartBroadcast("com.yugahashimoto.andcode.RUN_SCHEDULE"))
    }

    @Test
    fun `restores a stopped runtime on a foreground return`() {
        assertTrue(
            shouldRestoreOnForegroundReturn(
                status = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                idleStopInProgress = false,
                userStoppedRuntime = false,
            ),
        )
    }

    /**
     * The idle auto-stop's own `manager.stop()` call still reads the status as Ready for its whole
     * duration - there is no Stopping state - so a return landing in that window has to be treated
     * the same as an already-Stopped runtime.
     */
    @Test
    fun `restores a runtime whose idle stop is still in flight`() {
        assertTrue(
            shouldRestoreOnForegroundReturn(
                status = LocalRuntimeStatus.Ready("1.18.11", 4097),
                idleStopInProgress = true,
                userStoppedRuntime = false,
            ),
        )
    }

    @Test
    fun `leaves a healthy runtime alone on a foreground return`() {
        assertFalse(
            shouldRestoreOnForegroundReturn(
                status = LocalRuntimeStatus.Ready("1.18.11", 4097),
                idleStopInProgress = false,
                userStoppedRuntime = false,
            ),
        )
    }

    /** A deliberate stop must not be second-guessed just because the app was reopened. */
    @Test
    fun `does not restore a runtime the user deliberately stopped`() {
        assertFalse(
            shouldRestoreOnForegroundReturn(
                status = LocalRuntimeStatus.Stopped("1.18.11", 4097),
                idleStopInProgress = false,
                userStoppedRuntime = true,
            ),
        )
        assertFalse(
            shouldRestoreOnForegroundReturn(
                status = LocalRuntimeStatus.Ready("1.18.11", 4097),
                idleStopInProgress = true,
                userStoppedRuntime = true,
            ),
        )
    }
}
