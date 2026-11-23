package com.xern.xrncal.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * One past event, flattened to just what ranking needs. An all-day event's [start] and [end] are
 * midnight UTC of its first and last day, as the laptop stores them ([TitleSuggestions.floatingDay]):
 * its day is read from that, in no zone.
 */
data class TitleSample(
    val title: String,
    val calendarId: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String? = null,
    /** The RRULE value (with or without "RRULE:"), or null for a single event. */
    val rrule: String? = null,
)

data class TitleSuggestion(
    /** Display casing, taken from the most recent event with this title. */
    val title: String,
    /** The calendar this title normally lives on - what picking it applies. */
    val calendarId: String,
    /** Estimated number of times it occurred in the window, recurrence expanded. */
    val uses: Int,
    val lastUsed: Instant,
    /** Median length of its timed occurrences; null if it is only ever all-day. */
    val durationMinutes: Int?,
    /** True when its occurrences are usually all-day. */
    val allDay: Boolean,
    val location: String?,
    val score: Double,
)

/**
 * Ranking for the event-title autocomplete (the laptop app's title-suggestions.ts).
 *
 * The point is not to find every event whose title contains what was typed - search does that. It
 * is to answer "given what I usually put here, at this time, on this day, what am I most likely
 * about to write?", and to carry the calendar that title normally lives on along with the answer.
 *
 * Four signals, combined behind a match gate:
 *
 * - **frequency** - how often the title has come up in the lookback window
 * - **recency** - how long ago it last did, on a 30-day half-life
 * - **time fit** - how close the slot being filled is to the hours this title normally occupies
 * - **day fit** - how often it lands on this weekday
 *
 * Everything here is pure so the weights can be argued with in a test rather than by staring at the
 * running app.
 */
object TitleSuggestions {
    /** Average days per period, used only to estimate how often a series fires. */
    private val FREQ_DAYS = mapOf("DAILY" to 1.0, "WEEKLY" to 7.0, "MONTHLY" to 30.44, "YEARLY" to 365.25)

    /** How far back a title still counts. */
    const val LOOKBACK_DAYS = 365L

    /** Recency half-life. A title last used 30 days ago scores half of one used today. */
    private const val RECENCY_HALF_LIFE_DAYS = 30.0

    /** Spread of the time-of-day fit curve, in hours. 2h off still scores ~0.73. */
    private const val HOUR_SIGMA = 2.5

    /** `uses` at which the frequency signal saturates. */
    private const val FREQ_SATURATION = 40.0

    private const val W_FREQ = 0.34
    private const val W_RECENCY = 0.28
    private const val W_TIME_FIT = 0.26
    private const val W_DAY_FIT = 0.12

    const val DEFAULT_LIMIT = 6

    private const val DAY_MS = 86_400_000.0

    /** The instant an all-day date is stored at, here as on the laptop: midnight UTC. */
    fun floatingDay(date: LocalDate): Instant = date.atStartOfDay(ZoneOffset.UTC).toInstant()

    /** Collapses case and runs of whitespace so "Team  Standup" and "team standup" group. */
    fun normalize(title: String): String = JsText.collapse(JsText.trim(title).lowercase())

    /**
     * A title, or what was typed, as the two are matched: normalized, and without an emoji leading
     * it. The emoji in front of "🍴 Lunch at Pablo's" is not a word to type: "lunch" is how that
     * title starts. And an emoji picked before typing (the editor's emoji button) must not hide
     * every title stored without that emoji. Titles are still grouped and shown with theirs.
     */
    private fun matchKey(text: String): String = normalize(TitleEmoji.withoutLeading(text))

    private val RRULE_PREFIX = Regex("^RRULE:", RegexOption.IGNORE_CASE)

    private fun ruleParts(rrule: String): Map<String, String> {
        val parts = HashMap<String, String>()
        for (chunk in RRULE_PREFIX.replaceFirst(rrule, "").split(';')) {
            val eq = chunk.indexOf('=')
            if (eq > 0) parts[chunk.substring(0, eq).uppercase()] = chunk.substring(eq + 1)
        }
        return parts
    }

    private val COMPACT_UNTIL = Regex("(\\d{4})(\\d{2})(\\d{2})(?:T(\\d{2})(\\d{2})(\\d{2})Z?)?")

