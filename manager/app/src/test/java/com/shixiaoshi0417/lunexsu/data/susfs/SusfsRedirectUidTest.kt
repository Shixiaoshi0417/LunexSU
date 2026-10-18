package com.shixiaoshi0417.lunexsu.data.susfs

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SusfsRedirectUidTest {
    private fun status(implementation: String) = SusfsStatus(protocol = "compatible", implementation = implementation,
        features = listOf("CONFIG_KSU_SUSFS_OPEN_REDIRECT"))

    @Test fun `built-in offers and accepts all five UID schemes`() {
        val builtin = status("builtin")
        assertEquals((0..4).toList(), SusfsInputs.redirectUidSchemes(builtin))
        for (uid in 0..4) assertTrue(SusfsInputs.validRedirect("/source", "/destination", uid, builtin))
        for (uid in listOf(-1, 5, Int.MAX_VALUE)) assertFalse(SusfsInputs.validRedirect("/source", "/destination", uid, builtin))
        assertFalse(SusfsInputs.validRedirect("/source", "/source", 3, builtin))
        assertFalse(SusfsInputs.validRedirect("relative", "/destination", 4, builtin))
        assertFalse(SusfsInputs.validRedirect("/source", "relative", 4, builtin))
    }

    @Test fun `LKM only offers and accepts schemes zero through two`() {
        val lkm = status("lkm")
        assertEquals((0..2).toList(), SusfsInputs.redirectUidSchemes(lkm))
        for (uid in 0..2) assertTrue(SusfsInputs.validRedirect("/source", "/destination", uid, lkm))
        for (uid in listOf(-1, 3, 4, 5)) assertFalse(SusfsInputs.validRedirect("/source", "/destination", uid, lkm))
    }

    @Test fun `unknown protocols implementations unavailable capabilities and errors remain read-only`() {
        val builtin = status("builtin")
        val readonly = listOf(builtin.copy(protocol = "unknown_protocol"), builtin.copy(protocol = "root_unavailable"),
            builtin.copy(protocol = "not_detected"), builtin.copy(implementation = "unknown"),
            builtin.copy(implementation = "future"), builtin.copy(features = emptyList()), builtin.copy(error = "Invalid config"),
            status("lkm").copy(protocol = "unknown_protocol"))
        for (current in readonly) {
            assertTrue(SusfsInputs.redirectUidSchemes(current).isEmpty())
            for (uid in 0..4) assertFalse(SusfsInputs.validRedirect("/source", "/destination", uid, current))
        }
    }

    @Test fun `imported schemes three and four retain their values when previewed and edited`() {
        for (uid in 3..4) {
            val json = """{"schema":1,"automatic":false,"settings":{},"rules":[{"kind":"redirect","path":"/source","destination":"/destination","uid_scheme":$uid}],"lkm":{"hidden_modules":[],"mount_prefixes":[]}}"""
            val config = SusfsInputs.preview(json)
            val original = config.getJSONArray("rules").getJSONObject(0)
            assertEquals(uid, original.getInt("uid_scheme"))
            assertTrue(SusfsInputs.validRedirect(original.getString("path"), original.getString("destination"), uid, status("builtin")))
            // LKM cannot edit/apply this scope, but checking it must not clamp or rewrite imported JSON.
            assertFalse(SusfsInputs.validRedirect(original.getString("path"), original.getString("destination"), uid, status("lkm")))
            assertEquals(uid, SusfsInputs.preview(config.toString()).getJSONArray("rules").getJSONObject(0).getInt("uid_scheme"))
            val replacement = JSONObject(original.toString()).put("destination", "/edited_destination")
            SusfsInputs.replaceRule(config, original.toString(), replacement)
            val saved = SusfsInputs.preview(config.toString()).getJSONArray("rules").getJSONObject(0)
            assertEquals(uid, saved.getInt("uid_scheme"))
            assertEquals("/edited_destination", saved.getString("destination"))
            assertFalse(config.getBoolean("automatic"))
        }
    }
}
