package com.xern.xrncal.domain

import java.text.Normalizer
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

/** How a title says an event repeats. */
sealed interface EntryRepeat {
    data object Daily : EntryRepeat

    /** Every week: on [byDay] when the title names a day ("mỗi thứ 4"), else on the day it starts. */
    data class Weekly(val byDay: DayOfWeek? = null) : EntryRepeat

    data object Monthly : EntryRepeat

    data object Yearly : EntryRepeat

    /** Every year on a lunar date ("Giỗ ông 12/8 âm lịch"). The editor has no lunar repeat yet. */
    data class LunarYearly(val spec: LunarSpec) : EntryRepeat

    /**
     * The editor's repeat for an event starting on [start], as the laptop's editor sets it: every
     * week on the day named, or on [start]'s day. Null for a lunar one, which has none.
     */
    fun toRepeat(start: LocalDate): Repeat? = when (this) {
        Daily -> Repeat(Frequency.DAILY)
        is Weekly -> Repeat(Frequency.WEEKLY, weekdays = setOf(byDay ?: start.dayOfWeek))
        Monthly -> Repeat(Frequency.MONTHLY)
        Yearly -> Repeat(Frequency.YEARLY)
        is LunarYearly -> null
    }
}

/** What [NaturalEntry.parse] found in a title. */
data class ParsedEntry(
    /** The title with what was recognised taken out. Never empty. */
    val title: String,
    /** For a lunar date, its next Gregorian date (today counts). */
    val date: LocalDate? = null,
    val start: LocalTime? = null,
    /** Earlier than [start] means the next day ("22h-1h"). */
    val end: LocalTime? = null,
    val durationMinutes: Int? = null,
    val allDay: Boolean = false,
    val repeat: EntryRepeat? = null,
    /** The phrases recognised, as typed, in the order they appear. */
    val matched: List<String> = emptyList(),
)

/** What a title is read against. */
data class EntryContext(
    /** The day "today", "thứ 6" and a year left out are counted from. */
    val today: LocalDate,
    /** For "next week" and "thứ 2 tuần sau". */
    val firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY,
)

/** The part of the editor's form a title can set. All-day: [endDate] is the last day covered. */
data class EntrySlot(
    val allDay: Boolean,
    val startDate: LocalDate,
    val startTime: LocalTime,
    val endDate: LocalDate,
    val endTime: LocalTime,
)

/**
 * Dates, times and repeats typed into a new event's title, in Vietnamese and English: "Họp 3h
 * chiều thứ 6", "Lunch with Sam tomorrow at 12:30", "Giỗ ông 12/8 âm lịch", "Chạy bộ 6h sáng hàng
 * ngày" (the laptop app's natural-entry.ts, pattern for pattern).
 *
 * [parse] says what it found and what the title is without it; the editor fills its fields from
 * that as the title is typed ([applyEntry]) and saves the shorter title. Only what is clearly a date
 * or time is taken: a bare number is never a time ("Phòng 3"), a period word belongs to the title
 * unless it sits next to a time or a day ("Ăn tối 7h" keeps "Ăn tối"), and "Mai" with a capital is
 * a name, not tomorrow.
 *
 * Diacritics are optional throughout ("thu 6", "3h chieu"), except where leaving them off makes
 * another word ("mốt", the day after tomorrow, is "một", one, without them). Dates are day first,
 * as the app shows them.
 */
object NaturalEntry {
    // ─── Pattern pieces (matched against the lower-cased title) ──────────────────

    private const val W = JsText.SPACE
    private const val S = "$W+"
    private const val O = "$W*"
    private const val NGAY = "ng[aà]y"
    private const val THANG = "(?:th[aá]ng|thg)"
    private const val NAM = "n[aă]m"
    private const val THU = "th[uứ]"
    private const val TUAN = "tu[aầ]n"
    private const val NHUAN = "nhu[aậ]n"
    private const val AM_LICH = "(?:\\(${O})?(?:[aâ]m${O}l[iị]ch|[aâ]m|[aâ]l)(?:${O}\\))?"
    private const val LUNAR_MONTH = "\\d{1,2}|gi[eê]ng|ch[aạ]p"
    private const val PERIOD_WORDS = "s[aá]ng|tr[uư]a|chi[eề]u|t[oố]i|(?:đ|d)[eê]m|khuya|morning|afternoon|evening|night"
    private const val EN_WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val EN_MONTH =
        "january|february|march|april|may|june|july|august|september|october|november|december|" +
            "jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec"

