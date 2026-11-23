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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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

// ---------------- search ----------------

/**
 * One Calendar's search: its Future and Past tabs over the field, each match at its next date
 * (Future, soonest first) or its last one (Past, latest first), over the wallpaper.
 */
@Composable
fun SearchScreen(query: String, onQuery: (String) -> Unit, past: Boolean, onPast: (Boolean) -> Unit, results: List<Occurrence>, look: Look, onBack: () -> Unit, onEvent: (Occurrence) -> Unit) {
    val ink = LocalInk.current
    Box(Modifier.fillMaxSize()) { LocalBackdrop.current() }
    Column(Modifier.fillMaxSize().background(ink.page)) {
        Column(Modifier.fillMaxWidth().background(ink.bar).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                BarIcon(Icons.AutoMirrored.Filled.ArrowBack, onBack, description = "Back")
                Text("Search", style = type(22, color = ink.text), modifier = Modifier.padding(start = 10.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                for ((label, isPast) in listOf("FUTURE" to false, "PAST" to true)) {
                    val on = past == isPast
                    Column(Modifier.weight(1f).clickable { onPast(isPast) }.semantics { selected = on }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, style = type(14, FontWeight.Medium, if (on) ink.text else ink.muted), modifier = Modifier.padding(vertical = 10.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) ink.text else Color.Transparent))
                    }
                }
            }
        }
        Field(query, onQuery, Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        LazyColumn(Modifier.fillMaxSize().navigationBarsPadding()) {
            items(results, key = { it.resourceId + it.start }) { o -> EventRow(o, look, { onEvent(o) }, showDate = true) }
        }
    }
}

// ---------------- settings ----------------

/** The account as Settings shows it. */
data class AccountView(val address: String, val user: String, val connected: Boolean, val status: SyncStatus, val calendars: Int)

/**
 * The events that couldn't be sent, each with Keep mine and Keep theirs, and the CalDAV account
 * (One Calendar's Accounts): address, user, password, Connect, Sync now, Sign out, the last sync.
 */
@Composable
fun AccountsScreen(
    account: AccountView,
    problems: List<Problem>,
    look: Look,
    connecting: Boolean,
    connectError: String?,
    onBack: () -> Unit,
    onConnect: (String, String, String) -> Unit,
    onSync: () -> Unit,
    onSignOut: () -> Unit,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
) = OptionsFrame("Accounts", onBack) {
    val ink = LocalInk.current
    Section("CalDAV")
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
}

/** An Options page: One Calendar's blue bar with back and the page's name, then its rows. */
@Composable
fun OptionsFrame(title: String, onBack: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxSize().background(ink.screen)) {
        Row(Modifier.fillMaxWidth().background(ink.accent).statusBarsPadding().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            BarIcon(Icons.AutoMirrored.Filled.ArrowBack, onBack, Color.White, "Back")
            Text(title, style = type(20, FontWeight.Medium, Color.White), modifier = Modifier.padding(start = 8.dp))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp), content = content)
    }
}

@Composable
fun Section(title: String) {
    val ink = LocalInk.current
    Text(title, style = type(14, FontWeight.SemiBold, ink.accent), modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 4.dp))
}

@Composable
fun SmallLabel(text: String) {
    Text(text, style = type(13, color = LocalInk.current.muted), modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 4.dp))
}

@Composable
fun Choose(label: String, value: String, options: List<String>, onPick: (Int) -> Unit) {
    val ink = LocalInk.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = type(16, color = ink.text))
                Text(value, style = type(14, color = ink.muted))
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = ink.muted)
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
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
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
