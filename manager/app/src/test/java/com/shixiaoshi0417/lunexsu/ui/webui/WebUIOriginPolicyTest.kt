package com.shixiaoshi0417.lunexsu.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUIOriginPolicyTest {
    private val origin = WebUIOriginPolicy.ORIGIN

    @Test fun exactHttpsOriginAndDefaultPortAreLocal() {
        listOf(origin, "$origin/", "$origin/index.html", "$origin:443/child.html",
            "HTTPS://MUI.KERNELSU.ORG/app.js?v=1#section", "$origin/nested/a%20b.css")
            .forEach { assertTrue(it, WebUIOriginPolicy.isLocal(it)) }
    }

    @Test fun lookalikeOriginsAndAuthoritiesAreNotLocal() {
        listOf("http://mui.kernelsu.org/", "$origin:444/index.html", "$origin./",
            "https://sub.mui.kernelsu.org/", "https://mui.kernelsu.org.attacker.invalid/",
            "https://mui.kernelsu.org@attacker.invalid/", "https://attacker@mui.kernelsu.org/",
            "https://mui%2ekernelsu.org/", "https://mui.kernelsu.org:/", "$origin:0443/",
            "https://mui.kernelsu.org\\@attacker.invalid/", "https:////mui.kernelsu.org/",
            "//mui.kernelsu.org/index.html", " $origin/", "$origin/\nfile")
            .forEach { assertFalse(it, WebUIOriginPolicy.isLocal(it)) }
    }

    @Test fun alternateDocumentSchemesNeverBecomeLocal() {
        listOf("about:blank", "about:srcdoc", "data:text/html,<script>ksu.exec('x')</script>",
            "blob:$origin/id", "file:///data/adb/modules/test/webroot/index.html",
            "content://module/index.html", "javascript:ksu.exec('x')", "ksu://icon/android",
            "intent://example.invalid/#Intent;scheme=https;end")
            .forEach { assertFalse(it, WebUIOriginPolicy.isLocal(it)) }
    }

    @Test fun decodedTraversalAndAmbiguousLocalPathsFailClosed() {
        listOf("/../secret", "/a/./b", "/%2e%2e/secret", "/a/%2E%2e/secret",
            "/a%2fb", "/a%5Cb", "/%252e%252e/secret", "/%00index.html", "/%0dfile",
            "//attacker.invalid/script.js", "/bad%ZZ")
            .forEach { assertFalse(it, WebUIOriginPolicy.isLocal(origin + it)) }
    }

    @Test fun onlyLocalGetResourcesCanReachTheAssetLoader() {
        assertEquals(WebUIOriginPolicy.Resource.LOCAL, WebUIOriginPolicy.resource("$origin/index.html", isMainFrame = true))
        assertEquals(WebUIOriginPolicy.Resource.LOCAL, WebUIOriginPolicy.resource("$origin/app.js"))
        listOf("POST", "PUT", "HEAD", "DELETE", "get").forEach {
            assertEquals(it, WebUIOriginPolicy.Resource.BLOCK, WebUIOriginPolicy.resource("$origin/index.html", it))
        }
    }

    @Test fun remoteScriptsFramesImagesFetchesAndRedirectTargetsAreBlocked() {
        listOf("https://cdn.invalid/app.js", "https://remote.invalid/iframe.html",
            "http://remote.invalid/image.png", "https://remote.invalid/api",
            "wss://remote.invalid/socket", "blob:$origin/worker", "data:text/html,attack")
            .forEach { url ->
                assertEquals(url, WebUIOriginPolicy.Resource.BLOCK, WebUIOriginPolicy.resource(url))
                assertEquals(url, WebUIOriginPolicy.Resource.BLOCK, WebUIOriginPolicy.resource(url, isMainFrame = true))
            }
    }

    @Test fun iconsAreAReadOnlyNonDocumentException() {
        assertEquals(WebUIOriginPolicy.Resource.ICON, WebUIOriginPolicy.resource("ksu://icon/com.example.app"))
        assertEquals(WebUIOriginPolicy.Resource.ICON, WebUIOriginPolicy.resource("ksu://icon/android"))
        assertEquals(WebUIOriginPolicy.Resource.BLOCK, WebUIOriginPolicy.resource("ksu://icon/android", isMainFrame = true))
        listOf("ksu://icon/", "ksu://icon/a/b", "ksu://icon/../file", "ksu://icon:443/android",
            "ksu://icon/android?x=1", "ksu://icon/%61ndroid", "ksu://else/android")
            .forEach { assertEquals(it, WebUIOriginPolicy.Resource.BLOCK, WebUIOriginPolicy.resource(it)) }
    }

    @Test fun onlyUserInitiatedTopLevelExternalWebLinksLeaveForABrowser() {
        val remote = "https://example.invalid/help"
        assertEquals(WebUIOriginPolicy.Navigation.EXTERNAL_BROWSER, WebUIOriginPolicy.navigation(remote, true, true))
        assertEquals(WebUIOriginPolicy.Navigation.EXTERNAL_BROWSER, WebUIOriginPolicy.navigation("http://example.invalid/", true, true))
        assertEquals(WebUIOriginPolicy.Navigation.BLOCK, WebUIOriginPolicy.navigation(remote, false, true))
        assertEquals(WebUIOriginPolicy.Navigation.BLOCK, WebUIOriginPolicy.navigation(remote, true, false))
        assertEquals(WebUIOriginPolicy.Navigation.BLOCK, WebUIOriginPolicy.navigation(remote, false, false))
    }

    @Test fun redirectsNeverOpenExternalContentEvenWhenGestureIsCarriedOver() {
        assertEquals(WebUIOriginPolicy.Navigation.BLOCK,
            WebUIOriginPolicy.navigation("https://remote.invalid/redirect", true, true, isRedirect = true))
        assertEquals(WebUIOriginPolicy.Navigation.BLOCK,
            WebUIOriginPolicy.navigation("https://remote.invalid/frame", false, true, isRedirect = true))
        assertEquals(WebUIOriginPolicy.Navigation.LOCAL,
            WebUIOriginPolicy.navigation("$origin/next.html", true, false, isRedirect = true))
    }

    @Test fun allLocalFramesRemainTrustedAndUnsupportedExternalSchemesNeverLaunch() {
        assertEquals(WebUIOriginPolicy.Navigation.LOCAL, WebUIOriginPolicy.navigation("$origin/frame.html", false, false))
        listOf("file:///tmp/file", "data:text/html,attack", "blob:$origin/id", "about:blank",
            "javascript:alert(1)", "intent://x/#Intent;end", "ksu://icon/android", "tel:123")
            .forEach { assertEquals(it, WebUIOriginPolicy.Navigation.BLOCK, WebUIOriginPolicy.navigation(it, true, true)) }
    }

    @Test fun browserLinksCannotContainCredentialsOrAmbiguousAuthorities() {
        listOf("https://user:password@example.invalid/", "https://example.invalid\\@other.invalid/",
            "https://example%2einvalid/", "https://example.invalid/\nnext")
            .forEach { assertFalse(it, WebUIOriginPolicy.isExternalWebLink(it)) }
    }

    @Test fun cspRestrictsEveryExecutableAndNetworkSink() {
        val directives = WebUIOriginPolicy.contentSecurityPolicy.split("; ").toSet()
        assertTrue(directives.containsAll(listOf("default-src 'none'", "script-src $origin 'unsafe-inline' 'unsafe-eval'",
            "frame-src $origin", "connect-src $origin", "worker-src 'none'", "object-src 'none'",
            "base-uri 'none'", "form-action $origin", "frame-ancestors $origin")))
        assertFalse(WebUIOriginPolicy.contentSecurityPolicy.contains("blob:"))
        assertFalse(WebUIOriginPolicy.contentSecurityPolicy.contains("*"))
    }
}
