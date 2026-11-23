package com.xern.xrncal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The laptop app's reminders.test.ts. */
class RemindersTest {
    private val MIN = 60_000L
    private val defaults = Reminders.Defaults(timed = 10, allDay = null)

    @Test
    fun normalizesWholeMinutesNoDuplicatesNoNegativesSoonestFirst() {
        val list = Reminders.normalizeMinutes(listOf(30.0, 10.4, 10.0, -5.0, 99999.0)) + EventReminder(30, email = true)
        assertEquals(
            listOf(EventReminder(10), EventReminder(30), EventReminder(30, email = true), EventReminder(40320)),
            Reminders.normalize(list),
        )
    }

    @Test
    fun resolvesWhatRings() {
        assertEquals(emptyList<EventReminder>(), Reminders.effective(emptyList(), false, listOf(EventReminder(30)), defaults))
        assertEquals(listOf(EventReminder(30)), Reminders.effective(null, false, listOf(EventReminder(30)), defaults))
        assertEquals(listOf(EventReminder(10)), Reminders.effective(null, false, null, defaults))
        assertEquals(emptyList<EventReminder>(), Reminders.effective(null, true, listOf(EventReminder(30)), defaults))
        assertEquals(listOf(EventReminder(900)), Reminders.effective(null, true, null, Reminders.Defaults(null, 900)))
    }

    @Test
    fun countsAllDayRemindersBackFromLocalMidnight() {
        val hcm = ZoneId.of("Asia/Ho_Chi_Minh")
        val date = LocalDate.of(2026, 9, 24)
        val anchor = Reminders.anchor(date.atStartOfDay(ZoneOffset.UTC).toInstant(), true, date, hcm)
        assertEquals(Instant.parse("2026-09-23T17:00:00Z"), anchor)
        assertEquals(Instant.parse("2026-09-23T02:00:00Z"), anchor.minusSeconds(900 * 60L))
    }

    @Test
    fun ringsFromItsTimeUntilTheEventStarts() {
        val start = Instant.parse("2026-04-01T09:00:00Z").toEpochMilli()
        val list = listOf(EventReminder(10))
        assertNull(Reminders.due(start, list, start - 11 * MIN, 2 * MIN).ring)
        assertEquals(10, Reminders.due(start, list, start - 10 * MIN, 2 * MIN).ring?.minutesBefore)
        assertEquals(10, Reminders.due(start, list, start - 5 * MIN, 2 * MIN).ring?.minutesBefore)
        assertNull(Reminders.due(start, list, start + MIN, 2 * MIN).ring)
    }

    @Test
    fun givesAnAtStartReminderAShortGrace() {
        val start = Instant.parse("2026-04-01T09:00:00Z").toEpochMilli()
        assertTrue(Reminders.due(start, listOf(EventReminder(0)), start + MIN, 2 * MIN).ring != null)
        assertNull(Reminders.due(start, listOf(EventReminder(0)), start + 3 * MIN, 2 * MIN).ring)
    }

    @Test
    fun ringsOnlyTheLatestOverdueAndNeverAnEmailOne() {
        val start = Instant.parse("2026-04-01T09:00:00Z").toEpochMilli()
        val r = Reminders.due(start, listOf(EventReminder(1440), EventReminder(60), EventReminder(30, email = true)), start - 20 * MIN, 2 * MIN)
        assertEquals(60, r.ring?.minutesBefore)
        assertEquals(listOf(60, 1440), r.handled.map { it.minutesBefore }.sorted())
    }

    @Test
    fun describesHowFarOffAnOccurrenceIs() {
        val utc = ZoneOffset.UTC
        val now = Instant.parse("2026-04-01T08:00:00Z")
        fun w(s: String, allDay: Boolean = false) = Reminders.describeWhen(Instant.parse(s), allDay, LocalDate.parse(s.take(10)), now, utc)
        assertEquals(Reminders.When.Now, w("2026-04-01T07:59:00Z"))
        assertEquals(Reminders.When.InMinutes(10), w("2026-04-01T08:10:00Z"))
        assertTrue(w("2026-04-01T15:00:00Z") is Reminders.When.TodayAt)
        assertTrue(w("2026-04-02T09:00:00Z") is Reminders.When.TomorrowAt)
        assertTrue(w("2026-04-05T09:00:00Z") is Reminders.When.OnDateAt)
        assertEquals(Reminders.When.Today, w("2026-04-01T00:00:00Z", true))
        assertEquals(Reminders.When.Tomorrow, w("2026-04-02T00:00:00Z", true))
    }

    @Test
    fun describesOffsetsInTheLargestWholeUnit() {
        assertEquals(Reminders.Offset.AtStart, Reminders.describeOffset(0, false))
        assertEquals(Reminders.Offset.Minutes(45), Reminders.describeOffset(45, false))
        assertEquals(Reminders.Offset.Hours(2), Reminders.describeOffset(120, false))
        assertEquals(Reminders.Offset.Days(2), Reminders.describeOffset(2880, false))
        assertEquals(Reminders.Offset.Weeks(1), Reminders.describeOffset(10080, false))
        assertEquals(Reminders.Offset.DaysBeforeAt(1, "09:00"), Reminders.describeOffset(900, true))
        assertEquals(Reminders.Offset.DaysBeforeAt(1, "18:00"), Reminders.describeOffset(360, true))
        assertEquals(Reminders.Offset.DaysBeforeAt(7, "09:00"), Reminders.describeOffset(9540, true))
        assertEquals(Reminders.Offset.OnDayAt("00:00"), Reminders.describeOffset(0, true))
    }

    @Test
    fun calDavHoldsTheDisplayReminders() {
        val local = listOf(EventReminder(10), EventReminder(60), EventReminder(30, email = true))
        assertEquals(listOf(EventReminder(10), EventReminder(60)), Reminders.forCalDav(local))
    }
}
