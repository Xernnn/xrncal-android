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

    private val DECIMAL = Regex("[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?")
    private val PREFIXED = Regex("0(?:[xX](?<hex>[0-9a-fA-F]+)|[oO](?<oct>[0-7]+)|[bB](?<bin>[01]+))")

    /** What JavaScript's `Number(text)` makes of a string: 0 for blank, NaN for what is not a number. */
    fun number(text: String): Double {
        val s = trim(text)
        if (s.isEmpty()) return 0.0
        if (DECIMAL.matches(s)) return s.toDouble()
        PREFIXED.matchEntire(s)?.let { m ->
            val (digits, radix) = m.groups["hex"]?.let { it.value to 16 } ?: m.groups["oct"]?.let { it.value to 8 } ?: (m.groups["bin"]!!.value to 2)
            return digits.toBigInteger(radix).toDouble()
        }
        return when (s) {
            "Infinity", "+Infinity" -> Double.POSITIVE_INFINITY
            "-Infinity" -> Double.NEGATIVE_INFINITY
            else -> Double.NaN
        }
    }
}
