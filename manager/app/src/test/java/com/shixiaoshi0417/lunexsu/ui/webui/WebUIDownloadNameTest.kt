package com.shixiaoshi0417.lunexsu.ui.webui

import org.junit.Assert.assertEquals
import org.junit.Test

class WebUIDownloadNameTest {
    @Test fun pathPartsAreDropped() {
        assertEquals("passwd", sanitizeWebUIDownloadFileName("../../etc/passwd"))
        assertEquals("evil.sh", sanitizeWebUIDownloadFileName("C:\\tmp\\evil.sh"))
    }

    @Test fun hiddenEmptyAndControlNamesFallBack() {
        assertEquals("download.bin", sanitizeWebUIDownloadFileName(null))
        assertEquals("download.bin", sanitizeWebUIDownloadFileName("   "))
        assertEquals("download.bin", sanitizeWebUIDownloadFileName(".."))
        assertEquals("bashrc", sanitizeWebUIDownloadFileName(".bashrc"))
        assertEquals("ab.txt", sanitizeWebUIDownloadFileName("a\u0000b\n.txt"))
    }

    @Test fun longNamesAreCapped() {
        assertEquals(128, sanitizeWebUIDownloadFileName("a".repeat(300)).length)
    }

    @Test fun invalidMimeTypesAreReplaced() {
        assertEquals("text/plain", sanitizeWebUIDownloadMimeType("x.txt", "text/plain"))
        assertEquals("text/plain", sanitizeWebUIDownloadMimeType("x.txt", "text/plain\r\nX: y"))
        assertEquals("application/octet-stream", sanitizeWebUIDownloadMimeType("x.unknownext", null))
    }
}
