package com.konprostart.tariffiacode.feature.update

import com.konprostart.tariffiacode.runtime.local.compareRuntimeVersions
import com.konprostart.tariffiacode.runtime.local.normalizeRuntimeVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** A published TariffiaCode APK update found on GitHub Releases. */
data class AppUpdateRelease(
    val version: String,
    val apkUrl: String,
) {
    companion object {
        /** Asset name pattern published by the release workflow for a given tag (e.g. v1.2.27). */
        fun apkAssetName(tag: String): String = "tariffiacode-$tag-release.apk"
    }
}

sealed interface AppUpdateCheck {
    val currentVersion: String

    data class UpToDate(
        override val currentVersion: String,
        val latestVersion: String,
    ) : AppUpdateCheck

    data class Available(
        override val currentVersion: String,
        val release: AppUpdateRelease,
    ) : AppUpdateCheck
}

/** Default network fetch of the Releases API payload, used when no override is injected. */
private fun defaultFetchRelease(): String {
    val request =
        Request.Builder()
            .url(AppUpdateReleaseClient.RELEASES_ENDPOINT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "TariffiaCode")
            .get()
            .build()
    return OkHttpClient().newCall(request).execute().use { response ->
        require(response.isSuccessful) { "TariffiaCode release check failed with HTTP ${response.code}" }
        requireNotNull(response.body) { "TariffiaCode release response had no body" }.string()
    }
}

/**
 * Checks the public TariffiaCode GitHub Releases for a newer published APK.
 *
 * Mirrors the OpenCode runtime updater's networking and version-comparison approach
 * ([com.konprostart.tariffiacode.runtime.local.LocalRuntimeReleaseClient]) but is independent: this
 * only reports that an APK update exists and hands back the official download URL. It never downloads
 * or installs anything - the user starts the download explicitly.
 *
 * [fetchRelease] is injectable so the parsing/comparison logic is unit-testable without a network.
 */
class AppUpdateReleaseClient(
    private val fetchRelease: () -> String = ::defaultFetchRelease,
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    /**
     * @return [AppUpdateCheck.Available] when a newer non-draft, non-prerelease release with the
     *   expected `tariffiacode-<tag>-release.apk` asset exists, else [AppUpdateCheck.UpToDate].
     *   Throws on network/API/parse failures; callers surface that as an error state.
     */
    fun check(currentVersion: String): AppUpdateCheck {
        val payload = fetchRelease()
        val releases = json.decodeFromString<List<GitHubReleaseDto>>(payload)
        val latest =
            releases
                .filter { !it.draft && !it.prerelease }
                .mapNotNull { dto ->
                    val version =
                        runCatching { normalizeRuntimeVersion(dto.tagName) }.getOrNull() ?: return@mapNotNull null
                    dto to version
                }
                .maxWithOrNull(compareBy { (_, version) -> ParsedVersion(version) })
                ?: return AppUpdateCheck.UpToDate(normalizeRuntimeVersion(currentVersion), normalizeRuntimeVersion(currentVersion))

        val (dto, latestVersion) = latest
        val normalizedCurrent = normalizeRuntimeVersion(currentVersion)
        if (compareRuntimeVersions(latestVersion, normalizedCurrent) <= 0) {
            return AppUpdateCheck.UpToDate(normalizedCurrent, latestVersion)
        }

        val assetName = AppUpdateRelease.apkAssetName(dto.tagName.trim())
        val asset =
            requireNotNull(dto.assets.firstOrNull { it.name == assetName }) {
                "TariffiaCode release ${dto.tagName} does not contain $assetName"
            }
        val downloadUrl = asset.downloadUrl.toHttpUrl()
        require(downloadUrl.isHttps) { "TariffiaCode APK download URL must use HTTPS" }
        return AppUpdateCheck.Available(
            normalizedCurrent,
            AppUpdateRelease(version = latestVersion, apkUrl = withDownloadParam(downloadUrl)),
        )
    }

    /**
     * Preference order for the release asset download URL, expressed as the canonical GitHub
     * release URL with `?download=1` (forces the browser "save" flow on Android).
     */
    private fun withDownloadParam(url: HttpUrl): String = url.newBuilder().addQueryParameter("download", "1").build().toString()

    @Serializable
    private data class GitHubReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("draft") val draft: Boolean = false,
        @SerialName("prerelease") val prerelease: Boolean = false,
        @SerialName("assets") val assets: List<GitHubReleaseAssetDto> = emptyList(),
    )

    @Serializable
    private data class GitHubReleaseAssetDto(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    private class ParsedVersion(private val version: String) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int = compareRuntimeVersions(version, other.version)
    }

    companion object {
        /** Public Releases API for the TariffiaCode repository (server-side latest-first order). */
        val RELEASES_ENDPOINT: HttpUrl = "https://api.github.com/repos/Konprostart/TariffiaCode/releases".toHttpUrl()
    }
}
