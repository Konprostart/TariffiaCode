package com.konprostart.tariffiacode.feature.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads an update APK to a local file. Kept behind an interface so the update flow is testable
 * without the network.
 */
interface AppUpdateApkDownloader {
    /** Download [apkUrl] to [destination], overwriting it. Throws on any failure. */
    suspend fun download(
        apkUrl: String,
        destination: File,
    )
}

/** Default [AppUpdateApkDownloader] backed by OkHttp, streaming the body straight to [File]. */
class OkHttpAppUpdateApkDownloader(
    private val client: OkHttpClient = OkHttpClient(),
) : AppUpdateApkDownloader {
    override suspend fun download(
        apkUrl: String,
        destination: File,
    ) {
        withContext(Dispatchers.IO) {
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
        }
    }
}