    /** `20260301T090000Z` (RFC 5545 form) or an ISO date or time (UTC unless it says) -> the instant. */
    internal fun untilInstant(until: String): Instant? {
        val compact = COMPACT_UNTIL.matchEntire(until)
        val text = if (compact == null) {
            until
        } else {
            val (y, m, d, hh, mm, ss) = compact.destructured
            "$y-$m-${d}T${hh.ifEmpty { "00" }}:${mm.ifEmpty { "00" }}:${ss.ifEmpty { "00" }}Z"
        }
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (e: DateTimeParseException) {
            try {
                LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)
            } catch (e: DateTimeParseException) {
                try {
                    LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant()
                } catch (e: DateTimeParseException) {
                    null
                }
            }
        }
    }

    private fun days(from: Instant, to: Instant): Double = Duration.between(from, to).toMillis() / DAY_MS

    /**
     * How many times a stored event (its [rrule] and first [start]) actually happened inside the
     * window from [windowStart] to [now].
     *
     * A weekly standup is one row, not fifty-two, so counting rows would rank the most habitual
     * events lowest - exactly backwards. Expanding the RRULE properly would mean running the
     * expander over every candidate on every keystroke, so this estimates from FREQ/INTERVAL/BYDAY
     * instead. Being a few occurrences out does not change an ordering that then goes through a log
     * curve. A rule it can't read counts as a single event.
     */
    fun estimateOccurrences(rrule: String?, start: Instant, windowStart: Instant, now: Instant): Int {
        if (rrule.isNullOrEmpty()) return 1

        val parts = ruleParts(rrule)
        val period = FREQ_DAYS[(parts["FREQ"] ?: "").uppercase()] ?: return 1

        val interval = JsText.number(parts["INTERVAL"] ?: "x").let { if (it.isNaN() || it == 0.0) 1.0 else max(1.0, it) }
        val from = maxOf(start, windowStart)
        val until = parts["UNTIL"]?.takeIf { it.isNotEmpty() }?.let(::untilInstant)
        val to = if (until != null && until < now) until else now
        if (!to.isAfter(from)) return 0

        val perPeriod = parts["BYDAY"]?.takeIf { it.isNotEmpty() }?.split(',')?.count { it.isNotEmpty() } ?: 1
        var n = days(from, to) / (period * interval) * perPeriod
        val count = parts["COUNT"]?.takeIf { it.isNotEmpty() }?.let(JsText::number)
        if (count != null && !count.isNaN()) n = min(n, max(0.0, count))

        return max(1L, Math.round(n)).toInt()
    }

    /**
     * When this title was last actually used.
     *
     * For a plain event that is its start. For a live series it is [now] - a weekly meeting set up
     * in 2023 and still running is not two years stale, and using its first date would bury the
     * most-used titles in the list.
     */
    fun effectiveLastUsed(rrule: String?, start: Instant, now: Instant): Instant {
        if (rrule.isNullOrEmpty()) return start
        val until = ruleParts(rrule)["UNTIL"]?.takeIf { it.isNotEmpty() }?.let(::untilInstant)
        if (until != null && until < now) return until
        return maxOf(start, now)
    }

    /**
     * How well a stored title answers what has been typed, both normalized.
     *
     * Returns 0 for "not a match at all", which drops the candidate. A whole-string prefix beats a
     * word-start prefix, which beats an interior substring, by enough that the other signals reorder
     * within a tier rather than across tiers. Multi-token queries must match every token; the
     * weakest token sets the tier.
     */
    fun matchWeight(normalizedTitle: String, normalizedQuery: String): Double {
        if (normalizedQuery.isEmpty()) return 1.0
        if (normalizedTitle.startsWith(normalizedQuery)) return 1.0

        val words = normalizedTitle.split(' ')
        var weight = 1.0

        for (token in normalizedQuery.split(' ').filter { it.isNotEmpty() }) {
            val tier = when {
                words.any { it.startsWith(token) } -> 0.75
                normalizedTitle.contains(token) -> 0.35
                else -> return 0.0
            }
            weight = min(weight, tier)
        }

        return weight
    }

    /** Shortest distance between two hours on a 24-hour clock: 23:00 and 01:00 are 2 apart. */
    private fun hourDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 24
        return min(d, 24 - d)
    }

    private fun median(values: List<Int>): Int? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) Math.round((sorted[mid - 1] + sorted[mid]) / 2.0).toInt() else sorted[mid]
    }

    private fun <T> mostCommon(values: List<T>): T {
        val counts = HashMap<T, Int>()
        var best = values.first()
        var bestCount = 0
        for (v in values) {
            val n = (counts[v] ?: 0) + 1
            counts[v] = n
            // Strictly greater, and `values` arrives newest-first, so a tie keeps the most recently
            // used one.
            if (n > bestCount) {
                best = v
                bestCount = n
            }
        }
        return best
    }

    /** The date an occurrence is on: an all-day one's own (stored at midnight UTC), a timed one's in [zone]. */
    private fun dayOf(allDay: Boolean, start: Instant, zone: ZoneId): LocalDate =
        if (allDay) start.atOffset(ZoneOffset.UTC).toLocalDate() else start.atZone(zone).toLocalDate()

    private class Group(val display: String, val matchKey: String, var lastUsed: Instant) {
        val samples = mutableListOf<TitleSample>()
        var uses = 0
    }

    /**
     * The titles most likely to be typed into a slot starting at [targetStart] (an all-day one's
     * at [floatingDay]), best first, given what has been typed so far ([query]; empty means "what
     * usually goes here?"). Hours and weekdays are read in [zone]; [now] is injectable so tests do
     * not depend on the wall clock.
     */
    fun rank(
        samples: List<TitleSample>,
        query: String,
        targetStart: Instant,
        targetAllDay: Boolean,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        limit: Int = DEFAULT_LIMIT,
    ): List<TitleSuggestion> {
        val windowStart = now.minus(Duration.ofDays(LOOKBACK_DAYS))
        val normalizedQuery = matchKey(query)

        val target = targetStart.atZone(zone)
        val targetHour = target.hour + target.minute / 60.0
        val targetWeekday = dayOf(targetAllDay, targetStart, zone).dayOfWeek

        // Group by normalized title, newest first so display casing and tie-breaks both follow the
        // most recent use.
        val ordered = samples.sortedByDescending { it.start }
        val groups = LinkedHashMap<String, Group>()

        for (sample in ordered) {
            val key = normalize(sample.title)
            if (key.isEmpty()) continue
            val matched = matchKey(sample.title)
            if (matchWeight(matched, normalizedQuery) == 0.0) continue

            val group = groups.getOrPut(key) { Group(JsText.trim(sample.title), matched, sample.start) }
            group.samples += sample
            group.uses += estimateOccurrences(sample.rrule, sample.start, windowStart, now)
            val lastUsed = effectiveLastUsed(sample.rrule, sample.start, now)
            if (lastUsed > group.lastUsed) group.lastUsed = lastUsed
        }

        val suggestions = mutableListOf<TitleSuggestion>()

        for (group in groups.values) {
            val weight = matchWeight(group.matchKey, normalizedQuery)
            if (weight == 0.0 || group.uses == 0) continue

            val freq = min(1.0, ln1p(group.uses.toDouble()) / ln1p(FREQ_SATURATION))

            val daysSince = max(0.0, days(group.lastUsed, now))
            val recency = 0.5.pow(daysSince / RECENCY_HALF_LIFE_DAYS)

            val timed = group.samples.filter { !it.allDay }
            val allDayShare = (group.samples.size - timed.size).toDouble() / group.samples.size

            val timeFit = when {
                targetAllDay -> allDayShare
                timed.isEmpty() -> 0.0
                else -> {
                    // Best fit, not average: a title used at both 09:00 and 17:00 should score full
                    // marks at either, and a circular mean of the two would say 13:00.
                    val closest = timed.minOf { s ->
                        val local = s.start.atZone(zone)
                        hourDistance(local.hour + local.minute / 60.0, targetHour)
                    }
                    (1 - allDayShare) * exp(-(closest * closest) / (2 * HOUR_SIGMA * HOUR_SIGMA))
                }
            }

            val onTargetDay = group.samples.count { dayOf(it.allDay, it.start, zone).dayOfWeek == targetWeekday }
            // Floored rather than raw so a title that simply never lands on this weekday is demoted,
            // not eliminated.
            val dayFit = 0.2 + 0.8 * (onTargetDay.toDouble() / group.samples.size)

            val durations = timed.map { Math.round(Duration.between(it.start, it.end).toMillis() / 60_000.0).toInt() }

            suggestions += TitleSuggestion(
                title = group.display,
                calendarId = mostCommon(group.samples.map { it.calendarId }),
                uses = group.uses,
                lastUsed = group.lastUsed,
                durationMinutes = median(durations.filter { it > 0 }),
                allDay = allDayShare > 0.5,
                location = group.samples.firstOrNull { !it.location.isNullOrEmpty() }?.location,
                score = weight * (W_FREQ * freq + W_RECENCY * recency + W_TIME_FIT * timeFit + W_DAY_FIT * dayFit),
            )
        }

        return suggestions
            .sortedWith(compareByDescending<TitleSuggestion> { it.score }.thenBy { it.title })
            .take(limit.coerceAtLeast(0))
    }
}
