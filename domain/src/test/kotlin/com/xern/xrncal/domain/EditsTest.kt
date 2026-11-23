package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.ICalParser
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Changes to a resource keep everything they don't touch, and write times the way other clients read them. */
class EditsTest {
    private val hcm = ZoneId.of("Asia/Ho_Chi_Minh")
    private val sydney = ZoneId.of("Australia/Sydney")
    private val now = Instant.parse("2026-11-24T02:00:00Z")

    private fun draft(title: String, day: String, from: String, to: String, zone: ZoneId = hcm) =
        EventDraft(title, false, LocalDate.parse(day), LocalTime.parse(from), LocalDate.parse(day), LocalTime.parse(to), zone)

    @Test
    fun aNewEventIsWrittenInItsZoneWithItsBlockAndAlarms() {
        val text = Edits.create(draft("Dentist", "2026-11-26", "09:00", "10:00", sydney).copy(reminders = listOf(EventReminder(10)), location = "Room 3", notes = "Line one\nLine two"), "uid-1", now)
        assertTrue(text.contains("DTSTART;TZID=Australia/Sydney:20261126T090000\r\n"))
        assertTrue(text.contains("DTEND;TZID=Australia/Sydney:20261126T100000\r\n"))
        assertTrue(text.contains("BEGIN:VTIMEZONE\r\nTZID:Australia/Sydney"))
        assertTrue(text.indexOf("BEGIN:VTIMEZONE") < text.indexOf("BEGIN:VEVENT"))
        assertTrue(text.contains("TRIGGER:-PT10M"))
        assertTrue(text.contains("DESCRIPTION:Line one\\nLine two"))
        assertTrue(text.contains("PRODID:-//xrncal//xrncal Android//EN"))
        val o = expand(text, "2026-11-26", "2026-11-26", sydney).single()
        assertEquals(Instant.parse("2026-11-25T22:00:00Z"), o.start)
        assertEquals("Room 3", o.location)
    }

