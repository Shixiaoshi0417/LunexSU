package com.shixiaoshi0417.lunexsu.ui.theme.pack

import org.junit.Assert.assertThrows
import org.junit.Test

class StrictThemeJsonTest {
    private fun reject(vararg texts: String) = texts.forEach { text ->
        assertThrows(text, IllegalArgumentException::class.java) { StrictThemeJson.requireObject(text) }
    }

    @Test fun acceptsStandardJsonAndAllStringEscapesWithoutCountingQuotedBrackets() {
        StrictThemeJson.requireObject(""" {"meta_name":"Theme", "values":[null,true,false,0,-0,12,-12.34e+2,1E-2,{},[]]} """)
        StrictThemeJson.requireObject("""{"text":"{}[]'/* not a comment */# \\\" \\/ \b\f\n\r\t \u0041"}""")
        StrictThemeJson.requireObject("{\"text\":\"" + "[{}]".repeat(40) + "\"}")
    }

    @Test fun allowsExactly32ContainersIncludingRootForObjectsAndArrays() {
        StrictThemeJson.requireObject("{\"x\":".repeat(32) + "0" + "}".repeat(32))
        StrictThemeJson.requireObject("{\"x\":" + "[".repeat(31) + "0" + "]".repeat(31) + "}")
        reject("{\"x\":".repeat(33) + "0" + "}".repeat(33),
            "{\"x\":" + "[".repeat(32) + "0" + "]".repeat(32) + "}")
    }

    @Test fun rejectsAndroidLeniencyRatherThanTryingToCountThroughIt() {
        reject("{'x':1}", "{x:1}", "{\"x\":unquoted}", "{/* \" */ \"x\":1}",
            "{\"x\":1// comment\n}", "{# comment\n\"x\":1}", "{\"x\"=1}",
            "{\"x\"=>1}", "{\"x\":1;\"y\":2}", "{\"x\":[1,,2]}", "{\"x\":[1;2]}",
            "{\"x\":True}", "{\"x\":0x10}")
        // A quote in a single-quoted string used to hide subsequent containers from the pre-scan.
        reject("{'quote':'\"','nested':" + "[".repeat(33) + "0" + "]".repeat(33) + "}")
    }

    @Test fun rejectsMismatchedNegativeUnclosedAndTrailingContainers() {
        reject("", "[]", "null", "}", "}{", "{}{}", "{} trailing", "{", "{\"x\":[}",
            "{\"x\":{]}", "{\"x\":[1,]}", "{\"x\":1,}", "{\"x\":[1}", "{\"x\":1}}")
    }

    @Test fun rejectsInvalidStringsEscapesAndNumbers() {
        reject("{\"x\":\"unfinished}", "{\"x\":\"line\nbreak\"}", """{"x":"\q"}""",
            """{"x":"\u000x"}""", """{"x":"\u00"}""", "{\"x\":01}", "{\"x\":-}",
            "{\"x\":+1}", "{\"x\":.5}", "{\"x\":1.}", "{\"x\":1e}", "{\"x\":NaN}",
            "{\"x\":Infinity}", "{\"x\":truefalse}", "\uFEFF{}", "\u000b{}")
    }

    @Test fun checksSizeAndCustomDepthBudgetsOnSmallInputs() {
        StrictThemeJson.requireObject("{}", maxChars = 2, maxDepth = 1)
        assertThrows(IllegalArgumentException::class.java) { StrictThemeJson.requireObject("{}", maxChars = 1) }
        assertThrows(IllegalArgumentException::class.java) { StrictThemeJson.requireObject("{\"x\":[]}", maxDepth = 1) }
    }
}
