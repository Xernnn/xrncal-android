package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.ICalParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** .ics files opened from other apps: one event to the editor, several to an import (docs/spec.md, "Other apps"). */
class IcsFilesTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val hcm = ZoneId.of("Asia/Ho_Chi_Minh")
    private val defaults = Reminders.Defaults(timed = 15, allDay = 720)

    private fun ics(vararg body: String) = (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Example//Made-up Exporter//EN", "METHOD:PUBLISH") + body + "END:VCALENDAR").joinToString("\r\n") + "\r\n"

    private val hcmBlock = arrayOf(
        "BEGIN:VTIMEZONE", "TZID:Asia/Ho_Chi_Minh", "BEGIN:STANDARD", "DTSTART:19750613T000000", "TZOFFSETFROM:+0800", "TZOFFSETTO:+0700", "TZNAME:+07", "END:STANDARD", "END:VTIMEZONE",
    )

    @Test
    fun aFileWithOneEventOpensTheEditorWithItsFields() {
        val text = ics(
            *hcmBlock,
            "BEGIN:VEVENT", "UID:board-games-1@example.com", "DTSTAMP:20261120T000000Z",
            "SUMMARY:Board games night", "LOCATION:Made-up Café\\, Level 2", "DESCRIPTION:Bring snacks",
            "DTSTART;TZID=Asia/Ho_Chi_Minh:20261205T190000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261205T210000",
            "RRULE:FREQ=MONTHLY;BYDAY=1SA",
            "ORGANIZER:mailto:host@example.com", "ATTENDEE;CN=Sam:mailto:sam@example.com", "ATTENDEE:mailto:kim@example.org",
            "BEGIN:VALARM", "ACTION:DISPLAY", "TRIGGER:-PT30M", "END:VALARM",
            "END:VEVENT",
        )
        val d = (IcsFiles.read(text, sydney, defaults) as IcsFiles.Read.One).draft
        assertEquals("Board games night", d.title)
        assertEquals("Made-up Café, Level 2", d.location)
        assertEquals(hcm, d.zone)
        assertEquals(LocalDate.of(2026, 12, 5), d.startDate)
        assertEquals(LocalTime.of(19, 0), d.startTime)
        assertEquals(LocalTime.of(21, 0), d.endTime)
        assertEquals("FREQ=MONTHLY;BYDAY=1SA", d.rrule)
        assertEquals(listOf(EventReminder(30)), d.reminders)
        assertEquals("Bring snacks\n\nGuests: sam@example.com, kim@example.org", d.notes)
    }

    @Test
    fun aUtcOrFloatingTimeOpensInThePhonesZoneAndAnEventWithoutAlarmsTakesTheDefaults() {
        val utc = (IcsFiles.read(ics("BEGIN:VEVENT", "UID:u1", "SUMMARY:Webinar", "DTSTART:20261201T230000Z", "DURATION:PT90M", "END:VEVENT"), sydney, defaults) as IcsFiles.Read.One).draft
        assertEquals(sydney, utc.zone)
        assertEquals(LocalDate.of(2026, 12, 2), utc.startDate)
        assertEquals(LocalTime.of(10, 0), utc.startTime)
        assertEquals(LocalTime.of(11, 30), utc.endTime)
        assertEquals(listOf(EventReminder(15)), utc.reminders)
        val day = (IcsFiles.read(ics("BEGIN:VEVENT", "UID:u2", "SUMMARY:Holiday", "DTSTART;VALUE=DATE:20261228", "DTEND;VALUE=DATE:20261231", "END:VEVENT"), sydney, defaults) as IcsFiles.Read.One).draft
        assertTrue(day.allDay)
        assertEquals(LocalDate.of(2026, 12, 28), day.startDate)
        assertEquals(LocalDate.of(2026, 12, 30), day.endDate)
        assertEquals(listOf(EventReminder(720)), day.reminders)
    }

    /** Three events (one a series with a changed date), one of which the calendar holds already. */
    private val three = ics(
        *hcmBlock,
        "BEGIN:VEVENT", "UID:standup@example.com", "SUMMARY:Standup", "DTSTART;TZID=Asia/Ho_Chi_Minh:20261207T093000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261207T094500", "RRULE:FREQ=WEEKLY;COUNT=4", "END:VEVENT",
        "BEGIN:VEVENT", "UID:standup@example.com", "RECURRENCE-ID;TZID=Asia/Ho_Chi_Minh:20261214T093000", "SUMMARY:Standup (moved)", "DTSTART;TZID=Asia/Ho_Chi_Minh:20261214T110000", "DTEND;TZID=Asia/Ho_Chi_Minh:20261214T111500", "END:VEVENT",
        "BEGIN:VEVENT", "UID:fair@example.com", "SUMMARY:Book fair", "DTSTART;VALUE=DATE:20261219", "DTEND;VALUE=DATE:20261221", "END:VEVENT",
        "BEGIN:VEVENT", "UID:review@example.com", "SUMMARY:Year review", "DTSTART:20261203T020000Z", "DTEND:20261203T030000Z", "END:VEVENT",
        "BEGIN:VTODO", "UID:todo@example.com", "SUMMARY:Not an event", "END:VTODO",
    )

    @Test
    fun aFileWithSeveralEventsIsAnImportOfEachEventWithItsDates() {
        val many = IcsFiles.read(three, sydney, defaults) as IcsFiles.Read.Many
        assertEquals(listOf("Standup", "Book fair", "Year review"), many.events.map { it.title })
        assertEquals(LocalDate.of(2026, 12, 3), many.first)
        assertEquals(LocalDate.of(2026, 12, 20), many.last)
        val standup = many.events[0]
        // The series and its changed date stay together, with the zone they name and no METHOD.
        assertEquals(2, standup.ics.split("BEGIN:VEVENT").size - 1)
        assertTrue(standup.ics.contains("BEGIN:VTIMEZONE\r\nTZID:Asia/Ho_Chi_Minh"))
        assertFalse(standup.ics.contains("METHOD"))
        assertFalse(many.events[1].ics.contains("VTIMEZONE"))
        assertFalse(many.events.any { it.ics.contains("VTODO") })
        assertEquals("Standup", ParsedResource.parse(standup.ics, sydney).master("standup@example.com")!!.title)
    }

    @Test
    fun anImportSkipsWhatTheCalendarHoldsAndGivesANewUidOnlyWhereAnotherCalendarHasIt() {
        val events = (IcsFiles.read(three, sydney, defaults) as IcsFiles.Read.Many).events
        val plan = IcsFiles.plan(events, "home", here = setOf("fair@example.com", "other@example.com"), elsewhere = setOf("review@example.com"))
        assertEquals(1, plan.alreadyThere)
        val copy = IcsFiles.copyUid("review@example.com", "home")
        assertEquals(listOf("standup@example.com", copy), plan.add.map { it.uid })
        assertFalse(copy == IcsFiles.copyUid("review@example.com", "work"))
        val review = ParsedResource.parse(plan.add[1].ics, sydney)
        assertEquals(listOf(copy), review.uids)
        assertEquals("Year review", review.events.single().title)
        // The series keeps its UID on both of its VEVENTs.
        assertEquals(listOf("standup@example.com"), ParsedResource.parse(plan.add[0].ics, sydney).uids)
        // Importing the same file again into that calendar adds nothing.
        val again = IcsFiles.plan(events, "home", here = setOf("standup@example.com", "fair@example.com", copy), elsewhere = setOf("review@example.com"))
        assertEquals(0, again.add.size)
        assertEquals(3, again.alreadyThere)
    }

    @Test
    fun aFileThatIsNotACalendarOrHasNoEventsSaysSoInOneLine() {
        assertEquals(IcsFiles.Read.Unreadable(IcsFiles.NOT_CALENDAR), IcsFiles.read("%PDF-1.7 not a calendar", sydney, defaults))
        assertEquals(IcsFiles.Read.Unreadable(IcsFiles.NOT_CALENDAR), IcsFiles.read("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n", sydney, defaults))
        assertEquals(IcsFiles.Read.Unreadable(IcsFiles.NOT_CALENDAR), IcsFiles.read("BEGIN:VCARD\r\nFN:Sam\r\nEND:VCARD\r\n", sydney, defaults))
        assertEquals(IcsFiles.Read.Unreadable(IcsFiles.NO_EVENTS), IcsFiles.read(ics("BEGIN:VTODO", "UID:t", "SUMMARY:Task", "END:VTODO"), sydney, defaults))
    }

    @Test
    fun eventsWithoutAUidEachGetTheirOwn() {
        val text = ics(
            "BEGIN:VEVENT", "SUMMARY:One", "DTSTART;VALUE=DATE:20261201", "END:VEVENT",
            "BEGIN:VEVENT", "SUMMARY:Two", "DTSTART;VALUE=DATE:20261202", "END:VEVENT",
        )
        val events = (IcsFiles.read(text, sydney, defaults) as IcsFiles.Read.Many).events
        var n = 0
        val plan = IcsFiles.plan(events, "home", emptySet(), emptySet(), fresh = { "made-${++n}" })
        assertEquals(listOf("made-1", "made-2"), plan.add.map { it.uid })
        assertEquals(listOf("made-2"), ParsedResource.parse(plan.add[1].ics, sydney).uids)
        assertTrue(ICalParser.parse(plan.add[0].ics).children("VEVENT").single().prop("UID")!!.value == "made-1")
    }
}
