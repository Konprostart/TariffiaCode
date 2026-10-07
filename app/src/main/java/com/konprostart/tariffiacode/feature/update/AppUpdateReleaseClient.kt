package com.konprostart.tariffiacode.feature.update

import com.konprostart.tariffiacode.runtime.local.compareRuntimeVersions
import com.konprostart.tariffiacode.runtime.local.normalizeRuntimeVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request

/** A published TariffiaCode APK update found on GitHub Releases. */
data class AppUpdateRelease(
    val version: String,
    val apkUrl: String,
    /** Expected SHA-256 (lowercase hex) of the APK, from GitHub's own release-asset `digest`. */
    val sha256: String,
    /** Expected APK size in bytes when GitHub reports it; a cheap pre-hash truncation check. */
    val sizeBytes: Long? = null,
)

/**
 * Where the in-app update checker looks, and which release asset it accepts.
 *
 * Two channels exist so the debug build never offers a production APK (and vice versa):
 * - [Release]: the normal production releases feed; only stable, non-prerelease tags; expects the
 *   production `tariffiacode-<tag>-release.apk` asset. Unchanged production behaviour.
 * - [Debug]: a dedicated rolling `debug-latest` prerelease holding exactly one asset,
 *   `tariffiacode-debug.apk`. Prereleases are allowed here (the channel *is* a prerelease).
 */
