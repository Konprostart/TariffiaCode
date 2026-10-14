package com.konprostart.tariffiacode.core.router

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts and stops the router from `adb`, in debug builds only.
 *
 * Exists so the reachability question can be answered by running one command, rather than by building
 * a screen first and then finding out the thing it was supposed to prove does not work. Debug source
 * set only: it never reaches a release build, and nothing in the app has to know it is here.
 *
 *   adb shell am broadcast -a com.konprostart.tariffiacode.ROUTER_START
 *   adb shell am broadcast -a com.konprostart.tariffiacode.ROUTER_STOP
 *   adb shell cat /data/data/com.konprostart.tariffiacode/files/runtime/router-endpoint.txt
 */
class RouterDebugReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            START -> RouterService.start(context)
            STOP -> RouterService.stop(context)
            else -> Unit
        }
    }

    private companion object {
        const val START = "com.konprostart.tariffiacode.ROUTER_START"
        const val STOP = "com.konprostart.tariffiacode.ROUTER_STOP"
    }
}
