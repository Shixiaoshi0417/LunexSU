package com.shixiaoshi0417.lunexsu.data.themestore

import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemeLibraryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val remote = RemoteTheme("399", "Theme", "Author", "", "1", "", "https://example.org/1.fpt", "phone", "official")
    private fun library() = ThemeLibrary(File(temporary.root, "themes"))
    private fun stage(payload: String = "version one") = temporary.newFolder().also {
        File(it, "Theme.fpt").writeText(payload)
    }

    @Test fun revisionUsesVersionAndDownloadUrlNotJustId() {
        val local = library().publish(stage(), remote)
        assertTrue(local.matchesRevision(remote))
        assertFalse(local.matchesRevision(remote.copy(version = "2")))
        assertFalse(local.matchesRevision(remote.copy(downloadUrl = "https://example.org/new.fpt")))
    }

    @Test fun barePayloadAndCorruptOrMissingIdentityMarkersAreIgnored() {
        val dir = File(temporary.root, "themes/Author/Theme").also { it.mkdirs() }
        File(dir, "Theme.fpt").writeText("complete payload, preview not finished")
        val library = library()
        assertTrue(library.scan().isEmpty())
        File(dir, ThemeLibrary.META_FILE_NAME).writeText("{")
        assertTrue(library.scan().isEmpty())
        File(dir, ThemeLibrary.META_FILE_NAME).writeText("{\"name\":\"Theme\"}")
        assertTrue(library.scan().isEmpty())
    }

    @Test fun legacyCompletedThemesRetainTheirStoreId() {
        val dir = File(temporary.root, "themes/Author/Theme").also { it.mkdirs() }
        File(dir, "Theme.fpt").writeText("legacy")
        File(dir, ThemeLibrary.META_FILE_NAME).writeText(JSONObject().put("id", "399").put("name", "Theme").toString())
        assertEquals("399", library().scan().single().id)
    }

    @Test fun updatingReplacesRevisionOnlyAfterCompletePublication() {
        val library = library()
        val old = library.publish(stage(), remote)
        val update = remote.copy(version = "2", downloadUrl = "https://example.org/2.fpt")
        val staging = stage("version two")
        assertEquals(old, library.scan().single())
        val published = library.publish(staging, update)
        assertFalse(staging.exists())
        assertFalse(File(old.localPath).exists())
        assertTrue(published.matchesRevision(update))
        assertEquals("version two", File(library.scan().single().localPath).readText())
    }

    @Test fun failedOrCancelledUpdateKeepsOldRevision() {
        val library = library()
        val old = library.publish(stage(), remote)
        assertThrows(IOException::class.java) { library.publish(temporary.newFolder(), remote.copy(version = "2")) }
        val cancelled = stage("unfinished update")
        cancelled.deleteRecursively()
        assertEquals(old, library.scan().single())
        assertEquals("version one", File(old.localPath).readText())
    }

    @Test fun sameNamesWithDifferentIdsDoNotOverwriteEachOther() {
        val library = library()
        val first = library.publish(stage("first"), remote)
        val second = library.publish(stage("second"), remote.copy(id = "400"))
        assertEquals(2, library.scan().size)
        assertEquals("first", File(first.localPath).readText())
        assertEquals("second", File(second.localPath).readText())
    }
}
