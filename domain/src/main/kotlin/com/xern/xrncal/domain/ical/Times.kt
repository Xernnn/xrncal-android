package com.xern.xrncal.domain.ical

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.zone.ZoneOffsetTransition
import java.time.zone.ZoneOffsetTransitionRule
import java.time.zone.ZoneRules
import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule

/**
 * A DATE or DATE-TIME value as it is written: a calendar day ([time] null), or a clock time that is
 * UTC, in a TZID, or floating (neither).
 */
data class ICalTime(val date: LocalDate, val time: LocalTime? = null, val utc: Boolean = false, val tzid: String? = null) {
    val isDate: Boolean get() = time == null
    val local: LocalDateTime get() = LocalDateTime.of(date, time ?: LocalTime.MIDNIGHT)

    /** The value text: `20260305`, `20260305T090000`, `20260305T090000Z`. */
    fun value(): String = if (time == null) date.format(DATE) else local.format(DATE_TIME) + if (utc) "Z" else ""

    /** VALUE=DATE for a date, TZID for a zoned time. */
    fun params(): MutableList<Param> = when {
        time == null -> mutableListOf(Param("VALUE", "DATE"))
        tzid != null && !utc -> mutableListOf(Param("TZID", paramValue(tzid)))
        else -> mutableListOf()
    }

    fun property(name: String) = Property(name, params(), value())

    /** The instant this names, zones resolved through [zones]; a date is its midnight in the floating zone. */
    fun instant(zones: Zones): Instant = when {
        utc -> local.toInstant(ZoneOffset.UTC)
        time == null -> Zones.instantOf(local, zones.floating.rules)
        else -> Zones.instantOf(local, zones.rules(tzid))
    }

    companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** `20260305`, `20260305T0900[00][Z]`; null for anything else. */
        fun parse(value: String, tzid: String? = null): ICalTime? {
            val v = value.trim()
            return try {
                when {
                    v.length == 8 -> ICalTime(LocalDate.parse(v, DATE))
                    v.length >= 13 && v[8] == 'T' -> {
                        val utc = v.endsWith("Z") || v.endsWith("z")
                        val body = if (utc) v.dropLast(1) else v
                        val date = LocalDate.parse(body.substring(0, 8), DATE)
                        val clock = body.substring(9)
                        val h = clock.substring(0, 2).toInt()
                        val m = clock.substring(2, 4).toInt()
                        val s = if (clock.length >= 6) clock.substring(4, 6).toInt().coerceAtMost(59) else 0
                        // 24:00:00 is the next midnight, as some writers put it.
                        if (h == 24) ICalTime(date.plusDays(1), LocalTime.MIDNIGHT, utc, if (utc) null else tzid?.takeIf { it.isNotBlank() })
                        else ICalTime(date, LocalTime.of(h, m, s), utc, if (utc) null else tzid?.takeIf { it.isNotBlank() })
                    }
                    else -> null
                }
            } catch (e: RuntimeException) {
                null
            }
        }

        fun of(p: Property?): ICalTime? {
            p ?: return null
            val tzid = p.param("TZID")?.let(ICalParser::unquote)
            val first = p.value.substringBefore(',')
            return parse(first.substringBefore('/'), tzid)
        }

        /** EXDATE and RDATE values: a list, each a date or a time (an RDATE PERIOD counts by its start). */
        fun list(p: Property): List<ICalTime> {
            val tzid = p.param("TZID")?.let(ICalParser::unquote)
            return p.value.split(',').mapNotNull { parse(it.substringBefore('/'), tzid) }
        }

        fun date(date: LocalDate) = ICalTime(date)

        fun utc(instant: Instant) = ICalTime(
            instant.atOffset(ZoneOffset.UTC).toLocalDate(),
            instant.atOffset(ZoneOffset.UTC).toLocalTime().withNano(0),
            utc = true,
        )

        /** [instant] as a clock time in [zone], written with its TZID (UTC as `...Z`). */
        fun zoned(instant: Instant, zone: ZoneId): ICalTime {
            if (Zones.isUtc(zone.id)) return utc(instant)
            val local = LocalDateTime.ofInstant(instant, zone).withNano(0)
            return ICalTime(local.toLocalDate(), local.toLocalTime(), tzid = zone.id)
        }

        fun zoned(local: LocalDateTime, zone: ZoneId): ICalTime =
            if (Zones.isUtc(zone.id)) ICalTime(local.toLocalDate(), local.toLocalTime().withNano(0), utc = true)
            else ICalTime(local.toLocalDate(), local.toLocalTime().withNano(0), tzid = zone.id)
    }
}

