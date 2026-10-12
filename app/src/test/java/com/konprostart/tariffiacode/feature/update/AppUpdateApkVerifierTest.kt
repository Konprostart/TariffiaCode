package com.konprostart.tariffiacode.feature.update

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AppUpdateApkVerifierTest {
    private val signer = "a".repeat(64)

    private fun release(signerSha256: String? = signer) =
        AppUpdateRelease(
            version = "9.9.9",
            apkUrl = "https://github.com/Konprostart/TariffiaCode/releases/download/v9.9.9/x.apk",
            sha256 = "a".repeat(64),
            applicationId = "com.konprostart.tariffiacode",
            versionCode = 76,
            signerSha256 = signerSha256,
        )

    private fun apk(
        packageName: String = "com.konprostart.tariffiacode",
        versionCode: Long = 76,
        signerSha256: String? = signer,
    ) = AppUpdateApkInfo(packageName, versionCode, signerSha256)

    private val installed = AppUpdateApkInfo("com.konprostart.tariffiacode", 75, signer)

    @Test
    fun `accepts an APK that matches the installed app and the manifest`() {
        assertNull(appUpdateInstallBlocker(apk(), installed, release()))
    }

    @Test
    fun `rejects a file that is not a readable APK`() {
        assertNotNull(appUpdateInstallBlocker(null, installed, release()))
    }

    @Test
    fun `rejects a different package`() {
        assertNotNull(appUpdateInstallBlocker(apk(packageName = "com.konprostart.tariffiacode.debug"), installed, release()))
    }

    @Test
    fun `rejects an APK that is not newer than the installed build`() {
        assertNotNull(appUpdateInstallBlocker(apk(versionCode = 75), installed, release()))
    }

    @Test
    fun `rejects an APK not signed by the key the manifest declares`() {
        assertNotNull(appUpdateInstallBlocker(apk(signerSha256 = "b".repeat(64)), installed, release()))
    }

    @Test
    fun `rejects an APK signed differently than the installed app`() {
        assertNotNull(appUpdateInstallBlocker(apk(signerSha256 = "b".repeat(64)), installed, release(signerSha256 = null)))
    }
}
