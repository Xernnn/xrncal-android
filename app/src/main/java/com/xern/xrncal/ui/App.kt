package com.xern.xrncal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xern.xrncal.Graph
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.Problem
import com.xern.xrncal.data.View
import com.xern.xrncal.domain.Agenda
import com.xern.xrncal.domain.EntryContext
import com.xern.xrncal.domain.LocationSuggestions
import com.xern.xrncal.domain.TitleEmoji
import com.xern.xrncal.domain.TitleSuggestions
import com.xern.xrncal.domain.HolidayCalendar
import com.xern.xrncal.domain.MonthLayout
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Reminders
import com.xern.xrncal.domain.Scope
import com.xern.xrncal.domain.WeekStart
import com.xern.xrncal.domain.ical.Zones
import com.xern.xrncal.work.SyncWork
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What is open over the calendar, last on top. */
sealed interface Screen {
    /**
     * The editor: a new event ([occ] null), or [occ] opened on its own date's fields; [locked] for
     * one that can't be changed, [problem] when it couldn't be sent.
     */
    data class Edit(val state: EditorState, val occ: Occurrence?, val locked: Boolean = false, val problem: String? = null) : Screen
    data object Search : Screen
    data object Options : Screen
    data class Option(val page: OptionPage) : Screen
    data object Accounts : Screen
}

/** Pagers start in the middle of this many pages each way: a hundred years of months. */
private const val MID = 1200

/** The first day of the week: Settings', or the phone's when it is one of the three offered. */
fun firstDayOf(choice: DayOfWeek?): DayOfWeek =
    choice ?: WeekFields.of(Locale.getDefault()).firstDayOfWeek.takeIf { it in WeekStart.CHOICES } ?: DayOfWeek.MONDAY

/**
 * The app: One Calendar's top bar (menu, the period's name, `+`, Today) over the chosen view, the
 * drawer, and the screens opened over them (detail, editor, search, settings).
 */
