package com.shixiaoshi0417.lunexsu.ui.webui

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WebUIShellCommandTest {
    private fun run(script: String): Pair<Int, String> {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun argumentsRemainLiteralIncludingEmptyQuotesAndShellSyntax() {
        val values = listOf("a b", "", "a'b", "\$(printf expanded)", "*", "x;y", "line\nbreak")
        val (code, output) = run(WebUIShellCommand.spawn("printf", listOf("[%s]\\n") + values))
        assertEquals(0, code)
        assertEquals(values.joinToString("") { "[$it]\n" }, output)
    }

    @Test fun cwdAndEnvironmentAreLiteral() {
        val directory = Files.createTempDirectory("webui cwd '").toFile()
        try {
            val script = WebUIShellCommand.options(directory.path, mapOf("VALUE" to "a b '\$HOME;literal")) +
                "printf '%s\\n%s' \"\$PWD\" \"\$VALUE\""
            assertEquals(0 to "${directory.path}\na b '\$HOME;literal", run(script))
        } finally {
            directory.delete()
        }
    }

    @Test fun failedDirectoryChangeCannotRunTheCommand() {
        val directory = Files.createTempDirectory("webui absent ").toFile()
        directory.delete()
        val (code, output) = run(WebUIShellCommand.options(directory.path, emptyMap()) + "printf SHOULD_NOT_RUN")
        assertNotEquals(0, code)
        org.junit.Assert.assertFalse(output.contains("SHOULD_NOT_RUN"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun environmentNamesCannotBecomeShellSyntax() {
        WebUIShellCommand.options(null, mapOf("A;printf injected" to "value"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nulCannotSilentlyTruncateAnArgument() {
        WebUIShellCommand.spawn("printf", listOf("a\u0000b"))
    }
}
