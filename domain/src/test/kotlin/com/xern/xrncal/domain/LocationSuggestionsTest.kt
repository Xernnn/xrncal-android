package com.xern.xrncal.domain

import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The laptop app's location-suggestions.test.ts, with the same places and the same answers. The
 * Location field offers the places used before that match what is typed, the ones used lately and
 * often first. `now` is injected because recency is relative to it.
 */
class LocationSuggestionsTest {
    private val now = Instant.parse("2026-09-15T12:00:00.000Z")

    private fun daysAgo(days: Long): Instant = now.minus(Duration.ofDays(days))

    private fun at(location: String, start: Instant = daysAgo(1), rrule: String? = null) = LocationSample(location, start, rrule)

    private fun rank(samples: List<LocationSample>, query: String, limit: Int = LocationSuggestions.DEFAULT_LIMIT): List<String> =
        LocationSuggestions.rank(samples, query, now, limit).map { it.location }

    // ─── Folding (the phone's TextFold is the laptop's text-fold.ts) ─────────────

    @Test
    fun takesTheAccentsOffVietnameseDIncluded() {
        assertEquals("Ca phe sua da", TextFold.accents("Cà phê sữa đá"))
        assertEquals("DUONG Nguyen Hue", TextFold.accents("ĐƯỜNG Nguyễn Huệ"))
        // Typed as separate marks (NFD) or as one letter (NFC): the same.
        assertEquals(
            TextFold.accents(Normalizer.normalize("Phở", Normalizer.Form.NFC)),
            TextFold.accents(Normalizer.normalize("Phở", Normalizer.Form.NFD)),
        )
    }

    @Test
    fun leavesTheWordsLowerCasedWithOneSpaceBetweenThem() {
        assertEquals("12 le loi q 1", TextFold.forMatch("  12 Lê Lợi,  Q.1 "))
        assertEquals("room 3 hq", TextFold.forMatch("(Room 3) — HQ"))
        assertEquals("", TextFold.forMatch(" ,;- "))
    }

    // ─── rank ────────────────────────────────────────────────────────────────────

    @Test
    fun offersNothingForAnEmptyField() {
        val samples = listOf(at("Cà phê Sáng"), at("Office"))
        assertEquals(emptyList<String>(), rank(samples, ""))
        assertEquals(emptyList<String>(), rank(samples, "   "))
        // Punctuation alone names no place either.
        assertEquals(emptyList<String>(), rank(samples, " , "))
    }

    @Test
    fun findsAPlaceByHowItStartsByAWordInItOrByLettersInsideAWord() {
        val samples = listOf(at("Blue Door Studio"), at("Harbour Library"))
        assertEquals(listOf("Blue Door Studio"), rank(samples, "blu"))
        assertEquals(listOf("Blue Door Studio"), rank(samples, "door"))
        assertEquals(listOf("Harbour Library"), rank(samples, "brar"))
        assertEquals(emptyList<String>(), rank(samples, "zzz"))
    }

    @Test
    fun ignoresAccentsAndCapitals() {
        val samples = listOf(at("Cà phê Sáng, 12 Lê Lợi"), at("Đình làng"), at("Office"))
        assertEquals(listOf("Cà phê Sáng, 12 Lê Lợi"), rank(samples, "ca phe"))
        assertEquals(listOf("Cà phê Sáng, 12 Lê Lợi"), rank(samples, "CA PHE"))
        assertEquals(listOf("Cà phê Sáng, 12 Lê Lợi"), rank(samples, "le loi"))
        assertEquals(listOf("Đình làng"), rank(samples, "dinh"))
        // And the other way round: accents typed find a place stored without them.
        assertEquals(listOf("Ca phe Sang"), rank(listOf(at("Ca phe Sang")), "cà phê"))
    }

    @Test
    fun needsEveryWordTypedToBeInThePlace() {
        val samples = listOf(at("Blue Door Studio"), at("Blue Whale Pool"))
        assertEquals(listOf("Blue Whale Pool"), rank(samples, "blue pool"))
        assertEquals(emptyList<String>(), rank(samples, "blue moon"))
    }

    @Test
    fun putsThePlaceUsedMostRecentlyFirstOtherThingsBeingEqual() {
        val samples = listOf(at("Studio North", daysAgo(90)), at("Studio South", daysAgo(2)))
        assertEquals(listOf("Studio South", "Studio North"), rank(samples, "studio"))
    }

