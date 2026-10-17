package com.shixiaoshi0417.lunexsu.ui.webui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ServiceWorkerClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/** No network fallback is ever allowed, including asset misses and unsupported schemes. */
internal open class LocalModuleWebViewClient(
    private val loadLocal: (Uri) -> WebResourceResponse?,
    private val loadIcon: (Uri) -> WebResourceResponse?,
    private val openExternal: (Uri) -> Unit
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
        intercept(request.url, request.method, request.isForMainFrame)

    @Deprecated("Legacy callback must also fail closed")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse =
        intercept(Uri.parse(url), "GET", false)

    private fun intercept(uri: Uri, method: String, isMainFrame: Boolean): WebResourceResponse {
        val resource = WebUIOriginPolicy.resource(uri.toString(), method, isMainFrame)
        if (resource == WebUIOriginPolicy.Resource.BLOCK) return failure(403, "Forbidden")
        val response = runCatching {
            when (resource) {
                WebUIOriginPolicy.Resource.LOCAL -> loadLocal(uri)
                WebUIOriginPolicy.Resource.ICON -> loadIcon(uri)
                WebUIOriginPolicy.Resource.BLOCK -> null
            }
        }.getOrNull() ?: return failure(404, "Not Found")
        // Local loaders must never delegate to a redirect. shouldInterceptRequest is NOT
        // called for every redirect hop; CSP and blockNetworkLoads are also mandatory.
        if (response.statusCode in 300..399) return failure(403, "Forbidden")
        val headers = response.responseHeaders.orEmpty().filterKeys {
            !it.equals("Location", true) && !it.equals("Refresh", true) &&
                !it.equals("Content-Security-Policy", true)
        }
        response.responseHeaders = headers + securityHeaders
        return response
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        navigate(request.url, request.isForMainFrame, request.hasGesture(), request.isRedirect)

    @Deprecated("Legacy callback has no trustworthy frame/gesture information")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        !WebUIOriginPolicy.isLocal(url)

    private fun navigate(uri: Uri, isMainFrame: Boolean, hasGesture: Boolean, isRedirect: Boolean): Boolean =
        when (WebUIOriginPolicy.navigation(uri.toString(), isMainFrame, hasGesture, isRedirect)) {
            WebUIOriginPolicy.Navigation.LOCAL -> false
            WebUIOriginPolicy.Navigation.EXTERNAL_BROWSER -> {
                openExternal(uri)
                true
            }
            WebUIOriginPolicy.Navigation.BLOCK -> true
        }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        // Defense against native loadUrl() mistakes and navigations not covered by the
        // override callback (e.g. POST). This is NOT a JS frame-caller authentication check.
        if (url == null || !WebUIOriginPolicy.isLocal(url)) {
            view.removeJavascriptInterface("ksu")
            view.settings.javaScriptEnabled = false
            view.stopLoading()
        }
        super.onPageStarted(view, url, favicon)
    }

    private fun failure(code: Int, reason: String) = WebResourceResponse(
        "text/plain", "utf-8", code, reason, securityHeaders,
        ByteArrayInputStream("Module WebUI: local content only".toByteArray(Charsets.UTF_8))
    )

    private val securityHeaders = mapOf(
        "Content-Security-Policy" to WebUIOriginPolicy.contentSecurityPolicy,
        "Cache-Control" to "no-store",
        "X-Content-Type-Options" to "nosniff",
        "Referrer-Policy" to "no-referrer"
    )
}

/** Must be applied before attaching a bridge or loading a document. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
internal fun configureLocalModuleWebView(view: WebView): Boolean {
    // Never reuse the historical/default profile: an already-installed service worker
    // could serve documents without this client's CSP. This profile is exclusively for
    // local-only bridged views, with no migration of old workers/cache/browser storage.
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return false
    WebViewCompat.setProfile(view, LOCAL_MODULE_PROFILE)
    WebViewCompat.getProfile(view).serviceWorkerController.apply {
        serviceWorkerWebSettings.apply {
            blockNetworkLoads = true
            allowContentAccess = false
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        })
    }
    view.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        blockNetworkLoads = true
        cacheMode = WebSettings.LOAD_NO_CACHE
        javaScriptCanOpenWindowsAutomatically = false
        // WebChromeClient refuses popup WebViews; never reuse this bridged view for one.
        setSupportMultipleWindows(true)
        setGeolocationEnabled(false)
    }
    view.setDownloadListener { _, _, _, _, _ -> /* No implicit download/navigation escape. */ }
    return true
}

internal const val LOCAL_MODULE_PROFILE = "lunexsu-local-modules-v1"