/**
 * How a document's TZIDs turn clock times into instants. An IANA name is read from the zone
 * database whatever block the file gives it (blocks are often cut short); then Windows names
 * ("W. Europe Standard Time") and names with a prefix ("/mozilla.org/.../Europe/Berlin"); then the
 * file's own VTIMEZONE; and a name nothing explains is read in [floating], the phone's zone, as the
 * laptop app does.
 */
class Zones(private val blocks: Map<String, Component> = emptyMap(), val floating: ZoneId = ZoneId.systemDefault()) {
    private val cache = HashMap<String, ZoneRules>()

    fun rules(tzid: String?): ZoneRules {
        if (tzid.isNullOrBlank()) return floating.rules
        return cache.getOrPut(tzid) {
            ianaFor(tzid)?.rules ?: blocks[tzid]?.let { runCatching { fromVtimezone(it) }.getOrNull() } ?: floating.rules
        }
    }

    /** The zone a TZID names, when it names one the zone database has. */
    fun zoneId(tzid: String?): ZoneId? = tzid?.let(::ianaFor)

    companion object {
        private val UTC_NAMES = setOf(
            "utc", "etc/utc", "uct", "etc/uct", "gmt", "etc/gmt", "gmt0", "etc/gmt0", "etc/gmt+0", "etc/gmt-0",
            "greenwich", "etc/greenwich", "universal", "etc/universal", "zulu", "etc/zulu", "z",
        )

        fun isUtc(name: String): Boolean = name.trim().lowercase() in UTC_NAMES

        fun of(calendar: Component, floating: ZoneId = ZoneId.systemDefault()): Zones =
            Zones(calendar.children("VTIMEZONE").mapNotNull { tz -> tz.prop("TZID")?.value?.trim()?.let { it to tz } }.toMap(), floating)

        private val AREA_CITY = Regex("""((?:Africa|America|Antarctica|Arctic|Asia|Atlantic|Australia|Europe|Indian|Pacific|Etc)/[A-Za-z0-9_+\-]+(?:/[A-Za-z0-9_+\-]+)?)$""")

        /** The IANA zone for a TZID: an IANA name, a Windows name, or an IANA name with a prefix; else null. */
        fun ianaFor(tzid: String): ZoneId? {
            val name = tzid.trim().removeSurrounding("\"")
            if (name.isEmpty()) return null
            if (isUtc(name)) return ZoneOffset.UTC
            WindowsZones.names[name]?.let { win -> runCatching { ZoneId.of(win) }.getOrNull()?.let { return it } }
            runCatching { ZoneId.of(name) }.getOrNull()?.let { return it }
            AREA_CITY.find(name)?.groupValues?.get(1)?.let { runCatching { ZoneId.of(it) }.getOrNull()?.let { z -> return z } }
            return null
        }

        /**
         * The instant of a clock time under [rules]: a time in the gap the clocks skip is moved on by
         * the gap's length, and one that happens twice is the first (RFC 5545 3.3.5, java.time).
         */
        fun instantOf(local: LocalDateTime, rules: ZoneRules): Instant {
            val offsets = rules.getValidOffsets(local)
            return when (offsets.size) {
                1 -> local.toInstant(offsets[0])
                0 -> {
                    val gap = rules.getTransition(local)
                    local.plus(gap.duration).toInstant(gap.offsetAfter)
                }
                else -> local.toInstant(offsets[0])
            }
        }

        fun localOf(instant: Instant, rules: ZoneRules): LocalDateTime = LocalDateTime.ofEpochSecond(instant.epochSecond, instant.nano, rules.getOffset(instant))

        /** Offsets from a VTIMEZONE's observances, worked out to 2100; after that the last one carries on. */
        fun fromVtimezone(tz: Component): ZoneRules {
            data class Onset(val at: Instant, val from: ZoneOffset, val to: ZoneOffset)
            val onsets = mutableListOf<Onset>()
            val horizon = LocalDateTime.of(2100, 1, 1, 0, 0)
            for (obs in tz.components) {
                if (!obs.name.equals("STANDARD", true) && !obs.name.equals("DAYLIGHT", true)) continue
                val from = offset(obs.prop("TZOFFSETFROM")?.value) ?: continue
                val to = offset(obs.prop("TZOFFSETTO")?.value) ?: continue
                val start = ICalTime.of(obs.prop("DTSTART"))?.local ?: continue
                val starts = mutableListOf(start)
                obs.props("RDATE").forEach { p -> ICalTime.list(p).forEach { starts += it.local } }
                obs.prop("RRULE")?.value?.let { rule ->
                    runCatching {
                        val wall = floatingUntil(rule) { utc -> utc.plusSeconds(from.totalSeconds.toLong()) }
                        val it = RecurrenceRule(wall).iterator(DateTime(start.year, start.monthValue - 1, start.dayOfMonth, start.hour, start.minute, start.second))
                        var n = 0
                        while (it.hasNext() && n++ < 400) {
                            val d = it.nextDateTime()
                            val local = LocalDateTime.of(d.year, d.month + 1, d.dayOfMonth, d.hours, d.minutes, d.seconds)
                            if (local.isAfter(horizon)) break
                            starts += local
                        }
                    }
                }
                starts.distinct().forEach { onsets += Onset(it.toInstant(from), from, to) }
            }
            if (onsets.isEmpty()) throw IllegalArgumentException("a VTIMEZONE without observances")
            onsets.sortBy { it.at }
            val base = onsets.first().from
            var current = base
            val transitions = mutableListOf<ZoneOffsetTransition>()
            for (o in onsets) {
                if (o.to == current) continue
                transitions += ZoneOffsetTransition.of(LocalDateTime.ofEpochSecond(o.at.epochSecond, 0, current), current, o.to)
                current = o.to
            }
            return ZoneRules.of(base, base, emptyList(), transitions, emptyList())
        }

        fun offset(text: String?): ZoneOffset? {
            val t = text?.trim() ?: return null
            if (t.length < 5) return null
            return runCatching {
                val sign = if (t[0] == '-') -1 else 1
                val h = t.substring(1, 3).toInt()
                val m = t.substring(3, 5).toInt()
                val s = if (t.length >= 7) t.substring(5, 7).toInt() else 0
                ZoneOffset.ofTotalSeconds(sign * (h * 3600 + m * 60 + s))
            }.getOrNull()
        }

        /** A rule whose UTC UNTIL is turned into a wall-clock one by [toWall], so it can run on floating times. */
        internal fun floatingUntil(rule: String, toWall: (LocalDateTime) -> LocalDateTime): String =
            rule.replace(Regex("""UNTIL=(\d{8}T\d{6})Z""", RegexOption.IGNORE_CASE)) { m ->
                val utc = runCatching { LocalDateTime.parse(m.groupValues[1].uppercase(), ICalTime.DATE_TIME) }.getOrNull()
                if (utc == null) m.value else "UNTIL=" + toWall(utc).format(ICalTime.DATE_TIME)
            }
    }
}

