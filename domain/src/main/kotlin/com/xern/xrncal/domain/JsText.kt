package com.xern.xrncal.domain

/**
 * White space as the laptop app's JavaScript means it, for the ports that read typed text the way
 * it does. JavaScript's `\s` and `trim()` take the Unicode spaces (a no-break space, U+3000,
 * U+FEFF); Java's `\s` is ASCII only, and Kotlin's `trim()` also takes U+001C..U+001F and leaves
 * U+FEFF.
 */
internal object JsText {
    /** The characters of `\s`, for a regex character class. */
    const val SPACE_CHARS = "\\t\\n\\x0B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    /** `\s`: one white-space character. */
    const val SPACE = "[$SPACE_CHARS]"

    private val RUNS = Regex("$SPACE+")

    fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
        else -> c in '\u2000'..'\u200A'
    }

    fun trim(s: String): String = s.trim(::isSpace)

    fun trimStart(s: String): String = s.trimStart(::isSpace)

    /** Every run of white space as one plain space: `replace(/\s+/g, ' ')`. */
    fun collapse(s: CharSequence): String = RUNS.replace(s, " ")
}
