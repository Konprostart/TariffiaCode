package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Downloads an update APK to a local file, verifying it against the expected SHA-256 before keeping
 * it. Kept behind an interface so the update flow is testable without the network.
 */
interface AppUpdateApkDownloader {
    /**
     * Download [apkUrl] to [destination], then keep it only if its SHA-256 equals [expectedSha256]
     * (lowercase or uppercase hex). Throws and deletes [destination] on any failure - a non-success
     * response, a truncated/corrupt body, a size or hash mismatch - so a partial or tampered APK is
     * never handed to the installer. [expectedSizeBytes], when known, rejects a truncated download
     * before it is hashed.
     */
    suspend fun download(
        apkUrl: String,
        destination: File,
        expectedSha256: String,
        expectedSizeBytes: Long? = null,
    )
}

/**
 * Default [AppUpdateApkDownloader] backed by OkHttp, streaming the body straight to [File].
 *
 * The client is HTTPS and GitHub-asset-host only ([AppUpdateHttp.assetClient]): a cleartext URL, a
 * redirect to an unexpected host, or a redirect that downgrades to cleartext is rejected before any
 * bytes are kept.
 */
class OkHttpAppUpdateApkDownloader(
    private val client: OkHttpClient = AppUpdateHttp.assetClient(),
) : AppUpdateApkDownloader {
    override suspend fun download(
        apkUrl: String,
        destination: File,
        expectedSha256: String,
        expectedSizeBytes: Long?,
    ) {
        withContext(Dispatchers.IO) {
            try {
                val request =
                    Request.Builder()
                        .url(apkUrl)
                        .header("User-Agent", "TariffiaCode")
                        .get()
                        .build()
                client.newCall(request).execute().use { response ->
                    require(response.isSuccessful) { "APK download failed with HTTP ${response.code}" }
                    val body = requireNotNull(response.body) { "APK download response had no body" }
                    destination.parentFile?.mkdirs()
                    destination.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                if (expectedSizeBytes != null) {
                    require(destination.length() == expectedSizeBytes) { "Downloaded APK size mismatch" }
                }
                val actual = sha256Hex(destination)
                require(actual.equals(expectedSha256, ignoreCase = true)) {
                    "Downloaded APK failed SHA-256 verification"
                }
            } catch (error: Throwable) {
                // Never leave a partial or unverified APK on disk for a later install to pick up.
                destination.delete()
                throw error
            }
        }
    }
}

/** SHA-256 of [file] as lowercase hex. */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var read = input.read(buffer)
        while (read >= 0) {
            if (read > 0) digest.update(buffer, 0, read)
            read = input.read(buffer)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
