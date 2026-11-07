package com.pleos.airdesk

import org.json.JSONObject
import org.json.JSONTokener

/** Android JSONObject is lenient and can overwrite duplicate keys. Validate syntax first. */
internal object StrictJson {
    fun objectValue(text: String, maximumBytes: Int = 8192): JSONObject {
        require(text.toByteArray(Charsets.UTF_8).size <= maximumBytes)
        val scanner = Scanner(text)
        scanner.value(0)
        scanner.space()
        require(scanner.position == text.length)
        return JSONObject(text)
    }

    private class Scanner(val text: String) {
        var position = 0
        fun space() { while (position < text.length && text[position] in " \n\r\t") position++ }
        private fun take(char: Char) {
            space(); require(position < text.length && text[position++] == char)
        }
        fun value(depth: Int) {
            require(depth <= 32)
            space(); require(position < text.length)
            when (text[position]) {
                '{' -> {
                    position++; space()
                    val keys = mutableSetOf<String>()
                    if (position < text.length && text[position] == '}') { position++; return }
                    while (true) {
                        space(); val key = string()
                        require(keys.add(key)) { "Duplicate JSON property" }
                        take(':'); value(depth + 1); space()
                        if (position < text.length && text[position] == '}') { position++; break }
                        take(',')
                    }
                }
                '[' -> {
                    position++; space()
                    if (position < text.length && text[position] == ']') { position++; return }
                    while (true) {
                        value(depth + 1); space()
                        if (position < text.length && text[position] == ']') { position++; break }
                        take(',')
                    }
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> {
                    val match = NUMBER.find(text, position)
                    require(match != null && match.range.first == position)
                    position = match.range.last + 1
                }
            }
        }
        private fun literal(word: String) { require(text.startsWith(word, position)); position += word.length }
        private fun string(): String {
            val start = position
            require(position < text.length && text[position++] == '"')
            while (position < text.length) {
                val char = text[position++]
                if (char == '"') {
                    val decoded = JSONTokener(text.substring(start, position)).nextValue() as String
                    require(validUnicode(decoded))
                    return decoded
                }
                require(char.code >= 32)
                if (char == '\\') {
                    require(position < text.length)
                    when (text[position++]) {
                        '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                        'u' -> {
                            require(position + 4 <= text.length && text.substring(position, position + 4).all { it in "0123456789abcdefABCDEF" })
                            position += 4
                        }
                        else -> error("Invalid escape")
                    }
                }
            }
            error("Unterminated string")
        }
        companion object { val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?") }
    }
}