    private fun period(name: String): String = "(?<$name>$PERIOD_WORDS)"

    /** "thứ 6", "thứ sáu", "t6", "chủ nhật", "cn"; groups `${p}n` (thứ ...), `${p}t` (tN), `${p}cn`. */
    private fun vnWeekday(p: String): String =
        "${THU}${O}(?<${p}n>[2-7]|hai|ba|t[uư]|n[aă]m|s[aá]u|b[aả]y)|t(?<${p}t>[2-7])|(?<${p}cn>ch[uủ]${O}nh[aậ]t|cn)"

    /** One clock time: hour, optional separator + minutes, optional am/pm or period word. */
    private fun clock(p: String): String =
        "(?<${p}h>\\d{1,2})" +
            "(?:(?<${p}sep>:|h|g(?=\\d)|${O}gi[oờ]${O})(?<${p}min>\\d{2}|${O}r[uư][oỡ]i)?(?:${O}(?:ph[uú]t|p))?)?" +
            "(?:${O}(?<${p}mer>a\\.?m\\.?|p\\.?m\\.?|$PERIOD_WORDS))?"

    /** A word or number may not continue on either side of a match. */
    private fun pattern(source: String): Pattern = Pattern.compile("(?<![\\p{L}\\p{N}])(?:$source)(?![\\p{L}\\p{N}])")

    private val LUNAR_NUMERIC = pattern(
        "(?:${NGAY}${S})?(?<d>\\d{1,2})${O}(?:\\/|-|${THANG})${O}(?<m>${LUNAR_MONTH})" +
            "(?:${O}(?<leap1>${NHUAN}))?${O}${AM_LICH}(?:${O}(?<leap2>${NHUAN}))?",
    )

    // Tháng giêng and tháng chạp are lunar months by name.
    private val LUNAR_NAMED_MONTH = pattern(
        "(?:${NGAY}${S}|m[uùoồ]ng${O})?(?<d>\\d{1,2})${O}${THANG}${O}(?<m>gi[eê]ng|ch[aạ]p)(?:${O}(?<leap1>${NHUAN}))?(?:${O}${AM_LICH})?",
    )
    private val LUNAR_RAM = pattern("r[aằ]m${O}${THANG}${O}(?<m>${LUNAR_MONTH})(?:${O}(?<leap1>${NHUAN}))?(?:${O}${AM_LICH})?")
    private val LUNAR_MUNG = pattern(
        "m[uùoồ]ng${O}(?<d>\\d{1,2})${O}(?:${THANG}${O}(?<m>${LUNAR_MONTH})(?:${O}(?<leap1>${NHUAN}))?(?:${O}${AM_LICH})?|(?<tet>t[eế]t))",
    )

    private val DURATION = pattern(
        "(?:for|trong)${S}(?<h>\\d{1,2})(?:[.,](?<tenth>\\d))?${O}(?:hours?|hrs?|h|ti[eế]ng|gi[oờ])" +
            "(?:${O}(?<m>\\d{1,2})(?:${O}(?:minutes?|mins?|ph[uú]t|p|m))?)?" +
            "|(?:for|trong)${S}(?<mm>\\d{1,3})${O}(?:minutes?|mins?|ph[uú]t|p)",
    )

    private val DATE_SLASH = pattern("(?:(?:${NGAY}|on)${S})?(?<d>\\d{1,2})\\/(?<m>\\d{1,2})(?:\\/(?<y>\\d{4}|\\d{2}))?")
    private val DATE_VN = pattern(
        "(?:${NGAY}${O})?(?<d>\\d{1,2})${O}${THANG}${O}(?<m>\\d{1,2})(?:${O}(?:${NAM}${O})?(?<y>\\d{4}))?",
    )
    private val DATE_EN_MONTH_FIRST = pattern(
        "(?:on${S})?(?<mon>${EN_MONTH})\\.?${S}(?<d>\\d{1,2})(?:st|nd|rd|th)?(?:,?${S}(?<y>\\d{4}))?",
    )
    private val DATE_EN_DAY_FIRST = pattern(
        "(?:on${S})?(?:the${S})?(?<d>\\d{1,2})(?:st|nd|rd|th)?${S}(?:of${S})?(?<mon>${EN_MONTH})\\.?(?:,?${S}(?<y>\\d{4}))?",
    )

