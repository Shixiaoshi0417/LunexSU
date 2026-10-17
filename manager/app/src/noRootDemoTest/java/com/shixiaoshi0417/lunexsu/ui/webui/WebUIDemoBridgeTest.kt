package com.shixiaoshi0417.lunexsu.ui.webui

import com.shixiaoshi0417.lunexsu.BuildConfig
import com.shixiaoshi0417.lunexsu.ui.util.createRootShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUIDemoBridgeTest {
    @Test fun synchronousBridgeStillUsesNonExecutingDemoShell() {
        assertTrue(BuildConfig.NO_ROOT_DEMO)
        createRootShell(true).use { assertFalse(it.isRoot) }
        // Deliberately nonexistent command: no process or privileged side effect is used.
        assertEquals("", WebViewInterface(WebUIState()).exec("lunexsu_demo_webui_never_execute"))
    }
}
