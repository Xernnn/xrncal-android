package com.xern.xrncal.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The laptop app's title-suggestions.test.ts, with the same history and the same answers. The
 * ranking exists to answer "what usually goes in this slot?", so almost every test here fixes one
 * signal and varies another. `now` is injected because every one of them is relative to it.
 */
class TitleSuggestionsTest {
    private val now = Instant.parse("2026-09-15T12:00:00.000Z")

    /** A timed sample, an hour long unless told otherwise. */
    private fun sample(
        title: String,
        start: String = "2026-09-14T09:00:00.000Z",
        end: String? = null,
        calendarId: String = "cal-work",
        allDay: Boolean = false,
        location: String? = null,
        rrule: String? = null,
    ): TitleSample {
        val startAt = Instant.parse(start)
        return TitleSample(title, calendarId, startAt, end?.let(Instant::parse) ?: startAt.plus(Duration.ofHours(1)), allDay, location, rrule)
    }

    private fun rank(
        samples: List<TitleSample>,
        query: String = "",
        targetStart: String = "2026-09-15T09:00:00.000Z",
        targetAllDay: Boolean = false,
        limit: Int = TitleSuggestions.DEFAULT_LIMIT,
        zone: ZoneId = ZoneId.systemDefault(),
    ) = TitleSuggestions.rank(samples, query, Instant.parse(targetStart), targetAllDay, now, zone, limit)

    private fun daysBefore(iso: String, days: Long): String = Instant.parse(iso).minus(Duration.ofDays(days)).toString()

    // ─── normalize ───────────────────────────────────────────────────────────────

    @Test
    fun collapsesCaseAndRunsOfWhitespaceSoNearIdenticalTitlesGroup() {
        assertEquals("team standup", TitleSuggestions.normalize("  Team   Standup "))
        assertEquals(TitleSuggestions.normalize("team standup"), TitleSuggestions.normalize("TEAM STANDUP"))
    }

    // ─── matchWeight ─────────────────────────────────────────────────────────────

    @Test
    fun acceptsEverythingWhenNothingHasBeenTypedYet() {
        assertEquals(1.0, TitleSuggestions.matchWeight("anything at all", ""), 0.0)
    }

    @Test
    fun ranksAWholeStringPrefixAboveAWordPrefixAboveAnInteriorMatch() {
        val wholePrefix = TitleSuggestions.matchWeight("standup with design", "stand")
        val wordPrefix = TitleSuggestions.matchWeight("design standup", "stand")
        val interior = TitleSuggestions.matchWeight("understanding tests", "stand")
        assertTrue(wholePrefix > wordPrefix)
        assertTrue(wordPrefix > interior)
        assertTrue(interior > 0)
    }

    @Test
    fun rejectsATitleThatIsMissingAnyTokenOfAMultiWordQuery() {
        assertTrue(TitleSuggestions.matchWeight("team standup", "team stand") > 0)
        assertEquals(0.0, TitleSuggestions.matchWeight("team standup", "team retro"), 0.0)
    }

    @Test
    fun takesTheWeakestTokenAsTheTierSoOneLooseTokenDemotesTheMatch() {
        // "team" is a word prefix (0.75), "andup" is only interior (0.35).
        assertEquals(0.35, TitleSuggestions.matchWeight("team standup", "team andup"), 0.0)
    }

    // ─── estimateOccurrences ─────────────────────────────────────────────────────

    private val windowStart = Instant.parse("2025-09-15T00:00:00.000Z")
    private val windowEnd = Instant.parse("2026-09-15T00:00:00.000Z")

    private fun estimate(rrule: String?, start: String) = TitleSuggestions.estimateOccurrences(rrule, Instant.parse(start), windowStart, windowEnd)

    @Test
    fun countsAOneOffEventOnce() {
        assertEquals(1, estimate(null, "2026-09-01T09:00:00.000Z"))
    }