    private val REPEAT_EVERY_WEEKDAY = pattern("(?:every|each)${S}(?<en>${EN_WEEKDAY})s?|m[oỗ]i${O}(?:${vnWeekday("w")})")
    private val REPEAT_DAILY = pattern("(?:every|each)${S}day|daily|(?:h[aàằ]ng|m[oỗ]i)${O}${NGAY}")
    private val REPEAT_WEEKLY = pattern("(?:every|each)${S}week|weekly|(?:h[aàằ]ng|m[oỗ]i)${O}${TUAN}")
    private val REPEAT_MONTHLY = pattern("(?:every|each)${S}month|monthly|(?:h[aàằ]ng|m[oỗ]i)${O}th[aá]ng")
    private val REPEAT_YEARLY = pattern("(?:every|each)${S}year|yearly|annually|(?:h[aàằ]ng|m[oỗ]i)${O}${NAM}")

    // "ăn tối", "bữa trưa": the period word is a meal there, not a time of day.
    private const val NOT_A_MEAL = "(?<!(?:[aă]n|b[uữ]a)$W)"
    private val RELATIVE_DAY = pattern(
        "(?:${NOT_A_MEAL}${period("p1")}${S})?" +
            "(?<rel>h[oô]m${O}nay|nay|today|tonight|${NGAY}${O}mai|mai|tomorrow|${NGAY}${O}kia|${NGAY}${O}mốt|mốt|(?:the${S})?day${S}after${S}tomorrow)" +
            "(?:${S}${period("p2")})?" +
            "|in${S}(?<n1>\\d{1,2})${S}days?|(?<n2>\\d{1,2})${O}${NGAY}${O}n[uữ]a|sau${S}(?<n3>\\d{1,2})${O}${NGAY}",
    )
    private val VN_WEEKDAY = pattern(
        "(?:${NOT_A_MEAL}${period("p1")}${S})?(?:v[aà]o${S})?(?:${vnWeekday("v")})" +
            "(?:${S}(?<mod>${TUAN}${S}(?:sau|t[ớo]i|n[aà]y)|n[aà]y|t[ớo]i))?(?:${S}${period("p2")})?",
    )
    private val EN_WEEKDAY_RE = pattern(
        "(?:(?<mod>next|this|coming|on)${S})?(?<wd>${EN_WEEKDAY})(?:${S}(?<nw>next${S}week))?" +
            "(?:${S}(?<p2>morning|afternoon|evening|night))?",
    )

    private val TIME_RANGE = pattern(
        "(?:(?:from|t[uừ]|v[aà]o|l[uú]c|at)${S})?${clock("a")}${O}(?:-|–|—|to|until|till|(?:đ|d)[eế]n|tới)${O}${clock("b")}",
    )
    private val TIME = pattern("(?:v[aà]o${S})?(?:(?<at>at|@|l[uú]c)${O})?${clock("a")}")
    private val NOON = pattern("(?:at${S})?(?:noon|midday)")
    private val ALL_DAY = pattern("all${O}-?${O}day|c[aả]${S}${NGAY}|nguy[eê]n${S}${NGAY}")

    // ─── Values ──────────────────────────────────────────────────────────────────

    /** am/pm, or the part of the day a period word names. */
    private enum class Part { AM, PM, MORNING, NOON, AFTERNOON, EVENING, NIGHT }

    /** A period word just before this point ("3h tối| nay"). */
    private val PERIOD_BEFORE = Pattern.compile("(?:$PERIOD_WORDS)$W*\\z")

    // A meal says when a bare hour is ("Ăn tối 7h", "Dinner at 7") but stays in the title.
    private val MEAL_PERIOD = listOf(
        Pattern.compile("(?:[aă]n|b[uữ]a)$W+s[aá]ng|breakfast") to Part.MORNING,
        Pattern.compile("(?:[aă]n|b[uữ]a)$W+tr[uư]a|lunch") to Part.NOON,
        Pattern.compile("(?:[aă]n|b[uữ]a)$W+t[oố]i|dinner|supper") to Part.EVENING,
    )

    private val MORNING_WORD = Regex("s[aá]ng|morning")
    private val NOON_WORD = Regex("tr[uư]a")
    private val AFTERNOON_WORD = Regex("chi[eề]u|afternoon")
    private val EVENING_WORD = Regex("t[oố]i|evening")

    private fun periodOf(word: String?): Part? = when {
        word == null -> null
        MORNING_WORD.matches(word) -> Part.MORNING
        NOON_WORD.matches(word) -> Part.NOON
        AFTERNOON_WORD.matches(word) -> Part.AFTERNOON
        EVENING_WORD.matches(word) -> Part.EVENING
        else -> Part.NIGHT
    }

