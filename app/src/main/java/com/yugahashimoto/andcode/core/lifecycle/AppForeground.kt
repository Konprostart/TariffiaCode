package com.yugahashimoto.andcode.core.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether any activity of the app is currently visible to the user.
 *
 * An interface rather than a bare object so callers that only need to read it - the local runtime's
 * idle-stop check, the background poll loops it gates - can be handed a fake in tests instead of
 * touching [ProcessLifecycleOwner], which requires a real main-thread Looper to observe.
 */
interface AppForeground {
    val foreground: StateFlow<Boolean>
}

/**
 * Backed by the process lifecycle rather than any one activity's, so rotating the screen or moving
 * between activities never reads as the app leaving the foreground - only the whole task doing that
 * does. `STARTED` (not `RESUMED`) is what marks it: that already covers the brief window where an
 * activity is visible but not yet interactive, e.g. behind a system dialog, which should still
 * count as "the app is on screen" for a check that only cares about waking the user's attention.
 */
class ProcessLifecycleAppForeground private constructor() : AppForeground, DefaultLifecycleObserver {
    private val mutableForeground = MutableStateFlow(false)
    override val foreground: StateFlow<Boolean> = mutableForeground.asStateFlow()

    override fun onStart(owner: LifecycleOwner) {
        mutableForeground.value = true
    }

    override fun onStop(owner: LifecycleOwner) {
        mutableForeground.value = false
    }

    companion object {
        /** Registers with the process lifecycle and returns the [AppForeground] to hold onto. */
        fun install(): ProcessLifecycleAppForeground =
            ProcessLifecycleAppForeground().also { instance ->
                ProcessLifecycleOwner.get().lifecycle.addObserver(instance)
            }
    }
}
