package com.shixiaoshi0417.lunexsu.data.themestore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeStoreModelsTest {

    @Test
    fun normalizeUpgradesOnlyTheStoreHost() {
        assertEquals("https://folk.mysqil.com/images/a.webp", normalizeStoreUrl(" http://folk.mysqil.com/images/a.webp "))
        assertEquals("https://cdn.folk.mysqil.com/a.webp", normalizeStoreUrl("http://cdn.folk.mysqil.com/a.webp"))
        assertEquals("http://example.com/a.webp", normalizeStoreUrl("http://example.com/a.webp"))
        // A look-alike host must not be treated as the store.
        assertEquals("http://evilfolk.mysqil.com.example/a", normalizeStoreUrl("http://evilfolk.mysqil.com.example/a"))
        assertEquals("https://example.com/a", normalizeStoreUrl("https://example.com/a"))
    }

    @Test
    fun parseKeepsFirstIdAndDropsUnsafeDownloads() {
        val body = """
            [
              {"id": 399, "name": "Lacrimosa", "author": "Yuki", "description": "d", "version": "1.0",
               "preview_url": "http://folk.mysqil.com/images/x.webp",
               "download_url": "https://cdn.example.org/Lacrimosa.fpt", "type": "phone", "source": "github"},
              {"id": 399, "name": "Duplicate", "download_url": "https://cdn.example.org/dup.fpt"},
              {"id": 400, "name": "Plain", "download_url": "http://cdn.example.org/plain.fpt"},
              {"id": 401, "name": "", "download_url": "https://cdn.example.org/t.fpt", "type": "", "source": ""},
              {"name": "No id", "download_url": "https://cdn.example.org/n.fpt"},
              "not an object"
            ]
        """.trimIndent()
        val themes = parseThemeStoreIndex(body)
        assertEquals(listOf("399", "401"), themes.map { it.id })
        val first = themes[0]
        assertEquals("Lacrimosa", first.name)
        assertEquals("https://folk.mysqil.com/images/x.webp", first.previewUrl)
        assertEquals("github", first.source)
        val second = themes[1]
        assertEquals("401", second.name)
        assertEquals(THEME_TYPE_PHONE, second.type)
        assertEquals(THEME_SOURCE_THIRD_PARTY, second.source)
    }

    @Test
    fun filterMatchesQueryAuthorSourceAndType() {
        val official = theme("1", name = "Night Sky", author = "Yuki", source = THEME_SOURCE_OFFICIAL)
        val github = theme("2", name = "Eva", author = "Someone", source = "github", type = THEME_TYPE_TABLET)
        assertTrue(ThemeStoreFilter().matches(official))
        assertTrue(ThemeStoreFilter(query = "night").matches(official))
        assertFalse(ThemeStoreFilter(query = "night").matches(github))
        assertTrue(ThemeStoreFilter(author = "yuki").matches(official))
        assertTrue(ThemeStoreFilter(source = THEME_SOURCE_OFFICIAL).matches(official))
        // Anything that is not official counts as third-party, including "github".
        assertTrue(ThemeStoreFilter(source = THEME_SOURCE_THIRD_PARTY).matches(github))
        assertFalse(ThemeStoreFilter(source = THEME_SOURCE_THIRD_PARTY).matches(official))
        assertFalse(ThemeStoreFilter(tablet = false).matches(github))
        assertFalse(ThemeStoreFilter(phone = false, tablet = false).matches(official))
    }

    @Test
    fun onlyHttpsDownloadsAreSafe() {
        assertTrue(isSafeDownloadUrl("https://cdn.example.org/a.fpt"))
        assertFalse(isSafeDownloadUrl("http://cdn.example.org/a.fpt"))
        assertFalse(isSafeDownloadUrl("file:///sdcard/a.fpt"))
    }

    @Test
    fun sanitizedNamesStayInsideTheLibrary() {
        assertEquals("unnamed", ThemeDownloader.sanitizeFilename(".."))
        assertEquals("unnamed", ThemeDownloader.sanitizeFilename("  "))
        assertEquals("a_b_c", ThemeDownloader.sanitizeFilename("a/b\\c"))
        assertEquals("_etc_passwd", ThemeDownloader.sanitizeFilename("../etc/passwd"))
    }

    @Test
    fun onlyTextualHeadersAreRejectedAsErrorPages() {
        assertTrue(ThemeDownloader.looksLikeTextBody("<!DOCTYPE html>\n".toByteArray()))
        assertTrue(ThemeDownloader.looksLikeTextBody("{\"error\":\"nope\"}".toByteArray()))
        // A package whose random IV happens to start with '<' or '{' is still a package.
        val iv = byteArrayOf('<'.code.toByte(), 0x9f.toByte(), 0x01, 0x7f, 0x00, 0x33, 0xc4.toByte(), 0x10,
            0x55, 0x80.toByte(), 0x02, 0x41, 0xee.toByte(), 0x07, 0x6a, 0x12)
        assertFalse(ThemeDownloader.looksLikeTextBody(iv))
        assertFalse(ThemeDownloader.looksLikeTextBody(byteArrayOf('{'.code.toByte()) + iv.copyOfRange(1, 16)))
        assertFalse(ThemeDownloader.looksLikeTextBody("PK\u0003\u0004 zip".toByteArray()))
    }

    private fun theme(id: String, name: String, author: String, source: String, type: String = THEME_TYPE_PHONE) =
        RemoteTheme(id, name, author, "", "", "", "https://x/$id.fpt", type, source)
}