    private val VN_WEEKDAY_WORDS = mapOf(
        "2" to 1, "hai" to 1, "3" to 2, "ba" to 2, "4" to 3, "tư" to 3, "tu" to 3, "5" to 4, "năm" to 4, "nam" to 4,
        "6" to 5, "sáu" to 5, "sau" to 5, "7" to 6, "bảy" to 6, "bay" to 6,
    )

    /** ISO weekday (1 Monday ... 7 Sunday) from a vnWeekday() match. */
    private fun vnWeekdayOf(g: Groups, p: String): Int? {
        if (g["${p}cn"] != null) return 7
        val word = g["${p}n"] ?: g["${p}t"]
        return word?.let { VN_WEEKDAY_WORDS[it] }
    }

    private val EN_WEEKDAYS = EN_WEEKDAY.split('|')

    private fun enWeekdayOf(word: String): Int = EN_WEEKDAYS.indexOf(word) + 1

    private val EN_MONTH_NUMBERS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6, "jul" to 7, "aug" to 8,
        "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    private fun enMonthOf(word: String): Int = EN_MONTH_NUMBERS.getValue(word.substring(0, if (word == "sept") 4 else 3))

    private val GIENG = Regex("gi[eê]ng")
    private val CHAP = Regex("ch[aạ]p")

    private fun lunarMonthOf(word: String): Int = when {
        GIENG.matches(word) -> 1
        CHAP.matches(word) -> 12
        else -> word.toInt()
    }

    private data class Clock(
        val hour: Int,
        val minute: Int,
        /** am/pm or a period word was given with it. */
        val meridiem: Part?,
        /** Written with a separator ("3h", "3:00", "3 giờ"). */
        val separated: Boolean,
        /** Written with a leading zero ("05:30"): a 24-hour clock, taken as is. */
        val padded: Boolean,
    )

    private fun clockOf(g: Groups, p: String): Clock? {
        val rawHour = g["${p}h"] ?: return null
        val rawMinute = g["${p}min"]
        val minute = when {
            rawMinute == null -> 0
            rawMinute.any { it in '0'..'9' } -> rawMinute.toInt()
            else -> 30
        }
        if (minute > 59) return null
        val mer = g["${p}mer"]?.replace(".", "")
        return Clock(
            hour = rawHour.toInt(),
            minute = minute,
            meridiem = when (mer) {
                null -> null
                "am" -> Part.AM
                "pm" -> Part.PM
                else -> periodOf(mer)
            },
            separated = g["${p}sep"] != null,
            padded = rawHour.length == 2 && rawHour.startsWith('0'),
        )
    }

    /** The 24-hour hour a clock means, or null if it cannot be one. */
    private fun resolveHour(c: Clock, context: Part? = null): Int? {
        val hour = c.hour
        val mer = c.meridiem ?: context
        if (mer == Part.AM || mer == Part.PM) {
            if (hour < 1 || hour > 12) return null
            return hour % 12 + if (mer == Part.PM) 12 else 0
        }
        if (hour > 23) return null
        return when (mer) {
            Part.MORNING -> hour
            Part.NOON -> if (hour in 1..4) hour + 12 else hour
            Part.AFTERNOON, Part.EVENING -> if (hour in 1..11) hour + 12 else hour
            Part.NIGHT -> if (hour in 6..11) hour + 12 else if (hour == 12) 0 else hour
            // "Họp 3h" is a meeting at three in the afternoon, not the night; a leading zero
            // ("03:00") is a 24-hour clock read as written.
            else -> if (!c.padded && hour in 1..6) hour + 12 else hour
        }
    }

    private fun dateOrNull(year: Int, month: Int, day: Int): LocalDate? =
        try {
            LocalDate.of(year, month, day)
        } catch (e: DateTimeException) {
            null
        }

    /** The date given by day and month, in [year] - or, with none given, the next one from today. */
    private fun solarDate(today: LocalDate, day: Int, month: Int, year: Int?): LocalDate? {
        if (year != null) return dateOrNull(if (year < 100) 2000 + year else year, month, day)
        for (y in listOf(today.year, today.year + 1)) {
            val d = dateOrNull(y, month, day)
            if (d != null && !d.isBefore(today)) return d
        }
        // February 29th in a year without one: the next leap year's.
        for (y in today.year + 2..today.year + 8) dateOrNull(y, month, day)?.let { return it }
        return null
    }

