package com.xern.xrncal.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One past event's place, flattened to what ranking needs. */
data class LocationSample(
    val location: String,
    val start: Instant,
    /** The RRULE value, or null for a single event. */
    val rrule: String? = null,
)

data class LocationSuggestion(
    /** As it was typed most often; the latest spelling on a tie. */
    val location: String,
    /** Estimated number of times it was used in the window, recurrence expanded. */
    val uses: Int,
    val lastUsed: Instant,
    val score: Double,
)

/**
 * Ranking for the Location field's autocomplete (the laptop app's location-suggestions.ts): the
 * places typed before that match what is being typed, the ones used lately and often first.
 *
 * A place has no hour or weekday it belongs to, so of the four signals the title ranking uses
 * ([TitleSuggestions]) only two apply here: how often, and how recently. Matching ignores accents,
 * capitals and punctuation, and nothing is offered for an empty field: every place ever used is not
 * an answer to anything.
 */
object LocationSuggestions {
    /**
     * How far back a place still counts as one the user goes to. Longer than the title window's
     * year: places stay where they are, and one visited every year (the dentist, a family home)
     * should still be there the second time.
     */
    const val LOOKBACK_DAYS = 730L

    /** Recency half-life: a place last used 30 days ago scores half of one used today. */
    private const val RECENCY_HALF_LIFE_DAYS = 30.0

    /** `uses` at which the frequency signal saturates. */
    private const val FREQ_SATURATION = 40.0

    private const val W_FREQ = 0.5
    private const val W_RECENCY = 0.5

    const val DEFAULT_LIMIT = 5

    private class Group(var lastUsed: Instant) {
        /** Each spelling of the place and how many samples carry it, newest first. */
        val spellings = LinkedHashMap<String, Int>()
        var uses = 0
    }

    /** The spelling seen most; the newest one when several are level. */
    private fun mainSpelling(spellings: Map<String, Int>): String {
        var best = ""
        var bestCount = 0
        // Strictly greater, and the map was filled newest first.
        for ((spelling, count) in spellings) {
            if (count > bestCount) {
                best = spelling
                bestCount = count
            }
        }
        return best
    }

    /**
     * The places to offer for what is in the field ([query]), best first. [now] is injectable so
     * tests do not depend on the wall clock.
     */
    fun rank(
        samples: List<LocationSample>,
        query: String,
        now: Instant = Instant.now(),
        limit: Int = DEFAULT_LIMIT,
    ): List<LocationSuggestion> {
        val folded = TextFold.forMatch(query)
        if (folded.isEmpty()) return emptyList()

        val windowStart = now.minus(Duration.ofDays(LOOKBACK_DAYS))
        val typed = JsText.trim(query)

        // "Cà phê Sáng", "ca phe sang" and "Cà phê  Sáng" are one place.
        val ordered = samples.sortedByDescending { it.start }
        val groups = LinkedHashMap<String, Group>()

        for (sample in ordered) {
            val spelling = JsText.collapse(JsText.trim(sample.location))
            val key = TextFold.forMatch(spelling)
            if (key.isEmpty() || TitleSuggestions.matchWeight(key, folded) == 0.0) continue

            val group = groups.getOrPut(key) { Group(sample.start) }
            group.spellings[spelling] = (group.spellings[spelling] ?: 0) + 1
            group.uses += TitleSuggestions.estimateOccurrences(sample.rrule, sample.start, windowStart, now)
            val lastUsed = TitleSuggestions.effectiveLastUsed(sample.rrule, sample.start, now)
            if (lastUsed > group.lastUsed) group.lastUsed = lastUsed
        }

        val suggestions = mutableListOf<LocationSuggestion>()

        for ((key, group) in groups) {
            if (group.uses == 0) continue
            val location = mainSpelling(group.spellings)
            // Offering exactly what is already in the field would change nothing.
            if (location == typed) continue

            val freq = min(1.0, ln1p(group.uses.toDouble()) / ln1p(FREQ_SATURATION))
            val daysSince = max(0.0, Duration.between(group.lastUsed, now).toMillis() / 86_400_000.0)
            val recency = 0.5.pow(daysSince / RECENCY_HALF_LIFE_DAYS)

            suggestions += LocationSuggestion(
                location = location,
                uses = group.uses,
                lastUsed = group.lastUsed,
                score = TitleSuggestions.matchWeight(key, folded) * (W_FREQ * freq + W_RECENCY * recency),
            )
        }

        return suggestions
            .sortedWith(compareByDescending<LocationSuggestion> { it.score }.thenByDescending { it.lastUsed }.thenBy { it.location })
            .take(limit.coerceAtLeast(0))
    }
}
