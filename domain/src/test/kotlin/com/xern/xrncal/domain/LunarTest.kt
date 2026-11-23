package com.xern.xrncal.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The laptop app's lunar-vietnam.test.ts and lunar-next-dates.test.ts, with the same dates. */
class LunarTest {
    @Test
    fun findsTetAcrossSeveralYears() {
        val t2024 = Lunar.solarToLunar(10, 2, 2024)
        assertEquals(listOf(1, 1, 2024), listOf(t2024.day, t2024.month, t2024.year))
        assertEquals("Giáp Thìn", t2024.canChiYear)
        val t2025 = Lunar.solarToLunar(29, 1, 2025)
        assertEquals(listOf(1, 1, 2025), listOf(t2025.day, t2025.month, t2025.year))
        assertEquals("Ất Tỵ", t2025.canChiYear)
        val t2026 = Lunar.solarToLunar(17, 2, 2026)
        assertEquals(listOf(1, 1, 2026), listOf(t2026.day, t2026.month, t2026.year))
        assertEquals("Bính Ngọ", t2026.canChiYear)
    }

    @Test
    fun findsMidAutumn() {
        val d = Lunar.solarToLunar(25, 9, 2026)
        assertEquals(15, d.day)
        assertEquals(8, d.month)
    }

    @Test
    fun labelsTheFirstTheFullMoonAndOtherDays() {
        val first = Lunar.label(Lunar.solarToLunar(17, 2, 2026))
        assertEquals("1/1", first.label)
        assertTrue(first.isFirstDay)
        assertTrue(first.isTet)
        val full = Lunar.label(Lunar.solarToLunar(25, 9, 2026))
        assertEquals("15/8", full.label)
        assertTrue(full.isFullMoon)
        val normal = Lunar.label(Lunar.solarToLunar(18, 8, 2026))
        assertEquals("6", normal.label)
        assertFalse(normal.isFirstDay)
    }

    @Test
    fun placesAnAnniversaryOnItsDateEachYear() {
        val spec = LunarSpec(10, 3)
        assertEquals(LocalDate.of(2024, 4, 18), Lunar.resolve(spec, 2024))
        assertEquals(LocalDate.of(2025, 4, 7), Lunar.resolve(spec, 2025))
        assertEquals(LocalDate.of(2026, 4, 26), Lunar.resolve(spec, 2026))
        assertEquals(LocalDate.of(2027, 4, 16), Lunar.resolve(spec, 2027))
    }

    @Test
    fun aResolvedDateMapsBackToItsLunarDate() {
        for (year in 2024..2028) {
            val d = Lunar.resolve(LunarSpec(5, 7), year)
            assertNotNull(d)
            val back = Lunar.ofSolar(d!!)
            assertEquals(5, back.day)
            assertEquals(7, back.month)
            assertFalse(back.leap)
            assertEquals(year, d.year)
        }
    }

    @Test
    fun day30FallsBackToDay29InAShortMonth() {
        assertEquals(LocalDate.of(2025, 3, 28), Lunar.resolve(LunarSpec(30, 2), 2025))
    }

    @Test
    fun aLeapAnniversaryFallsBackToTheOrdinaryMonth() {
        assertEquals(Lunar.resolve(LunarSpec(15, 6), 2026), Lunar.resolve(LunarSpec(15, 6, leap = true), 2026))
    }

    @Test
    fun aLeapMonthIsUsedWhenTheYearHasOne() {
        val ordinary = Lunar.resolve(LunarSpec(15, 7), 2025)
        val leap = Lunar.resolve(LunarSpec(15, 7, leap = true), 2025)
        assertEquals(LocalDate.of(2025, 8, 8), ordinary)
        assertEquals(LocalDate.of(2025, 9, 6), leap)
    }

    @Test
    fun leapMonthDaysAreMarked() {
        val d = Lunar.ofSolar(LocalDate.of(2025, 9, 6))
        assertTrue(d.leap)
        assertEquals("15/7*", Lunar.label(d).label)
        assertEquals("15/7* Ất Tỵ", Lunar.longLabel(d))
    }

    @Test
    fun mungMotCarriesTheNewMoonWithItsMonth() {
        assertEquals(MoonMark(Moon.NEW, "1/10"), Lunar.moon(Lunar.ofSolar(LocalDate.of(2026, 11, 9))))
    }

    @Test
    fun ramCarriesTheFullMoonWithItsMonth() {
        assertEquals(MoonMark(Moon.FULL, "15/10"), Lunar.moon(Lunar.ofSolar(LocalDate.of(2026, 11, 23))))
        assertEquals(MoonMark(Moon.FULL, "15/8"), Lunar.moon(Lunar.ofSolar(LocalDate.of(2026, 9, 25))))
    }

    @Test
    fun tetIsMarkedAsAMungMot() {
        assertEquals(MoonMark(Moon.NEW, "Tết", tet = true), Lunar.moon(Lunar.ofSolar(LocalDate.of(2026, 2, 17))))
        assertEquals(Moon.NEW, Lunar.moon(Lunar.ofSolar(LocalDate.of(2025, 1, 29)))!!.moon)
    }

    @Test
    fun anOrdinaryDayHasNoMarker() {
        assertNull(Lunar.moon(Lunar.ofSolar(LocalDate.of(2026, 11, 24))))
        assertNull(Lunar.moon(Lunar.ofSolar(LocalDate.of(2026, 11, 22)))) // 14/10, the eve of rằm
        assertNull(Lunar.moon(Lunar.ofSolar(LocalDate.of(2026, 11, 10)))) // 2/10
    }

    @Test
    fun aLeapMonthsMoonsCarryItsStar() {
        assertEquals(MoonMark(Moon.NEW, "1/7*"), Lunar.moon(Lunar.ofSolar(LocalDate.of(2025, 8, 23))))
        assertEquals(MoonMark(Moon.FULL, "15/7*"), Lunar.moon(Lunar.ofSolar(LocalDate.of(2025, 9, 6))))
        // The ordinary 7th month's, a month before.
        assertEquals(MoonMark(Moon.FULL, "15/7"), Lunar.moon(Lunar.ofSolar(LocalDate.of(2025, 8, 8))))
    }

    private val gio = LunarSpec(3, 2)

    @Test
    fun nextDatesLeaveOutThisYearsOnceItHasGone() {
        assertEquals(
            listOf("2027-03-10", "2028-02-27", "2029-03-17", "2030-03-06").map(LocalDate::parse),
            Lunar.next(gio, LocalDate.of(2026, 11, 19), 4),
        )
    }

    @Test
    fun nextDatesStartWithThisYearsWhileItIsToCome() {
        assertEquals(listOf("2026-03-21", "2027-03-10").map(LocalDate::parse), Lunar.next(gio, LocalDate.of(2026, 1, 5), 2))
    }

    @Test
    fun theDayItselfCountsAsToCome() {
        assertEquals(listOf(LocalDate.of(2026, 3, 21)), Lunar.next(gio, LocalDate.of(2026, 3, 21), 1))
    }

    @Test
    fun lunarToSolarRoundTripsTet() {
        assertEquals(LocalDate.of(2026, 2, 17), Lunar.lunarToSolar(1, 1, 2026))
        val back = Lunar.ofSolar(LocalDate.of(2026, 2, 17))
        assertEquals(listOf(1, 1, 2026), listOf(back.day, back.month, back.year))
    }
}
