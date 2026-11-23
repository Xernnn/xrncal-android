package com.xern.xrncal.domain

import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * What the phone's store keeps beside a resource's text, so a month's events are found without
 * reading every resource: the span its dates can fall in, whether it repeats, and its words for
 * search. Floating and all-day times are read in UTC with 14 hours either side, which covers
 * every zone.
 */
data class ResourceIndex(val uid: String, val title: String, val rangeStart: Long, val rangeEnd: Long, val recurring: Boolean, val search: String) {
    companion object {
        private const val MARGIN_MS = 14 * 3_600_000L
        const val OPEN_END = Long.MAX_VALUE

        fun of(ics: String): ResourceIndex {
            val res = ParsedResource.parseOrNull(ics, ZoneOffset.UTC)
                ?: return ResourceIndex("", "", Long.MIN_VALUE, OPEN_END, false, "")
            var lo = Long.MAX_VALUE
            var hi = Long.MIN_VALUE
            var recurring = false
            for (v in res.events) {
                val s = v.startInstant ?: continue
                val floating = v.allDay || (v.start!!.tzid == null && !v.start.utc)
                val margin = if (floating) MARGIN_MS else 0L
                val start = s.toEpochMilli() - margin
                var end = s.plus(v.length).toEpochMilli() + margin + if (v.allDay) 86_400_000L else 0L
                if (v.isSeries && v.recurrenceId == null) {
                    recurring = true
                    end = seriesEnd(v)?.let { it.toEpochMilli() + v.length.toMillis() + MARGIN_MS + 86_400_000L } ?: OPEN_END
                    v.rdates.forEach { r -> end = maxOf(end, r.instant(v.zones).toEpochMilli() + v.length.toMillis() + MARGIN_MS + 86_400_000L) }
                }
                lo = minOf(lo, start)
                hi = maxOf(hi, end)
            }
            if (lo == Long.MAX_VALUE) return ResourceIndex(res.uids.firstOrNull().orEmpty(), "", Long.MIN_VALUE, OPEN_END, false, "")
            val main = res.masters.firstOrNull() ?: res.events.first()
            val words = res.events.flatMap { listOf(it.title, it.location, it.notes) }.distinct().joinToString(" ")
            return ResourceIndex(main.uid, main.title, lo, hi, recurring, TextFold.forMatch(words))
        }

        /** The last moment a series can have a date at: its UNTIL; null when it runs on (or counts). */
        private fun seriesEnd(v: EventView): Instant? {
            val rule = v.rrule ?: return v.rdates.maxOfOrNull { it.instant(v.zones) } ?: v.startInstant
            if (rule.contains("COUNT=", ignoreCase = true)) return null
            val m = Regex("""UNTIL=(\d{8})(T(\d{6})Z?)?""", RegexOption.IGNORE_CASE).find(rule) ?: return null
            val day = runCatching { LocalDate.parse(m.groupValues[1], com.xern.xrncal.domain.ical.ICalTime.DATE) }.getOrNull() ?: return null
            return day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        }
    }
}

/**
 * Where a new event goes on a CalDAV server (the laptop app's resourceNameFor). The name is the UID
 * where a server will take that as a name; a UID from another program can be anything, and
 * Radicale refuses `: ' " * ? ,` in a path (and a `/` would name another collection), so such a
 * UID gets a name made from its hash: the same each time, so a retry writes the same resource.
 */
object ResourceNames {
    private val UNSAFE = Regex("""[:'"*?,/\\<>|#%]""")
    private const val MAX_BYTES = 180

    fun of(uid: String): String {
        val usable = uid.isNotEmpty() &&
            !UNSAFE.containsMatchIn(uid) &&
            uid.none { it.code < 0x20 || it.code in 0x7f..0x9f } &&
            !uid.startsWith('.') &&
            !uid.endsWith(' ') && !uid.endsWith('.') &&
            uid.toByteArray(Charsets.UTF_8).size <= MAX_BYTES
        return if (usable) encode(uid) else sha256(uid).take(40)
    }

    /** `<calendar>/<name>.ics` */
    fun href(calendarUrl: String, uid: String): String = calendarUrl.trimEnd('/') + "/" + of(uid) + ".ics"

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** encodeURIComponent: everything but A-Z a-z 0-9 - _ . ! ~ * ' ( ) as UTF-8 percent escapes. */
    private fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-_.!~*'()") append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }
}