    @Test
    fun countsAWeeklySeriesRoughlyOnceAWeekAcrossTheWindow() {
        val n = estimate("FREQ=WEEKLY;BYDAY=MO", "2023-01-02T09:00:00.000Z")
        assertTrue("$n", n > 45)
        assertTrue("$n", n < 56)
    }

    @Test
    fun multipliesAWeeklySeriesByTheNumberOfDaysItFiresOn() {
        val once = estimate("FREQ=WEEKLY;BYDAY=MO", "2023-01-02T09:00:00.000Z")
        val thrice = estimate("FREQ=WEEKLY;BYDAY=MO,WE,FR", "2023-01-02T09:00:00.000Z")
        assertTrue(thrice > once * 2.5)
    }

    @Test
    fun halvesForIntervalTwo() {
        val weekly = estimate("FREQ=WEEKLY", "2023-01-02T09:00:00.000Z")
        val fortnightly = estimate("FREQ=WEEKLY;INTERVAL=2", "2023-01-02T09:00:00.000Z")
        // vitest's toBeCloseTo(weekly / 2, -0.5): within 10^0.5 / 2.
        assertEquals(weekly / 2.0, fortnightly.toDouble(), 1.5811)
    }

    @Test
    fun neverExceedsAnExplicitCount() {
        assertEquals(5, estimate("FREQ=DAILY;COUNT=5", "2026-01-01T09:00:00.000Z"))
    }

    @Test
    fun stopsCountingASeriesThatEndedInRfc5545CompactForm() {
        // Ran for about a month inside the window, then stopped.
        val n = estimate("FREQ=DAILY;UNTIL=20251015T090000Z", "2025-09-15T09:00:00.000Z")
        assertTrue("$n", n > 25)
        assertTrue("$n", n < 35)
    }

    @Test
    fun countsNothingForASeriesThatFinishedBeforeTheWindowOpened() {
        assertEquals(0, estimate("FREQ=DAILY;UNTIL=20200101T090000Z", "2019-01-01T09:00:00.000Z"))
    }

    @Test
    fun treatsAnUnparseableRuleAsASingleEventRatherThanThrowing() {
        assertEquals(1, estimate("NONSENSE", "2026-09-01T09:00:00.000Z"))
    }

    // ─── effectiveLastUsed ───────────────────────────────────────────────────────

    private val noon = Instant.parse("2026-09-15T12:00:00.000Z")

    @Test
    fun usesTheStartForAOneOffEvent() {
        assertEquals(Instant.parse("2026-03-01T09:00:00.000Z"), TitleSuggestions.effectiveLastUsed(null, Instant.parse("2026-03-01T09:00:00.000Z"), noon))
    }

    @Test
    fun treatsAStillRunningSeriesAsCurrentNotAsOldAsItsFirstOccurrence() {
        // A standup set up in 2023 and still running is not two and a half years stale - scoring
        // it on its first date would bury the most-used title in the list.
        assertEquals(noon, TitleSuggestions.effectiveLastUsed("FREQ=WEEKLY", Instant.parse("2023-01-02T09:00:00.000Z"), noon))
    }

    @Test
    fun usesUntilForASeriesThatHasFinished() {
        assertEquals(
            Instant.parse("2026-04-01T09:00:00.000Z"),
            TitleSuggestions.effectiveLastUsed("FREQ=WEEKLY;UNTIL=20260401T090000Z", Instant.parse("2023-01-02T09:00:00.000Z"), noon),
        )
    }

    // ─── rank ────────────────────────────────────────────────────────────────────

    @Test
    fun returnsNothingWhenThereIsNoHistory() {
        assertEquals(emptyList<TitleSuggestion>(), rank(emptyList()))
    }

    @Test
    fun suggestsWithoutAQueryAtAllWhichIsTheWhatGoesHereCase() {
        val out = rank(listOf(sample("Standup"), sample("Retro")))
        assertEquals(listOf("Retro", "Standup"), out.map { it.title }.sorted())
    }

