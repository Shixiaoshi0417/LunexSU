package com.shixiaoshi0417.lunexsu.ui.webui

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Compile-only for the local audit. Separate root-free instrumentation entry point;
 * no Natives, root shell, real module files, network server or real exec bridge.
 */
class WebUIIsolationTestRunner : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(targetContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            testRequestsAndNavigation()
            val rendererSupported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
            if (rendererSupported) testRendererIsolation()
            result.putBoolean("renderer_exercised", rendererSupported)
            result.putString("tests", if (rendererSupported)
                "profile_isolation,request_policy,navigation_redirects,local_scripts_frames,csp_remote_data_blob,bridge_revocation"
                else "unsupported_engine_fails_closed")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            result.putString("error", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private class Request(
        url: String,
        private val main: Boolean = false,
        private val gesture: Boolean = false,
        private val redirect: Boolean = false,
        private val verb: String = "GET"
    ) : WebResourceRequest {
        private val uri = Uri.parse(url)
        override fun getUrl() = uri
        override fun isForMainFrame() = main
        override fun hasGesture() = gesture
        override fun isRedirect() = redirect
        override fun getMethod() = verb
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun response(mime: String, body: String) = WebResourceResponse(
        mime, "utf-8", ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
    )

    private fun testRequestsAndNavigation() {
        runOnMainSync {
            val view = WebView(targetContext)
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                    check(!configureLocalModuleWebView(view))
                    check(!view.settings.javaScriptEnabled)
                    return@runOnMainSync
                }
                check(configureLocalModuleWebView(view))
                check(WebViewCompat.getProfile(view).name == LOCAL_MODULE_PROFILE)
                val workerSettings = WebViewCompat.getProfile(view).serviceWorkerController.serviceWorkerWebSettings
                check(workerSettings.blockNetworkLoads && !workerSettings.allowFileAccess && !workerSettings.allowContentAccess)
                check(view.settings.blockNetworkLoads && !view.settings.allowFileAccess && !view.settings.allowContentAccess)
                check(view.settings.mixedContentMode == WebSettings.MIXED_CONTENT_NEVER_ALLOW)
                check(view.settings.cacheMode == WebSettings.LOAD_NO_CACHE)
                check(view.settings.supportMultipleWindows() && !view.settings.javaScriptCanOpenWindowsAutomatically)
                val opened = mutableListOf<String>()
                var localLoads = 0
                var iconLoads = 0
                val client = LocalModuleWebViewClient(
                    loadLocal = {
                        localLoads++
                        if (it.path == "/missing") null else response("text/html", "<p>local</p>")
                    },
                    loadIcon = { iconLoads++; response("image/png", "fixture") },
                    openExternal = { opened += it.toString() }
                )
                val origin = WebUIOriginPolicy.ORIGIN
                val local = client.shouldInterceptRequest(view, Request("$origin/index.html", main = true))
                check(local.statusCode == 200)
                check(local.responseHeaders["Content-Security-Policy"] == WebUIOriginPolicy.contentSecurityPolicy)
                check(client.shouldInterceptRequest(view, Request("$origin/missing")).statusCode == 404)
                val before = localLoads
                listOf("https://remote.invalid/script.js", "$origin:444/frame", "https://mui.kernelsu.org./frame",
                    "https://mui.kernelsu.org@remote.invalid/", "https://mui%2ekernelsu.org/", "$origin/%2e%2e/secret",
                    "file:///data/adb/modules/a/webroot/index.html", "content://fixture/file", "data:text/html,attack")
                    .forEach {
                        val denied = client.shouldInterceptRequest(view, Request(it))
                        check(denied.statusCode == 403 && denied.responseHeaders.containsKey("Content-Security-Policy"))
                    }
                check(client.shouldInterceptRequest(view, Request("$origin/index.html", verb = "POST")).statusCode == 403)
                check(localLoads == before)
                check(client.shouldInterceptRequest(view, Request("ksu://icon/android")).statusCode == 200)
                check(client.shouldInterceptRequest(view, Request("ksu://icon/android", main = true)).statusCode == 403)
                check(iconLoads == 1)
                check(!client.shouldOverrideUrlLoading(view, Request("$origin/frame.html")))
                check(client.shouldOverrideUrlLoading(view, Request("https://remote.invalid/help", main = true, gesture = true)))
                check(opened == listOf("https://remote.invalid/help"))
                check(client.shouldOverrideUrlLoading(view, Request("https://remote.invalid/iframe", gesture = true)))
                check(client.shouldOverrideUrlLoading(view, Request("https://remote.invalid/redirect", main = true, gesture = true, redirect = true)))
                check(client.shouldOverrideUrlLoading(view, Request("https://remote.invalid/automatic", main = true)))
                check(opened.size == 1)
                // Simulate a future local loader accidentally returning a redirect.
                val redirecting = LocalModuleWebViewClient(
                    loadLocal = { object : WebResourceResponse("text/html", "utf-8", null) {
                        override fun getStatusCode() = 302
                    } },
                    loadIcon = { null }, openExternal = { error("Redirect must not open a browser") }
                )
                check(redirecting.shouldInterceptRequest(view, Request("$origin/redirect")).statusCode == 403)
                client.onPageStarted(view, "https://remote.invalid/unexpected-native-load", null)
                check(!view.settings.javaScriptEnabled)
            } finally {
                view.destroy()
            }
        }
    }

    private class Probe {
        val events: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val local = CountDownLatch(3)
        val settled = CountDownLatch(1)

        @JavascriptInterface fun report(label: String) {
            if (events.add(label) && label in setOf("local-top", "local-script", "local-frame")) local.countDown()
            if (label == "settled") settled.countDown()
        }
    }

    private fun testRendererIsolation() {
        lateinit var view: WebView
        val probe = Probe()
        val paths: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val origin = WebUIOriginPolicy.ORIGIN
        val html = """
            <!doctype html><meta charset="utf-8">
            <script>ksu.report('local-top');</script>
            <script src="/app.js"></script>
            <iframe src="/child.html"></iframe>
            <iframe src="/refresh.html"></iframe>
            <script src="https://remote.invalid/attack.js"></script>
            <iframe src="https://remote.invalid/attack.html"></iframe>
            <iframe src="data:text/html,%3Cscript%3Eksu.report('untrusted-data')%3C/script%3E"></iframe>
            <script>
              const frame = document.createElement('iframe');
              frame.src = URL.createObjectURL(new Blob(["<script>ksu.report('untrusted-blob')<\/script>"], {type: 'text/html'}));
              document.body.appendChild(frame);
              fetch('https://remote.invalid/api').then(() => ksu.report('untrusted-fetch')).catch(() => {});
              try { new Worker('/worker.js'); } catch (_) {}
              if (navigator.serviceWorker) navigator.serviceWorker.register('/worker.js').catch(() => {});
              setTimeout(() => ksu.report('settled'), 1000);
            </script>
        """.trimIndent()
        runOnMainSync {
            view = WebView(targetContext)
            check(configureLocalModuleWebView(view))
            view.webChromeClient = WebChromeClient() // Refuse all popup WebViews.
            view.webViewClient = LocalModuleWebViewClient(
                loadLocal = { uri ->
                    check(WebUIOriginPolicy.isLocal(uri.toString()))
                    paths += uri.path.orEmpty()
                    when (uri.path) {
                        "/index.html" -> response("text/html", html)
                        "/app.js" -> response("application/javascript", "ksu.report('local-script');")
                        "/child.html" -> response("text/html", "<script>ksu.report('local-frame');</script>")
                        "/refresh.html" -> response("text/html", "<meta http-equiv='refresh' content='0;url=https://remote.invalid/redirect'>")
                        "/worker.js" -> response("application/javascript", "throw new Error('Worker must not execute');")
                        else -> null
                    }
                },
                loadIcon = { null },
                openExternal = { error("Automatic navigation must never launch an external browser") }
            )
            // The probe has no exec API or privileged operations.
            view.addJavascriptInterface(probe, "ksu")
            view.loadUrl("$origin/index.html")
        }
        try {
            check(probe.local.await(15, TimeUnit.SECONDS)) { "Local API/script/frame stopped working: ${probe.events}" }
            check(probe.settled.await(15, TimeUnit.SECONDS)) { "Fixture did not settle" }
            check(probe.events.none { it.startsWith("untrusted-") }) { "Untrusted content reached bridge: ${probe.events}" }
            check("/worker.js" !in paths) { "Worker registration bypassed CSP" }
            runOnMainSync { check(WebUIOriginPolicy.isLocal(view.url.orEmpty()) && view.settings.javaScriptEnabled) }
        } finally {
            runOnMainSync { view.removeJavascriptInterface("ksu"); view.destroy() }
        }
    }
}