    /** The next Gregorian date of a lunar day and month (today counts). */
    private fun nextLunarDate(today: LocalDate, spec: LunarSpec): LocalDate? {
        for (y in listOf(today.year, today.year + 1, today.year + 2)) {
            val date = Lunar.resolve(spec, y)
            if (date != null && !date.isBefore(today)) return date
        }
        return null
    }

    /** This weekday from today on (today counts). */
    private fun upcomingWeekday(today: LocalDate, weekday: Int): LocalDate =
        today.plusDays(((weekday - today.dayOfWeek.value + 7) % 7).toLong())

    /** This weekday in next week, by the week start the user chose. */
    private fun weekdayNextWeek(today: LocalDate, weekday: Int, firstDayOfWeek: DayOfWeek): LocalDate {
        val next = WeekStart.startOfWeek(today, firstDayOfWeek).plusWeeks(1)
        return next.plusDays(((weekday - next.dayOfWeek.value + 7) % 7).toLong())
    }

    // ─── Scanning ────────────────────────────────────────────────────────────────

    /** A match's named groups; null for one that took no part, or that the pattern doesn't have. */
    private class Groups(private val m: Matcher) {
        operator fun get(name: String): String? =
            try {
                m.group(name)
            } catch (e: IllegalArgumentException) {
                null
            }
    }

    // Words that only led into what was taken out ("lúc", "vào", "at", "on").
    private val DANGLING = Regex("(?iu)(?:^|$W)(?:at|on|from|in|l[uú]c|v[aà]o|t[uừ]|ng[aà]y|@|[-–—,;:])\\z")
    private val EDGES = Regex("^[${JsText.SPACE_CHARS},;:\\-–—@]+|[${JsText.SPACE_CHARS},;:\\-–—@]+\\z")

    /**
     * The title, lower-cased (character for character, so positions line up with the original),
     * with what has been recognised blanked out so that no later pattern can match it again.
     */
    private class Scanner(val text: String) {
        val spans = mutableListOf<Pair<Int, Int>>()

        /** The whole title lower-cased, nothing blanked. */
        val lower: String
        private var work: String

        init {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val ch = String(Character.toChars(text.codePointAt(i)))
                val l = ch.lowercase()
                out.append(if (l.length == ch.length) l else ch)
                i += ch.length
            }
            lower = out.toString()
            work = lower
        }

        /** Each match of [p] not yet taken; [accept] returns false to leave it for other patterns. */
        fun scan(p: Pattern, accept: (g: Groups, at: Int) -> Boolean) {
            var from = 0
            while (from <= work.length) {
                val m = p.matcher(work)
                if (!m.find(from)) return
                val start = m.start()
                val end = m.end()
                if (end == start) {
                    from = end + 1
                    continue
                }
                if (accept(Groups(m), start)) {
                    spans += start to end
                    work = work.substring(0, start) + "\u0000".repeat(end - start) + work.substring(end)
                }
                from = end
            }
        }

        /** The title without what was taken, tidied. */
        fun rest(): String {
            val kept = StringBuilder(text.length)
            for (i in text.indices) kept.append(if (work[i] == '\u0000') ' ' else text[i])
            var out = JsText.trim(JsText.collapse(kept))
            var guard = 0
            while (guard < 4 && DANGLING.containsMatchIn(out)) {
                out = JsText.trim(DANGLING.replaceFirst(out, ""))
                guard++
            }
            return JsText.trim(EDGES.replace(out, ""))
        }

