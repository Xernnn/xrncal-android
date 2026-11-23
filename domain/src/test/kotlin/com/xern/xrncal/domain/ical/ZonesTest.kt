package com.xern.xrncal.domain.ical

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The laptop app's vtimezone.test.ts. Every other client turns this app's clock times back into
 * instants through the VTIMEZONE it writes, so the block has to agree with the zone database on
 * every day it covers: here it is read back with this app's own VTIMEZONE reader, under a name the
 * zone database doesn't know, so only the block can answer.
 */
class ZonesTest {
    private fun lines(c: Component) = ICalWriter.write(c).trimEnd().split("\r\n")

    private val zones = listOf(
        "Australia/Sydney" to 2026, "Australia/Sydney" to 2024, "Australia/Sydney" to 2006,
        "Asia/Ho_Chi_Minh" to 2026, "Europe/Berlin" to 2026, "America/New_York" to 2026, "America/New_York" to 2005,
        "Pacific/Auckland" to 2026, "Asia/Kolkata" to 2026, "Asia/Kathmandu" to 2026, "America/St_Johns" to 2026,
        "America/Santiago" to 2026, "Australia/Lord_Howe" to 2026, "Europe/Moscow" to 2010, "Asia/Tehran" to 2021,
        "America/Sao_Paulo" to 2018, "Africa/Casablanca" to 2024,
    )

    @Test
    fun eachBlockReadsBackAtTheInstantItWasWrittenEveryDayOfTheSpan() {
        for ((name, year) in zones) {
            val zone = ZoneId.of(name)
            val block = VTimezones.of(zone, year)
            block.set("TZID", "Custom/Test")
            val rules = Zones.fromVtimezone(block)
            var t = LocalDateTime.of(year, 1, 1, 0, 30).toInstant(ZoneOffset.UTC)
            val end = LocalDateTime.of(maxOf(year, LocalDate.now().year) + 2, 12, 31, 0, 0).toInstant(ZoneOffset.UTC)
            var checked = 0
            while (t.isBefore(end)) {
                val wall = LocalDateTime.ofInstant(t, zone)
                val stable = zone.rules.getOffset(t.minusSeconds(3600)) == zone.rules.getOffset(t) && zone.rules.getOffset(t.plusSeconds(3600)) == zone.rules.getOffset(t)
                if (stable) {
                    assertEquals("$name from $year at $t", t, Zones.instantOf(wall, rules))
                    checked++
                }
                t = t.plusSeconds(13 * 3600)
            }
            assertTrue(checked > 1000)
        }
    }

    @Test
    fun statesSydneysRulesAsYearlyRulesLikeOtherClients() {
        assertEquals(
            listOf(
                "BEGIN:VTIMEZONE", "TZID:Australia/Sydney",
                "BEGIN:STANDARD", "DTSTART:20250406T030000", "RRULE:FREQ=YEARLY;BYMONTH=4;BYDAY=1SU", "TZOFFSETFROM:+1100", "TZOFFSETTO:+1000", "END:STANDARD",
                "BEGIN:DAYLIGHT", "DTSTART:20251005T020000", "RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=1SU", "TZOFFSETFROM:+1000", "TZOFFSETTO:+1100", "END:DAYLIGHT",
                "END:VTIMEZONE",
            ),
            lines(VTimezones.of(ZoneId.of("Australia/Sydney"), 2026)),
        )
    }

    @Test
    fun picksLastSundayForEuropeAndSecondSundayForTheUs() {
        val berlin = lines(VTimezones.of(ZoneId.of("Europe/Berlin"), 2026))
        assertTrue(berlin.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU"))
        assertTrue(berlin.contains("RRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU"))
        val ny = lines(VTimezones.of(ZoneId.of("America/New_York"), 2026))
        assertTrue(ny.contains("RRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=2SU"))
        assertTrue(ny.contains("RRULE:FREQ=YEARLY;BYMONTH=11;BYDAY=1SU"))
    }

    @Test
    fun givesAZoneWithoutSummerTimeOneObservance() {
        assertEquals(
            listOf("BEGIN:VTIMEZONE", "TZID:Asia/Ho_Chi_Minh", "BEGIN:STANDARD", "DTSTART:19700101T000000", "TZOFFSETFROM:+0700", "TZOFFSETTO:+0700", "END:STANDARD", "END:VTIMEZONE"),
            lines(VTimezones.of(ZoneId.of("Asia/Ho_Chi_Minh"), 2026)),
        )
    }

    @Test
    fun namesZonesTheWaysOtherProgramsDo() {
        assertEquals(ZoneId.of("Europe/Berlin"), Zones.ianaFor("W. Europe Standard Time"))
        assertEquals(ZoneId.of("Asia/Bangkok"), Zones.ianaFor("SE Asia Standard Time"))
        assertEquals(ZoneId.of("Europe/Berlin"), Zones.ianaFor("/mozilla.org/20050126_1/Europe/Berlin"))
        assertEquals(ZoneId.of("Asia/Ho_Chi_Minh"), Zones.ianaFor("Asia/Ho_Chi_Minh"))
        assertEquals(ZoneOffset.UTC, Zones.ianaFor("Etc/UTC"))
        assertNull(Zones.ianaFor("tzone://Microsoft/Custom"))
        for (win in WindowsZones.names.keys) assertTrue(win, Zones.ianaFor(win) != null)
    }

    @Test
    fun readsATzidNothingExplainsInThePhonesZone() {
        val z = Zones(floating = ZoneId.of("Asia/Ho_Chi_Minh"))
        val t = ICalTime.parse("20261110T090000", "tzone://Microsoft/Custom")!!
        assertEquals(Instant.parse("2026-11-10T02:00:00Z"), t.instant(z))
    }

    @Test
    fun aFilesOwnBlockExplainsAnUnknownName() {
        val block = VTimezones.of(ZoneId.of("America/New_York"), 2026).also { it.set("TZID", "Eastern (made up)") }
        val cal = Component("VCALENDAR", components = mutableListOf(block))
        val z = Zones.of(cal, ZoneId.of("Asia/Ho_Chi_Minh"))
        assertEquals(Instant.parse("2026-07-01T13:00:00Z"), ICalTime.parse("20260701T090000", "Eastern (made up)")!!.instant(z))
        assertEquals(Instant.parse("2026-12-01T14:00:00Z"), ICalTime.parse("20261201T090000", "Eastern (made up)")!!.instant(z))
    }

    @Test
    fun aTimeInTheSpringGapMovesOnAndARepeatedOneIsTheFirst() {
        val sydney = ZoneId.of("Australia/Sydney").rules
        assertEquals(Instant.parse("2026-10-03T16:30:00Z"), Zones.instantOf(LocalDateTime.of(2026, 10, 4, 2, 30), sydney))
        assertEquals(Instant.parse("2027-04-03T15:30:00Z"), Zones.instantOf(LocalDateTime.of(2027, 4, 4, 2, 30), sydney))
    }
}