    @Test
    fun dropsTitlesThatDoNotMatchWhatHasBeenTyped() {
        val out = rank(listOf(sample("Standup"), sample("Retro")), query = "ret")
        assertEquals(listOf("Retro"), out.map { it.title })
    }

    @Test
    fun groupsDifferingCaseAndSpacingIntoOneSuggestionShowingTheNewestCasing() {
        val out = rank(
            listOf(
                sample("Team Standup", start = "2026-09-14T09:00:00.000Z"),
                sample("team  standup", start = "2026-09-07T09:00:00.000Z"),
                sample("TEAM STANDUP", start = "2026-08-31T09:00:00.000Z"),
            ),
        )
        assertEquals(1, out.size)
        assertEquals("Team Standup", out[0].title)
        assertEquals(3, out[0].uses)
    }

    @Test
    fun ranksATitleUsedOftenAboveOneUsedOnceAllElseEqual() {
        val often = listOf(0L, 7L, 14L, 21L).map { d -> sample("Standup", start = daysBefore("2026-09-14T09:00:00.000Z", d)) }
        val out = rank(often + sample("Retro"))
        assertEquals("Standup", out[0].title)
    }

    @Test
    fun countsARecurringSeriesByItsOccurrencesNotAsASingleRow() {
        // The whole point: one weekly row must outrank three one-off rows, because in lived terms
        // it happened fifty times and they happened three.
        val out = rank(
            listOf(
                sample("Weekly sync", rrule = "FREQ=WEEKLY", start = "2025-01-06T09:00:00.000Z"),
                sample("Ad hoc", start = "2026-09-14T09:00:00.000Z"),
                sample("Ad hoc", start = "2026-09-13T09:00:00.000Z"),
                sample("Ad hoc", start = "2026-09-12T09:00:00.000Z"),
            ),
        )
        assertEquals("Weekly sync", out[0].title)
        assertTrue(out[0].uses > 40)
    }

    @Test
    fun ranksARecentlyUsedTitleAboveALongStaleOneOfEqualFrequency() {
        val out = rank(listOf(sample("Fresh", start = "2026-09-13T09:00:00.000Z"), sample("Stale", start = "2025-11-01T09:00:00.000Z")))
        assertEquals("Fresh", out[0].title)
    }

    @Test
    fun prefersTheTitleThatUsuallyHappensAtTheHourBeingFilled() {
        val out = rank(
            listOf(sample("Morning standup", start = "2026-09-14T09:00:00.000Z"), sample("Evening gym", start = "2026-09-14T18:00:00.000Z")),
            targetStart = "2026-09-15T18:15:00.000Z",
        )
        assertEquals("Evening gym", out[0].title)
    }

    @Test
    fun scoresATitleUsedAtTwoDifferentHoursWellAtEitherOfThem() {
        // A best-fit, not an average: the mean of 09:00 and 17:00 is 13:00, which is the one time
        // this title never happens.
        val twice = listOf(sample("Clinic", start = "2026-09-14T09:00:00.000Z"), sample("Clinic", start = "2026-09-12T17:00:00.000Z"))
        val atNine = rank(twice, targetStart = "2026-09-15T09:00:00.000Z")[0]
        val atFive = rank(twice, targetStart = "2026-09-15T17:00:00.000Z")[0]
        val atOne = rank(twice, targetStart = "2026-09-15T13:00:00.000Z")[0]
        // vitest's toBeCloseTo(..., 5): within 10^-5 / 2.
        assertEquals(atNine.score, atFive.score, 0.000005)
        assertTrue(atOne.score < atNine.score)
    }

    @Test
    fun prefersTheTitleThatUsuallyLandsOnTheWeekdayBeingFilled() {
        // 2026-09-15 is a Tuesday. Both titles are equally frequent and equally recent; only the
        // weekday separates them.
        val out = rank(
            listOf(
                // Tuesdays
                sample("Tuesday review", start = "2026-09-08T09:00:00.000Z"),
                sample("Tuesday review", start = "2026-09-01T09:00:00.000Z"),
                // Saturdays
                sample("Saturday errands", start = "2026-09-12T09:00:00.000Z"),
                sample("Saturday errands", start = "2026-09-05T09:00:00.000Z"),
            ),
            targetStart = "2026-09-15T09:00:00.000Z",
        )
        assertEquals("Tuesday review", out[0].title)
    }

