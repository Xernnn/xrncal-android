package com.xern.xrncal

import android.content.SharedPreferences
import com.xern.xrncal.data.PrefsSettings
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.SettingsMigration
import org.junit.Assert.assertEquals
import org.junit.Test

/** Today's colour is xrncal's red, and an install that never changed One Calendar's red moves to it. */
class SignalColourTest {
    private val picked = 0xFF8E24AA.toInt() // Grape, from the picker

    @Test
    fun theDefaultIsTheLaptopsRedInEachTheme() {
        assertEquals(0xFFF0616D.toInt(), Settings().signalFor(dark = true))
        assertEquals(0xFFEB5757.toInt(), Settings().signalFor(dark = false))
        // A colour picked is drawn as it is in both.
        assertEquals(picked, Settings(signal = picked).signalFor(dark = true))
        assertEquals(picked, Settings(signal = picked).signalFor(dark = false))
    }

    @Test
    fun onlyAnUnchangedDefaultMoves() {
        assertEquals(Settings.SIGNAL_RED, SettingsMigration.signal(2, Settings.ONE_CALENDAR_RED))
        assertEquals(Settings.SIGNAL_RED, SettingsMigration.signal(1, Settings.ONE_CALENDAR_RED))
        assertEquals(picked, SettingsMigration.signal(2, picked))
        // Picked again after the update, One Calendar's red is a choice like any other.
        assertEquals(Settings.ONE_CALENDAR_RED, SettingsMigration.signal(3, Settings.ONE_CALENDAR_RED))
    }

    @Test
    fun settingsSavedByPhaseTwoAreReadWithTheNewRedAndSavedAgain() {
        val prefs = FakePrefs(mapOf("version" to 2, "defaultView" to "MONTH", "signal" to Settings.ONE_CALENDAR_RED))
        val s = PrefsSettings(prefs).value
        assertEquals(Settings.SIGNAL_RED, s.signal)
        assertEquals("MONTH", s.defaultView.name)
        assertEquals(SettingsMigration.VERSION, prefs.values["version"])
        assertEquals(Settings.SIGNAL_RED, prefs.values["signal"])
        // Read again, nothing moves.
        assertEquals(Settings.SIGNAL_RED, PrefsSettings(prefs).value.signal)
    }

    @Test
    fun aColourPickedBeforeTheUpdateStays() {
        val prefs = FakePrefs(mapOf("version" to 2, "defaultView" to "WEEK", "signal" to picked))
        assertEquals(picked, PrefsSettings(prefs).value.signal)
        assertEquals(picked, prefs.values["signal"])
    }

    @Test
    fun oneCalendarsRedPickedAfterTheUpdateStays() {
        val prefs = FakePrefs(mapOf("version" to SettingsMigration.VERSION, "defaultView" to "WEEK", "signal" to Settings.ONE_CALENDAR_RED))
        assertEquals(Settings.ONE_CALENDAR_RED, PrefsSettings(prefs).value.signal)
    }

    @Test
    fun aNewInstallStartsWithTheNewRed() {
        assertEquals(Settings.SIGNAL_RED, PrefsSettings(FakePrefs(emptyMap())).value.signal)
    }
}

/** Preferences in memory, as Android keeps them, for reading and saving [PrefsSettings] in a test. */
private class FakePrefs(initial: Map<String, Any?>) : SharedPreferences {
    val values = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = if (key in values) values[key] as String? else defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = if (key in values) (values[key] as Set<String>?)?.toMutableSet() else defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as Boolean? ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { changes[key] = null }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
