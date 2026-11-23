package com.xern.xrncal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One Calendar's colour choices, and how a colour is written to CalDAV. */
class PaletteTest {
    @Test
    fun offersEveryColourOneCalendarGivesCalendars() {
        // The colours a One Calendar user's calendars come in, from its Google-style picker.
        val oneCalendar = listOf(0x795548, 0x616161, 0xD50000, 0xF6BF26, 0x039BE5, 0xF09300, 0xF4511E, 0x0B8043, 0x8E24AA, 0x7CB342, 0x3F51B5)
        for (rgb in oneCalendar) assertNotNull("#%06X".format(rgb), Palette.named((0xFF000000 or rgb.toLong()).toInt()))
        assertEquals(Palette.ALL.size, Palette.ALL.map { it.argb }.toSet().size)
        assertEquals(24, Palette.ALL.size)
    }

    @Test
    fun writesAnEventsColourAsTheLaptopAndACalendarsAsApple() {
        assertEquals("#3f51b5", Palette.hexOf(0xFF3F51B5.toInt()))
        assertEquals("#3F51B5FF", Palette.calendarColor(0xFF3F51B5.toInt()))
        // And reads both back.
        assertEquals(0xFF3F51B5.toInt(), EventView.hexColor("#3F51B5FF"))
        assertEquals(0xFF3F51B5.toInt(), EventView.hexColor("#3f51b5"))
        assertTrue(Palette.ALL.all { EventView.hexColor(it.hex) == it.argb })
    }
}
