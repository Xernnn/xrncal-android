package com.xern.xrncal

import com.xern.xrncal.data.Clock
import com.xern.xrncal.data.HourSize
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.ThemeMode
import com.xern.xrncal.data.View
import com.xern.xrncal.ui.Wallpapers
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A new install starts as the owner has the laptop and One Calendar set up (docs/spec.md, phase 2 "Defaults"). */
class SettingsDefaultsTest {
    private val s = Settings()

    @Test
    fun theLookIsTheLaptops() {
        assertEquals(ThemeMode.DARK, s.theme)
        assertEquals("Cyber City", Wallpapers.preset(s.wallpaper)!!.name)
        assertEquals(0.15f, s.overlay)
        assertEquals(2, s.blur)
    }

    @Test
    fun theViewsAreTheLaptopsAndOneCalendars() {
        assertEquals(Clock.H24, s.clock)
        assertEquals(DayOfWeek.MONDAY, s.weekStart)
        assertTrue(s.weekNumbers && s.lunar && s.monthPopup)
        // One Calendar's month chips are titles only; the laptop's times fit its wider cells.
        assertFalse(s.monthTimes)
        assertEquals("Asia/Ho_Chi_Minh", s.secondZone)
        assertEquals(listOf("Asia/Ho_Chi_Minh"), s.preferredZones)
        assertEquals(15, s.timeStep)
        assertEquals(View.WEEK, s.defaultView)
        assertEquals(8, s.dayStart)
        assertEquals(HourSize.MEDIUM, s.hourSize)
        assertEquals(60, s.eventMinutes)
        assertEquals(15 to 720, s.timedReminder to s.allDayReminder)
    }

    @Test
    fun aWallpaperSettingNamesAPresetAFileOrNone() {
        assertEquals(listOf("Misty Forest", "Purple Sunset", "Cyber City", "Cosmic Night"), Wallpapers.PRESETS.map { it.name })
        assertEquals("cosmicNight", Wallpapers.preset("preset:cosmicNight")!!.key)
        assertNull(Wallpapers.preset(Wallpapers.NONE))
        assertNull(Wallpapers.preset("file:wallpaper-1.jpg"))
        assertNull(Wallpapers.preset("preset:unknown"))
    }
}
