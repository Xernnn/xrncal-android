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
    fun theGutterWritesTheHourAsANumberOnly() {
        // 24 hours: two digits and no ":00", midnight to 23.
        assertEquals("08", Format.hourLabel(8, true))
        assertEquals((0..23).map { "%02d".format(it) }, (0..23).map { Format.hourLabel(it, true) })
        // 12 hours: One Calendar's short form.
        assertEquals("8a", Format.hourLabel(8, false))
        assertEquals("12a", Format.hourLabel(0, false))
        assertEquals("12p", Format.hourLabel(12, false))
        assertEquals("11p", Format.hourLabel(23, false))
    }

    @Test
    fun theSecondZoneWritesItsHourTheSameWayAndKeepsMinutesOffTheHour() {
        // Ho Chi Minh City from Sydney in summer: 08:00 in Sydney is 04:00 there.
        val hcm = day.atTime(8, 0).atZone(zone).withZoneSameInstant(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalTime()
        assertEquals("04", Format.hourLabel(hcm.hour, true, hcm.minute))
        assertEquals("4a", Format.hourLabel(hcm.hour, false, hcm.minute))
        // A zone off the whole hour (India, +5:30, from Sydney) keeps its minutes.
        val there = day.atTime(8, 0).atZone(zone).withZoneSameInstant(ZoneId.of("Asia/Kolkata")).toLocalTime()
        assertEquals("02:30", Format.hourLabel(there.hour, true, there.minute))
        assertEquals("2:30a", Format.hourLabel(there.hour, false, there.minute))
    }

    @Test
    fun theWeekHeaderNamesTheDaysInTwoLetters() {
        assertEquals("Mo", Format.weekdayTwo(DayOfWeek.MONDAY))
        assertEquals("Tu", Format.weekdayTwo(DayOfWeek.TUESDAY))
    }

    @Test
    fun theLaptopsWeekHeaderWritesTheWeekdayInCapitalsAndMarksTheWeekend() {
        assertEquals(listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU"), DayOfWeek.entries.map { Format.weekdayCaps(it) })
        assertEquals(listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY), DayOfWeek.entries.filter { Format.weekend(it) })
    }
}
