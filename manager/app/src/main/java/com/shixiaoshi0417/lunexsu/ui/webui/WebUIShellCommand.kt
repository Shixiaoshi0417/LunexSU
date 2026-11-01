package com.shixiaoshi0417.lunexsu.ui.webui

/** spawn arguments and option values are data; only exec's command is shell source. */
internal object WebUIShellCommand {
    private fun quote(value: String): String {
        require('\u0000' !in value) { "Shell values cannot contain NUL" }
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun options(cwd: String?, env: Map<String, String>): String = buildString {
        if (!cwd.isNullOrEmpty()) append("cd -- ${quote(cwd)} || exit $?;\n")
        env.forEach { (key, value) ->
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(key)) { "Invalid environment name" }
            append("export $key=${quote(value)};\n")
        }
    }

    fun spawn(command: String, args: List<String>): String {
        require(command.isNotEmpty()) { "Empty executable" }
        return (listOf(command) + args).joinToString(" ", transform = ::quote)
    }
}
