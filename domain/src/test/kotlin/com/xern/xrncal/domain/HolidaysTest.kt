package com.xern.xrncal.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The laptop app's holiday-calendars.test.ts. */
class HolidaysTest {
    @Test
    fun vietnamHas2026sPublicAndLunarHolidays() {
        val vn = Holidays.vietnam(2026)
        assertTrue(vn.size > 15)
        assertTrue(vn.any { it.title.contains("Tết Dương Lịch") && it.date == LocalDate.of(2026, 1, 1) })
        assertTrue(vn.any { it.title.contains("Quốc khánh") && it.date == LocalDate.of(2026, 9, 2) })
        assertTrue(vn.any { it.title.contains("Giải phóng Miền Nam") && it.date == LocalDate.of(2026, 4, 30) })
        assertTrue(vn.any { it.title.contains("Mùng 1 Tết") && it.date == LocalDate.of(2026, 2, 17) })
        assertTrue(vn.any { it.title.contains("Giỗ Tổ Hùng Vương") && it.date == LocalDate.of(2026, 4, 26) })
        assertTrue(vn.any { it.title.contains("Trung Thu") && it.date == LocalDate.of(2026, 9, 25) })
        assertEquals(vn.sortedBy { it.date }, vn)
    }

    @Test
    fun internationalHas2026sDays() {
        val intl = Holidays.international(2026)
        assertTrue(intl.size >= 8)
        assertTrue(intl.any { it.title == "New Year's Day" && it.date == LocalDate.of(2026, 1, 1) })
        assertTrue(intl.any { it.title == "Christmas Day" && it.date == LocalDate.of(2026, 12, 25) })
    }

    @Test
    fun severalYearsGiveEveryYearsHolidays() {
        val both = Holidays.between(HolidayCalendar.VIETNAM, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31))
        assertTrue(both.size > 30)
        assertEquals(setOf(2026, 2027), both.map { it.date.year }.toSet())
    }

    /**
     * Tết 2026 is on 17 February, late, so the last days of the lunar year before fall past the
     * 400 days from 1 January 2025 that the laptop app's lunar-to-solar walk covers: there, 2026
     * has no Ông Táo and no Giao Thừa.
     */
    @Test
    fun theLastDaysOfALateLunarYearAreFound() {
        val vn = Holidays.vietnam(2026)
        assertEquals(LocalDate.of(2026, 2, 16), vn.first { it.title.startsWith("Đêm Giao Thừa") }.date)
        assertEquals(LocalDate.of(2026, 2, 10), vn.first { it.title.startsWith("Ông Táo") }.date)
    }
}
