package com.shixiaoshi0417.lunexsu.ui.util

/** Literal arguments for profile/sepolicy commands, never shell fragments. */
internal object ProfileCommand {
    // Keep the same ID grammar as the template editor and ksud's profile storage.
    fun isValidId(id: String): Boolean {
        var segmentStart = true
        for (c in id) {
            val letter = c in 'A'..'Z' || c in 'a'..'z'
            when {
                c == '.' -> {
                    if (segmentStart) return false
                    segmentStart = true
                }
                segmentStart -> {
                    if (!letter) return false
                    segmentStart = false
                }
                !letter && c !in '0'..'9' && c != '_' -> return false
            }
        }
        return !segmentStart
    }

    fun requireValidId(id: String): String {
        require(isValidId(id)) { "Invalid profile ID: expected dot-separated ASCII identifiers" }
        return id
    }

    /** POSIX quoting preserves quotes, whitespace and newlines as one argument. */
    fun quote(value: String): String {
        require('\u0000' !in value) { "NUL cannot be passed through a shell" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun build(daemon: String, vararg arguments: String): String =
        (listOf(daemon) + arguments).joinToString(" ", transform = ::quote)

    fun profile(daemon: String, operation: String, id: String, vararg values: String): String {
        requireValidId(id)
        return build(daemon, "profile", operation, "--", id, *values)
    }
}
