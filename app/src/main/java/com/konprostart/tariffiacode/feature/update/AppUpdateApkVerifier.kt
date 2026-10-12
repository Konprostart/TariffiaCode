package com.konprostart.tariffiacode.feature.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.security.MessageDigest

/**
 * Facts about an APK read from its package metadata without installing it.
 *
 * @param packageName the APK's application id, or null when it could not be read.
 * @param versionCode the APK's version code, or 0 when unknown.
 * @param signerSha256 lowercase hex SHA-256 of the APK signer's certificate, or null when unavailable.
 */
data class AppUpdateApkInfo(
    val packageName: String?,
    val versionCode: Long,
    val signerSha256: String?,
)

/**
 * Reads [AppUpdateApkInfo] for a downloaded APK and for the installed app, so the update flow can
 * reject an APK Android would refuse to install over this build. Behind an interface so the decision
 * logic is unit-testable without Android.
 */
interface AppUpdateApkInspector {
    /** Facts for the APK at [apk], or null when it is not a readable APK. */
    fun inspect(apk: File): AppUpdateApkInfo?

    /** Facts for this installed app. */
    fun installed(): AppUpdateApkInfo
}

/**
 * Why a downloaded APK must not be handed to the installer, or null when it may be.
 *
 * Android refuses an update whose package, signer or version does not line up with the installed app,
 * but its failure is the generic "App not installed / package conflicts with an existing package",
 * with no way to tell what mismatched. Checking here first turns that into a precise reason, and stops
 * the app from ever offering a debug-channel APK to a release install (or the reverse), or an APK
 * signed by a different key than the running build.
 */
internal fun appUpdateInstallBlocker(
    apk: AppUpdateApkInfo?,
    installed: AppUpdateApkInfo,
    release: AppUpdateRelease,
): String? {
    if (apk == null) return "The downloaded file is not a readable APK"
    val installedPackage =
        installed.packageName
            ?: return "Could not read this app's package name"
    if (apk.packageName != installedPackage) {
        return "The downloaded update is for ${apk.packageName ?: "an unknown package"}, not $installedPackage"
    }
    if (release.applicationId != installedPackage) {
        return "The release metadata is for ${release.applicationId}, not $installedPackage"
    }
    if (apk.versionCode <= installed.versionCode) {
        return "Update ${apk.versionCode} is not newer than the installed build ${installed.versionCode}"
    }
    val expectedSigner = release.signerSha256
    if (expectedSigner != null && !apk.signerSha256.equals(expectedSigner, ignoreCase = true)) {
        return "The downloaded APK is not signed by the key the release metadata declares"
    }
    val installedSigner = installed.signerSha256
    if (installedSigner != null && !apk.signerSha256.equals(installedSigner, ignoreCase = true)) {
        return "The downloaded APK is signed with a different key than the installed app, so Android cannot update it in place"
    }
    return null
}

/** [AppUpdateApkInspector] backed by [PackageManager]. */
class AndroidAppUpdateApkInspector(
    private val context: Context,
) : AppUpdateApkInspector {
    override fun inspect(apk: File): AppUpdateApkInfo? =
        readArchive(context.packageManager.getPackageArchiveInfo(apk.absolutePath, signatureFlags()))

    override fun installed(): AppUpdateApkInfo {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, signatureFlags()) }.getOrNull()
        return readArchive(info) ?: AppUpdateApkInfo(context.packageName, 0L, null)
    }

    private fun readArchive(info: PackageInfo?): AppUpdateApkInfo? {
        info ?: return null
        return AppUpdateApkInfo(
            packageName = info.packageName,
            versionCode = PackageInfoCompat.getLongVersionCode(info),
            signerSha256 = signerSha256(info),
        )
    }

    private fun signerSha256(info: PackageInfo): String? = certificate(info)?.let { sha256Hex(it.toByteArray()) }

    private fun certificate(info: PackageInfo): Signature? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()
        } else {
            legacySignature(info)
        }

    /** On API < 28 [PackageInfo.signingInfo] does not exist and [PackageInfo.signatures] is the only source. */
    @Suppress("DEPRECATION")
    private fun legacySignature(info: PackageInfo): Signature? = info.signatures?.firstOrNull()

    private fun signatureFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            legacySignatureFlag()
        }

    @Suppress("DEPRECATION")
    private fun legacySignatureFlag(): Int = PackageManager.GET_SIGNATURES
}

private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
