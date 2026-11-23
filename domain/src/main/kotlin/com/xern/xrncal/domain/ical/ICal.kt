package com.xern.xrncal.domain.ical

/**
 * An iCalendar document as it was written (RFC 5545): components holding properties and other
 * components, in their order. Nothing is thrown away when it is read, so a resource that another
 * program wrote goes back to the server with everything this app doesn't model (attendees,
 * categories, X- properties, alarms it can't show) exactly as it came.
 */
class Param(val name: String, val value: String) {
    override fun toString() = "$name=$value"
}

class Property(var name: String, val params: MutableList<Param> = mutableListOf(), var value: String = "") {
    fun param(name: String): String? = params.firstOrNull { it.name.equals(name, ignoreCase = true) }?.value

    fun setParam(name: String, value: String?) {
        params.removeAll { it.name.equals(name, ignoreCase = true) }
        if (value != null) params += Param(name.uppercase(), value)
    }

    /** The value as TEXT, with its escapes undone. */
    val text: String get() = ICalText.unescape(value)

    fun copy(): Property = Property(name, params.map { Param(it.name, it.value) }.toMutableList(), value)

    override fun toString() = ICalWriter.line(this)
}

class Component(
    val name: String,
    val properties: MutableList<Property> = mutableListOf(),
    val components: MutableList<Component> = mutableListOf(),
) {
    fun prop(name: String): Property? = properties.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun props(name: String): List<Property> = properties.filter { it.name.equals(name, ignoreCase = true) }

    fun has(name: String): Boolean = prop(name) != null

    fun remove(name: String) {
        properties.removeAll { it.name.equals(name, ignoreCase = true) }
    }

    /**
     * Sets the one property [name] to [value] (raw, already escaped), in the place the first one had,
     * so a rewritten resource keeps its order. Params are replaced only when [params] is given.
     */
    fun set(name: String, value: String, params: List<Param>? = null) {
        val at = properties.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        if (at < 0) {
            properties += Property(name.uppercase(), params.orEmpty().toMutableList(), value)
            return
        }
        val old = properties[at]
        old.value = value
        if (params != null) {
            old.params.clear()
            old.params.addAll(params)
        }
        properties.removeAll { it !== old && it.name.equals(name, ignoreCase = true) }
    }

    /** Sets a TEXT property, or removes it when [text] is null or blank. */
    fun setText(name: String, text: String?) {
        if (text.isNullOrBlank()) remove(name) else set(name, ICalText.escape(text), properties.firstOrNull { it.name.equals(name, true) }?.params?.filterNot { it.name.equals("ALTREP", true) })
    }

    fun text(name: String): String? = prop(name)?.text

    fun children(name: String): List<Component> = components.filter { it.name.equals(name, ignoreCase = true) }

    fun copy(): Component = Component(name, properties.map { it.copy() }.toMutableList(), components.map { it.copy() }.toMutableList())

    override fun toString() = ICalWriter.write(this)
}

class ICalParseException(message: String) : IllegalArgumentException(message)

