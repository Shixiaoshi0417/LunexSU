package com.shixiaoshi0417.lunexsu.ui.webui

import java.net.URI

/** Pure policy: a bridge belongs to the entire WebView, NOT just its top-level URL. */
internal object WebUIOriginPolicy {
    const val HOST = "mui.kernelsu.org"
    const val ORIGIN = "https://$HOST"

    enum class Resource { LOCAL, ICON, BLOCK }
    enum class Navigation { LOCAL, EXTERNAL_BROWSER, BLOCK }

    private val encodedPathSeparator = Regex("%(?:2f|5c|25)", RegexOption.IGNORE_CASE)
    private val packagePath = Regex("/[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    // Explicit source expressions deliberately exclude blob:/data: executable documents.
    // Module-authored inline/eval JS remains trusted, as does every file in its webroot.
    val contentSecurityPolicy = listOf(
        "default-src 'none'",
        "script-src $ORIGIN 'unsafe-inline' 'unsafe-eval'",
        "style-src $ORIGIN 'unsafe-inline'",
        "img-src $ORIGIN data: ksu://icon",
        "font-src $ORIGIN data:",
        "media-src $ORIGIN data:",
        "connect-src $ORIGIN",
        "frame-src $ORIGIN",
        "child-src $ORIGIN",
        "worker-src 'none'",
        "object-src 'none'",
        "base-uri 'none'",
        "form-action $ORIGIN",
        "frame-ancestors $ORIGIN"
    ).joinToString("; ")

    private fun parse(url: String): URI? {
        // Android Uri and Chromium are more lenient than URI. Reject ambiguous spellings
        // before either parser can interpret backslashes, whitespace or escaped authorities.
        if (url.any { it <= ' ' || it == '\\' || it == '\u007f' }) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.isOpaque || uri.rawUserInfo != null || uri.rawAuthority.isNullOrEmpty()) return null
        if (uri.rawAuthority.any { it == '%' || it.code > 127 }) return null
        return uri
    }

    fun isLocal(url: String): Boolean {
        val uri = parse(url) ?: return false
        if (!uri.scheme.equals("https", true)) return false
        if (!uri.rawAuthority.equals(HOST, true) && !uri.rawAuthority.equals("$HOST:443", true)) return false
        val path = uri.path.orEmpty()
        if (path.isNotEmpty() && (!path.startsWith('/') || path.startsWith("//"))) return false
        if (path.any { it < ' ' || it == '\\' || it == '\u007f' }) return false
        if (path.split('/').any { it == "." || it == ".." }) return false
        // One unambiguous decoding pass for the Android asset handler. Do not accept
        // encoded separators or double-encoded traversal into a root-backed file loader.
        if (encodedPathSeparator.containsMatchIn(uri.rawPath.orEmpty())) return false
        return true
    }

    fun isExternalWebLink(url: String): Boolean {
        val uri = parse(url) ?: return false
        return (uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) &&
            !uri.host.isNullOrEmpty() && !isLocal(url)
    }

    private fun isIcon(url: String): Boolean {
        val uri = parse(url) ?: return false
        return uri.scheme.equals("ksu", true) && uri.rawAuthority.equals("icon", true) &&
            uri.rawQuery == null && uri.rawFragment == null &&
            packagePath.matches(uri.rawPath.orEmpty())
    }

    fun resource(url: String, method: String = "GET", isMainFrame: Boolean = false): Resource = when {
        method != "GET" -> Resource.BLOCK
        isLocal(url) -> Resource.LOCAL
        !isMainFrame && isIcon(url) -> Resource.ICON
        else -> Resource.BLOCK
    }

    fun navigation(
        url: String,
        isMainFrame: Boolean,
        hasGesture: Boolean,
        isRedirect: Boolean = false
    ): Navigation = when {
        isLocal(url) -> Navigation.LOCAL
        isMainFrame && hasGesture && !isRedirect && isExternalWebLink(url) -> Navigation.EXTERNAL_BROWSER
        else -> Navigation.BLOCK
    }
}
