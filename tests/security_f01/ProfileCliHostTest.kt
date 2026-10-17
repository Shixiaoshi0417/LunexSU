package com.shixiaoshi0417.lunexsu.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs the unmodified production CLI method bodies against a recording shell, never root. */
class ProfileCliHostTest {
    @Test
    fun invalidIdsAndNulAreRejectedBeforeAcquiringAShell() {
        ShellProbe.reset()
        for (id in listOf("", "../outside", "a/b", "a\\b", "a b", "a\nb", "a'b", "a\"b", "\$(id)", "a;b")) {
            assertEquals("", getSepolicy(id))
            assertFalse(setSepolicy(id, "rules"))
            assertEquals("", getAppProfileTemplate(id))
            assertFalse(setAppProfileTemplate(id, "{}"))
            assertFalse(deleteAppProfileTemplate(id))
        }
        assertFalse(setSepolicy("com.example.app", "a\u0000b"))
        assertFalse(setAppProfileTemplate("com.example.app", "a\u0000b"))
        assertFalse(isSepolicyValid("a\u0000b"))
        assertTrue(isSepolicyValid(null))
        assertEquals(0, ShellProbe.opens)
        assertTrue(ShellProbe.commands.isEmpty())
    }

    @Test
    fun productionMethodsPassEachValueAsOneLiteralArgument() {
        ShellProbe.reset()
        val id = "com.example.App_123"
        val payload = "'\"\n\t padded / \\ \$(printf expanded); `printf expanded` & | #\n"
        assertTrue(isSepolicyValid(payload))
        getSepolicy(id)
        assertTrue(setSepolicy(id, payload))
        getAppProfileTemplate(id)
        assertTrue(setAppProfileTemplate(id, payload))
        assertTrue(deleteAppProfileTemplate(id))
        val expected = listOf(
            listOf("sepolicy", "check", "--", payload),
            listOf("profile", "get-sepolicy", "--", id),
            listOf("profile", "set-sepolicy", "--", id, payload),
            listOf("profile", "get-template", "--", id),
            listOf("profile", "set-template", "--", id, payload),
            listOf("profile", "delete-template", "--", id),
        )
        assertEquals(expected.size, ShellProbe.commands.size)
        val shell = requireNotNull(System.getenv("F01_TEST_SH"))
        for ((command, arguments) in ShellProbe.commands.zip(expected)) {
            val receiver = "f01_receiver() { printf '%s\\000' \"\$@\"; }\n$command"
            val process = ProcessBuilder(shell).start()
            process.outputStream.use { it.write(receiver.toByteArray(Charsets.UTF_8)) }
            val received = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val error = process.errorStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(error, 0, process.waitFor())
            assertEquals(arguments, received.split('\u0000').dropLast(1))
        }
    }

    @Test
    fun cliGrammarMatchesExistingEditorAndListingDiscardsUnsafeNames() {
        val ids = listOf("a", "android", "webview_zygote", "com.example.App_123", "a-b", "a..b", "a.0b", "a\nb", "a.\u00e9")
        for (id in ids) assertEquals(id, isValidTemplateId(id), ProfileCommand.isValidId(id))
        ShellProbe.reset()
        ShellProbe.output = ids
        assertEquals(ids.filter(ProfileCommand::isValidId), listAppProfileTemplates())
    }
}
