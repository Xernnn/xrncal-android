package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xern.xrncal.SyncStatus
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.data.Clock
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.Problem
import com.xern.xrncal.data.Settings
import com.xern.xrncal.data.View
import com.xern.xrncal.domain.EventLength
import com.xern.xrncal.domain.Occurrence
import com.xern.xrncal.domain.Reminders
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

// ---------------- drawer ----------------

/**
 * The drawer: the views (List, Month, Week, Day), Search, Sync and Settings, then each calendar
 * with its colour and a switch, by account, and the holiday calendars.
 */
@Composable
fun DrawerContent(
    view: View,
    calendars: List<CalendarInfo>,
    serverName: String,
    syncing: Boolean,
    onView: (View) -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onSettings: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxHeight().width(300.dp).background(ink.sheet).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        DrawerItem(Icons.AutoMirrored.Outlined.ViewList, "List", view == View.LIST) { onView(View.LIST) }
        DrawerItem(Icons.Outlined.CalendarMonth, "Month", view == View.MONTH) { onView(View.MONTH) }
        DrawerItem(Icons.Outlined.CalendarViewWeek, "Week", view == View.WEEK) { onView(View.WEEK) }
        DrawerItem(Icons.Outlined.CalendarViewDay, "Day", view == View.DAY) { onView(View.DAY) }
        HorizontalDivider(color = ink.line, modifier = Modifier.padding(vertical = 6.dp))
        DrawerItem(Icons.Outlined.Search, "Search", false, onSearch)
        DrawerItem(Icons.Outlined.Sync, if (syncing) "Syncing" else "Sync", false, onSync)
        DrawerItem(Icons.Outlined.Settings, "Settings", false, onSettings)
        for ((group, title) in listOf(EventRepository.SERVER_GROUP to serverName, EventRepository.LOCAL_GROUP to "Phone", EventRepository.HOLIDAY_GROUP to "Holidays")) {
            val list = calendars.filter { it.group == group }
            if (list.isEmpty()) continue
            HorizontalDivider(color = ink.line, modifier = Modifier.padding(vertical = 6.dp))
            Text(title, style = type(13, FontWeight.Medium, ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 4.dp, end = 16.dp))
            for (c in list) {
                Row(Modifier.fillMaxWidth().clickable { onToggle(c.id, !c.shown) }.padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(Color(c.color), 16.dp)
                    Spacer(Modifier.width(14.dp))
                    Text(c.name, style = type(15, color = ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Switch(checked = c.shown, onCheckedChange = { onToggle(c.id, it) }, colors = SwitchDefaults.colors(checkedTrackColor = Color(c.color)))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp).clip(RoundedCornerShape(24.dp))
            .background(if (selected) ink.accentTint else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) ink.accent else ink.muted)
        Spacer(Modifier.width(20.dp))
        Text(label, style = type(16, FontWeight.Medium, if (selected) ink.accent else ink.text))
    }
}

// ---------------- search ----------------

@Composable
fun SearchScreen(query: String, onQuery: (String) -> Unit, results: List<Occurrence>, look: Look, onBack: () -> Unit, onEvent: (Occurrence) -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().background(ink.page)) {
        Row(Modifier.fillMaxWidth().background(ink.bar).statusBarsPadding().padding(end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(Icons.AutoMirrored.Filled.ArrowBack, onBack)
            Field(query, onQuery, Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(results, key = { it.resourceId + it.start }) { o -> EventRow(o, look, { onEvent(o) }, showDate = true) }
        }
    }
}

// ---------------- settings ----------------

/** The account as Settings shows it. */
data class AccountView(val address: String, val user: String, val connected: Boolean, val status: SyncStatus, val calendars: Int)

@Composable
fun SettingsScreen(
    s: Settings,
    account: AccountView,
    problems: List<Problem>,
    calendars: List<CalendarInfo>,
    look: Look,
    connecting: Boolean,
    connectError: String?,
    onBack: () -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onConnect: (String, String, String) -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
    onColor: (String, Int) -> Unit = { _, _ -> },
) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().background(ink.page)) {
        Row(Modifier.fillMaxWidth().background(ink.accent).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(Icons.AutoMirrored.Filled.ArrowBack, onBack, Color.White)
            Text("Settings", style = type(20, FontWeight.SemiBold, Color.White), modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Section("CalDAV account")
            var address by remember(account.address) { mutableStateOf(account.address) }
            var user by remember(account.user) { mutableStateOf(account.user) }
            var password by remember { mutableStateOf("") }
            SmallLabel("Server")
            Field(address, { address = it }, Modifier.padding(horizontal = 16.dp))
            SmallLabel("User name")
            Field(user, { user = it }, Modifier.padding(horizontal = 16.dp))
            SmallLabel("Password")
            Field(password, { password = it }, Modifier.padding(horizontal = 16.dp), password = true)
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onConnect(address, user, password) }, enabled = !connecting && address.isNotBlank() && user.isNotBlank()) {
                    Text(if (connecting) "Connecting" else "Connect", style = type(15, FontWeight.SemiBold, ink.accent))
                }
                if (account.connected) {
                    TextButton(onClick = onSync, enabled = !account.status.running) { Text("Sync now", style = type(15, FontWeight.SemiBold, ink.accent)) }
                    TextButton(onClick = onSignOut) { Text("Sign out", style = type(15, FontWeight.SemiBold, ink.warn)) }
                }
            }
            val line = when {
                connectError != null -> connectError
                !account.connected -> null
                account.status.running -> "Syncing"
                account.status.error.isNotEmpty() -> account.status.error
                account.status.lastSync > 0 -> "Synced " + Format.dayShort(Instant.ofEpochMilli(account.status.lastSync).atZone(look.zone).toLocalDate()) + " " +
                    Format.time(Instant.ofEpochMilli(account.status.lastSync).atZone(look.zone).toLocalTime(), look.h24) + " · ${account.calendars} calendars"
                else -> null
            }
            if (line != null) Text(line, style = type(14, color = if (connectError != null || (account.status.error.isNotEmpty() && !account.status.running)) ink.warn else ink.muted), modifier = Modifier.padding(horizontal = 20.dp))

            if (problems.isNotEmpty()) {
                Section("Not sent")
                for (p in problems) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
                        Text(p.title, style = type(16, FontWeight.Medium, ink.text))
                        Text(p.calendar + " · " + problemLabel(p.problem), style = type(13, color = ink.warn))
                        Row {
                            TextButton(onClick = { onKeepMine(p.id) }) { Text("Keep mine", style = type(14, FontWeight.Medium, ink.accent)) }
                            TextButton(onClick = { onKeepTheirs(p.id) }) { Text("Keep theirs", style = type(14, FontWeight.Medium, ink.accent)) }
                        }
                    }
                }
            }

            Section("Calendar colours")
            CalendarColors(calendars, onColor)

            Section("Calendar")
            val weekStarts = listOf(null to "Phone", DayOfWeek.MONDAY to "Monday", DayOfWeek.SUNDAY to "Sunday", DayOfWeek.SATURDAY to "Saturday")
            Choose("Week starts", weekStarts.first { it.first == s.weekStart }.second, weekStarts.map { it.second }) { i -> onChange { it.copy(weekStart = weekStarts[i].first) } }
            val views = listOf(View.MONTH to "Month", View.LIST to "List", View.WEEK to "Week", View.DAY to "Day")
            Choose("Opens on", views.first { it.first == s.defaultView }.second, views.map { it.second }) { i -> onChange { it.copy(defaultView = views[i].first) } }
            val writable = calendars.filter { !it.readOnly }
            Choose("New events in", writable.firstOrNull { it.id == s.defaultCalendar }?.name ?: writable.firstOrNull()?.name.orEmpty(), writable.map { it.name }) { i -> onChange { it.copy(defaultCalendar = writable[i].id) } }
            Choose("New event length", "${s.eventMinutes} min", EventLength.OPTIONS.map { "$it min" }) { i -> onChange { it.copy(eventMinutes = EventLength.OPTIONS[i]) } }
            val timed = listOf<Int?>(null) + Reminders.TIMED_PRESETS
            Choose("Reminder", s.timedReminder?.let { Reminders.label(it, false) } ?: "None", timed.map { m -> m?.let { Reminders.label(it, false) } ?: "None" }) { i -> onChange { it.copy(timedReminder = timed[i]) } }
            val allDay = listOf<Int?>(null) + Reminders.ALL_DAY_PRESETS.sorted()
            Choose("All-day reminder", s.allDayReminder?.let { Reminders.label(it, true) } ?: "None", allDay.map { m -> m?.let { Reminders.label(it, true) } ?: "None" }) { i -> onChange { it.copy(allDayReminder = allDay[i]) } }
            val clocks = listOf(Clock.SYSTEM to "Phone", Clock.H24 to "24-hour", Clock.H12 to "12-hour")
            Choose("Clock", clocks.first { it.first == s.clock }.second, clocks.map { it.second }) { i -> onChange { it.copy(clock = clocks[i].first) } }
            Toggle("Week numbers", s.weekNumbers) { on -> onChange { it.copy(weekNumbers = on) } }
            Toggle("Lunar dates", s.lunar) { on -> onChange { it.copy(lunar = on) } }
            Text(ZoneId.systemDefault().id, style = type(13, color = ink.muted), modifier = Modifier.padding(start = 20.dp, top = 16.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    val ink = LocalInk.current
    Text(title, style = type(14, FontWeight.SemiBold, ink.accent), modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 4.dp))
}