    @Test
    fun prefersAllDayHistoryWhenTheSlotBeingFilledIsAllDay() {
        val out = rank(
            listOf(
                sample("Timed thing", start = "2026-09-14T09:00:00.000Z"),
                sample("Day off", start = "2026-09-14T00:00:00.000Z", end = "2026-09-14T00:00:00.000Z", allDay = true),
            ),
            targetStart = "2026-09-15T00:00:00.000Z",
            targetAllDay = true,
        )
        assertEquals("Day off", out[0].title)
    }

    @Test
    fun carriesTheCalendarTheTitleIsMostOftenFiledUnder() {
        val out = rank(
            listOf(
                sample("Standup", calendarId = "cal-work", start = "2026-09-14T09:00:00.000Z"),
                sample("Standup", calendarId = "cal-work", start = "2026-09-07T09:00:00.000Z"),
                sample("Standup", calendarId = "cal-personal", start = "2026-09-01T09:00:00.000Z"),
            ),
        )
        assertEquals("cal-work", out[0].calendarId)
    }

    @Test
    fun breaksACalendarTieTowardTheMostRecentUse() {
        val out = rank(
            listOf(
                sample("Gym", calendarId = "cal-new", start = "2026-09-14T09:00:00.000Z"),
                sample("Gym", calendarId = "cal-old", start = "2026-08-01T09:00:00.000Z"),
            ),
        )
        assertEquals("cal-new", out[0].calendarId)
    }

    @Test
    fun reportsTheMedianLengthOfTheTimedOccurrences() {
        val out = rank(
            listOf(
                sample("Call", start = "2026-09-14T09:00:00.000Z", end = "2026-09-14T09:30:00.000Z"),
                sample("Call", start = "2026-09-13T09:00:00.000Z", end = "2026-09-13T10:00:00.000Z"),
                sample("Call", start = "2026-09-12T09:00:00.000Z", end = "2026-09-12T09:30:00.000Z"),
            ),
        )
        assertEquals(30, out[0].durationMinutes)
    }

    @Test
    fun honoursTheLimit() {
        val many = listOf("a", "b", "c", "d", "e", "f", "g").map { sample(it) }
        assertEquals(3, rank(many, limit = 3).size)
    }

    @Test
    fun ignoresBlankTitlesInsteadOfOfferingAnEmptyRow() {
        assertEquals(emptyList<TitleSuggestion>(), rank(listOf(sample("   "))))
    }

    // ─── With an emoji leading the title ─────────────────────────────────────────
    // An emoji picked in the editor leads the title. It is not something to match on: "lunch" is
    // how "🍴 Lunch at Pablo's" starts, and a title typed after picking an emoji still finds the
    // titles stored without one.

    private val history = listOf(
        sample("🍴 Lunch at Pablo's", start = "2026-09-14T05:00:00.000Z"),
        sample("Launch review", start = "2026-09-13T05:00:00.000Z"),
        sample("Team lunch", start = "2026-09-12T05:00:00.000Z"),
    )

    @Test
    fun findsTheTitleByItsWordsAsTheStartOfIt() {
        val out = rank(history, query = "lunch")
        assertEquals(listOf("🍴 Lunch at Pablo's", "Team lunch"), out.map { it.title })
        // The emoji stays on the suggestion.
        assertTrue(out[0].title.startsWith("🍴"))
        // A whole-title prefix, not merely a word in it.
        assertEquals(1.0, TitleSuggestions.matchWeight(TitleSuggestions.normalize("lunch at pablo"), "lunch"), 0.0)
        assertEquals(listOf("🍴 Lunch at Pablo's"), rank(history, query = "lunch at p").map { it.title })
    }

