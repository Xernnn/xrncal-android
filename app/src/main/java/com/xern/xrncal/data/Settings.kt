package com.xern.xrncal.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xern.xrncal.domain.EventLength
import com.xern.xrncal.domain.HolidayCalendar
import java.security.KeyStore
import java.time.DayOfWeek
import java.time.ZoneId
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class View { MONTH, LIST, WEEK, DAY, YEAR }

/** 12 or 24 hours, or as the phone is set. */
enum class Clock { SYSTEM, H12, H24 }

/** The app's light or dark, or as the phone is set. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** One Calendar's hour sizes for the day and week grids: Medium fits 08:00 to 23:00 on the owner's phone. */
enum class HourSize(val dp: Int, val label: String) { SMALL(30, "Small"), MEDIUM(40, "Medium"), LARGE(56, "Large") }

/**
 * The owner's choices (docs/spec.md, "Settings" and phase 2's Options). The defaults are the
 * owner's laptop and One Calendar, as phase 2's "Defaults" lists them.
 */
data class Settings(
    /** Null: the phone's own first day of the week. */
    val weekStart: DayOfWeek? = DayOfWeek.MONDAY,
    val defaultView: View = View.WEEK,
    /** A new timed event's reminder, minutes before; null for none. One Calendar's 15 minutes. */
    val timedReminder: Int? = 15,
    /** A new all-day event's reminder, minutes before its day; null for none. One Calendar's 12 hours. */
    val allDayReminder: Int? = 720,
    val eventMinutes: Int = EventLength.DEFAULT,
    val clock: Clock = Clock.H24,
    val weekNumbers: Boolean = true,
    val lunar: Boolean = true,
    /** Where a new event goes; null: the first calendar that can take one. */
    val defaultCalendar: String? = null,
    /** The holiday calendars shown. */
    val holidays: Set<String> = setOf(HolidayCalendar.VIETNAM.id),
    /** Colours picked for the holiday calendars (kept here: they live only on the phone). */
    val holidayColors: Map<String, Int> = emptyMap(),

    // Theme.
    val theme: ThemeMode = ThemeMode.DARK,
    /** `preset:<key>`, `file:<name>` (imported from the gallery) or `none`. */
    val wallpaper: String = "preset:cyberCity",
    /** How dark the overlay over the wallpaper is, 0 to 0.8. */
    val overlay: Float = 0.15f,
    /** The wallpaper's blur, 0 to 20. */
    val blur: Int = 2,
    /** Today's date and the time line: One Calendar's signal colour. */
    val signal: Int = SIGNAL_RED,

    // Views.
    val monthPopup: Boolean = true,
    /** The hour the day and week grids open at. */
    val dayStart: Int = 8,
    val hourSize: HourSize = HourSize.MEDIUM,
    /** Shown under each hour in the day and week gutters; null for none. */
    val secondZone: String? = "Asia/Ho_Chi_Minh",
    /** First in the editor's zone list. */
    val preferredZones: List<String> = listOf("Asia/Ho_Chi_Minh"),
    /**
     * A timed chip in the month leads with its start time, as the laptop's month. Off by default:
     * a phone's month cell is a seventh of the screen, and the time left 3 or 4 letters of a title.
     */
    val monthTimes: Boolean = false,
    /** Minutes between the times the editor's time list offers. */
    val timeStep: Int = 15,

    // Appointments.
    /** Title and place suggestions in the editor. */
    val suggestions: Boolean = true,

    // Reminders.
    /** Leave the title and place off notifications on the lock screen. */
    val privateNotifications: Boolean = false,
    val snoozeMinutes: Int = 10,

    // Synchronisation.
    /** How many years back events are shown and searched; 0 for all. */
    val historyYears: Int = 3,
    val syncMinutes: Int = 30,

    // Time zone.
    val deviceZone: Boolean = true,
    /** The zone everything is shown in when [deviceZone] is off. */
    val zone: String? = null,

    // Widgets.
    val widgetDark: Boolean = true,
    val widgetText: Int = 0xFFFFFFFF.toInt(),
    val widgetBackground: Int = 0xFF000000.toInt(),
    val widgetOpacity: Float = 0.5f,
    /** Calendars left off the widgets. */
    val widgetHidden: Set<String> = emptySet(),

    // Emoji.
    val emoji: Boolean = true,
    /** The emojis picked, with how often, in the laptop's format. */
    val emojiUse: String = "",
) {
    /** The zone the app shows everything in. */
    fun zoneId(): ZoneId = if (deviceZone) ZoneId.systemDefault() else zone?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    companion object {
        const val SIGNAL_RED = 0xFFF44336.toInt()
        val SNOOZES = listOf(5, 10, 15, 30, 60)
        val SYNC_INTERVALS = listOf(15, 30, 60, 120, 360)
        val HISTORY = listOf(1, 2, 3, 5, 0)
        val TIME_STEPS = listOf(5, 10, 15, 30)
    }
}