@Composable
fun App(graph: Graph) {
    val ink = LocalInk.current
    val context = LocalContext.current
    val settings by graph.settings.flow.collectAsState()
    val revision by graph.store.revision.collectAsState()
    val status by graph.status.collectAsState()
    val now = rememberNow()
    val zone = settings.zoneId()
    val today = now.atZone(zone).toLocalDate()
    val firstDay = firstDayOf(settings.weekStart)
    val calendars by produceState(emptyList<CalendarInfo>(), revision, settings.holidays) { value = graph.events.calendars() }
    val look = Look(
        colors = calendars.associate { it.id to it.color }, today = today, h24 = Format.is24(context, settings.clock),
        lunar = settings.lunar, weekNumbers = settings.weekNumbers, zone = zone,
        readOnly = calendars.filter { it.readOnly }.map { it.id }.toSet(),
        monthTimes = settings.monthTimes,
        secondZone = settings.secondZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }?.takeIf { it.rules != zone.rules },
    )
    val scope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val hourHeight = settings.hourSize.dp.dp
    val stack = remember { mutableStateListOf<Screen>() }
    var view by rememberSaveable { mutableStateOf(settings.defaultView) }
    var anchor by remember { mutableStateOf(today) }
    var popup by remember { mutableStateOf<LocalDate?>(null) }
    var asking by remember { mutableStateOf<Pair<String, (Scope) -> Unit>?>(null) }
    var undo by remember { mutableStateOf<EventRepository.Undo?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val writable = calendars.filter { !it.readOnly && HolidayCalendar.of(it.id) == null }
    val defaults = Reminders.Defaults(settings.timedReminder, settings.allDayReminder)

    fun defaultCalendar(): String? = writable.firstOrNull { it.id == settings.defaultCalendar }?.id
        ?: writable.firstOrNull { it.group == EventRepository.SERVER_GROUP }?.id ?: writable.firstOrNull()?.id

    fun add(day: LocalDate, hour: Int? = null) {
        val cal = defaultCalendar() ?: return
        stack += Screen.Edit(EditorState(newDraft(day, hour, now, zone, settings.eventMinutes, defaults.forNew(false)), cal), null)
    }

    // Tapping an event opens the editor straight away, as One Calendar and the laptop do.
    suspend fun editorFor(o: Occurrence): Screen.Edit? {
        val holiday = HolidayCalendar.of(o.calendarId) != null
        val draft = if (holiday) {
            com.xern.xrncal.domain.EventDraft(o.title, true, o.firstDay, java.time.LocalTime.MIDNIGHT, o.lastDay, java.time.LocalTime.MIDNIGHT, zone, notes = o.notes, color = o.color)
        } else {
            graph.events.draft(o, Scope.THIS) ?: return null
        }
        val problem = if (holiday) null else graph.events.resource(o.resourceId)?.problem
        return Screen.Edit(EditorState(draft, o.calendarId), o, locked = holiday || o.calendarId in look.readOnly, problem = problem)
    }

    fun open(o: Occurrence) {
        popup = null
        scope.launch { editorFor(o)?.let { stack += it } }
    }

    LaunchedEffect(Unit) {
        graph.open.collect { (id, start) ->
            graph.events.find(id, start)?.let { o ->
                val e = editorFor(o) ?: return@let
                stack.clear()
                stack += e
            }
        }
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isEmpty() && drawer.isOpen) { scope.launch { drawer.close() } }

    // Each view opens on the page that holds [anchor], the date the last one was on.
    val months = remember { YearMonth.from(today) }
    val weeks = remember(firstDay) { WeekStart.startOfWeek(today, firstDay) }
    val days = remember { today }

    var title by remember { mutableStateOf("") }
    var subtitle by remember { mutableStateOf<String?>(null) }
    var todayAction by remember { mutableStateOf<() -> Unit>({}) }
    val serverName = graph.account.address.let { a -> runCatching { java.net.URI(a.trim()).host }.getOrNull() ?: a }.ifBlank { "CalDAV" }

    LocalBackdrop.current()
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = stack.isEmpty(),
        drawerContent = {
            DrawerContent(
                view = view,
                syncing = status.running,
                onView = {
                    view = it
                    scope.launch { drawer.close() }
                },
                onSearch = {
                    stack += Screen.Search
                    scope.launch { drawer.close() }
                },
                onSync = { scope.launch { graph.syncNow() } },
                onAccounts = {
                    stack += Screen.Accounts
                    scope.launch { drawer.close() }
                },
                onOptions = {
                    stack += Screen.Options
                    scope.launch { drawer.close() }
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title, subtitle, today.dayOfMonth, calendars, serverName,
                onMenu = { scope.launch { drawer.open() } },
                onSubtitle = { view = View.MONTH },
                onAdd = { add(if (view == View.DAY || YearMonth.from(anchor) != YearMonth.from(today)) anchor else today) },
                onToday = { todayAction() },
                onToggle = { id, on -> scope.launch { graph.events.setShown(id, on, graph.settings) } },
            )
            Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                key(view, firstDay) {
                    when (view) {
                        View.MONTH -> {
                            val pager = rememberPagerState(initialPage = MID + ChronoUnit.MONTHS.between(months, YearMonth.from(anchor)).toInt()) { 2 * MID }
                            Follow(pager) { p -> anchor = months.plusMonths((p - MID).toLong()).let { if (it == YearMonth.from(today)) today else it.atDay(1) } }
                            title = Format.month(months.plusMonths((pager.currentPage - MID).toLong()), today)
                            subtitle = null
                            todayAction = { scope.launch { pager.animateScrollToPage(MID + ChronoUnit.MONTHS.between(months, YearMonth.from(today)).toInt()) } }
                            HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                                val ym = months.plusMonths((page - MID).toLong())
                                val grid = MonthLayout.weeks(ym, firstDay)
                                val list = occurrences(graph, grid.first().first(), grid.last().last(), revision, settings.holidays)
                                MonthPage(ym, firstDay, list, look, onDay = { d ->
                                    // One Calendar's "Show month day popup"; off, a day opens its Day view.
                                    if (settings.monthPopup) popup = d else {
                                        anchor = d
                                        view = View.DAY
                                    }
                                }, onEvent = ::open)
                            }
                        }
                        View.LIST -> {
                            val pager = rememberPagerState(initialPage = MID + ChronoUnit.MONTHS.between(months, YearMonth.from(anchor)).toInt()) { 2 * MID }
                            val ym = months.plusMonths((pager.currentPage - MID).toLong())
                            title = Format.month(ym, today)
                            subtitle = null
                            var from by remember { mutableStateOf(anchor) }
                            Follow(pager) { p ->
                                val m = months.plusMonths((p - MID).toLong())
                                val start = if (m == YearMonth.from(today)) today else m.atDay(1)
                                if (YearMonth.from(from) != m) from = start
                                anchor = start
                            }
                            val list = occurrences(graph, from, from.plusMonths(6), revision, settings.holidays)
                            val listState = rememberLazyListState()
                            LaunchedEffect(from) { listState.scrollToItem(0) }
                            todayAction = {
                                from = today
                                scope.launch { pager.animateScrollToPage(MID + ChronoUnit.MONTHS.between(months, YearMonth.from(today)).toInt()) }
                            }
                            Column {
                                HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                                    val m = months.plusMonths((page - MID).toLong())
                                    val grid = MonthLayout.weeks(m, firstDay, rows = null)
                                    MiniMonth(m, firstDay, occurrences(graph, grid.first().first(), grid.last().last(), revision, settings.holidays), look) { d -> from = d }
                                }
                                AgendaList(list, from, from.plusMonths(6), look, ::open, listState)
                            }
                        }
                        View.WEEK -> {
                            val pager = rememberPagerState(initialPage = MID + ChronoUnit.WEEKS.between(weeks, WeekStart.startOfWeek(anchor, firstDay)).toInt()) { 2 * MID }
                            Follow(pager) { p -> anchor = weeks.plusWeeks((p - MID).toLong()).let { if (WeekStart.startOfWeek(today, firstDay) == it) today else it } }
                            val start = weeks.plusWeeks((pager.currentPage - MID).toLong())
                            title = Format.month(YearMonth.from(start.plusDays(3)), today)
                            subtitle = "week ${WeekStart.isoWeekOfRow(start)}"
                            todayAction = { scope.launch { pager.animateScrollToPage(MID + ChronoUnit.WEEKS.between(weeks, WeekStart.startOfWeek(today, firstDay)).toInt()) } }
                            val scroll = rememberScrollState()
                            // Opened at the day's start (08:00), as One Calendar.
                            LaunchedEffect(scroll.maxValue > 0) { if (scroll.value == 0) scroll.scrollTo(with(density) { (hourHeight * settings.dayStart).roundToPx() }) }
                            HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                                val d0 = weeks.plusWeeks((page - MID).toLong())
                                val week = (0L until 7L).map { d0.plusDays(it) }
                                TimeGridPage(week, occurrences(graph, week.first(), week.last(), revision, settings.holidays), look, now, ::open, { d, h -> add(d, h) }, scroll, hourHeight)
                            }
                        }
                        View.YEAR -> {
                            val pager = rememberPagerState(initialPage = MID + (anchor.year - today.year)) { 2 * MID }
                            Follow(pager) { p -> anchor = (today.year + p - MID).let { y -> if (y == today.year) today else LocalDate.of(y, 1, 1) } }
                            title = "${today.year + pager.currentPage - MID}"
                            subtitle = null
                            todayAction = { scope.launch { pager.animateScrollToPage(MID) } }
                            HorizontalPager(pager, beyondViewportPageCount = 0) { page ->
                                val y = today.year + page - MID
                                val list = occurrences(graph, LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), revision, settings.holidays)
                                YearPage(y, firstDay, list, look) { d ->
                                    anchor = d
                                    view = View.DAY
                                }
                            }
                        }
                        View.DAY -> {
                            val pager = rememberPagerState(initialPage = MID + ChronoUnit.DAYS.between(days, anchor).toInt()) { 2 * MID }
                            Follow(pager) { p -> anchor = days.plusDays((p - MID).toLong()) }
                            val shown = days.plusDays((pager.currentPage - MID).toLong())
                            title = Format.dayTitle(shown, today)
                            subtitle = Format.month(YearMonth.from(shown), today)
                            todayAction = { scope.launch { pager.animateScrollToPage(MID + ChronoUnit.DAYS.between(days, today).toInt()) } }
                            val scroll = rememberScrollState()
                            // Opened at the day's start (08:00), as One Calendar.
                            LaunchedEffect(scroll.maxValue > 0) { if (scroll.value == 0) scroll.scrollTo(with(density) { (hourHeight * settings.dayStart).roundToPx() }) }
                            HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                                val d = days.plusDays((page - MID).toLong())
                                TimeGridPage(listOf(d), occurrences(graph, d, d, revision, settings.holidays), look, now, ::open, { day, h -> add(day, h) }, scroll, hourHeight)
                            }
                        }
                    }
                }
            }
        }
    }

    popup?.let { day ->
        val list = occurrences(graph, day, day, revision, settings.holidays)
        DayPopup(day, Agenda.sortDay(list, day), look, onEvent = ::open, onAdd = {
            popup = null
            add(day)
        }, onDayView = {
            popup = null
            anchor = day
            view = View.DAY
        }, onDismiss = { popup = null })
    }

    when (val top = stack.lastOrNull()) {
        is Screen.Edit -> {
            val state = top.state
            val occ = top.occ
            fun close() {
                stack.removeAll { it is Screen.Edit }
            }
            // A date of a series stays in its calendar unless the whole series is saved; the
            // event's own calendar is offered even when it can't take changes (to show it).
            val choices = if (top.locked) calendars.filter { it.id == state.calendarId } else writable
            // The laptop's suggestions learn from the events already there, read once the editor opens.
            val history by produceState<EventRepository.History?>(null, top) { value = graph.events.history() }
            val aids = EditorAids(
                emoji = settings.emoji,
                emojiFavorites = TitleEmoji.favorites(settings.emojiUse),
                onEmoji = { e -> graph.settings.update { it.copy(emojiUse = TitleEmoji.recordUse(it.emojiUse, e)) } },
                titles = { q ->
                    val h = history
                    if (!settings.suggestions || h == null) emptyList()
                    else {
                        val at = if (state.allDay) TitleSuggestions.floatingDay(state.startDate) else java.time.LocalDateTime.of(state.startDate, state.startTime).atZone(state.zone).toInstant()
                        TitleSuggestions.rank(h.titles, q, at, state.allDay, now, zone)
                    }
                },
                places = { q -> history?.takeIf { settings.suggestions }?.let { LocationSuggestions.rank(it.places, q, now) }.orEmpty() },
                entry = EntryContext(today, firstDay),
                preferredZones = settings.preferredZones,
                timeStep = settings.timeStep,
                eventMinutes = settings.eventMinutes,
                reminderDefaults = if (occ == null) defaults else null,
            )
            EditorScreen(
                state, occ == null, choices, look,
                onCancel = { stack.removeAt(stack.lastIndex) },
                onSave = {
                    val draft = state.draft()
                    fun save(s: Scope) {
                        asking = null
                        scope.launch {
                            if (occ == null) graph.events.create(state.calendarId, draft)
                            else graph.events.update(occ, draft, s, state.calendarId)
                            anchor = draft.startDate
                            close()
                        }
                    }
                    when {
                        occ == null -> save(Scope.ALL)
                        !state.changed() -> close()
                        // "This event" or "All events" is asked when there is something to save.
                        occ.recurring && state.calendarId == occ.calendarId -> asking = "Save" to ::save
                        else -> save(Scope.ALL)
                    }
                },
                locked = top.locked,
                syncProblem = top.problem,
                onDelete = occ?.let { o ->
                    {
                        fun delete(s: Scope) {
                            asking = null
                            scope.launch {
                                undo = graph.events.delete(o, s)
                                close()
                            }
                        }
                        if (o.recurring) asking = "Delete" to ::delete else delete(Scope.ALL)
                    }
                },
                onCopy = {
                    // A new event with the same fields, in a calendar that can take it.
                    val cal = writable.firstOrNull { it.id == state.calendarId }?.id ?: defaultCalendar()
                    if (cal != null) stack += Screen.Edit(EditorState(state.draft().copy(reminders = state.reminders ?: defaults.forNew(state.allDay)), cal), null)
                },
                onShare = occ?.let { o -> { scope.launch { shareEvent(context, o.title, graph.events.shareText(o)) } } },
                onKeepMine = { occ?.let { o -> scope.launch { graph.events.keepMine(o.resourceId); close() } } },
                onKeepTheirs = {
                    occ?.let { o ->
                        scope.launch {
                            runCatching { graph.engine.keepTheirs(o.resourceId) }
                            graph.syncSoon(0)
                            close()
                        }
                    }
                },
                aids = aids,
            )
        }
        Screen.Search -> {
            var query by rememberSaveable { mutableStateOf("") }
            var past by rememberSaveable { mutableStateOf(false) }
            val results by produceState(emptyList<Occurrence>(), query, past, revision) {
                delay(200)
                value = graph.events.search(query, past)
            }
            SearchScreen(query, { query = it }, past, { past = it }, results, look, onBack = { stack.removeAt(stack.lastIndex) }, onEvent = ::open)
        }
        Screen.Options -> OptionsList(onPage = { stack += Screen.Option(it) }, onBack = { stack.removeAt(stack.lastIndex) })
        is Screen.Option -> OptionPageScreen(
            top.page, settings, calendars,
            onChange = { change ->
                val before = graph.settings.value
                graph.settings.update(change)
                val after = graph.settings.value
                if (after.syncMinutes != before.syncMinutes && graph.account.connected) SyncWork.keep(context, after.syncMinutes)
                scope.launch { graph.reminders.rearm() }
            },
            onColor = { id, color -> scope.launch { graph.events.setColor(id, color, graph.settings) } },
            onBack = { stack.removeAt(stack.lastIndex) },
        )
        Screen.Accounts -> {
            var connecting by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val problems by produceState(emptyList<Problem>(), revision) { value = graph.events.problems() }
            val account = AccountView(graph.account.address, graph.account.user, graph.account.connected, status, calendars.count { it.group == EventRepository.SERVER_GROUP })
            AccountsScreen(
                account, problems, look, connecting, error,
                onBack = { stack.removeAt(stack.lastIndex) },
                onConnect = { a, u, p ->
                    connecting = true
                    error = null
                    scope.launch {
                        error = graph.connect(a, u, p)
                        connecting = false
                        if (error == null) SyncWork.keep(context, settings.syncMinutes)
                    }
                },
                onSync = { scope.launch { graph.syncNow() } },
                onSignOut = {
                    scope.launch {
                        graph.signOut()
                        SyncWork.stop(context)
                    }
                },
                onKeepMine = { id -> scope.launch { graph.events.keepMine(id) } },
                onKeepTheirs = { id -> scope.launch { runCatching { graph.engine.keepTheirs(id) } } },
            )
        }
        null -> Unit
    }

    asking?.let { (what, go) -> ScopeDialog(what, { go(Scope.THIS) }, { go(Scope.ALL) }, { asking = null }) }

    // "Deleted" with Undo for a few seconds, as the laptop does.
    undo?.let { u ->
        LaunchedEffect(u) {
            delay(UNDO_MS)
            if (undo === u) undo = null
        }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            Row(
                Modifier.fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).background(ink.sheet).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Deleted", style = type(15, color = ink.text), modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    undo = null
                    scope.launch { graph.events.undo(u) }
                }) { Text("Undo", style = type(15, FontWeight.SemiBold, ink.accent)) }
            }
        }
    }
}

/** How long "Deleted" offers Undo. */
private const val UNDO_MS = 6_000L

/** An event as an .ics file through Android's share sheet (written to the app's cache, shared by its file provider). */
private suspend fun shareEvent(context: android.content.Context, title: String, ics: String) {
    val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val name = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(60).ifEmpty { "event" } + ".ics"
        val file = java.io.File(dir, name)
        file.writeText(ics)
        androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    }
    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/calendar")
        .putExtra(android.content.Intent.EXTRA_STREAM, uri)
        .putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(android.content.Intent.createChooser(send, "Share"))
}

/** Calls [onPage] whenever the pager settles on a page. */
@Composable
private fun Follow(pager: PagerState, onPage: (Int) -> Unit) {
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(onPage) }
}

/** The events from [from] to [to], read again when anything changes; the last ones stay until then. */
@Composable
fun occurrences(graph: Graph, from: LocalDate, to: LocalDate, revision: Long, holidays: Set<String>): List<Occurrence> {
    val state = produceState(emptyList<Occurrence>(), from, to, revision, holidays) { value = graph.events.occurrences(from, to) }
    return state.value
}