    @Test
    fun putsThePlaceUsedMostOftenFirstOtherThingsBeingEqual() {
        val samples = listOf(
            at("Studio North", daysAgo(3)),
            at("Studio South", daysAgo(3)),
            at("Studio South", daysAgo(10)),
            at("Studio South", daysAgo(17)),
            at("Studio South", daysAgo(24)),
        )
        assertEquals(listOf("Studio South", "Studio North"), rank(samples, "studio"))
    }

    @Test
    fun countsARepeatingEventAsEveryTimeItHappenedAndAsUsedUntilNow() {
        // One row, but fifty-odd visits a year, the last of them this week.
        val samples = listOf(
            at("Pool Lane 4", daysAgo(400), "FREQ=WEEKLY;BYDAY=TU"),
            at("Pool Hall", daysAgo(20)),
            at("Pool Hall", daysAgo(40)),
        )
        val ranked = LocationSuggestions.rank(samples, "pool", now)
        assertEquals(listOf("Pool Lane 4", "Pool Hall"), ranked.map { it.location })
        assertTrue(ranked[0].uses > 50)
        assertEquals(now, ranked[0].lastUsed)
    }

    @Test
    fun leavesOutASeriesThatEndedBeforeTheWindow() {
        val samples = listOf(at("Old Hall", Instant.parse("2019-01-07T09:00:00.000Z"), "FREQ=WEEKLY;UNTIL=20190601T000000Z"))
        assertEquals(emptyList<String>(), rank(samples, "old"))
    }

    @Test
    fun ranksAPlaceThatStartsWithWhatWasTypedAboveOneThatOnlyHasItInALaterWord() {
        val samples = listOf(at("Market Garden", daysAgo(3)), at("Garden Room", daysAgo(3)))
        assertEquals(listOf("Garden Room", "Market Garden"), rank(samples, "gar"))
    }

    @Test
    fun stillLetsAPlaceUsedFarMoreAndLatelyComeFirstOnALooserMatch() {
        val samples = listOf(
            at("Market Garden", daysAgo(1)),
            at("Market Garden", daysAgo(2)),
            at("Market Garden", daysAgo(3)),
            at("Garden Room", daysAgo(30)),
        )
        assertEquals(listOf("Market Garden", "Garden Room"), rank(samples, "gar"))
    }

    @Test
    fun offersOnePlaceOnceHoweverItWasSpelledInTheSpellingUsedMost() {
        val samples = listOf(
            at("ca phe sang", daysAgo(1)),
            at("Cà phê Sáng", daysAgo(5)),
            at("Cà phê  Sáng ", daysAgo(9)),
            at("CÀ PHÊ SÁNG", daysAgo(12)),
        )
        val ranked = LocationSuggestions.rank(samples, "ca", now)
        assertEquals(listOf("Cà phê Sáng"), ranked.map { it.location })
        assertEquals(4, ranked[0].uses)
        assertEquals(daysAgo(1), ranked[0].lastUsed)
    }

    @Test
    fun takesTheLatestSpellingWhenNoneWasUsedMoreThanAnother() {
        val samples = listOf(at("Blue door studio", daysAgo(9)), at("Blue Door Studio", daysAgo(2)))
        assertEquals(listOf("Blue Door Studio"), rank(samples, "blue"))
    }

    @Test
    fun doesNotOfferExactlyWhatIsAlreadyInTheField() {
        val samples = listOf(at("Office"), at("Office Annex"))
        assertEquals(listOf("Office Annex"), rank(samples, "Office"))
        // Typed without its capital or accents, the stored spelling is still worth offering.
        assertEquals(listOf("Office", "Office Annex"), rank(samples, "office"))
    }

    @Test
    fun keepsToTheLimitFiveUnlessToldOtherwise() {
        val samples = (1L..8L).map { i -> at("Room $i", daysAgo(i)) }
        assertEquals(listOf("Room 1", "Room 2", "Room 3", "Room 4", "Room 5"), rank(samples, "room"))
        assertEquals(listOf("Room 1", "Room 2"), rank(samples, "room", 2))
    }

    @Test
    fun skipsBlankPlaces() {
        assertEquals(emptyList<String>(), rank(listOf(at("   "), at("")), "a"))
    }

    @Test
    fun ordersLevelScoresByNameSoTheListDoesNotShuffleBetweenKeystrokes() {
        val samples = listOf(at("Room B", daysAgo(3)), at("Room A", daysAgo(3)))
        assertEquals(listOf("Room A", "Room B"), rank(samples, "room"))
    }
}
