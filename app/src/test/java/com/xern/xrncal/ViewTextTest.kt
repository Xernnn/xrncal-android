package com.xern.xrncal

import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.ui.Format
import com.xern.xrncal.ui.Look
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the month chips and the grids' gutters and headers write (docs/spec.md, phase 2 items 3 and 4). */
class ViewTextTest {
    private val zone = ZoneId.of("Australia/Sydney")
    private val day = LocalDate.of(2026, 11, 24)

    private fun timed(title: String, from: String, to: String): Occurrence {
        val s = LocalDateTime.parse("2026-11-24T$from").atZone(zone).toInstant()
        val e = LocalDateTime.parse("2026-11-24T$to").atZone(zone).toInstant()
        return Occurrence("r", "c", "u", null, title, "", "", false, s, e, day, day, false, false, null)
    }

    @Test
    fun aTimedMonthChipLeadsWithItsStartAsTheLaptopsDoes() {
        val look = Look(today = day, zone = zone, h24 = true)
        assertEquals("10:30 Dentist", look.chipText(timed("Dentist", "10:30", "11:30"), day))
        assertEquals("10:30a Dentist", look.copy(h24 = false).chipText(timed("Dentist", "10:30", "11:30"), day))
        assertEquals("Dentist", look.copy(monthTimes = false).chipText(timed("Dentist", "10:30", "11:30"), day))
        val allDay = Occurrence("r", "c", "u", null, "Trip", "", "", true, day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant(), day, day, false, false, null)
        assertEquals("Trip", look.chipText(allDay, day))
    }

    @Test
    fun theGutterWritesTheHourAndTheSecondZonesAsTheLaptop() {
        assertEquals("08:00", Format.hourLabel(8, true))
        assertEquals("8a", Format.hourLabel(8, false))
        // A zone off the whole hour (India from Sydney) keeps its minutes.
        val there = day.atTime(8, 0).atZone(zone).withZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalTime()
        assertEquals("02:30", Format.hourLabel(there.hour, true, there.minute))
        assertEquals("Mo", Format.weekdayTwo(DayOfWeek.MONDAY))
        assertEquals("Tu", Format.weekdayTwo(DayOfWeek.TUESDAY))
    }
}
