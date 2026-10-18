package com.shixiaoshi0417.lunexsu.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ProfileCommandTest {
    @Test
    fun acceptsExistingEditorIdsAndProfileKeys() {
        for (id in listOf("a", "android", "webview_zygote", "com.example.App_123", "Z9.a_b")) {
            assertTrue(id, ProfileCommand.isValidId(id))
        }
        // Long untrusted imports must not exhaust a recursive regex matcher.
        assertTrue(ProfileCommand.isValidId("a.".repeat(10_000) + "a"))
        assertFalse(ProfileCommand.isValidId("a.".repeat(10_000)))
    }

    @Test
    fun rejectsPathsWhitespaceQuotesAndShellSyntaxForEveryProfileOperation() {
        val invalid = listOf(
            "", ".", "..", "../outside", "/outside", "a/b", "a\\b", "a..b", ".a", "a.",
            "0app", "a.0app", "_app", "a-b", "a b", "a\tb", "a\nb", "a\rb", "a'b", "a\"b",
            "a;b", "a&b", "a|b", "\$(id)", "`id`", "a>b", "a<b", "a*b", "a?b", "a\u0000b", "a.\u00e9",
        )
        for (id in invalid) {
            assertFalse(id, ProfileCommand.isValidId(id))
            for (operation in listOf("get-template", "set-template", "delete-template", "get-sepolicy", "set-sepolicy")) {
                assertThrows("$operation: $id", IllegalArgumentException::class.java) {
                    ProfileCommand.profile("never-executed", operation, id, "value")
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProfileCommand.build("never-executed", "a\u0000b")
        }
        assertEquals("'\r\n'", ProfileCommand.quote("\r\n"))
    }

    @Test
    fun shellReceiverGetsExactArgumentsWithoutExpansion() {
        val shell = System.getenv("F01_TEST_SH") ?: "/bin/sh"
        assumeTrue("POSIX shell required for argv receiver", File(shell).isFile)
        val values = listOf(
            "", "ordinary", "it's quoted", "\"double quotes\"", "\nfirst\nsecond\n", " \t padded \n",
            "a/b\\c", "\$(printf expanded)", "`printf expanded`", "; & | > < * ? # ~",
            "'; printf INJECTED; #", "--option-like", "{\"id\":\"com.example.app\",\"rules\":[\"a'b\"]}",
        )
        val command = ProfileCommand.build("f01_receiver", *values.toTypedArray())
        // A local shell function only records argv. It never invokes ksud, su or a module.
        val receiver = "f01_receiver() { printf '%s\\000' \"\$@\"; }\n$command"
        val process = ProcessBuilder(shell).start()
        process.outputStream.use { it.write(receiver.toByteArray(Charsets.UTF_8)) }
        val actual = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val error = process.errorStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(error, 0, process.waitFor())
        assertEquals(values, actual.split('\u0000').dropLast(1))
    }
}
