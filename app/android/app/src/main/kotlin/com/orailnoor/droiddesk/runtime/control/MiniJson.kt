package com.orailnoor.droiddesk.runtime.control

/**
 * Minimal JSON reader/writer for the control protocol.
 *
 * android's org.json is stubbed out in JVM unit tests, and the protocol only
 * needs objects, arrays, strings, numbers, booleans and null, so this keeps
 * the bridge core free of Android dependencies.
 */
object MiniJson {
    class ParseException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Any? {
        val parser = Parser(text)
        parser.skipWhitespace()
        val value = parser.readValue(depth = 0)
        parser.skipWhitespace()
        if (!parser.atEnd()) throw ParseException("Trailing data at ${parser.pos}")
        return value
    }

    fun stringify(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

    private fun write(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> writeString(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            is Float -> out.append(if (value.isFinite()) value.toString() else "null")
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, item) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key.toString())
                    out.append(':')
                    write(out, item)
                }
                out.append('}')
            }
            is Iterable<*> -> {
                out.append('[')
                var first = true
                for (item in value) {
                    if (!first) out.append(',')
                    first = false
                    write(out, item)
                }
                out.append(']')
            }
            is Array<*> -> write(out, value.asList())
            else -> writeString(out, value.toString())
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ' || ch == ' ' || ch == ' ') {
                    out.append("\\u").append(String.format("%04x", ch.code))
                } else {
                    out.append(ch)
                }
            }
        }
        out.append('"')
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun atEnd() = pos >= text.length

        fun skipWhitespace() {
            while (pos < text.length && text[pos] in " \t\r\n") pos++
        }

        fun readValue(depth: Int): Any? {
            if (depth > 32) throw ParseException("Nesting too deep")
            skipWhitespace()
            if (atEnd()) throw ParseException("Unexpected end of input")
            return when (val ch = text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) readNumber()
                else throw ParseException("Unexpected '$ch' at $pos")
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!text.startsWith(word, pos)) throw ParseException("Invalid literal at $pos")
            pos += word.length
            return value
        }

        private fun readNumber(): Number {
            val start = pos
            if (text[pos] == '-') pos++
            while (pos < text.length && (text[pos].isDigit() || text[pos] in ".eE+-")) pos++
            val raw = text.substring(start, pos)
            return raw.toLongOrNull() ?: raw.toDoubleOrNull()
                ?: throw ParseException("Invalid number '$raw'")
        }

        private fun readString(): String {
            pos++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("Unterminated string")
                val ch = text[pos++]
                when {
                    ch == '"' -> return out.toString()
                    ch == '\\' -> {
                        if (atEnd()) throw ParseException("Bad escape")
                        when (val esc = text[pos++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) throw ParseException("Bad unicode escape")
                                val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                    ?: throw ParseException("Bad unicode escape")
                                out.append(code.toChar())
                                pos += 4
                            }
                            else -> throw ParseException("Bad escape '\\$esc'")
                        }
                    }
                    ch < ' ' -> throw ParseException("Control character in string")
                    else -> out.append(ch)
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++
            val items = mutableListOf<Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == ']') { pos++; return items }
            while (true) {
                items += readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) throw ParseException("Unterminated array")
                when (text[pos++]) {
                    ',' -> continue
                    ']' -> return items
                    else -> throw ParseException("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[pos] != '"') throw ParseException("Expected key at $pos")
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[pos++] != ':') throw ParseException("Expected ':'")
                map[key] = readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) throw ParseException("Unterminated object")
                when (text[pos++]) {
                    ',' -> continue
                    '}' -> return map
                    else -> throw ParseException("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }
    }
}
