package com.shixiaoshi0417.lunexsu.data.repository

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateImportSecurityTest {
    private fun template(id: Any): JSONObject = JSONObject()
        .put("id", id).put("name", "Test").put("description", "Regression test")

    @Test
    fun validatesWholeBatchBeforeWriting() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = TemplateRepositoryImpl { id, _ -> writes.add(id); true }
        for (id in listOf("../outside", "/outside", "a/b", "a\\b", "a'b", "a\"b", "a b", "a\nb", "\$(id)", "a;b")) {
            val input = JSONArray().put(template("com.example.valid")).put(template(id))
            val result = repository.importTemplates(input.toString())
            assertTrue(id, result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("index 1"))
            assertTrue(writes.isEmpty())
        }
    }

    @Test
    fun reportsMalformedAndNonStringIds() = runBlocking {
        val writes = mutableListOf<String>()
        val repository = TemplateRepositoryImpl { id, _ -> writes.add(id); true }
        for (input in listOf("not json", "[1]", "[{}]", template(123).toString())) {
            assertTrue(input, repository.importTemplates(input).isFailure)
        }
        assertTrue(writes.isEmpty())
    }

    @Test
    fun preservesTemplateDataAndMarksImportedTemplatesLocal() = runBlocking {
        val writes = mutableListOf<Pair<String, String>>()
        val repository = TemplateRepositoryImpl { id, json -> writes.add(id to json); true }
        val rules = "allow a b c 'quoted'\n\$(literal); \"quotes\" \\ path /"
        val input = template("com.example.App_123").put("local", false)
            .put("rules", JSONArray().put(rules))
        assertTrue(repository.importTemplates(input.toString()).isSuccess)
        assertEquals("com.example.App_123", writes.single().first)
        val stored = JSONObject(writes.single().second)
        assertTrue(stored.getBoolean("local"))
        assertEquals(rules, stored.getJSONArray("rules").getString(0))
    }

    @Test
    fun reportsStorageFailureInsteadOfPretendingImportSucceeded() = runBlocking {
        val repository = TemplateRepositoryImpl { _, _ -> false }
        val result = repository.importTemplates(template("com.example.app").toString())
        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("Failed to save template com.example.app"))
    }
}