@Composable
private fun SmallLabel(text: String) {
    Text(text, style = type(13, color = LocalInk.current.muted), modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
private fun Choose(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    val ink = LocalInk.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text(label, style = type(16, color = ink.text), modifier = Modifier.weight(1f))
            Text(value, style = type(16, color = ink.muted))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o, style = type(15)) }, onClick = {
                    open = false
                    onPick(i)
                })
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = type(16, color = ink.text), modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = ink.accent))
    }
}

/**
 * One Calendar's Calendar colors page: every calendar as a bar in its colour, by account, the
 * holiday calendars last; tapping one opens the colour picker.
 */
@Composable
fun CalendarColors(calendars: List<CalendarInfo>, onColor: (String, Int) -> Unit) {
    val ink = LocalInk.current
    var picking by remember { mutableStateOf<CalendarInfo?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        for (c in calendars) {
            val bg = Color(c.color)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(bg).clickable { picking = c }.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.name, style = type(16, FontWeight.Medium, onColor(bg)), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = "Change colour of ${c.name}", tint = onColor(bg))
            }
        }
    }
    picking?.let { c ->
        ColorPickerDialog(c.name, c.color, null, onPick = { picked ->
            if (picked != null) onColor(c.id, picked)
            picking = null
        }, onDismiss = { picking = null })
    }
}