/**
 * VTIMEZONE blocks written from the zone database, so a timed event goes to the server in its own
 * zone and every other client reads its clock time back the same (the laptop app's vtimezone.ts).
 * The zone's present yearly rules are stated as RRULEs; changes in the span before those rules began
 * are listed one by one.
 */
object VTimezones {
    private val cache = HashMap<String, Component>()

    /** A VTIMEZONE for [zone] that is right from the start of [fromYear] - 1 on. */
    @Synchronized
    fun of(zone: ZoneId, fromYear: Int, toYear: Int = maxOf(fromYear, LocalDate.now().year) + 1): Component =
        cache.getOrPut("${zone.id}|$fromYear|$toYear") { build(zone, fromYear, toYear) }.copy()

    private fun stamp(t: LocalDateTime) = t.format(ICalTime.DATE_TIME)

    private fun observance(kind: String, start: LocalDateTime, from: ZoneOffset, to: ZoneOffset, rrule: String? = null) = Component(
        kind,
        buildList {
            add(Property("DTSTART", value = stamp(start)))
            if (rrule != null) add(Property("RRULE", value = rrule))
            add(Property("TZOFFSETFROM", value = format(from)))
            add(Property("TZOFFSETTO", value = format(to)))
        }.toMutableList(),
    )

    fun format(o: ZoneOffset): String {
        val s = o.totalSeconds
        val abs = kotlin.math.abs(s)
        val base = "%s%02d%02d".format(if (s < 0) "-" else "+", abs / 3600, abs / 60 % 60)
        return if (abs % 60 != 0) base + "%02d".format(abs % 60) else base
    }

