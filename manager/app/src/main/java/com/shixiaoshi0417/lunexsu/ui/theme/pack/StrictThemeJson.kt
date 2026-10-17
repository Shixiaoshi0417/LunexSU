package com.shixiaoshi0417.lunexsu.ui.theme.pack

/**
 * Parses the complete RFC 8259 grammar without building a tree. Android's JSONTokener also accepts
 * comments, single quotes and unquoted values; none may reach it through the FPT import boundary.
 * The container byte budget bounds tokens/strings, and each object/array checks depth BEFORE descent.
 */
internal object StrictThemeJson {
    const val MAX_DEPTH = 32
    const val MAX_CHARS = 1024 * 1024

    fun requireObject(text: String, maxDepth: Int = MAX_DEPTH, maxChars: Int = MAX_CHARS) {
        require(text.length <= maxChars) { "Theme configuration exceeds size limit" }
        require(maxDepth in 1..MAX_DEPTH) { "Invalid JSON depth limit" }
        Parser(text, maxDepth).parse()
    }

    private class Parser(private val text: String, private val maxDepth: Int) {
        private var position = 0

        fun parse() {
            whitespace()
            require(peek() == '{') { "Theme configuration must be a JSON object" }
            value(0)
            whitespace()
            require(position == text.length) { "Trailing theme configuration data" }
        }

        private fun value(depth: Int) {
            whitespace()
            when (peek()) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> number()
                else -> invalid()
            }
        }

        private fun objectValue(depth: Int) {
            checkDepth(depth)
            expect('{')
            whitespace()
            if (take('}')) return
            do {
                whitespace()
                string()
                whitespace()
                expect(':')
                value(depth)
                whitespace()
                if (take('}')) return
                expect(',')
            } while (true)
        }

        private fun arrayValue(depth: Int) {
            checkDepth(depth)
            expect('[')
            whitespace()
            if (take(']')) return
            do {
                value(depth)
                whitespace()
                if (take(']')) return
                expect(',')
            } while (true)
        }

        private fun checkDepth(depth: Int) {
            require(depth <= maxDepth) { "Theme configuration is too deeply nested" }
        }

        private fun string() {
            expect('"')
            while (position < text.length) {
                val char = text[position++]
                when {
                    char == '"' -> return
                    char == '\\' -> {
                        when (peek()) {
                            '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> position++
                            'u' -> {
                                position++
                                repeat(4) {
                                    require(peek() in '0'..'9' || peek() in 'a'..'f' || peek() in 'A'..'F') {
                                        "Invalid JSON Unicode escape"
                                    }
                                    position++
                                }
                            }
                            else -> invalid()
                        }
                    }
                    char.code < 0x20 -> invalid()
                }
            }
            invalid()
        }

        private fun number() {
            take('-')
            if (!take('0')) {
                require(peek() in '1'..'9') { "Invalid JSON number" }
                digits()
            }
            if (take('.')) {
                require(peek() in '0'..'9') { "Invalid JSON fraction" }
                digits()
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                require(peek() in '0'..'9') { "Invalid JSON exponent" }
                digits()
            }
        }

        private fun digits() {
            while (peek() in '0'..'9') position++
        }

        private fun literal(value: String) {
            require(text.startsWith(value, position)) { "Invalid JSON literal" }
            position += value.length
        }

        private fun whitespace() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\r' || peek() == '\n') position++
        }

        private fun peek(): Char? = text.getOrNull(position)

        private fun take(char: Char): Boolean {
            if (peek() != char) return false
            position++
            return true
        }

        private fun expect(char: Char) {
            require(take(char)) { "Invalid JSON at character $position: expected $char" }
        }

        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid JSON at character $position")
    }
}