    @Test
    fun ignoresAnEmojiLeadingWhatWasTyped() {
        assertEquals(listOf("🍴 Lunch at Pablo's", "Team lunch"), rank(history, query = "🍴 lun").map { it.title })
        // Another emoji than the stored one, or one on a title stored without any.
        assertEquals(listOf("🍴 Lunch at Pablo's"), rank(history, query = "🥗 lunch at").map { it.title })
        assertEquals(listOf("Launch review"), rank(history, query = "🚀 laun").map { it.title })
    }

    @Test
    fun ranksTheTitleWithTheEmojiLevelWithTheSameTitleWithoutIt() {
        val pair = listOf(sample("🍴 Lunch", start = "2026-09-14T05:00:00.000Z"), sample("Lunch", start = "2026-09-14T05:00:00.000Z"))
        val out = rank(pair, query = "lun")
        assertEquals(2, out.size)
        assertEquals(out[0].score, out[1].score, 0.0)
    }

    @Test
    fun stillMatchesAnEmojiFurtherIntoTheTitleAsTyped() {
        val out = rank(listOf(sample("Lunch 🍴 with Sam")), query = "lunch 🍴")
        assertEquals(listOf("Lunch 🍴 with Sam"), out.map { it.title })
    }

    @Test
    fun putsAPrefixMatchAheadOfAnInteriorOneEvenWhenTheInteriorIsMoreFrequent() {
        val out = rank(
            listOf(sample("Standup", start = "2026-09-14T09:00:00.000Z")) +
                listOf(0L, 1L, 2L, 3L, 4L).map { d -> sample("Understanding the roadmap", start = daysBefore("2026-09-14T09:00:00.000Z", d)) },
            query = "stand",
        )
        assertEquals("Standup", out[0].title)
    }

    // ─── Zones ───────────────────────────────────────────────────────────────────

    private val zones = listOf("UTC", "Australia/Sydney", "Asia/Ho_Chi_Minh", "America/Los_Angeles").map(ZoneId::of)

    @Test
    fun prefersTheTitleThatUsuallyHappensAtThisHourRegardlessOfTheZone() {
        // Sample and target are both built from local wall times, so they shift together and the
        // fit is the same everywhere. Asserting it in four zones is what proves that.
        for (zone in zones) {
            fun at(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant().toString()
            val out = rank(
                listOf(sample("Breakfast", start = at("2026-09-14T08:00")), sample("Dinner", start = at("2026-09-14T20:00"))),
                targetStart = at("2026-09-15T20:00"),
                zone = zone,
            )
            assertEquals(zone.id, "Dinner", out[0].title)
        }
    }

    @Test
    fun scoresAllDayHistoryIdenticallyInEveryZone() {
        // All-day events are floating dates stored at midnight UTC. Reading their weekday by
        // converting to local time lands a day early west of GMT - the same mistake that once made
        // every Sunday event vanish - and would show up here as Los Angeles disagreeing with UTC
        // about the day fit.
        val samples = listOf(
            sample("Tuesday holiday", start = "2026-09-01T00:00:00.000Z", end = "2026-09-01T00:00:00.000Z", allDay = true),
            sample("Wednesday holiday", start = "2026-09-02T00:00:00.000Z", end = "2026-09-02T00:00:00.000Z", allDay = true),
        )
        // 2026-09-22 is a Tuesday.
        fun ranked(zone: ZoneId) =
            rank(samples, query = "holiday", targetStart = "2026-09-22T00:00:00.000Z", targetAllDay = true, zone = zone).map { it.title to it.score }

        val baseline = ranked(ZoneId.of("UTC"))
        assertEquals("Tuesday holiday", baseline[0].first)
        for (zone in zones.drop(1)) assertEquals(zone.id, baseline, ranked(zone))
    }

    @Test
    fun storesAnAllDayDateAtMidnightUtc() {
        assertEquals(Instant.parse("2026-09-22T00:00:00Z"), TitleSuggestions.floatingDay(java.time.LocalDate.of(2026, 9, 22)))
    }
}
