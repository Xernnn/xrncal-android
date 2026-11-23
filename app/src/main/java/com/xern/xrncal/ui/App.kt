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
    data class Detail(val occ: Occurrence) : Screen
    data class Edit(val state: EditorState, val occ: Occurrence?, val scope: Scope) : Screen
    data object Search : Screen
    data object Settings : Screen
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
    val stack = remember { mutableStateListOf<Screen>() }
    var view by rememberSaveable { mutableStateOf(settings.defaultView) }
    var anchor by remember { mutableStateOf(today) }
    var popup by remember { mutableStateOf<LocalDate?>(null) }
    var asking by remember { mutableStateOf<Pair<String, (Scope) -> Unit>?>(null) }
    var confirmDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val writable = calendars.filter { !it.readOnly && HolidayCalendar.of(it.id) == null }
    val defaults = Reminders.Defaults(settings.timedReminder, settings.allDayReminder)

    fun defaultCalendar(): String? = writable.firstOrNull { it.id == settings.defaultCalendar }?.id
        ?: writable.firstOrNull { it.group == EventRepository.SERVER_GROUP }?.id ?: writable.firstOrNull()?.id

    fun add(day: LocalDate, hour: Int? = null) {
        val cal = defaultCalendar() ?: return
        stack += Screen.Edit(EditorState(newDraft(day, hour, now, zone, settings.eventMinutes, defaults.forNew(false)), cal), null, Scope.ALL)
    }

    fun open(o: Occurrence) {
        popup = null
        stack += Screen.Detail(o)
    }

    LaunchedEffect(Unit) {
        graph.open.collect { (id, start) ->
            graph.events.find(id, start)?.let {
                stack.clear()
                stack += Screen.Detail(it)
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
    var todayAction by remember { mutableStateOf<() -> Unit>({}) }

    LocalBackdrop.current()
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = stack.isEmpty(),
        drawerContent = {
            DrawerContent(
                view = view,
                calendars = calendars,
                serverName = graph.account.address.let { a -> runCatching { java.net.URI(a.trim()).host }.getOrNull() ?: a }.ifBlank { "CalDAV" },
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
                onSettings = {
                    stack += Screen.Settings
                    scope.launch { drawer.close() }
                },
                onToggle = { id, on -> scope.launch { graph.events.setShown(id, on, graph.settings) } },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(ink.bar).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(Icons.Filled.Menu, { scope.launch { drawer.open() } })
                Text(title, style = type(24, color = ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
                BarIcon(Icons.Filled.Add, { add(if (view == View.DAY || YearMonth.from(anchor) != YearMonth.from(today)) anchor else today) })
                TodayButton(today.dayOfMonth) { todayAction() }
            }
            Box(Modifier.fillMaxSize().navigationBarsPadding()) {
                key(view, firstDay) {
                    when (view) {
                        View.MONTH -> {
                            val pager = rememberPagerState(initialPage = MID + ChronoUnit.MONTHS.between(months, YearMonth.from(anchor)).toInt()) { 2 * MID }
                            Follow(pager) { p -> anchor = months.plusMonths((p - MID).toLong()).let { if (it == YearMonth.from(today)) today else it.atDay(1) } }
                            title = Format.month(months.plusMonths((pager.currentPage - MID).toLong()), today)
                            todayAction = { scope.launch { pager.animateScrollToPage(MID + ChronoUnit.MONTHS.between(months, YearMonth.from(today)).toInt()) } }
                            HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                                val ym = months.plusMonths((page - MID).toLong())
                                val grid = MonthLayout.weeks(ym, firstDay)
                                val list = occurrences(graph, grid.first().first(), grid.last().last(), revision, settings.holidays)
                                MonthPage(ym, firstDay, list, look, onDay = { popup = it }, onEvent = ::open)
                            }
                        }
                        View.LIST -> {
                            val pager = rememberPagerState(initialPage = MID + ChronoUnit.MONTHS.between(months, YearMonth.from(anchor)).toInt()) { 2 * MID }
                            val ym = months.plusMonths((pager.currentPage - MID).toLong())
                            title = Format.month(ym, today)
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
                            todayAction = { scope.launch { pager.animateScrollToPage(MID + ChronoUnit.WEEKS.between(weeks, WeekStart.startOfWeek(today, firstDay)).toInt()) } }
                            val scroll = rememberScrollState()
                            LaunchedEffect(scroll.maxValue > 0) { if (scroll.value == 0) scroll.scrollTo((scroll.maxValue * 7 / 17f).toInt()) }
                            HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                                val d0 = weeks.plusWeeks((page - MID).toLong())
                                val week = (0L until 7L).map { d0.plusDays(it) }
                                TimeGridPage(week, occurrences(graph, week.first(), week.last(), revision, settings.holidays), look, now, ::open, { d, h -> add(d, h) }, scroll)
                            }
                        }
                        View.YEAR -> Unit
                        View.DAY -> {
                            val pager = rememberPagerState(initialPage = MID + ChronoUnit.DAYS.between(days, anchor).toInt()) { 2 * MID }
                            Follow(pager) { p -> anchor = days.plusDays((p - MID).toLong()) }
                            title = Format.month(YearMonth.from(days.plusDays((pager.currentPage - MID).toLong())), today)
                            todayAction = { scope.launch { pager.animateScrollToPage(MID + ChronoUnit.DAYS.between(days, today).toInt()) } }
                            val scroll = rememberScrollState()
                            LaunchedEffect(scroll.maxValue > 0) { if (scroll.value == 0) scroll.scrollTo((scroll.maxValue * 7 / 17f).toInt()) }
                            HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                                val d = days.plusDays((page - MID).toLong())
                                TimeGridPage(listOf(d), occurrences(graph, d, d, revision, settings.holidays), look, now, ::open, { day, h -> add(day, h) }, scroll)
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
        }, onDismiss = { popup = null })
    }

    when (val top = stack.lastOrNull()) {
        is Screen.Detail -> DetailRoute(
            graph, top.occ, look, revision, defaults,
            onBack = { stack.removeAt(stack.lastIndex) },
            onEdit = { o ->
                val go = { s: Scope ->
                    asking = null
                    scope.launch {
                        val draft = graph.events.draft(o, s) ?: return@launch
                        stack += Screen.Edit(EditorState(draft, o.calendarId), o, s)
                    }
                    Unit
                }
                if (o.recurring) asking = "Edit" to go else go(Scope.ALL)
            },
            onDelete = { o ->
                val go = { s: Scope ->
                    asking = null
                    confirmDelete = null
                    scope.launch {
                        graph.events.delete(o, s)
                        stack.removeAll { it is Screen.Detail }
                    }
                    Unit
                }
                if (o.recurring) asking = "Delete" to go else confirmDelete = { go(Scope.ALL) }
            },
            onKeepMine = { id -> scope.launch { graph.events.keepMine(id) } },
            onKeepTheirs = { id ->
                scope.launch {
                    runCatching { graph.engine.keepTheirs(id) }
                    graph.syncSoon(0)
                }
            },
        )
        is Screen.Edit -> {
            val state = top.state
            // One date of a series stays in its calendar; a whole event can move to another.
            val choices = if (top.occ != null && top.occ.recurring && top.scope == Scope.THIS) writable.filter { it.id == state.calendarId } else writable
            EditorScreen(state, top.occ == null, choices, look, defaults, onCancel = { stack.removeAt(stack.lastIndex) }, onSave = {
                val draft = state.draft()
                scope.launch {
                    if (top.occ == null) graph.events.create(state.calendarId, draft)
                    else graph.events.update(top.occ, draft, top.scope, state.calendarId)
                    anchor = draft.startDate
                    stack.removeAll { it is Screen.Edit || it is Screen.Detail }
                }
            })
        }
        Screen.Search -> {
            var query by rememberSaveable { mutableStateOf("") }
            val results by produceState(emptyList<Occurrence>(), query, revision) {
                delay(200)
                value = graph.events.search(query)
            }
            SearchScreen(query, { query = it }, results, look, onBack = { stack.removeAt(stack.lastIndex) }, onEvent = ::open)
        }
        Screen.Settings -> {
            var connecting by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            val problems by produceState(emptyList<Problem>(), revision) { value = graph.events.problems() }
            val account = AccountView(graph.account.address, graph.account.user, graph.account.connected, status, calendars.count { it.group == EventRepository.SERVER_GROUP })
            SettingsScreen(
                settings, account, problems, calendars, look, connecting, error,
                onBack = { stack.removeAt(stack.lastIndex) },
                onChange = { change ->
                    graph.settings.update(change)
                    scope.launch { graph.reminders.rearm() }
                },
                onConnect = { a, u, p ->
                    connecting = true
                    error = null
                    scope.launch {
                        error = graph.connect(a, u, p)
                        connecting = false
                        if (error == null) SyncWork.keep(context)
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
                onColor = { id, color -> scope.launch { graph.events.setColor(id, color, graph.settings) } },
            )
        }
        null -> Unit
    }

    asking?.let { (what, go) -> ScopeDialog(what, { go(Scope.THIS) }, { go(Scope.ALL) }, { asking = null }) }
    confirmDelete?.let { go ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete?", style = type(18, FontWeight.SemiBold, ink.text)) },
            confirmButton = { TextButton(onClick = go) { Text("Delete", style = type(14, FontWeight.Medium, ink.warn)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel", style = type(14, FontWeight.Medium, ink.accent)) } },
        )
    }
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

@Composable
private fun DetailRoute(
    graph: Graph,
    occ: Occurrence,
    look: Look,
    revision: Long,
    defaults: Reminders.Defaults,
    onBack: () -> Unit,
    onEdit: (Occurrence) -> Unit,
    onDelete: (Occurrence) -> Unit,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
) {
    val holiday = HolidayCalendar.of(occ.calendarId) != null
    val info by produceState(DetailInfo(null, null, null, emptyList(), null), occ, revision) {
        val cal = graph.events.calendars().firstOrNull { it.id == occ.calendarId }
        val row = if (holiday) null else graph.events.resource(occ.resourceId)
        val master = if (holiday) null else graph.events.parsed(occ.resourceId)?.master(occ.uid)
        val start = master?.start
        val zone = when {
            start == null || start.isDate -> null
            start.utc -> ZoneOffset.UTC
            else -> Zones.ianaFor(start.tzid ?: "")
        }
        val reminders = if (holiday) emptyList() else Reminders.effective(occ.reminders)
        value = DetailInfo(cal, zone, master?.rrule, reminders, row?.problem)
    }
    val editable = !holiday && occ.calendarId !in look.readOnly
    DetailScreen(
        occ, info, look, onBack,
        onEdit = if (editable) ({ onEdit(occ) }) else null,
        onDelete = if (editable) ({ onDelete(occ) }) else null,
        onKeepMine = { onKeepMine(occ.resourceId) },
        onKeepTheirs = { onKeepTheirs(occ.resourceId) },
    )
}