/** What changes when settings saved by an older version are read. */
object SettingsMigration {
    /** 1: 0.1.0, which saved every setting whenever one changed. 2: phase 2. */
    const val VERSION = 2

    /**
     * The reminder defaults as read from version [from]: 0.1.0's own defaults (10 minutes, none)
     * become One Calendar's (15 minutes, 12 hours), since 0.1.0 wrote them back with any other
     * change and they can't say whether the owner chose them; anything else was chosen and stays.
     */
    fun reminders(from: Int, timed: Int?, allDay: Int?): Pair<Int?, Int?> {
        if (from >= 2) return timed to allDay
        val d = Settings()
        return (if (timed == 10) d.timedReminder else timed) to (if (allDay == null) d.allDayReminder else allDay)
    }
}

/** Where [Settings] are kept: preferences on the phone, memory in the tests. */
abstract class SettingsStore {
    protected val state = MutableStateFlow(Settings())
    val flow: StateFlow<Settings> = state
    val value: Settings get() = state.value

    fun update(change: (Settings) -> Settings) {
        val next = change(state.value)
        state.value = next
        save(next)
    }

    protected abstract fun save(s: Settings)
}

class MemorySettings(initial: Settings = Settings()) : SettingsStore() {
    init {
        state.value = initial
    }

    override fun save(s: Settings) = Unit
}

class PrefsSettings(private val prefs: SharedPreferences) : SettingsStore() {
    private inline fun <reified E : Enum<E>> enum(key: String, default: E): E =
        prefs.getString(key, null)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private fun int(key: String, default: Int) = prefs.getInt(key, default)
    private fun bool(key: String, default: Boolean) = prefs.getBoolean(key, default)
    private fun float(key: String, default: Float) = prefs.getFloat(key, default)
    private fun string(key: String, default: String?) = if (prefs.contains(key)) prefs.getString(key, null) else default

    init {
        val d = Settings()
        // Saved by 0.1.0 when there is something saved and no version: it wrote none.
        val from = prefs.getInt("version", if (prefs.contains("defaultView")) 1 else SettingsMigration.VERSION)
        val (timed, allDay) = SettingsMigration.reminders(
            from,
            if (prefs.contains("timedReminder")) prefs.getInt("timedReminder", -1).takeIf { it >= 0 } else d.timedReminder,
            if (prefs.contains("allDayReminder")) prefs.getInt("allDayReminder", -1).takeIf { it >= 0 } else d.allDayReminder,
        )
        // 0.1.0 wrote no key for "as the phone"; from 2 on that is "PHONE".
        val week = prefs.getString("weekStart", null)
        state.value = Settings(
            weekStart = when {
                week == null -> if (from < 2) null else d.weekStart
                week == "PHONE" -> null
                else -> runCatching { DayOfWeek.valueOf(week) }.getOrNull()
            },
            defaultView = enum("defaultView", d.defaultView),
            timedReminder = timed,
            allDayReminder = allDay,
            eventMinutes = EventLength.minutes(prefs.getInt("eventMinutes", d.eventMinutes)),
            clock = enum("clock", d.clock),
            weekNumbers = bool("weekNumbers", d.weekNumbers),
            lunar = bool("lunar", d.lunar),
            defaultCalendar = prefs.getString("defaultCalendar", null),
            holidays = prefs.getStringSet("holidays", null)?.toSet() ?: d.holidays,
            holidayColors = prefs.getString("holidayColors", null).orEmpty().split(';').mapNotNull { e ->
                val (k, v) = e.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                v.toLongOrNull()?.let { k to it.toInt() }
            }.toMap(),
            theme = enum("theme", d.theme),
            wallpaper = string("wallpaper", d.wallpaper) ?: d.wallpaper,
            overlay = float("overlay", d.overlay),
            blur = int("blur", d.blur),
            signal = int("signal", d.signal),
            monthPopup = bool("monthPopup", d.monthPopup),
            dayStart = int("dayStart", d.dayStart).coerceIn(0, 23),
            hourSize = enum("hourSize", d.hourSize),
            secondZone = string("secondZone", d.secondZone)?.takeIf { it.isNotEmpty() },
            preferredZones = string("preferredZones", null)?.split(',')?.filter { it.isNotBlank() } ?: d.preferredZones,
            monthTimes = bool("monthTimes", d.monthTimes),
            timeStep = int("timeStep", d.timeStep),
            suggestions = bool("suggestions", d.suggestions),
            privateNotifications = bool("privateNotifications", d.privateNotifications),
            snoozeMinutes = int("snoozeMinutes", d.snoozeMinutes),
            historyYears = int("historyYears", d.historyYears),
            syncMinutes = int("syncMinutes", d.syncMinutes),
            deviceZone = bool("deviceZone", d.deviceZone),
            zone = string("zone", d.zone)?.takeIf { it.isNotEmpty() },
            widgetDark = bool("widgetDark", d.widgetDark),
            widgetText = int("widgetText", d.widgetText),
            widgetBackground = int("widgetBackground", d.widgetBackground),
            widgetOpacity = float("widgetOpacity", d.widgetOpacity),
            widgetHidden = prefs.getStringSet("widgetHidden", null)?.toSet() ?: d.widgetHidden,
            emoji = bool("emoji", d.emoji),
            emojiUse = string("emojiUse", d.emojiUse).orEmpty(),
        )
        if (from < SettingsMigration.VERSION) save(state.value)
    }

