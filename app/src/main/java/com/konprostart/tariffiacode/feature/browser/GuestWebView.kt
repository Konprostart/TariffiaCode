package com.konprostart.tariffiacode.feature.browser

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.konprostart.tariffiacode.BuildConfig

/**
 * The shared WebView for in-app previews of a page served by a runtime: the on-device guest
 * ([GuestBrowserScreen]) and a port forwarded from a VPS ([com.konprostart.tariffiacode.feature.preview.VpsPreviewScreen]).
 * JavaScript and DOM storage are enabled because dev servers and tool UIs are interactive web apps.
 *
 * [url] is loaded once, when the view is created. A caller that changes the URL should recreate the
 * view (for example by keying the call site on the URL) or drive the returned [WebView] directly.
 */
@Composable
fun GuestWebView(
    url: String,
    modifier: Modifier = Modifier,
    onProgress: (Int) -> Unit = {},
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit = { _, _, _ -> },
    onCreated: (WebView) -> Unit = {},
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            // Lets the in-guest agent drive this WebView over CDP (the devtools abstract socket is
            // reachable from the guest, which shares the app's UID), so a page can be shown to the
            // user and operated by human and agent at the same time.
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            WebView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                configure(onProgress, onNavigationStateChange)
                onCreated(this)
                if (url.isNotBlank()) {
                    loadUrl(normalizeWebUrl(url))
                }
            }
        },
    )
}

/** Coerces user/agent input into an http(s) URL, defaulting to the loopback root. */
internal fun normalizeWebUrl(raw: String): String {
    val trimmed = raw.trim()
    val candidate = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    return candidate.takeIf { android.net.Uri.parse(it).scheme in setOf("http", "https") } ?: "http://127.0.0.1"
}

@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configure(
    onProgress: (Int) -> Unit,
    onNavigationStateChange: (url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
) {
    // Guest pages (dev servers, dashboards, tool UIs) are interactive web apps that need JS.
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    webChromeClient =
        object : WebChromeClient() {
            override fun onProgressChanged(
                view: WebView?,
                newProgress: Int,
            ) {
                onProgress(newProgress)
            }
        }
    webViewClient =
        object : WebViewClient() {
            override fun onPageFinished(
                view: WebView?,
                url: String?,
            ) {
                onProgress(100)
                onNavigationStateChange(
                    url.orEmpty(),
                    view?.canGoBack() == true,
                    view?.canGoForward() == true,
                )
            }
        }
}
