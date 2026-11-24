package com.xern.xrncal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Open the calendar at this time" from a clock widget or another app (docs/spec.md, "Other apps"). */
class TimeLinkTest {
    private val sydney = ZoneId.of("Australia/Sydney")
    private val hcm = ZoneId.of("Asia/Ho_Chi_Minh")

    private fun ms(text: String) = Instant.parse(text).toEpochMilli()

    @Test
    fun aTimeLinkOpensTheDayItFallsOnHere() {
        val link = "content://com.android.calendar/time/" + ms("2026-12-03T22:30:00Z")
        assertEquals(LocalDate.of(2026, 12, 4), TimeLink.day(link, sydney))
        assertEquals(LocalDate.of(2026, 12, 4), TimeLink.day(link, hcm))
        assertEquals(LocalDate.of(2026, 12, 3), TimeLink.day(link, ZoneId.of("UTC")))
        assertEquals(LocalDate.of(2026, 12, 4), TimeLink.day("$link?source=clock", sydney))
        assertNull(TimeLink.day("content://com.android.calendar/events/12", sydney))
        assertNull(TimeLink.day("https://example.com/time/1796083200000", sydney))
        assertNull(TimeLink.day("content://com.android.calendar/time/", sydney))
    }
}
