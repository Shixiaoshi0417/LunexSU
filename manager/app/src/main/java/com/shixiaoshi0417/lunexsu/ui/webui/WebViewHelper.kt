package com.shixiaoshi0417.lunexsu.ui.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Message
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.shixiaoshi0417.lunexsu.R
import com.shixiaoshi0417.lunexsu.data.repository.ModuleRepositoryImpl
import com.shixiaoshi0417.lunexsu.ui.util.AppIconCache
import com.shixiaoshi0417.lunexsu.ui.util.createRootShell
import com.shixiaoshi0417.lunexsu.ui.util.withMainUserUid
import com.shixiaoshi0417.lunexsu.ui.viewmodel.SuperUserViewModel
import java.io.File

fun Activity.setTaskDescription(label: String) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        @Suppress("DEPRECATION")
        setTaskDescription(ActivityManager.TaskDescription(label))
    } else {
        val taskDescription = ActivityManager.TaskDescription.Builder()
            .setLabel(label)
            .build()
        setTaskDescription(taskDescription)
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal suspend fun prepareWebView(
    activity: Activity,
    moduleId: String,
    webUIState: WebUIState,
) {
    withContext(Dispatchers.IO) {
        val repo = ModuleRepositoryImpl()
        val modules = repo.getModules().getOrDefault(emptyList())
        val moduleInfo = modules.find { info -> info.id == moduleId }

        if (moduleInfo == null) {
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.no_such_module, moduleId))
            }
            return@withContext
        }

        if (!moduleInfo.hasWebUi || !moduleInfo.enabled || moduleInfo.update || moduleInfo.remove) {
            withContext(Dispatchers.Main) {
                webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.module_unavailable, moduleInfo.name))
            }
            return@withContext
        }

        webUIState.moduleName = moduleInfo.name
        webUIState.modDir = "/data/adb/modules/${moduleId}"

        if (SuperUserViewModel.apps.isEmpty()) {
            SuperUserViewModel().fetchAppList()
        }
        val shell = createRootShell(true)
        webUIState.rootShell = shell

        withContext(Dispatchers.Main) {
            activity.setTaskDescription(activity.getString(R.string.app_name) + " - ${moduleInfo.name}")

            val webView = WebView(activity)
            webView.setBackgroundColor(Color.TRANSPARENT)

            val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
            WebView.setWebContentsDebuggingEnabled(prefs.getBoolean("enable_web_debugging", false))

            if (!runCatching { configureLocalModuleWebView(webView) }.getOrDefault(false)) {
                webView.destroy()
                webUIState.uiEvent = WebUIEvent.Error("Module WebUI requires an updated Android System WebView with profile isolation")
                return@withContext
            }

            val webRoot = File("${webUIState.modDir}/webroot")
            val webViewAssetLoader = WebViewAssetLoader.Builder()
                .setDomain(WebUIOriginPolicy.HOST)
                .addPathHandler(
                    "/",
                    SuFilePathHandler(
                        activity,
                        webRoot,
                        shell,
                        { webUIState.currentInsets },
                        { enable -> webUIState.isInsetsEnabled = enable })
                )
                .build()

            // All frames share addJavascriptInterface. Only intercepted local documents
            // may enter this view; never decide bridge access from webView.url.
            webView.webViewClient = object : LocalModuleWebViewClient(
                loadLocal = { url -> webViewAssetLoader.shouldInterceptRequest(url) },
                loadIcon = { url ->
                    val packageName = url.path?.substring(1)
                    val appInfo = SuperUserViewModel.apps
                        .find { it.packageName == packageName }
                        ?.packageInfo?.applicationInfo
                    if (appInfo == null) {
                        null
                    } else {
                        val icon = AppIconCache.loadIconSync(activity, appInfo.withMainUserUid(activity), 512)
                        val stream = java.io.ByteArrayOutputStream()
                        icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
                        WebResourceResponse(
                            "image/png", null, 200, "OK",
                            mapOf("Access-Control-Allow-Origin" to WebUIOriginPolicy.ORIGIN),
                            java.io.ByteArrayInputStream(stream.toByteArray())
                        )
                    }
                },
                openExternal = { url -> openExternalWebLink(activity, url) }
            ) {
                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    webUIState.webCanGoBack = view?.canGoBack() ?: false
                    if (webUIState.isInsetsEnabled) webUIState.webView?.evaluateJavascript(webUIState.currentInsets.js, null)
                    super.doUpdateVisitedHistory(view, url, isReload)
                }
            }

            // WebChromeClient
            webView.webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
                    // Handle user-clicked target=_blank HTTP(S) anchors without creating
                    // another bridged WebView. Script popups and unknown targets are denied.
                    val hit = view.hitTestResult
                    if (isUserGesture && hit.type in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
                        hit.extra?.let { url -> openExternalWebLink(activity, Uri.parse(url)) }
                    }
                    return false
                }

                override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowAlert(message, result)
                    return true
                }

                override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowConfirm(message, result)
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?
                ): Boolean {
                    if (message == null || result == null || defaultValue == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowPrompt(message, defaultValue, result)
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?
                ): Boolean {
                    webUIState.filePathCallback?.onReceiveValue(null)
                    webUIState.filePathCallback = filePathCallback

                    val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    webUIState.uiEvent = WebUIEvent.ShowFileChooser(intent)
                    return true
                }
            }

            // Keep the existing local-module API (and demo's non-executing shell stubs).
            // These settings, client and CSP must remain installed for the bridge lifetime.
            val webviewInterface = WebViewInterface(webUIState)
            webUIState.webView = webView
            webView.addJavascriptInterface(webviewInterface, "ksu")
            webUIState.uiEvent = WebUIEvent.WebViewReady
        }
    }
}

private fun openExternalWebLink(activity: Activity, url: Uri) {
    if (!WebUIOriginPolicy.isExternalWebLink(url.toString())) return
    try {
        activity.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (_: ActivityNotFoundException) {
        // No browser installed: remain on the trusted local page, never fall back to WebView.
    }
}
