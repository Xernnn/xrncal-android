package com.xern.xrncal.data

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.xern.xrncal.domain.EventLength
import com.xern.xrncal.domain.HolidayCalendar
import java.security.KeyStore
import java.time.DayOfWeek
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class View { MONTH, LIST, WEEK, DAY }

/** 12 or 24 hours, or as the phone is set. */
enum class Clock { SYSTEM, H12, H24 }

/** The owner's choices (docs/spec.md, "Settings"). */
data class Settings(
    /** Null: the phone's own first day of the week. */
    val weekStart: DayOfWeek? = null,
    val defaultView: View = View.MONTH,
    /** Minutes before a timed event; null for none. */
    val timedReminder: Int? = 10,
    /** Minutes before an all-day event's day; null for none. */
    val allDayReminder: Int? = null,
    val eventMinutes: Int = EventLength.DEFAULT,
    val clock: Clock = Clock.SYSTEM,
    val weekNumbers: Boolean = true,
    val lunar: Boolean = true,
    /** Where a new event goes; null: the first calendar that can take one. */
    val defaultCalendar: String? = null,
    /** The holiday calendars shown. */
    val holidays: Set<String> = setOf(HolidayCalendar.VIETNAM.id),
)

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
    init {
        val d = Settings()
        state.value = Settings(
            weekStart = prefs.getString("weekStart", null)?.let { runCatching { DayOfWeek.valueOf(it) }.getOrNull() },
            defaultView = prefs.getString("defaultView", null)?.let { runCatching { View.valueOf(it) }.getOrNull() } ?: d.defaultView,
            timedReminder = if (prefs.contains("timedReminder")) prefs.getInt("timedReminder", 10).takeIf { it >= 0 } else d.timedReminder,
            allDayReminder = if (prefs.contains("allDayReminder")) prefs.getInt("allDayReminder", -1).takeIf { it >= 0 } else d.allDayReminder,
            eventMinutes = EventLength.minutes(prefs.getInt("eventMinutes", d.eventMinutes)),
            clock = prefs.getString("clock", null)?.let { runCatching { Clock.valueOf(it) }.getOrNull() } ?: d.clock,
            weekNumbers = prefs.getBoolean("weekNumbers", d.weekNumbers),
            lunar = prefs.getBoolean("lunar", d.lunar),
            defaultCalendar = prefs.getString("defaultCalendar", null),
            holidays = prefs.getStringSet("holidays", null)?.toSet() ?: d.holidays,
        )
    }

    override fun save(s: Settings) {
        prefs.edit()
            .putString("weekStart", s.weekStart?.name)
            .putString("defaultView", s.defaultView.name)
            .putInt("timedReminder", s.timedReminder ?: -1)
            .putInt("allDayReminder", s.allDayReminder ?: -1)
            .putInt("eventMinutes", s.eventMinutes)
            .putString("clock", s.clock.name)
            .putBoolean("weekNumbers", s.weekNumbers)
            .putBoolean("lunar", s.lunar)
            .putString("defaultCalendar", s.defaultCalendar)
            .putStringSet("holidays", s.holidays)
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
