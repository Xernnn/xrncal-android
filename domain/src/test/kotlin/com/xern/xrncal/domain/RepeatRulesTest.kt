package com.xern.xrncal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The editor's repeat choices, as RRULEs and back, and in words. */
class RepeatRulesTest {
    private val tue = LocalDate.parse("2026-11-24")
    private val hcm = ZoneId.of("Asia/Ho_Chi_Minh")

    @Test
    fun presetsAreTheRulesOtherCalendarsWrite() {
        fun rule(p: RepeatRules.Preset) = RepeatRules.rule(RepeatRules.ofPreset(p, tue)!!, tue, false, hcm)
        assertEquals("FREQ=DAILY", rule(RepeatRules.Preset.DAILY))
        assertEquals("FREQ=WEEKLY", rule(RepeatRules.Preset.WEEKLY))
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", rule(RepeatRules.Preset.WEEKDAYS))
        assertEquals("FREQ=MONTHLY", rule(RepeatRules.Preset.MONTHLY))
        assertEquals("FREQ=YEARLY", rule(RepeatRules.Preset.YEARLY))
        for (p in listOf(RepeatRules.Preset.DAILY, RepeatRules.Preset.WEEKLY, RepeatRules.Preset.WEEKDAYS, RepeatRules.Preset.MONTHLY, RepeatRules.Preset.YEARLY)) {
            assertEquals(p, RepeatRules.preset(rule(p), tue))
        }
        assertEquals(RepeatRules.Preset.NONE, RepeatRules.preset(null, tue))
        assertEquals(RepeatRules.Preset.WEEKLY, RepeatRules.preset("FREQ=WEEKLY;BYDAY=TU", tue))
    }

    @Test
    fun customRulesRoundTrip() {
        val r = Repeat(Frequency.WEEKLY, 2, setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), end = RepeatEnd(count = 10))
        val text = RepeatRules.rule(r, tue, false, hcm)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=10", text)
        assertEquals(r, RepeatRules.parse(text, tue))
        assertEquals(RepeatRules.Preset.CUSTOM, RepeatRules.preset(text, tue))
        val monthly = Repeat(Frequency.MONTHLY, monthlyByWeekday = true)
        assertEquals("FREQ=MONTHLY;BYDAY=4TU", RepeatRules.rule(monthly, tue, false, hcm))
        assertEquals(monthly, RepeatRules.parse("FREQ=MONTHLY;BYDAY=4TU", tue))
        // The fifth Tuesday is written as the last.
        assertEquals("FREQ=MONTHLY;BYDAY=-1TU", RepeatRules.rule(monthly, LocalDate.parse("2026-12-29"), false, hcm))
    }

    @Test
    fun anEndDateIsThatDaysEndInTheEventsZone() {
        val r = Repeat(Frequency.DAILY, end = RepeatEnd(until = LocalDate.parse("2026-12-31")))
        assertEquals("FREQ=DAILY;UNTIL=20261231T165959Z", RepeatRules.rule(r, tue, false, hcm))
        assertEquals("FREQ=DAILY;UNTIL=20261231", RepeatRules.rule(r, tue, true, hcm))
        assertEquals("FREQ=DAILY;UNTIL=20261231T165959Z", RepeatRules.fitUntil("FREQ=DAILY;UNTIL=20261231", false, hcm))
        assertEquals("FREQ=DAILY;UNTIL=20261231", RepeatRules.fitUntil("FREQ=DAILY;UNTIL=20261231T165959Z", true, hcm))
    }

    @Test
    fun aRuleThatSaysMoreIsCustomAndKept() {
        assertNull(RepeatRules.parse("FREQ=MONTHLY;BYDAY=MO,TU;BYSETPOS=1", tue))
        assertNull(RepeatRules.parse("FREQ=HOURLY", tue))
        assertEquals(RepeatRules.Preset.CUSTOM, RepeatRules.preset("FREQ=YEARLY;BYWEEKNO=1", tue))
        assertEquals("Custom", RepeatRules.describe("FREQ=YEARLY;BYWEEKNO=1", tue))
    }

    @Test
    fun describesARuleInAFewWords() {
        assertEquals("Every day", RepeatRules.describe("FREQ=DAILY", tue))
        assertEquals("Every week on Tue", RepeatRules.describe("FREQ=WEEKLY", tue))
        assertEquals("Every 2 weeks on Mon, Thu, 10 times", RepeatRules.describe("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH;COUNT=10", tue))
        assertEquals("Every week on weekdays", RepeatRules.describe("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", tue))
        assertEquals("Every month on day 24", RepeatRules.describe("FREQ=MONTHLY", tue))
        assertEquals("Every month on the fourth Tuesday", RepeatRules.describe("FREQ=MONTHLY;BYDAY=4TU", tue))
        assertEquals("Every year on 24 Nov, until 31 Dec 2030", RepeatRules.describe("FREQ=YEARLY;UNTIL=20301231T165959Z", tue))
    }
}
