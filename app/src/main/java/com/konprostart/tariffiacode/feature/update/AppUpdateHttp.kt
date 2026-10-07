package com.konprostart.tariffiacode.feature.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

/**
 * Transport policy for the in-app update flow.
 *
 * The app's global network security config permits cleartext because remote OpenCode runtimes are
 * reached over plain HTTP on LAN/loopback addresses that Android's config cannot express as rules
 * (see `network_security_config.xml`). The update flow must not inherit that allowance, and it must
 * only ever talk to GitHub: an APK download must be HTTPS, end to end, on GitHub hosts only.
 *
 * The allowed hosts are the ones the real GitHub Releases flow uses, verified against the live API:
 * - metadata: `api.github.com`;
 * - the release asset URL: `github.com`;
 * - the only redirect target for `github.com/.../releases/download/...`:
 *   `release-assets.githubusercontent.com`.
 */
internal object AppUpdateHttp {
    const val API_HOST = "api.github.com"
    const val ASSET_HOST = "github.com"
    const val ASSET_CDN_HOST = "release-assets.githubusercontent.com"

    /**
     * HTTPS-only, with no host restriction. A network interceptor runs for every redirect hop, not
     * only the first request.
     */
    fun httpsOnlyClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addNetworkInterceptor(HttpsOnlyInterceptor())
            .build()

    /** The release-metadata client: HTTPS, and only the GitHub API host. */
    fun apiClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addNetworkInterceptor(AllowedHostInterceptor(setOf(API_HOST)))
            .build()

    /** The APK download client: HTTPS, and only the asset host plus its CDN redirect target. */
    fun assetClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addNetworkInterceptor(AllowedHostInterceptor(setOf(ASSET_HOST, ASSET_CDN_HOST)))
            .build()
}

/** Rejects a cleartext update request before it leaves the device. */
internal class HttpsOnlyInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        require(url.isHttps) { "Refusing cleartext update request to ${url.scheme}://${url.host}" }
        return chain.proceed(chain.request())
    }
}

/**
 * Rejects any update request - including a redirect hop - that is not HTTPS on one of
 * [allowedHosts], so a redirect cannot move the update flow to an unexpected server.
 */
internal class AllowedHostInterceptor(
    private val allowedHosts: Set<String>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        require(url.isHttps) { "Refusing cleartext update request to ${url.scheme}://${url.host}" }
        require(url.host in allowedHosts) { "Refusing update request to unexpected host ${url.host}" }
        return chain.proceed(chain.request())
    }
}

/** Validates the release-metadata endpoint is HTTPS on the GitHub API host, and returns it. */
internal fun requireGitHubApiEndpoint(endpoint: String): HttpUrl {
    val url = endpoint.toHttpUrl()
    require(url.isHttps && url.host == AppUpdateHttp.API_HOST) {
        "Refusing update metadata from unexpected host ${url.host}"
    }
    return url
}