object ICalText {
    /** TEXT escaping (RFC 5545 3.3.11). A line break of any kind is written as \n, never as itself. */
    fun escape(text: String): String = buildString(text.length + 8) {
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> append("\\\\")
                ';' -> append("\\;")
                ',' -> append("\\,")
                '\r' -> {
                    append("\\n")
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                '\n' -> append("\\n")
                else -> append(c)
            }
            i++
        }
    }

    fun unescape(value: String): String {
        if (!value.contains('\\')) return value
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    '\\', ';', ',', ':', '"' -> out.append(n)
                    else -> out.append(c).append(n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

object ICalParser {
    /** A whole calendar with thousands of events is a few MB; past this it isn't a calendar resource. */
    const val MAX_CHARS = 8 * 1024 * 1024

    /** The outermost component of [text] (a VCALENDAR, for any calendar resource). */
    fun parse(text: String): Component {
        if (text.length > MAX_CHARS) throw ICalParseException("too large")
        val roots = mutableListOf<Component>()
        val stack = ArrayDeque<Component>()
        for (line in unfold(text)) {
            if (line.isBlank()) continue
            val prop = contentLine(line) ?: throw ICalParseException("not a content line: ${line.take(60)}")
            when {
                prop.name.equals("BEGIN", true) -> {
                    val c = Component(prop.value.trim().uppercase())
                    stack.lastOrNull()?.components?.add(c)
                    stack.addLast(c)
                }
                prop.name.equals("END", true) -> {
                    val open = stack.removeLastOrNull() ?: throw ICalParseException("END:${prop.value} without BEGIN")
                    if (!open.name.equals(prop.value.trim(), true)) throw ICalParseException("END:${prop.value} closes ${open.name}")
                    if (stack.isEmpty()) roots += open
                }
                else -> stack.lastOrNull()?.properties?.add(prop) ?: throw ICalParseException("${prop.name} outside a component")
            }
        }
        if (stack.isNotEmpty()) throw ICalParseException("${stack.last().name} is never closed")
        return roots.firstOrNull() ?: throw ICalParseException("no component")
    }

    /** The logical lines: a line starting with a space or a tab continues the one before (RFC 5545 3.1). */
    internal fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var start = true
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        val n = text.length
        while (i <= n) {
            val end = if (i < n) text.indexOfAny(charArrayOf('\r', '\n'), i).let { if (it < 0) n else it } else n
            val raw = text.substring(i, end)
            if (!start && (raw.startsWith(' ') || raw.startsWith('\t'))) {
                current.append(raw, 1, raw.length)
            } else {
                if (!start) lines += current.toString()
                current.setLength(0)
                current.append(raw)
                start = false
            }
            if (end >= n) break
            i = end + if (text[end] == '\r' && end + 1 < n && text[end + 1] == '\n') 2 else 1
        }
        if (!start) lines += current.toString()
        return lines
    }

    /** `NAME;PARAM=a,"b;c":value`, or null when there is no name or colon. */
    internal fun contentLine(line: String): Property? {
        var i = 0
        while (i < line.length && line[i] != ';' && line[i] != ':') i++
        if (i == 0 || i >= line.length) return null
        val name = line.substring(0, i).trim().uppercase()
        val params = mutableListOf<Param>()
        while (i < line.length && line[i] == ';') {
            i++
            val eq = line.indexOf('=', i)
            if (eq < 0) return null
            val pname = line.substring(i, eq).trim().uppercase()
            i = eq + 1
            val value = StringBuilder()
            while (i < line.length) {
                val c = line[i]
                if (c == '"') {
                    val close = line.indexOf('"', i + 1)
                    if (close < 0) return null
                    value.append(line, i, close + 1)
                    i = close + 1
                } else if (c == ';' || c == ':') {
                    break
                } else {
                    value.append(c)
                    i++
                }
            }
            params += Param(pname, value.toString())
        }
        if (i >= line.length || line[i] != ':') return null
        return Property(name, params, line.substring(i + 1))
    }

    /** A parameter's values without their quotes: `"a","b"` is a and b. */
    fun paramValues(raw: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val cur = StringBuilder()
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '"' -> {
                    val close = raw.indexOf('"', i + 1).let { if (it < 0) raw.length else it }
                    cur.append(raw, i + 1, close)
                    i = close + 1
                }
                c == ',' -> {
                    out += cur.toString()
                    cur.setLength(0)
                    i++
                }
                else -> {
                    cur.append(c)
                    i++
                }
            }
        }
        out += cur.toString()
        return out
    }

    fun unquote(raw: String): String = paramValues(raw).firstOrNull().orEmpty()
}

object ICalWriter {
    fun write(root: Component): String = buildString {
        writeTo(this, root)
    }

    private fun writeTo(out: StringBuilder, c: Component) {
        out.append(fold("BEGIN:${c.name}")).append("\r\n")
        for (p in c.properties) out.append(fold(line(p))).append("\r\n")
        for (child in c.components) writeTo(out, child)
        out.append(fold("END:${c.name}")).append("\r\n")
    }

    fun line(p: Property): String = buildString {
        append(p.name)
        for (param in p.params) append(';').append(param.name).append('=').append(param.value)
        append(':')
        // A value never holds a line break of its own: it would end the content line there.
        append(p.value.replace("\r\n", "\\n").replace('\r', ' ').replace('\n', ' '))
    }

    /** Folds at 75 octets (RFC 5545 3.1), never inside a UTF-8 sequence or a surrogate pair. */
    fun fold(line: String): String {
        if (line.length <= 75 && line.all { it.code < 0x80 }) return line
        val out = StringBuilder(line.length + 16)
        var octets = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (octets + size > limit) {
                out.append("\r\n ")
                octets = 0
                limit = 74 // the space that starts a continuation line counts
            }
            out.appendCodePoint(cp)
            octets += size
            i += chars
        }
        return out.toString()
    }
}

/** Quotes a parameter value when it holds `:`, `;` or `,` (RFC 5545 has no escape for `"` itself). */
fun paramValue(text: String): String {
    val clean = text.replace('"', '\'').replace('\r', ' ').replace('\n', ' ')
    return if (clean.any { it == ':' || it == ';' || it == ',' }) "\"$clean\"" else clean
}
