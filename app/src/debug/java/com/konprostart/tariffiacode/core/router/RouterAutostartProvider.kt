package com.konprostart.tariffiacode.core.router

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Starts the router when the app process comes up. Debug builds only.
 *
 * The reachability question needs the router running, and the only way this build had to start it was
 * a broadcast from `adb`. That is fine for a developer at a desk and useless to the person holding the
 * phone, which is exactly the person who has to answer this question.
 *
 * A provider is used because the system instantiates it during process start whether or not anything
 * in the app asks for it - so the router comes up the moment the app is opened, with no screen, no
 * extra permission, and nothing in the release build.
 */
class RouterAutostartProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val context = context ?: return false
        // Deferred, and caught.
        //
        // The first version called startForegroundService() directly from here. onCreate() runs
        // during process start, before anything is in the foreground, so that threw - and a throw
        // out of a provider's onCreate takes the whole process down. It took the instrumentation
        // process with it, which is how an unrelated SSH test started failing too.
        //
        // Nothing a debug convenience does is allowed to be able to kill the app, so both the delay
        // and the catch are load-bearing.
        Handler(Looper.getMainLooper()).post {
            runCatching { RouterService.start(context) }
                .onFailure { Log.e(TAG, "router did not autostart", it) }
        }
        return true
    }

    // Nothing here is ever queried; the provider exists only for its onCreate.
    override fun query(
        uri: android.net.Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: android.net.Uri): String? = null

    override fun insert(
        uri: android.net.Uri,
        values: ContentValues?,
    ): android.net.Uri? = null

    override fun delete(
        uri: android.net.Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: android.net.Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "RouterAutostart"
    }
}
