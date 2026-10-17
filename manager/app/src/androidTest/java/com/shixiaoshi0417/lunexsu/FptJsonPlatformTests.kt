package com.shixiaoshi0417.lunexsu

import com.shixiaoshi0417.lunexsu.ui.theme.pack.FptContainer
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Compile-only in the local audit; exercises the real Android JSONObject when explicitly run. */
internal object FptJsonPlatformTests {
    fun run(root: File) {
        // This is intentionally a platform-source assertion, not an assumption about JVM org.json.
        check(JSONObject("{'x':1}").getInt("x") == 1)
        check(JSONObject("{/* comment */ \"x\":1}").getInt("x") == 1)

        val directory = File(root, "strict-json-platform")
        fun read(text: String): JSONObject {
            directory.deleteRecursively()
            return FptContainer.read(encode(text).inputStream(), directory).first
        }
        try {
            val valid = "{\"x\":" + "[".repeat(31) + "0" + "]".repeat(31) + "}"
            check(read(valid).has("x"))
            check(read("""{"x":"[{}] \\\" /* not a comment */ \u0041","a":[true,null,{}]}""").has("a"))
            for (invalid in listOf(
                "{'x':1}", "{/* \" */ \"x\":1}", "{\"x\":1// comment\n}",
                "{'quote':'\"','x':" + "[".repeat(33) + "0" + "]".repeat(33) + "}",
                "{\"x\":" + "[".repeat(32) + "0" + "]".repeat(32) + "}",
                "{\"x\":[}", "{\"x\":[1,]}", "}{", "{}{}", "{\"x\":0",
            )) {
                val error = runCatching { read(invalid) }.exceptionOrNull()
                check(error is IllegalArgumentException) { "Expected strict rejection: $invalid, got $error" }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun encode(text: String): ByteArray {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use {
            it.putNextEntry(ZipEntry("theme.json"))
            it.write(text.toByteArray(Charsets.UTF_8))
            it.closeEntry()
        }
        val iv = ByteArray(16)
        val key = SecretKeySpec(MessageDigest.getInstance("SHA-256")
            .digest("FolkPatchThemeSecretKey2025".toByteArray(Charsets.UTF_8)), "AES")
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        return iv + cipher.doFinal(zip.toByteArray())
    }
}
