package com.shixiaoshi0417.lunexsu.ui.component.profile

import com.shixiaoshi0417.lunexsu.Natives
import com.shixiaoshi0417.lunexsu.data.model.TemplateInfo
import com.shixiaoshi0417.lunexsu.ui.screen.appprofile.AuthorizationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileEditsTest {
    private val timed = Natives.Profile(
        name = "example.app", currentUid = 10123, allowSu = true,
        rules = "allow old rule", flags = 0, authorizationMode = 2,
        authorizationGeneration = 17, authorizationDurationSeconds = 900,
        authorizationRemainingSeconds = 41, authorizationSupported = true,
    )

    @Test fun combinedSelinuxEditPersistsClearAndKeepsAuthorizationOnOneSave() = runBlocking {
        val edited = timed.withSELinux("u:r:new:s0", "")
        val writes = mutableListOf<Pair<String, String>>()
        assertTrue(persistProfileRules(edited, false) { name, rules -> writes.add(name to rules) })
        var saves = 0
        val repository = AuthorizationRepository(
            read = { edited.copy(authorizationRemainingSeconds = 40) },
            write = {
                saves++
                assertEquals("u:r:new:s0", it.context)
                assertEquals("", it.rules)
                assertFalse(it.rootUseDefault)
                assertEquals(17L, it.authorizationGeneration)
                assertEquals(0, it.authorizationOperation)
                assertEquals(900L, it.authorizationDurationSeconds)
                0
            },
        )
        val saved = repository.save(edited)
        assertEquals(0, saved.error)
        assertEquals(40L, saved.profile.authorizationRemainingSeconds)
        assertEquals(listOf("example.app" to ""), writes)
        assertEquals(1, saves)
    }

    @Test fun ruleWriteFailureIsReportedIncludingAnEmptyClear() {
        var calls = 0
        assertFalse(persistProfileRules(timed.withSELinux(timed.context, ""), false) { _, _ -> calls++; false })
        assertEquals(1, calls)
        for (profile in listOf(timed.copy(allowSu = false), timed.copy(rootUseDefault = true))) {
            assertTrue(persistProfileRules(profile, false) { _, _ -> fail("Unexpected write"); false })
        }
        assertTrue(persistProfileRules(timed.copy(rootUseDefault = false), true) { _, _ -> fail("Special app write"); false })
    }

    @Test fun templateCopiesAllRootFieldsAndReplacesFlagsInBothDirections() {
        val template = TemplateInfo(id = "restricted", uid = 2000, gid = 2000, groups = listOf(2000),
            capabilities = listOf(1, 2), context = "u:r:shell:s0", namespace = 2, flags = listOf(0))
        val applied = timed.withRootTemplate(template.id, template)
        assertEquals(timed.copy(rootUseDefault = false, rootTemplate = "restricted", uid = 2000, gid = 2000,
            groups = listOf(2000), capabilities = listOf(1, 2), context = "u:r:shell:s0", namespace = 2, flags = 1), applied)
        assertEquals(0L, applied.withRootTemplate("unrestricted", template.copy(flags = emptyList())).flags)
    }

    @Test fun templateDoesNotRearmConsumedOnceOrRenewExpiredTimed() = runBlocking {
        for (mode in listOf(1, 2)) {
            val expired = timed.copy(authorizationMode = mode, authorizationState = 0, authorizationRemainingSeconds = 0)
            val applied = expired.withRootTemplate("template", TemplateInfo())
            var writes = 0
            val rejected = AuthorizationRepository({ expired }, {
                writes++
                assertEquals(0, it.authorizationOperation)
                assertEquals(17L, it.authorizationGeneration)
                assertEquals(0L, it.authorizationRemainingSeconds)
                -11
            }).save(applied)
            assertEquals(-11, rejected.error)
            assertEquals(expired, rejected.profile)
            assertEquals(1, writes)
        }
    }
}