enum class AppUpdateChannel(
    val releasesEndpoint: String,
    val allowPrerelease: Boolean,
) {
    Release(
        releasesEndpoint = "https://api.github.com/repos/Konprostart/TariffiaCode/releases",
        allowPrerelease = false,
    ),
    Debug(
        // The single dedicated debug release; the client reads this tag directly.
        releasesEndpoint = "https://api.github.com/repos/Konprostart/TariffiaCode/releases/tags/debug-latest",
        allowPrerelease = true,
    ),
    ;

    /** Expected APK asset name for a release tag on this channel. */
    fun apkAssetName(tag: String): String =
        when (this) {
            Release -> "tariffiacode-${tag.trim()}-release.apk"
            Debug -> "tariffiacode-debug.apk"
        }

    /** True when [tag] belongs to this channel (guards against cross-channel assets/versions). */
    fun acceptsTag(tag: String): Boolean =
        when (this) {
            // Production tags are the plain version tags (e.g. v1.2.29); the debug channel must
            // never be treated as a production release.
            Release -> !tag.trim().startsWith(DEBUG_TAG_PREFIX)
            Debug -> tag.trim() == DEBUG_TAG
        }

    companion object {
        const val DEBUG_TAG = "debug-latest"
        private const val DEBUG_TAG_PREFIX = "debug-"
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

/**
 * Default network fetch of a Releases API payload, used when no override is injected.
 *
 * The blocking OkHttp call runs on [Dispatchers.IO]; on the Android main thread it would throw
 * [android.os.NetworkOnMainThreadException]. Mirrors how
 * [com.konprostart.tariffiacode.runtime.local.LocalRuntimeReleaseClient] performs its fetch.
 */
private suspend fun defaultFetchRelease(endpoint: String): String =
    withContext(Dispatchers.IO) {
        val request =
            Request.Builder()
                // Metadata comes only from the GitHub API host over HTTPS.
                .url(requireGitHubApiEndpoint(endpoint))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "TariffiaCode")
                .get()
                .build()
        // HTTPS + GitHub-API-host only, enforced again per redirect hop by the client.
        AppUpdateHttp.apiClient().newCall(request).execute().use { response ->
            require(response.isSuccessful) { "TariffiaCode release check failed with HTTP ${response.code}" }
            requireNotNull(response.body) { "TariffiaCode release response had no body" }.string()
        }
    }

/**
 * Checks GitHub Releases for a newer published APK on one [AppUpdateChannel].
 *
 * Mirrors the OpenCode runtime updater's networking and version-comparison approach
 * ([com.konprostart.tariffiacode.runtime.local.LocalRuntimeReleaseClient]) but is independent: this
 * only reports that an APK update exists and hands back the official download URL. It never downloads
 * or installs anything - the user starts the download explicitly.
 *
 * [fetchRelease] is injectable so the parsing/comparison logic is unit-testable without a network.
 */
class AppUpdateReleaseClient(
    private val channel: AppUpdateChannel = AppUpdateChannel.Release,
    private val fetchRelease: suspend () -> String = { defaultFetchRelease(channel.releasesEndpoint) },
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        },
) {
    /**
     * @return [AppUpdateCheck.Available] when a newer release on this channel, carrying the channel's
     *   expected APK asset, exists; else [AppUpdateCheck.UpToDate]. Throws on network/API/parse
     *   failures; callers surface that as an error state.
     */
    suspend fun check(currentVersion: String): AppUpdateCheck {
        val payload = fetchRelease()
        // The GitHub release-tag endpoint returns a single object; the list endpoint returns an array.
        // Both are handled so one client serves either channel shape.
        val releases =
            runCatching { json.decodeFromString<List<GitHubReleaseDto>>(payload) }
                .getOrElse { listOf(json.decodeFromString<GitHubReleaseDto>(payload)) }
        val latest =
            releases
                .filter { !it.draft && (channel.allowPrerelease || !it.prerelease) }
                .filter { channel.acceptsTag(it.tagName) }
                .mapNotNull { dto ->
                    // The release tag is the version for the production channel. The debug channel
                    // uses a rolling `debug-latest` tag, so its version comes from the release name
                    // (e.g. "1.2.30"). Fall back to the tag so both shapes work.
                    val versionSource =
                        if (channel == AppUpdateChannel.Debug) {
                            dto.name?.takeIf { it.isNotBlank() } ?: dto.tagName
                        } else {
                            dto.tagName
                        }
                    val version =
                        runCatching { normalizeRuntimeVersion(versionSource) }.getOrNull() ?: return@mapNotNull null
                    dto to version
                }
                .maxWithOrNull(compareBy { (_, version) -> ParsedVersion(version) })
                ?: return AppUpdateCheck.UpToDate(normalizeRuntimeVersion(currentVersion), normalizeRuntimeVersion(currentVersion))

        val (dto, latestVersion) = latest
        val normalizedCurrent = normalizeRuntimeVersion(currentVersion)
        if (compareRuntimeVersions(latestVersion, normalizedCurrent) <= 0) {
            return AppUpdateCheck.UpToDate(normalizedCurrent, latestVersion)
        }

        val assetName = channel.apkAssetName(dto.tagName)
        val asset =
            requireNotNull(dto.assets.firstOrNull { it.name == assetName }) {
                "TariffiaCode release ${dto.tagName} does not contain $assetName"
            }
        val downloadUrl = asset.downloadUrl.toHttpUrl()
        // The asset URL must be HTTPS on the GitHub asset host the Releases API actually returns
        // (`github.com`); the CDN redirect it points at is enforced by the download client.
        require(downloadUrl.isHttps && downloadUrl.host == AppUpdateHttp.ASSET_HOST) {
            "TariffiaCode APK download URL must be HTTPS on ${AppUpdateHttp.ASSET_HOST}"
        }
        // The expected hash must come from a trusted source: GitHub's own release-asset digest,
        // delivered over the same HTTPS API call as the download URL. Without it the update cannot
        // be verified, so refuse rather than accept whatever a download returns.
        val sha256 =
            sha256FromDigest(asset.digest)
                ?: error("TariffiaCode release ${dto.tagName} asset $assetName has no sha256 digest to verify against")
        return AppUpdateCheck.Available(
            normalizedCurrent,
            AppUpdateRelease(
                version = latestVersion,
                apkUrl = withDownloadParam(downloadUrl),
                sha256 = sha256,
                sizeBytes = asset.sizeBytes,
            ),
        )
    }

    /** Extracts the lowercase hex of a GitHub `digest` (`sha256:<hex>`), or null when unusable. */
    private fun sha256FromDigest(digest: String?): String? {
        val value = digest?.trim()?.lowercase() ?: return null
        val hex = value.removePrefix("sha256:")
        if (hex == value) return null
        return hex.takeIf { it.matches(SHA256_HEX) }
    }

    /**
     * Preference order for the release asset download URL, expressed as the canonical GitHub
     * release URL with `?download=1` (forces the browser "save" flow on Android).
     */
    private fun withDownloadParam(url: HttpUrl): String = url.newBuilder().addQueryParameter("download", "1").build().toString()

    @Serializable
    private data class GitHubReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("name") val name: String? = null,
        @SerialName("draft") val draft: Boolean = false,
        @SerialName("prerelease") val prerelease: Boolean = false,
        @SerialName("assets") val assets: List<GitHubReleaseAssetDto> = emptyList(),
    )

    @Serializable
    private data class GitHubReleaseAssetDto(
        @SerialName("name") val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
        /** GitHub's own `sha256:<hex>` asset digest, or null on releases that predate the field. */
        @SerialName("digest") val digest: String? = null,
        @SerialName("size") val sizeBytes: Long? = null,
    )

    private class ParsedVersion(private val version: String) : Comparable<ParsedVersion> {
        override fun compareTo(other: ParsedVersion): Int = compareRuntimeVersions(version, other.version)
    }

    companion object {
        /** Public Releases API for the TariffiaCode repository (server-side latest-first order). */
        val RELEASES_ENDPOINT: HttpUrl = AppUpdateChannel.Release.releasesEndpoint.toHttpUrl()

        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}
