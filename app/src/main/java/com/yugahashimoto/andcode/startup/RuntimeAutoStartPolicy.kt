package com.yugahashimoto.andcode.startup

import com.yugahashimoto.andcode.runtime.LocalRuntimeStatus

internal const val LOCAL_RUNTIME_ID = "local-android"

/** Which path is asking the runtime to come back; see [shouldAutoStartLocalRuntime]. */
internal enum class RuntimeAutoStartTrigger {
    /**
     * The app entering the foreground - a cold start's first frame, or an actual return from the
     * background (see [shouldRestoreOnForegroundReturn]). Always allowed to start the runtime when
     * it is otherwise eligible: a user who has the app on screen plainly wants it up.
     */
    AppLaunch,

    /**
     * `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`. With idle-stop on, starting the runtime here only
     * for the watchdog to stop it again minutes later burns battery for nothing the user asked for
     * - the schedule path and app launch both already start it on demand.
     */
    BootOrPackageReplaced,
}

/**
 * Keeps the automatic recovery path scoped to a configured local runtime. An explicit remote
 * selection must never be replaced just because the app was updated or the device rebooted.
 *
 * [trigger] further restricts the reboot/package-replace path: when [localRuntimeIdleStopEnabled]
 * is on, that path is refused outright, since app launch and scheduled runs already cover starting
 * the runtime on demand. It only matters for that trigger - app launch always starts regardless of
 * the setting, because a user who opens the app plainly wants the runtime up right now.
 */
internal fun shouldAutoStartLocalRuntime(
    onboardingCompleted: Boolean,
    localRuntimeStatus: LocalRuntimeStatus,
    selectedRuntimeId: String?,
    trigger: RuntimeAutoStartTrigger,
    localRuntimeIdleStopEnabled: Boolean,
): Boolean =
    onboardingCompleted &&
        localRuntimeStatus !is LocalRuntimeStatus.NotInstalled &&
        (selectedRuntimeId == null || selectedRuntimeId == LOCAL_RUNTIME_ID) &&
        (trigger == RuntimeAutoStartTrigger.AppLaunch || !localRuntimeIdleStopEnabled)

/**
 * Whether a foreground return should re-run [RuntimeAutoStartInitializer.restoreIfConfigured].
 *
 * Restricted to a runtime that is actually down - [Stopped][LocalRuntimeStatus.Stopped], or on its
 * way there via [idleStopInProgress] - because
 * [com.yugahashimoto.andcode.runtime.local.LocalRuntimeService.ACTION_START] cancels whatever
 * operation is currently in flight, so firing it at a [Ready][LocalRuntimeStatus.Ready] runtime, or
 * one mid-install or mid-update, would interrupt real work rather than being the no-op a healthy
 * runtime deserves.
 *
 * [idleStopInProgress] covers the brief window where
 * [com.yugahashimoto.andcode.runtime.local.LocalRuntimeService.checkIdleStop] has already decided to
 * stop the runtime but its `manager.stop()` call has not finished yet - the status is still read as
 * `Ready` for that whole window, since there is no `Stopping` state, and without this a return
 * landing inside it would fail the `Stopped` check above and leave the runtime down until the next
 * return instead of this one.
 *
 * [userStoppedRuntime] is the escape hatch for a deliberate stop: the runtime notification's Stop
 * action and
 * [com.yugahashimoto.andcode.feature.workspace.WorkspaceViewModel.stopLocalRuntime] both mean "leave
 * it down", and returning to the app a moment later must not second-guess that - unlike the idle
 * auto-stop, which is exactly what this restore exists to undo.
 */
internal fun shouldRestoreOnForegroundReturn(
    status: LocalRuntimeStatus,
    idleStopInProgress: Boolean,
    userStoppedRuntime: Boolean,
): Boolean = !userStoppedRuntime && (status is LocalRuntimeStatus.Stopped || idleStopInProgress)
