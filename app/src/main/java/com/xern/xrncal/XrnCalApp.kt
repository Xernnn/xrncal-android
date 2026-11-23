package com.xern.xrncal

import android.app.Application
import android.content.Context
import com.xern.xrncal.data.Account
import com.xern.xrncal.data.AlarmScheduler
import com.xern.xrncal.data.CalDav
import com.xern.xrncal.data.CalendarDatabase
import com.xern.xrncal.data.CalendarStore
import com.xern.xrncal.data.DavFailure
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.OkCalDav
import com.xern.xrncal.data.PrefsAccount
import com.xern.xrncal.data.PrefsSettings
import com.xern.xrncal.data.ReminderPlanner
import com.xern.xrncal.data.RoomCalendarStore
import com.xern.xrncal.data.RungLog
import com.xern.xrncal.data.SettingsStore
import com.xern.xrncal.data.SyncEngine
import com.xern.xrncal.work.AndroidAlarmScheduler
import com.xern.xrncal.work.PrefsRungLog
import com.xern.xrncal.work.SyncWork
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class XrnCalApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph.android(this)
        // The background sync, booked while an account is set; WorkManager keeps it across reboots.
        if (graph.account.connected) SyncWork.keep(this)
    }
}

/** The sync line in Settings: running, the last good sync, or what went wrong. */
data class SyncStatus(val running: Boolean = false, val lastSync: Long = 0, val error: String = "")

/** How a sync ended: through, failed (the sync line says why), or refused the network by Android. */
enum class SyncEnd { DONE, FAILED, REFUSED }

/** Everything the screens use, made once (manual wiring, as in the other xrn apps). */
class Graph(
    val store: CalendarStore,
    val settings: SettingsStore,
    val account: Account,
    alarms: AlarmScheduler,
    rungLog: RungLog,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
    client: (Account) -> CalDav = { OkCalDav(it.address, it.user, it.password) },
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(SyncStatus(lastSync = account.lastSync, error = account.lastError))
    val status: StateFlow<SyncStatus> = _status

    val engine = SyncEngine(store, account, client = client)
    val events = EventRepository(store, { settings.value }, zone, clock, changed = { changedHere() })
    val reminders = ReminderPlanner(events, { settings.value }, rungLog, alarms, zone, clock)

    private var pending: Job? = null

    private val _open = MutableSharedFlow<Pair<String, Long?>>(extraBufferCapacity = 4)

    /** An event a reminder's notification asks to open: its resource and the start of its date. */
    val open: SharedFlow<Pair<String, Long?>> = _open

    fun open(resourceId: String, start: Long?) {
        _open.tryEmit(resourceId to start)
    }

    /** Something changed on this phone: to the server soon, and the alarm follows it now. */
    private fun changedHere() {
        syncSoon()
        scope.launch { reminders.rearm() }
    }

    /** A sync in [delayMs] (2 seconds after changes stop), if an account is set. */
    fun syncSoon(delayMs: Long = 2_000) {
        if (!account.connected) return
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            syncNow()
        }
    }

    /**
     * Syncs, then sets the alarm again. [refused] says whether Android is refusing this app the
     * network (the background sync asks): a server out of reach then says nothing about the server,
     * so the sync line keeps what it said.
     */
    suspend fun syncNow(refused: () -> Boolean = { false }): SyncEnd {
        if (!account.connected) return SyncEnd.DONE
        _status.value = _status.value.copy(running = true)
        var end = SyncEnd.FAILED
        val error = try {
            engine.sync()
            account.lastSync = clock().toEpochMilli()
            end = SyncEnd.DONE
            ""
        } catch (e: CancellationException) {
            _status.value = _status.value.copy(running = false)
            throw e
        } catch (e: DavFailure) {
            if (e.status == 0 && refused()) {
                end = SyncEnd.REFUSED
                null
            } else {
                e.text()
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
        if (error != null) account.lastError = error
        _status.value = SyncStatus(false, account.lastSync, account.lastError)
        reminders.rearm()
        return end
    }

    /** Signs in: finds the calendars at [address]; returns what went wrong, or null. */
    suspend fun connect(address: String, user: String, password: String): String? {
        account.address = address.trim()
        account.user = user.trim()
        if (password.isNotEmpty()) account.password = password
        account.home = null
        return try {
            engine.connect()
            account.lastError = ""
            syncNow()
            null
        } catch (e: DavFailure) {
            e.text()
        } catch (e: IllegalArgumentException) {
            e.message ?: "Check the address"
        }
    }

    /** Signs out: the account's calendars leave the phone (the server keeps them); the phone's own stay. */
    suspend fun signOut() {
        account.address = ""
        account.user = ""
        account.password = ""
        account.home = null
        account.lastError = ""
        account.lastSync = 0
        for (c in store.calendars()) if (!c.local) store.removeCalendar(c.id)
        _status.value = SyncStatus()
        reminders.rearm()
    }

    fun start() {
        scope.launch {
            events.localCalendar()
            reminders.rearm()
            syncNow()
        }
    }

    companion object {
        fun android(context: Context): Graph {
            val db = CalendarDatabase.open(context)
            val settings = PrefsSettings(context.getSharedPreferences("settings", Context.MODE_PRIVATE))
            return Graph(
                store = RoomCalendarStore(db),
                settings = settings,
                // Options, Time zone: the phone's, or one chosen there.
                zone = { settings.value.zoneId() },
                account = PrefsAccount(context.getSharedPreferences("account", Context.MODE_PRIVATE)),
                alarms = AndroidAlarmScheduler(context),
                rungLog = PrefsRungLog(context.getSharedPreferences("reminders", Context.MODE_PRIVATE)),
            )
        }
    }
}