    private val DAYS = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** The RRULE a transition rule is, checked against java.time's own dates for 30 years; null if none fits. */
    private fun rrule(rule: ZoneOffsetTransitionRule, from: Int): String? {
        val first = rule.createTransition(from).dateTimeBefore
        val month = first.monthValue
        val dow = first.dayOfWeek
        val wd = DAYS[dow.value - 1]
        val day = first.dayOfMonth
        val length = first.toLocalDate().lengthOfMonth()
        val candidates = buildList {
            if (rule.dayOfWeek == null) add("BYMONTHDAY=$day" to { y: Int -> LocalDate.of(y, month, minOf(day, LocalDate.of(y, month, 1).lengthOfMonth())) })
            add("BYDAY=${(day - 1) / 7 + 1}$wd" to { y: Int -> LocalDate.of(y, month, 1).with(TemporalAdjusters.dayOfWeekInMonth((day - 1) / 7 + 1, dow)) })
            add("BYDAY=-1$wd" to { y: Int -> LocalDate.of(y, month, 1).with(TemporalAdjusters.lastInMonth(dow)) })
            if (day > length - 7) reverse()
            for (start in maxOf(1, day - 6)..minOf(day, length - 6)) {
                if ((start - 1) % 7 == 0) continue
                add("BYDAY=$wd;BYMONTHDAY=${(start..start + 6).joinToString(",")}" to { y: Int -> LocalDate.of(y, month, start).with(TemporalAdjusters.nextOrSame(dow)) })
            }
        }
        return candidates.firstOrNull { (_, dayIn) ->
            (from..from + 30).all { y -> rule.createTransition(y).dateTimeBefore.let { it.toLocalDate() == dayIn(y) && it.toLocalTime() == first.toLocalTime() } }
        }?.let { "FREQ=YEARLY;BYMONTH=$month;${it.first}" }
    }

    private fun build(zone: ZoneId, fromYear: Int, toYear: Int): Component {
        val rules = zone.rules
        val tz = Component("VTIMEZONE", mutableListOf(Property("TZID", value = zone.id)))
        val spanStart = LocalDateTime.of(fromYear - 1, 1, 1, 0, 0).toInstant(ZoneOffset.UTC)
        val last = rules.transitions.lastOrNull()
        val yearly = rules.transitionRules
        // The years the yearly rules hold from, if the zone has any.
        val rulesFrom = if (yearly.isEmpty()) null else maxOf(fromYear - 1, (last?.dateTimeBefore?.year ?: (fromYear - 2)) + 1)
        val stated = rulesFrom?.let { y -> yearly.map { it to rrule(it, y) } }?.takeIf { list -> list.all { it.second != null } }
        // Changes listed one by one: those in the span before the rules take over, or every one
        // through a decade past the span when no yearly rule fits.
        val listUntil = if (stated != null) LocalDateTime.of(rulesFrom, 1, 1, 0, 0).toInstant(ZoneOffset.UTC) else LocalDateTime.of(toYear + 11, 1, 1, 0, 0).toInstant(ZoneOffset.UTC)
        val listed = mutableListOf<ZoneOffsetTransition>()
        var t = rules.nextTransition(spanStart.minusSeconds(1))
        while (t != null && t.instant.isBefore(listUntil)) {
            listed += t
            t = rules.nextTransition(t.instant)
        }
        if (listed.isEmpty() && stated == null) {
            val o = rules.getOffset(spanStart)
            tz.components += observance("STANDARD", LocalDateTime.of(1970, 1, 1, 0, 0), o, o)
            return tz
        }
        if (listed.isNotEmpty()) {
            val before = listed.first().offsetBefore
            tz.components += observance("STANDARD", LocalDateTime.of(1970, 1, 1, 0, 0), before, before)
            for (c in listed) {
                val kind = if (c.offsetAfter.totalSeconds > c.offsetBefore.totalSeconds) "DAYLIGHT" else "STANDARD"
                tz.components += observance(kind, c.dateTimeBefore, c.offsetBefore, c.offsetAfter)
            }
        }
        if (stated != null) {
            val made = stated.map { (rule, rrule) -> rule.createTransition(rulesFrom!!) to rrule!! }
                .sortedBy { (tr, _) -> if (tr.offsetAfter.totalSeconds < tr.offsetBefore.totalSeconds) 0 else 1 }
            for ((tr, rrule) in made) {
                val kind = if (tr.offsetAfter.totalSeconds > tr.offsetBefore.totalSeconds) "DAYLIGHT" else "STANDARD"
                tz.components += observance(kind, tr.dateTimeBefore, tr.offsetBefore, tr.offsetAfter, rrule)
            }
        }
        return tz
    }
}