        fun matched(): List<String> = spans.sortedBy { it.first }.map { (s, e) -> JsText.trim(text.substring(s, e)) }
    }

    private sealed interface Time {
        data class Clocks(val a: Clock, val b: Clock? = null) : Time

        data object Noon : Time
    }

    /** What has been recognised so far. */
    private class Found {
        var lunarSpec: LunarSpec? = null
        var lunarDate: LocalDate? = null

        /** By strength: explicit (3) beats relative (2) beats a weekday (1). */
        var date: LocalDate? = null
        var dateStrength = 0

        /** ISO weekday, 1 Monday ... 7 Sunday. */
        var weekday: Int? = null

        /** "chiều mai", "tối nay": how a bare hour later on is read. */
        var dayPeriod: Part? = null

        /** The clock typed, read once the day words are known. */
        var time: Time? = null
        var durationMinutes: Int? = null
        var allDay = false
        var repeat: EntryRepeat? = null
    }

    /** For a bare end hour ("22h-1h", "2h-4h"): whichever of h and h+12 comes soonest after the start. */
    private fun soonestAfter(startMinutes: Int, end: Clock): Int {
        val candidates = if (end.hour < 12) listOf(end.hour, end.hour + 12) else listOf(end.hour % 24)
        fun wait(h: Int): Int = (h * 60 + end.minute - startMinutes + 1440 - 1) % 1440
        return candidates.reduce { best, h -> if (wait(h) < wait(best)) h else best }
    }

    private data class Read(val start: LocalTime? = null, val end: LocalTime? = null)

    /** The start (and end) a typed time or range means, given the part of the day it is in. */
    private fun readClocks(a: Clock, b: Clock?, dayPeriod: Part?): Read {
        if (b == null) {
            val hour = resolveHour(a, dayPeriod) ?: return Read()
            return Read(LocalTime.of(hour, a.minute))
        }
        // "3-5pm": the start takes the end's am/pm - unless that puts it after the end ("11-1pm").
        val inherits = a.meridiem == null && b.meridiem != null
        val inherited = if (inherits) a.copy(meridiem = b.meridiem) else a
        var startHour = resolveHour(inherited, dayPeriod) ?: return Read()
        val bare = b.meridiem == null && dayPeriod == null && !b.padded && b.hour <= 12
        var endHour = (if (bare) soonestAfter(startHour * 60 + a.minute, b) else resolveHour(b, dayPeriod)) ?: return Read()
        if (inherits && startHour * 60 + a.minute > endHour * 60 + b.minute) {
            startHour = resolveHour(a, dayPeriod) ?: return Read()
            if (bare) endHour = soonestAfter(startHour * 60 + a.minute, b)
        }
        return Read(LocalTime.of(startHour, a.minute), LocalTime.of(endHour, b.minute))
    }

    private val AFTER_TOMORROW = Regex("after|kia|mốt")
    private val TOMORROW = Regex("mai|tomorrow")
    private val NEXT_WEEK = Regex("sau|t[ớo]i")

    /**
     * What the title says about when the event is, or null when it says nothing (or nothing would
     * be left of it once that was taken out).
     */
    fun parse(rawTitle: String, context: EntryContext): ParsedEntry? {
        val text = Normalizer.normalize(rawTitle, Normalizer.Form.NFC)
        val today = context.today
        val scan = Scanner(text)
        val f = Found()

        fun setDate(value: LocalDate?, strength: Int): Boolean {
            if (value == null) return false
            if (f.date == null || strength > f.dateStrength) {
                f.date = value
                f.dateStrength = strength
            }
            return true
        }

        // Lunar dates first: "12/8 âm lịch" is not the 12th of August.
        fun takeLunar(day: Int, month: Int, leap: Boolean): Boolean {
            if (f.lunarSpec != null || day < 1 || day > 30 || month < 1 || month > 12) return false
            val spec = LunarSpec(day, month, leap)
            val date = nextLunarDate(today, spec) ?: return false
            f.lunarSpec = spec
            f.lunarDate = date
            return true
        }
        scan.scan(LUNAR_NUMERIC) { g, _ -> takeLunar(g["d"]!!.toInt(), lunarMonthOf(g["m"]!!), g["leap1"] != null || g["leap2"] != null) }
        scan.scan(LUNAR_NAMED_MONTH) { g, _ -> takeLunar(g["d"]!!.toInt(), lunarMonthOf(g["m"]!!), g["leap1"] != null) }
        scan.scan(LUNAR_RAM) { g, _ -> takeLunar(15, lunarMonthOf(g["m"]!!), g["leap1"] != null) }
        scan.scan(LUNAR_MUNG) { g, _ -> takeLunar(g["d"]!!.toInt(), if (g["tet"] != null) 1 else lunarMonthOf(g["m"]!!), g["leap1"] != null) }

        scan.scan(DURATION) { g, _ ->
            val mm = g["mm"]
            val minutes = mm?.toInt()
                ?: (g["h"]!!.toInt() * 60 + (g["tenth"]?.let { it.toInt() * 6 } ?: 0) + (g["m"]?.toInt() ?: 0))
            if (minutes <= 0 || minutes > 24 * 60) return@scan false
            f.durationMinutes = minutes
            true
        }

        fun year(y: String?): Int? = y?.toInt()
        scan.scan(DATE_SLASH) { g, _ -> setDate(solarDate(today, g["d"]!!.toInt(), g["m"]!!.toInt(), year(g["y"])), 3) }
        scan.scan(DATE_VN) { g, _ -> setDate(solarDate(today, g["d"]!!.toInt(), g["m"]!!.toInt(), year(g["y"])), 3) }
        scan.scan(DATE_EN_MONTH_FIRST) { g, _ -> setDate(solarDate(today, g["d"]!!.toInt(), enMonthOf(g["mon"]!!), year(g["y"])), 3) }
        scan.scan(DATE_EN_DAY_FIRST) { g, _ -> setDate(solarDate(today, g["d"]!!.toInt(), enMonthOf(g["mon"]!!), year(g["y"])), 3) }

        // Times before day words, so a period word right after a time is the time's ("3h chiều
        // thứ 6"); they are read at the end, once "tối nay" or a meal has said what part of the day
        // a bare hour is in.
        scan.scan(TIME_RANGE) { g, _ ->
            if (f.time != null) return@scan false
            val a = clockOf(g, "a")
            val b = clockOf(g, "b")
            // "Phòng 3-5" is not a time; "3-5pm" and "15h-17h" are.
            if (a == null || b == null || !(a.separated || a.meridiem != null || b.separated || b.meridiem != null)) return@scan false
            if (resolveHour(a) == null || resolveHour(b) == null) return@scan false
            f.time = Time.Clocks(a, b)
            true
        }
        scan.scan(TIME) { g, _ ->
            if (f.time != null) return@scan false
            val a = clockOf(g, "a")
            if (a == null || !(a.separated || a.meridiem != null || g["at"] != null) || resolveHour(a) == null) return@scan false
            f.time = Time.Clocks(a)
            true
        }
        scan.scan(NOON) { _, _ ->
            if (f.time != null) return@scan false
            f.time = Time.Noon
            true
        }

        scan.scan(REPEAT_EVERY_WEEKDAY) { g, _ ->
            val en = g["en"]
            val wd = if (en != null) enWeekdayOf(en) else vnWeekdayOf(g, "w")
            if (wd == null || wd == 0 || f.repeat != null) return@scan false
            f.weekday = wd
            setDate(upcomingWeekday(today, wd), 1)
            f.repeat = EntryRepeat.Weekly(DayOfWeek.of(wd))
            true
        }
        fun takeRepeat(repeat: EntryRepeat): (Groups, Int) -> Boolean = { _, _ ->
            if (f.repeat != null) {
                false
            } else {
                f.repeat = repeat
                true
            }
        }
        scan.scan(REPEAT_DAILY, takeRepeat(EntryRepeat.Daily))
        scan.scan(REPEAT_WEEKLY, takeRepeat(EntryRepeat.Weekly()))
        scan.scan(REPEAT_MONTHLY, takeRepeat(EntryRepeat.Monthly))
        scan.scan(REPEAT_YEARLY, takeRepeat(EntryRepeat.Yearly))

        scan.scan(RELATIVE_DAY) { g, at ->
            val n = g["n1"] ?: g["n2"] ?: g["n3"]
            if (n != null) return@scan n.toInt() > 0 && setDate(today.plusDays(n.toLong()), 2)
            val rel = g["rel"]!!
            // "nay" alone is not today, and "Mai" with a capital is a name.
            if (rel == "nay" && g["p1"] == null && !PERIOD_BEFORE.matcher(scan.lower.substring(0, at)).find()) return@scan false
            if (rel == "mai" && g["p1"] == null && scan.text[at] == 'M') return@scan false
            val days = if (AFTER_TOMORROW.containsMatchIn(rel)) 2L else if (TOMORROW.containsMatchIn(rel)) 1L else 0L
            f.dayPeriod = periodOf(g["p1"] ?: g["p2"]) ?: (if (rel == "tonight") Part.EVENING else f.dayPeriod)
            setDate(today.plusDays(days), 2)
        }

        scan.scan(VN_WEEKDAY) { g, _ ->
            val wd = vnWeekdayOf(g, "v") ?: return@scan false
            val mod = g["mod"]
            val nextWeek = mod != null && NEXT_WEEK.containsMatchIn(mod)
            f.weekday = f.weekday ?: wd
            f.dayPeriod = periodOf(g["p1"] ?: g["p2"]) ?: f.dayPeriod
            setDate(if (nextWeek) weekdayNextWeek(today, wd, context.firstDayOfWeek) else upcomingWeekday(today, wd), 1)
        }
        scan.scan(EN_WEEKDAY_RE) { g, _ ->
            val wd = enWeekdayOf(g["wd"]!!)
            val nextWeek = g["mod"] == "next" || g["nw"] != null
            f.weekday = f.weekday ?: wd
            f.dayPeriod = periodOf(g["p2"]) ?: f.dayPeriod
            setDate(if (nextWeek) weekdayNextWeek(today, wd, context.firstDayOfWeek) else upcomingWeekday(today, wd), 1)
        }

        scan.scan(ALL_DAY) { _, _ ->
            f.allDay = true
            true
        }

        if (scan.spans.isEmpty()) return null
        val title = scan.rest()
        if (title.isEmpty()) return null

        val dayPeriod = f.dayPeriod ?: MEAL_PERIOD.firstOrNull { (re, _) -> re.matcher(scan.lower).find() }?.second
        val clocks = when (val t = f.time) {
            Time.Noon -> Read(LocalTime.NOON)
            is Time.Clocks -> readClocks(t.a, t.b, dayPeriod)
            null -> Read()
        }
        val matched = scan.matched()

        val lunar = f.lunarSpec
        if (lunar != null) {
            return ParsedEntry(title, date = f.lunarDate, allDay = true, repeat = EntryRepeat.LunarYearly(lunar), matched = matched)
        }
        val repeat = f.repeat
        return ParsedEntry(
            title = title,
            date = f.date,
            start = clocks.start,
            end = clocks.end,
            durationMinutes = if (clocks.end == null) f.durationMinutes else null,
            allDay = f.allDay && clocks.start == null,
            // "Thứ 2 hàng tuần": weekly, on the day named.
            repeat = if (repeat is EntryRepeat.Weekly && repeat.byDay == null && f.weekday != null) {
                EntryRepeat.Weekly(DayOfWeek.of(f.weekday!!))
            } else {
                repeat
            },
            matched = matched,
        )
    }

    // ─── Applying it to the editor's slot ────────────────────────────────────────

    /**
     * [slot] with what the title said put into it. Whatever the title left out stays as it was: a
     * time alone keeps the day picked, a day alone keeps the hour, and an event keeps its length
     * unless an end or a length was given. An all-day slot has no length to keep, so a time typed
     * onto one makes an event of [defaultEventMinutes] (the length new events are set to have).
     * Clock arithmetic is on the wall clock, so "3pm" stays 3pm across a change of the clocks.
     */
    fun applyEntry(slot: EntrySlot, entry: ParsedEntry, defaultEventMinutes: Int = EventLength.DEFAULT): EntrySlot {
        val baseStart = LocalDateTime.of(slot.startDate, slot.startTime)
        val baseEnd = LocalDateTime.of(slot.endDate, slot.endTime)
        val length = if (!slot.allDay && baseEnd.isAfter(baseStart)) ChronoUnit.MINUTES.between(baseStart, baseEnd) else defaultEventMinutes.toLong()
        val spanDays = maxOf(0L, ChronoUnit.DAYS.between(slot.startDate, slot.endDate))
        val startDate = entry.date ?: slot.startDate
        fun timed(start: LocalDateTime, end: LocalDateTime) = EntrySlot(
            allDay = false,
            startDate = start.toLocalDate(),
            startTime = start.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
            endDate = end.toLocalDate(),
            endTime = end.toLocalTime().truncatedTo(ChronoUnit.MINUTES),
        )

        if (entry.repeat is EntryRepeat.LunarYearly) return slot.copy(allDay = true, startDate = startDate, endDate = startDate)

        if (entry.start != null) {
            val start = LocalDateTime.of(startDate, entry.start)
            var end = if (entry.end != null) LocalDateTime.of(startDate, entry.end) else start.plusMinutes(entry.durationMinutes?.toLong() ?: length)
            if (!end.isAfter(start)) end = end.plusDays(1)
            return timed(start, end)
        }

        val endDate = startDate.plusDays(spanDays)
        if (entry.allDay) return slot.copy(allDay = true, startDate = startDate, endDate = endDate)
        if (entry.durationMinutes != null && entry.durationMinutes != 0 && !slot.allDay) {
            val start = LocalDateTime.of(startDate, slot.startTime)
            return timed(start, start.plusMinutes(entry.durationMinutes.toLong()))
        }
        return slot.copy(startDate = startDate, endDate = endDate)
    }

    /** [draft]'s day, times and all-day switch with [entry] put into them; the title and the rest stay. */
    fun applyEntry(draft: EventDraft, entry: ParsedEntry, defaultEventMinutes: Int = EventLength.DEFAULT): EventDraft {
        val slot = applyEntry(EntrySlot(draft.allDay, draft.startDate, draft.startTime, draft.endDate, draft.endTime), entry, defaultEventMinutes)
        return draft.copy(allDay = slot.allDay, startDate = slot.startDate, startTime = slot.startTime, endDate = slot.endDate, endTime = slot.endTime)
    }
}
