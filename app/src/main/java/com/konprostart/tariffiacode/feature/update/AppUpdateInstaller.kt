package com.konprostart.tariffiacode.feature.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a downloaded update APK to Android's package installer, and manages the "install unknown apps"
 * permission on API 26+. Kept behind an interface so the update flow is testable without Android.
 */
interface AppUpdateInstaller {
    /** True when this app may currently start the package installer. */
    fun canInstall(): Boolean

    /** Fire the standard system install/update prompt for [apk]. */
    fun install(apk: File)

    /** Send the user to the system screen that grants this app permission to install packages. */
    fun requestInstallPermission()
}

/** [AppUpdateInstaller] using [FileProvider] and `ACTION_VIEW` — the standard, safe install flow. */
class AndroidAppUpdateInstaller(
    private val context: Context,
) : AppUpdateInstaller {
    override fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    override fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    override fun requestInstallPermission() {
        val intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
