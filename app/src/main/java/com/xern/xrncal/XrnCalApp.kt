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
import com.xern.xrncal.data.Ring
import com.xern.xrncal.data.RoomCalendarStore
import com.xern.xrncal.data.RungLog
import com.xern.xrncal.data.SettingsStore
import com.xern.xrncal.data.SyncEngine
import com.xern.xrncal.work.AndroidAlarmScheduler
import com.xern.xrncal.work.PrefsRungLog
import com.xern.xrncal.work.SyncWork
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class XrnCalApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = Graph.android(this)
        // The background sync, booked while an account is set; WorkManager keeps it across reboots.
        if (graph.account.connected) SyncWork.keep(this, graph.settings.value.syncMinutes)
        // HyperOS turns Autostart off at every install: say so while it is.
        com.xern.xrncal.work.OemAutostart.check(this)
    }
}

/** The sync line in Settings: running, the last good sync, or what went wrong. */
data class SyncStatus(val running: Boolean = false, val lastSync: Long = 0, val error: String = "")

/** How a sync ended: through, failed (the sync line says why), or refused the network by Android. */
enum class SyncEnd { DONE, FAILED, REFUSED }

/**
 * Requests made of the calendar screen (open this event, show this day) that wait for it: one
 * asked before anything collects is kept, not dropped as a SharedFlow without subscribers drops
 * it, and each is handed to one collector once. A request asked while another still waits
 * replaces it: the last tap wins.
 */
class Requests<T : Any> {
    private val held = MutableStateFlow<T?>(null)

    fun ask(request: T) {
        held.value = request
    }

    /** Each request once, as it comes, a held one first. */
    val flow: Flow<T> = flow {
        held.collect { r -> if (r != null && held.compareAndSet(r, null)) emit(r) }
    }
}

/** Everything the screens use, made once (manual wiring, as in the other xrn apps). */
class Graph(
    val store: CalendarStore,
    val settings: SettingsStore,
    val account: Account,
    alarms: AlarmScheduler,
    rungLog: RungLog,
    snoozes: com.xern.xrncal.data.SnoozeLog = com.xern.xrncal.data.MemorySnoozeLog(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Instant = Instant::now,
    client: (Account) -> CalDav = { OkCalDav(it.address, it.user, it.password) },
    /** The home-screen widgets drawn again (after a sync, a change, or new Options). */
    val refreshWidgets: () -> Unit = {},
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _status = MutableStateFlow(SyncStatus(lastSync = account.lastSync, error = account.lastError))
    val status: StateFlow<SyncStatus> = _status

    val engine = SyncEngine(store, account, client = client)
    val events = EventRepository(store, { settings.value }, zone, clock, changed = { changedHere() })
    val reminders = ReminderPlanner(events, { settings.value }, rungLog, alarms, zone, clock, snoozes)

    /** [syncSoon]'s sync: one waiting out its delay, whether one is running, and whether to run again after it. */
    private val soon = Any()
    private var waiting: Job? = null
    private var running = false
    private var again = false

    private val opens = Requests<Pair<String, Long?>>()

    /**
     * An event a reminder's notification or the agenda widget asks to open: its resource and the
     * start of its date. Held until the calendar collects it, since a tap that starts the app asks
     * before the calendar's first frame; each is handled once.
     */
    val open: Flow<Pair<String, Long?>> = opens.flow

    fun open(resourceId: String, start: Long?) = opens.ask(resourceId to start)

    private val days = Requests<LocalDate>()

    /** A day another app asked to see (a time link, docs/spec.md "Other apps"), held the same way. */
    val dayAsked: Flow<LocalDate> = days.flow

    fun showDay(day: LocalDate) = days.ask(day)

    /** Something changed on this phone: to the server soon, and the alarm follows it now. */
    private fun changedHere() {
        syncSoon()
        scope.launch { reminders.rearm() }
        refreshWidgets()
    }

    /**
     * A sync in [delayMs] (2 seconds after changes stop), if an account is set. A change made while
     * that sync runs doesn't cancel it, since a PUT or DELETE the server took would then never be
     * marked sent and come back as a conflict: the sync finishes, and another one follows it.
     */
    fun syncSoon(delayMs: Long = 2_000) {
        if (!account.connected) return
        synchronized(soon) {
            if (running) {
                again = true
                return
            }
            waiting?.cancel()
            waiting = scope.launch {
                delay(delayMs)
                synchronized(soon) {
                    // Put off by a later change in the moment the delay ended.
                    if (!isActive) return@launch
                    running = true
                    waiting = null
                }
                try {
                    syncNow()
                } finally {
                    val more = synchronized(soon) {
                        running = false
                        again.also { again = false }
                    }
                    if (more) syncSoon(delayMs)
                }
            }
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
        refreshWidgets()
        return end
    }

    /**
     * The reminder alarm went off: a sync first, so an event moved or deleted on the laptop doesn't
     * ring, but waited for [waitMs] at most. A call blocked on a slow or dead connection can't be
     * interrupted (8 seconds used to become about 75), so the sync isn't stopped either: it goes on
     * by itself while the reminders due are shown, and the alarm is set again.
     */
    suspend fun ring(waitMs: Long = RING_WAIT_MS, show: (Ring) -> Unit) {
        val sync = scope.launch { syncNow() }
        withTimeoutOrNull(waitMs) { sync.join() }
        reminders.ringing().forEach(show)
        reminders.rearm()
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
        refreshWidgets()
    }

    fun start() {
        scope.launch {
            events.localCalendar()
            reminders.rearm()
            syncNow()
        }
    }

    companion object {
        /** How long a reminder waits for the sync before it rings. */
        const val RING_WAIT_MS = 5_000L

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
                snoozes = com.xern.xrncal.work.PrefsSnoozeLog(context.getSharedPreferences("reminders", Context.MODE_PRIVATE)),
                refreshWidgets = { com.xern.xrncal.work.Widgets.refresh(context) },
            )
        }
    }
}
