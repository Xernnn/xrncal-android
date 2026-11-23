package com.xern.xrncal.domain.ical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ICalTest {
    /** Written by another program: attendees, categories, X- properties, folded lines, an alarm. */
    private val foreign = listOf(
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        "PRODID:-//Example Corp//Another Calendar 9.0//EN",
        "X-WR-CALNAME:Family",
        "BEGIN:VEVENT",
        "UID:urn:uuid:5b1e7c3a-0000-4000-8000-000000000001",
        "DTSTAMP:20261101T080000Z",
        "DTSTART;TZID=\"Europe/Berlin\":20261110T090000",
        "DTEND;TZID=\"Europe/Berlin\":20261110T100000",
        "SUMMARY:Planning\\, with notes\\; and a backslash \\\\",
        "DESCRIPTION:First line\\nSecond line which is long enough that the writer has to fold it ov",
        " er several lines of seventy-five octets",
        "ORGANIZER;CN=\"Alex Example\":mailto:alex@example.com",
        "ATTENDEE;CN=Sam;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT:mailto:sam@example.com",
        "CATEGORIES:Work,Planning",
        "X-MOZ-GENERATION:3",
        "X-SOMETHING;X-PARAM=\"a:b;c\":value:with:colons",
        "BEGIN:VALARM",
        "ACTION:AUDIO",
        "TRIGGER:-PT15M",
        "END:VALARM",
        "END:VEVENT",
        "END:VCALENDAR",
        "",
    ).joinToString("\r\n")

    @Test
    fun keepsEverythingItDoesNotModelOnARoundTrip() {
        val cal = ICalParser.parse(foreign)
        val again = ICalParser.parse(ICalWriter.write(cal))
        assertEquals(ICalWriter.write(cal), ICalWriter.write(again))
        val ev = again.children("VEVENT").single()
        assertEquals("Planning, with notes; and a backslash \\", ev.text("SUMMARY"))
        assertTrue(ev.text("DESCRIPTION")!!.startsWith("First line\nSecond line"))
        assertTrue(ev.text("DESCRIPTION")!!.endsWith("over several lines of seventy-five octets"))
        assertEquals("CN=Sam;PARTSTAT=ACCEPTED;ROLE=REQ-PARTICIPANT", ev.prop("ATTENDEE")!!.params.joinToString(";"))
        assertEquals("\"a:b;c\"", ev.prop("X-SOMETHING")!!.param("X-PARAM"))
        assertEquals("value:with:colons", ev.prop("X-SOMETHING")!!.value)
        assertEquals("Europe/Berlin", ICalParser.unquote(ev.prop("DTSTART")!!.param("TZID")!!))
        assertEquals("ACTION:AUDIO", ev.children("VALARM").single().properties.first().toString())
    }

    @Test
    fun foldsAt75OctetsWithoutSplittingACharacter() {
        val title = "Họp gia đình ở Đà Nẵng ".repeat(8)
        val p = Property("SUMMARY", value = ICalText.escape(title))
        val folded = ICalWriter.fold(ICalWriter.line(p))
        for (line in folded.split("\r\n")) assertTrue(line.toByteArray(Charsets.UTF_8).size <= 75)
        val back = ICalParser.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\n$folded\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n")
        assertEquals(title, back.children("VEVENT").single().text("SUMMARY"))
    }

    @Test
    fun aLineBreakInAValueNeverEndsTheContentLine() {
        val ev = Component("VEVENT")
        ev.setText("SUMMARY", "Lunch\r\nATTENDEE:mailto:x@example.com")
        val text = ICalWriter.write(Component("VCALENDAR", components = mutableListOf(ev)))
        val back = ICalParser.parse(text).children("VEVENT").single()
        assertEquals(null, back.prop("ATTENDEE"))
        assertEquals("Lunch\nATTENDEE:mailto:x@example.com", back.text("SUMMARY"))
    }

    @Test
    fun readsLfOnlyFilesAndAByteOrderMark() {
        val cal = ICalParser.parse("﻿BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:x\nSUMMARY:A\n  b\nEND:VEVENT\nEND:VCALENDAR\n")
        assertEquals("A b", cal.children("VEVENT").single().text("SUMMARY"))
    }

    @Test
    fun refusesWhatIsNotACalendar() {
        assertThrows(ICalParseException::class.java) { ICalParser.parse("<html>captive portal</html>") }
        assertThrows(ICalParseException::class.java) { ICalParser.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:x\r\n") }
        assertThrows(ICalParseException::class.java) { ICalParser.parse("BEGIN:VCALENDAR\r\nEND:VEVENT\r\n") }
    }

    @Test
    fun readsDatesAndTimesInTheirForms() {
        assertEquals("20260305", ICalTime.parse("20260305")!!.value())
        assertTrue(ICalTime.parse("20260305")!!.isDate)
        assertEquals("20260305T090000Z", ICalTime.parse("20260305T090000Z")!!.value())
        val zoned = ICalTime.parse("20260305T0900", "Australia/Sydney")!!
        assertEquals("20260305T090000", zoned.value())
        assertEquals("TZID=Australia/Sydney", zoned.params().single().toString())
        assertEquals("20260306T000000", ICalTime.parse("20260305T240000")!!.value())
        assertEquals(null, ICalTime.parse("2026-03-05"))
        val list = ICalTime.list(ICalParser.contentLine("EXDATE;TZID=Australia/Sydney:20260312T090000,20260416T090000")!!)
        assertEquals(listOf("20260312T090000", "20260416T090000"), list.map { it.value() })
        assertEquals(listOf("Australia/Sydney", "Australia/Sydney"), list.map { it.tzid })
    }
}
