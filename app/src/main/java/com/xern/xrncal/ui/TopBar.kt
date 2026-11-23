package com.xern.xrncal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xern.xrncal.data.CalendarInfo
import com.xern.xrncal.data.EventRepository
import com.xern.xrncal.data.View

/**
 * One Calendar's top bar: the menu, the period's title with its subtitle under it ("week 48" in
 * the week, the month in the day; tapping it zooms out to the month), then `+` (a menu with "Add
 * appointment"), the filter (the calendars, each with a checkbox in its colour) and Today (today's
 * date in a calendar glyph).
 */
@Composable
fun TopBar(
    title: String,
    subtitle: String?,
    today: Int,
    calendars: List<CalendarInfo>,
    serverName: String,
    onMenu: () -> Unit,
    onSubtitle: () -> Unit,
    onAdd: () -> Unit,
    onToday: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    val ink = LocalInk.current
    var adding by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(ink.bar).statusBarsPadding().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
        BarIcon(Icons.Filled.Menu, onMenu, description = "Menu")
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(title, style = type(22, color = ink.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = type(14, color = ink.muted),
                    maxLines = 1,
                    modifier = Modifier.clickable(onClickLabel = "Month", role = Role.Button, onClick = onSubtitle),
                )
            }
        }
        Box {
            BarIcon(Icons.Filled.Add, { adding = true }, description = "Add")
            DropdownMenu(expanded = adding, onDismissRequest = { adding = false }) {
                DropdownMenuItem(text = { Text("Add appointment", style = type(16)) }, onClick = {
                    adding = false
                    onAdd()
                })
            }
        }
        Box {
            BarIcon(Icons.Filled.FilterList, { filtering = true }, description = "Filter")
            DropdownMenu(expanded = filtering, onDismissRequest = { filtering = false }) {
                FilterList(calendars, serverName, onToggle)
            }
        }
        TodayButton(today, onToday)
    }
}

/**
 * One Calendar's filter: every calendar with a checkbox in its colour, by account (the server's,
 * the phone's), the holiday calendars last. It stays open while boxes are ticked.
 */
@Composable
fun FilterList(calendars: List<CalendarInfo>, serverName: String, onToggle: (String, Boolean) -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.widthIn(min = 220.dp, max = 300.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        var first = true
        for ((group, title) in listOf(EventRepository.SERVER_GROUP to serverName, EventRepository.LOCAL_GROUP to "Phone", EventRepository.HOLIDAY_GROUP to "Holidays")) {
            val list = calendars.filter { it.group == group }
            if (list.isEmpty()) continue
            if (!first) HorizontalDivider(color = ink.line)
            first = false
            Text(title, style = type(12, FontWeight.Medium, ink.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
            for (c in list) {
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onToggle(c.id, !c.shown) }.padding(start = 4.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = c.shown,
                        onCheckedChange = { onToggle(c.id, it) },
                        colors = CheckboxDefaults.colors(checkedColor = Color(c.color), uncheckedColor = Color(c.color), checkmarkColor = onColor(Color(c.color))),
                        modifier = Modifier.semantics { contentDescription = c.name },
                    )
                    Text(c.name, style = type(15, color = ink.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** One Calendar's drawer: the views, then Search, then Sync, then Accounts and Options. */
@Composable
fun DrawerContent(
    view: View,
    syncing: Boolean,
    onView: (View) -> Unit,
    onSearch: () -> Unit,
    onSync: () -> Unit,
    onAccounts: () -> Unit,
    onOptions: () -> Unit,
) {
    val ink = LocalInk.current
    Column(Modifier.fillMaxHeight().width(280.dp).background(ink.sheet).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(8.dp))
        DrawerItem(Icons.AutoMirrored.Outlined.ViewList, "List", view == View.LIST) { onView(View.LIST) }
        DrawerItem(Icons.Outlined.CalendarMonth, "Month", view == View.MONTH) { onView(View.MONTH) }
        DrawerItem(Icons.Outlined.CalendarViewWeek, "Week", view == View.WEEK) { onView(View.WEEK) }
        DrawerItem(Icons.Outlined.CalendarViewDay, "Day", view == View.DAY) { onView(View.DAY) }
        DrawerItem(Icons.Outlined.GridView, "Year", view == View.YEAR) { onView(View.YEAR) }
        HorizontalDivider(color = ink.line, modifier = Modifier.padding(vertical = 6.dp))
        DrawerItem(Icons.Outlined.Search, "Search", false, onSearch)
        HorizontalDivider(color = ink.line, modifier = Modifier.padding(vertical = 6.dp))
        DrawerItem(Icons.Outlined.Sync, if (syncing) "Syncing" else "Sync", false, onSync)
        HorizontalDivider(color = ink.line, modifier = Modifier.padding(vertical = 6.dp))
        DrawerItem(Icons.Outlined.AccountCircle, "Accounts", false, onAccounts)
        DrawerItem(Icons.Outlined.Settings, "Options", false, onOptions)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) ink.accentTint else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) ink.accent else ink.muted)
        Spacer(Modifier.width(22.dp))
        Text(label, style = type(16, FontWeight.Medium, if (selected) ink.accent else ink.text))
    }
}
