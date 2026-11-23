package com.xern.xrncal.domain

import com.xern.xrncal.domain.ical.Component
import com.xern.xrncal.domain.ical.ICalParser
import com.xern.xrncal.domain.ical.ICalWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** VALARMs read and written as the laptop app's parse-ics and write-ics do. */
class AlarmsTest {
    private fun vevent(vararg alarms: String): Component {
        val text = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nDTSTART:20260401T090000Z\r\n" + alarms.joinToString("") + "END:VEVENT\r\nEND:VCALENDAR\r\n"
        return ICalParser.parse(text).children("VEVENT").single()
    }

    @Test
    fun readsAlarmsCountedBackFromTheStart() {
        val ev = vevent(
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=START:-P1DT15H\r\nEND:VALARM\r\n",
            "BEGIN:VALARM\r\nACTION:EMAIL\r\nTRIGGER:-PT30M\r\nEND:VALARM\r\n",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:-PT5M\r\nEND:VALARM\r\n",
        )
        assertEquals(listOf(EventReminder(10), EventReminder(30, true), EventReminder(2340)), Alarms.read(ev))
    }

    @Test
    fun toldNoAlarmApartFromAlarmsThatNeverRing() {
        assertNull(Alarms.read(vevent()))
        // Apple's "none" marker, which the laptop app also reads as no reminders.
        val none = vevent("BEGIN:VALARM\r\nACTION:NONE\r\nTRIGGER;VALUE=DATE-TIME:19760401T005545Z\r\nEND:VALARM\r\n")
        assertEquals(emptyList<EventReminder>(), Alarms.read(none))
    }

    @Test
    fun writesTheListKeepingAlarmsItStillWantsAsTheyWere() {
        val ev = vevent(
            "BEGIN:VALARM\r\nACTION:AUDIO\r\nATTACH;FMTTYPE=audio/basic:http://example.com/bell.au\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT60M\r\nEND:VALARM\r\n",
        )
        Alarms.write(ev, listOf(EventReminder(10), EventReminder(1440)), "Dentist")
        val text = ICalWriter.write(ev)
        assertTrue(text, text.contains("ATTACH;FMTTYPE=audio/basic:http://example.com/bell.au"))
        assertEquals(listOf(EventReminder(10), EventReminder(1440)), Alarms.read(ev))
        assertTrue(text.contains("TRIGGER:-P1D\r\n"))
    }

    @Test
    fun writesNoneAsNoAlarmAndDropsTheOldNoneMarker() {
        val ev = vevent("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT10M\r\nEND:VALARM\r\n")
        Alarms.write(ev, emptyList(), "x")
        assertEquals(0, ev.children("VALARM").size)
        assertNull(Alarms.read(ev))
        Alarms.write(ev, listOf(EventReminder(5)), "x")
        assertEquals(listOf(EventReminder(5)), Alarms.read(ev))
        assertEquals(1, ev.children("VALARM").size)
        // Phase 1's marker, read as none, goes when the event is saved with no reminders...
        val marked = vevent("BEGIN:VALARM\r\nACTION:NONE\r\nTRIGGER;VALUE=DATE-TIME:19760401T005545Z\r\nEND:VALARM\r\n")
        Alarms.write(marked, emptyList(), "x")
        assertEquals(0, marked.children("VALARM").size)
        // ...and when it gets one, while an alarm this app can't read stays.
        val mixed = vevent(
            "BEGIN:VALARM\r\nACTION:NONE\r\nTRIGGER;VALUE=DATE-TIME:19760401T005545Z\r\nEND:VALARM\r\n",
            "BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER;RELATED=END:-PT5M\r\nEND:VALARM\r\n",
        )
        Alarms.write(mixed, listOf(EventReminder(15)), "x")
        val text = ICalWriter.write(mixed)
        assertTrue(text, !text.contains("ACTION:NONE") && text.contains("RELATED=END") && text.contains("TRIGGER:-PT15M"))
        Alarms.write(ev, null, "x")
        assertNull(Alarms.read(ev))
    }

    @Test
    fun writesTriggersAsDurations() {
        assertEquals("PT0S", IcalDuration.before(0))
        assertEquals("-PT10M", IcalDuration.before(10))
        assertEquals("-PT1H", IcalDuration.before(60))
        assertEquals("-P1DT15H", IcalDuration.before(2340))
        assertEquals("-P7D", IcalDuration.before(10080))
        assertEquals(-2340L, IcalDuration.parse("-P1DT15H")!!.totalMinutes)
        assertEquals(-10080L, IcalDuration.parse("-P1W")!!.totalMinutes)
        assertNull(IcalDuration.parse("P"))
    }
}
