package com.shixiaoshi0417.lunexsu.data.repository

import com.shixiaoshi0417.lunexsu.ui.util.ShellProbe
import kotlinx.coroutines.runBlocking
import okhttp3.HostHttp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory HTTP responses only; this fixture cannot contact a server. */
class TemplateRepositoryHostTest {
    private fun template(id: Any): JSONObject = JSONObject()
        .put("id", id).put("name", "Test").put("description", "Regression test")

    @Test
    fun remoteIndexRejectsUnsafeAndNonStringIdsBeforeFetchingThem() = runBlocking {
        ShellProbe.reset()
        val ids = JSONArray(listOf("../outside", "/outside", "a/b", "a b", "a\nb", "a'b", "a\"b", "\$(id)", "a;b", true, 123))
        HostHttp.reset(ids.toString())
        val writes = mutableListOf<String>()
        val repository = TemplateRepositoryImpl { id, _ -> writes.add(id); true }
        assertTrue(repository.getTemplates(true).isSuccess)
        assertEquals(listOf("https://kernelsu.org/templates/index.json"), HostHttp.requests)
        assertTrue(writes.isEmpty())
    }

    @Test
    fun remoteTemplateIdMustMatchIndex() = runBlocking {
        for (value in listOf(template("com.example.other"), template("../outside"), template(true))) {
            ShellProbe.reset()
            HostHttp.reset("[\"com.example.expected\"]", value.toString())
            val writes = mutableListOf<String>()
            val repository = TemplateRepositoryImpl { id, _ -> writes.add(id); true }
            assertTrue(repository.getTemplates(true).isSuccess)
            assertEquals(2, HostHttp.requests.size)
            assertTrue(writes.isEmpty())
        }
    }

    @Test
    fun validRemoteTemplateRetainsIdAndIsNotMarkedLocal() = runBlocking {
        ShellProbe.reset()
        HostHttp.reset("[\"com.example.app\"]", template("com.example.app").put("local", true).toString())
        val writes = mutableListOf<Pair<String, String>>()
        val repository = TemplateRepositoryImpl { id, json -> writes.add(id to json); true }
        assertTrue(repository.getTemplates(true).isSuccess)
        assertEquals("com.example.app", writes.single().first)
        assertEquals(false, JSONObject(writes.single().second).getBoolean("local"))
    }

    @Test
    fun storedTemplateIdMustMatchStorageKey() = runBlocking {
        ShellProbe.reset()
        ShellProbe.output = listOf(template("com.example.other").toString())
        val repository = TemplateRepositoryImpl { _, _ -> error("Read must not write") }
        assertTrue(repository.getTemplate("com.example.expected").isFailure)
    }
}