    override fun save(s: Settings) {
        prefs.edit()
            .putInt("version", SettingsMigration.VERSION)
            .putString("weekStart", s.weekStart?.name ?: "PHONE")
            .putString("defaultView", s.defaultView.name)
            .putInt("timedReminder", s.timedReminder ?: -1)
            .putInt("allDayReminder", s.allDayReminder ?: -1)
            .putInt("eventMinutes", s.eventMinutes)
            .putString("clock", s.clock.name)
            .putBoolean("weekNumbers", s.weekNumbers)
            .putBoolean("lunar", s.lunar)
            .putString("defaultCalendar", s.defaultCalendar)
            .putStringSet("holidays", s.holidays)
            .putString("holidayColors", s.holidayColors.entries.joinToString(";") { "${it.key}=${it.value.toLong() and 0xFFFFFFFF}" })
            .putString("theme", s.theme.name)
            .putString("wallpaper", s.wallpaper)
            .putFloat("overlay", s.overlay)
            .putInt("blur", s.blur)
            .putInt("signal", s.signal)
            .putBoolean("monthPopup", s.monthPopup)
            .putInt("dayStart", s.dayStart)
            .putString("hourSize", s.hourSize.name)
            .putString("secondZone", s.secondZone.orEmpty())
            .putString("preferredZones", s.preferredZones.joinToString(","))
            .putBoolean("monthTimes", s.monthTimes)
            .putInt("timeStep", s.timeStep)
            .putBoolean("suggestions", s.suggestions)
            .putBoolean("privateNotifications", s.privateNotifications)
            .putInt("snoozeMinutes", s.snoozeMinutes)
            .putInt("historyYears", s.historyYears)
            .putInt("syncMinutes", s.syncMinutes)
            .putBoolean("deviceZone", s.deviceZone)
            .putString("zone", s.zone.orEmpty())
            .putBoolean("widgetDark", s.widgetDark)
            .putInt("widgetText", s.widgetText)
            .putInt("widgetBackground", s.widgetBackground)
            .putFloat("widgetOpacity", s.widgetOpacity)
            .putStringSet("widgetHidden", s.widgetHidden)
            .putBoolean("emoji", s.emoji)
            .putString("emojiUse", s.emojiUse)
            .apply()
    }
}

/**
 * Bytes sealed with AES-GCM under an Android Keystore key that never leaves the phone's secure
 * hardware: the CalDAV password is kept this way, never in plain preferences.
 */
class KeystoreSeal(private val alias: String) {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plain))
    }

    fun open(sealed: String): ByteArray {
        val bytes = Base64.getDecoder().decode(sealed)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
}

/** The CalDAV login in prefs; the password sealed by the Keystore key `xrncal-caldav`. */
class PrefsAccount(private val prefs: SharedPreferences) : Account {
    private val seal = KeystoreSeal("xrncal-caldav")

    override var address: String
        get() = prefs.getString("address", "").orEmpty()
        set(v) = prefs.edit().putString("address", v).apply()
    override var user: String
        get() = prefs.getString("user", "").orEmpty()
        set(v) = prefs.edit().putString("user", v).apply()
    override var password: String
        get() = prefs.getString("password", null)?.let { runCatching { seal.open(it).toString(Charsets.UTF_8) }.getOrNull() }.orEmpty()
        set(v) = prefs.edit().putString("password", if (v.isEmpty()) null else seal.seal(v.toByteArray(Charsets.UTF_8))).apply()
    override var home: String?
        get() = prefs.getString("home", null)
        set(v) = prefs.edit().putString("home", v).apply()
    override var lastSync: Long
        get() = prefs.getLong("lastSync", 0)
        set(v) = prefs.edit().putLong("lastSync", v).apply()
    override var lastError: String
        get() = prefs.getString("lastError", "").orEmpty()
        set(v) = prefs.edit().putString("lastError", v).apply()
}