    @Test
    fun anAllDayEventEndsTheDayAfterItsLast() {
        val trip = EventDraft("Trip", true, LocalDate.parse("2026-11-28"), LocalTime.MIDNIGHT, LocalDate.parse("2026-11-30"), LocalTime.MIDNIGHT, hcm)
        val text = Edits.create(trip, "t", now)
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261128\r\n"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261201\r\n"))
        assertTrue(!text.contains("VTIMEZONE"))
    }

    /** Written by another program: guests, categories, an X- property, an alarm with a sound. */
    private val foreign = ics(
        vevent(
            "UID:standup-1", "SUMMARY:Standup", "DTSTART;TZID=Australia/Sydney:20261005T090000", "DTEND;TZID=Australia/Sydney:20261005T091500",
            "RRULE:FREQ=WEEKLY;BYDAY=MO", "EXDATE;TZID=Australia/Sydney:20261019T090000",
            "ORGANIZER;CN=Alex:mailto:alex@example.com", "ATTENDEE;PARTSTAT=ACCEPTED:mailto:sam@example.com", "CATEGORIES:Work", "X-OTHER-APP:42",
            "BEGIN:VALARM\r\nACTION:AUDIO\r\nTRIGGER:-PT5M\r\nEND:VALARM",
        ),
        vevent("UID:standup-1", "RECURRENCE-ID;TZID=Australia/Sydney:20261012T090000", "SUMMARY:Standup (late)", "DTSTART;TZID=Australia/Sydney:20261012T110000", "DTEND;TZID=Australia/Sydney:20261012T111500"),
    )

    private fun occ(text: String, day: String, title: String = "Standup") = expand(text, day, day, sydney).single { it.title == title }

    @Test
    fun oneDateChangedBecomesAnOverrideAndTheRestStays() {
        val o = occ(foreign, "2026-10-26")
        val d = Edits.draftOf(ParsedResource.parse(foreign, sydney), o, sydney).copy(title = "Standup (room 2)")
        val text = Edits.update(foreign, o, d, Scope.THIS, now)
        val cal = ICalParser.parse(text)
        assertEquals(3, cal.children("VEVENT").size)
        assertTrue(text.contains("RECURRENCE-ID;TZID=Australia/Sydney:20261026T090000"))
        for (kept in listOf("ORGANIZER;CN=Alex:mailto:alex@example.com", "CATEGORIES:Work", "X-OTHER-APP:42", "ACTION:AUDIO")) assertTrue(kept, text.contains(kept))
        val month = expand(text, "2026-10-01", "2026-10-31", sydney).map { it.firstDay.dayOfMonth to it.title }
        assertEquals(listOf(5 to "Standup", 12 to "Standup (late)", 26 to "Standup (room 2)"), month)
    }

    @Test
    fun oneDateDeletedIsAnExdateInTheSeriesForm() {
        val text = Edits.delete(foreign, occ(foreign, "2026-10-26"), Scope.THIS, now)!!
        assertTrue(text.contains("EXDATE;TZID=Australia/Sydney:20261026T090000"))
        assertEquals(listOf(5, 12), expand(text, "2026-10-01", "2026-10-31", sydney).map { it.firstDay.dayOfMonth })
        // Deleting an overridden date takes its override with it.
        val again = Edits.delete(text, occ(text, "2026-10-12", "Standup (late)"), Scope.THIS, now)!!
        assertEquals(1, ICalParser.parse(again).children("VEVENT").size)
        assertEquals(listOf(5), expand(again, "2026-10-01", "2026-10-31", sydney).map { it.firstDay.dayOfMonth })
    }

    @Test
    fun deletingTheWholeSeriesLeavesNothing() {
        assertNull(Edits.delete(foreign, occ(foreign, "2026-10-26"), Scope.ALL, now))
    }

    @Test
    fun aSeriesMovedToAnotherTimeTakesItsExdatesAndOverridesAlong() {
        val o = occ(foreign, "2026-10-26")
        val series = Edits.seriesDraft(ParsedResource.parse(foreign, sydney), o, sydney)
        assertEquals(LocalDate.parse("2026-10-26") to LocalTime.parse("09:00"), series.startDate to series.startTime)
        val text = Edits.update(foreign, o, series.copy(startTime = LocalTime.parse("10:00"), endTime = LocalTime.parse("10:15")), Scope.ALL, now)
        assertTrue(text.contains("DTSTART;TZID=Australia/Sydney:20261005T100000"))
        assertTrue(text.contains("EXDATE;TZID=Australia/Sydney:20261019T100000"))
        assertTrue(text.contains("RECURRENCE-ID;TZID=Australia/Sydney:20261012T100000"))
        val month = expand(text, "2026-10-01", "2026-10-31", sydney).map { it.firstDay.dayOfMonth to it.start.atZone(sydney).toLocalTime().toString() }
        assertEquals(listOf(5 to "10:00", 12 to "11:00", 26 to "10:00"), month)
        assertTrue(text.contains("X-OTHER-APP:42"))
        assertTrue(text.contains("SEQUENCE:1"))
    }

    @Test
    fun aSeriesOpenedOnALaterDateKeepsItsFirstDate() {
        val o = occ(foreign, "2026-10-26")
        val series = Edits.seriesDraft(ParsedResource.parse(foreign, sydney), o, sydney).copy(title = "Standup (renamed)")
        val text = Edits.update(foreign, o, series, Scope.ALL, now)
        assertTrue(text.contains("DTSTART;TZID=Australia/Sydney:20261005T090000"))
        assertTrue(text.contains("SUMMARY:Standup (renamed)"))
    }

    @Test
    fun theRepeatComesOffAndTakesItsExdatesWithIt() {
        val o = occ(foreign, "2026-10-05")
        val single = Edits.seriesDraft(ParsedResource.parse(foreign, sydney), o, sydney).copy(rrule = null)
        val text = Edits.update(foreign, o, single, Scope.ALL, now)
        val ev = ICalParser.parse(text).children("VEVENT").single()
        assertTrue(!ev.has("RRULE") && !ev.has("EXDATE"))
        assertEquals(listOf(5), expand(text, "2026-10-01", "2026-10-31", sydney).map { it.firstDay.dayOfMonth })
    }

    @Test
    fun removingEveryReminderLeavesNoAlarm() {
        val o = occ(foreign, "2026-10-05")
        val d = Edits.seriesDraft(ParsedResource.parse(foreign, sydney), o, sydney)
        assertEquals(listOf(EventReminder(5)), d.reminders)
        // No VALARM never rings, on this app and the laptop: nothing to mark.
        val text = Edits.update(foreign, o, d.copy(reminders = emptyList()), Scope.ALL, now)
        assertTrue(text, !text.contains("BEGIN:VALARM"))
        assertNull(expand(text, "2026-10-05", "2026-10-05", sydney).single().reminders)
        val back = Edits.update(text, o, d.copy(reminders = listOf(EventReminder(30))), Scope.ALL, now)
        assertEquals(listOf(EventReminder(30)), expand(back, "2026-10-05", "2026-10-05", sydney).single().reminders)
    }

    @Test
    fun aTimedEventMadeAllDayKeepsItsDay() {
        val single = ics(vevent("UID:x", "SUMMARY:Call", "DTSTART;TZID=Asia/Ho_Chi_Minh:20261126T090000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261126T100000"))
        val o = expand(single, "2026-11-26", "2026-11-26", hcm).single()
        val d = Edits.draftOf(ParsedResource.parse(single, hcm), o, hcm).copy(allDay = true)
        val text = Edits.update(single, o, d, Scope.ALL, now)
        assertTrue(text.contains("DTSTART;VALUE=DATE:20261126"))
        assertTrue(text.contains("DTEND;VALUE=DATE:20261127"))
    }

    @Test
    fun anEndBeforeTheStartIsRefused() {
        assertEquals("End before start", draft("x", "2026-11-26", "10:00", "09:00").problem())
        assertNull(draft("x", "2026-11-26", "09:00", "09:00").problem())
    }
}
