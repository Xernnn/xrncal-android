package com.xern.xrncal.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourcesTest {
    /** The laptop app's calendar-move.test.ts: a UID Radicale won't take in a path gets a hashed name. */
    @Test
    fun aUidTheServerWouldRefuseInAPathIsNamedByItsHash() {
        assertEquals("0b9d2c5a-1f4e-4c3a-9a1e-6b2f0d8e7c11", ResourceNames.of("0b9d2c5a-1f4e-4c3a-9a1e-6b2f0d8e7c11"))
        for (uid in listOf("urn:uuid:9f3c/77,a", "a'b", "a\"b", "star*", "what?", "x,y", ".hidden", "trailing ", "tab\tin", "x".repeat(181))) {
            val name = ResourceNames.of(uid)
            assertTrue(uid, name.matches(Regex("[0-9a-f]{40}")))
            assertEquals("the same name every time", name, ResourceNames.of(uid))
        }
        assertEquals("http://h/alex/work/abc%40example.com.ics", ResourceNames.href("http://h/alex/work/", "abc@example.com"))
    }

    @Test
    fun theIndexCoversASingleEventsSpanAndAnEndlessSeriesForever() {
        val one = ResourceIndex.of(ics(vevent("UID:a", "SUMMARY:Cà phê", "LOCATION:Đà Lạt", "DTSTART:20261126T020000Z", "DTEND:20261126T030000Z")))
        assertEquals(Instant.parse("2026-11-26T02:00:00Z").toEpochMilli(), one.rangeStart)
        assertEquals(Instant.parse("2026-11-26T03:00:00Z").toEpochMilli(), one.rangeEnd)
        assertEquals("ca phe da lat", one.search)
        assertEquals("a", one.uid)
        val endless = ResourceIndex.of(ics(vevent("UID:b", "DTSTART:20200101T020000Z", "DTEND:20200101T030000Z", "RRULE:FREQ=WEEKLY")))
        assertTrue(endless.recurring)
        assertEquals(ResourceIndex.OPEN_END, endless.rangeEnd)
        val until = ResourceIndex.of(ics(vevent("UID:c", "DTSTART:20200101T020000Z", "DTEND:20200101T030000Z", "RRULE:FREQ=WEEKLY;UNTIL=20201231T000000Z")))
        assertTrue(until.rangeEnd < Instant.parse("2021-01-03T00:00:00Z").toEpochMilli())
        // A floating or all-day date is a day somewhere: the span covers every zone.
        val day = ResourceIndex.of(ics(vevent("UID:d", "DTSTART;VALUE=DATE:20261126", "DTEND;VALUE=DATE:20261127")))
        assertTrue(day.rangeStart <= Instant.parse("2026-11-25T10:00:00Z").toEpochMilli())
        assertTrue(day.rangeEnd >= Instant.parse("2026-11-27T14:00:00Z").toEpochMilli())
    }
}
