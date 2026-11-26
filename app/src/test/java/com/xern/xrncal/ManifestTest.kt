package com.xern.xrncal

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest asks for every permission a feature needs. The unit tests stand in fakes for the
 * platform, so a missing one would only show on the phone (as USE_BIOMETRIC did for xrnnote).
 */
class ManifestTest {
    private val manifest by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
    }

    private fun named(tag: String): List<org.w3c.dom.Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }

    private val asked: Set<String> by lazy { named("uses-permission").map { it.getAttributeNS(ANDROID, "name") }.toSet() }

    @Test
    fun everyFeatureHasItsPermission() {
        val needed = mapOf(
            "android.permission.INTERNET" to "CalDAV sync (data/Dav.kt)",
            "android.permission.USE_EXACT_ALARM" to "reminders at their minute (work/ReminderAlarms.kt)",
            "android.permission.SCHEDULE_EXACT_ALARM" to "reminders at their minute on Android 12",
            "android.permission.POST_NOTIFICATIONS" to "reminders as notifications",
            "android.permission.RECEIVE_BOOT_COMPLETED" to "the reminder alarm and a sync after a reboot",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "Options' Battery row (work/OemAutostart.kt)",
        )
        needed.forEach { (permission, why) -> assertTrue("$permission, for $why", permission in asked) }
    }

    /** The receiver hears what it acts on: the alarm is explicit, the rest are system broadcasts. */
    @Test
    fun autostartOffOpensItsSwitchFromAPageOfThisAppsOwn() {
        val page = activity(".work.AutostartActivity")
        assertEquals("false", page.getAttributeNS(ANDROID, "exported"))
        assertEquals(true, com.xern.xrncal.work.OemAutostart.opAllows(android.app.AppOpsManager.MODE_ALLOWED))
        assertEquals(false, com.xern.xrncal.work.OemAutostart.opAllows(android.app.AppOpsManager.MODE_IGNORED))
        assertEquals(null, com.xern.xrncal.work.OemAutostart.opAllows(android.app.AppOpsManager.MODE_DEFAULT))
    }

    @Test
    fun theReminderReceiverHearsTheMomentsTheAlarmIsSetAgain() {
        val receiver = named("receiver").single { it.getAttributeNS(ANDROID, "name") == ".work.ReminderReceiver" }
        assertEquals("false", receiver.getAttributeNS(ANDROID, "exported"))
        val actions = receiver.getElementsByTagName("action").let { n -> (0 until n.length).map { (n.item(it) as org.w3c.dom.Element).getAttributeNS(ANDROID, "name") } }
        assertEquals(
            setOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED", "android.intent.action.TIME_SET", "android.intent.action.TIMEZONE_CHANGED"),
            actions.toSet(),
        )
    }

    /** Share hands out only the .ics files it writes, to the app shared with, read once. */
    @Test
    fun theShareProviderGivesOutOnlyTheSharedFolder() {
        val provider = named("provider").single { it.getAttributeNS(ANDROID, "name") == "androidx.core.content.FileProvider" }
        assertEquals("com.xern.xrncal.files", provider.getAttributeNS(ANDROID, "authorities"))
        assertEquals("false", provider.getAttributeNS(ANDROID, "exported"))
        assertEquals("true", provider.getAttributeNS(ANDROID, "grantUriPermissions"))
        val paths = File("src/main/res/xml/shared_files.xml").readText()
        assertTrue(paths, paths.contains("<cache-path name=\"shared\" path=\"shared/\" />") && !paths.contains("root-path") && !paths.contains("files-path"))
    }

    /** Each widget's receiver hears the launcher's update and names its provider file, which exists. */
    @Test
    fun everyWidgetHasItsReceiverAndProvider() {
        for ((name, info) in listOf(".work.AgendaWidget" to "widget_agenda_info", ".work.MonthWidget" to "widget_month_info", ".work.DateWidget" to "widget_date_info")) {
            val receiver = named("receiver").single { it.getAttributeNS(ANDROID, "name") == name }
            val actions = receiver.getElementsByTagName("action").let { n -> (0 until n.length).map { (n.item(it) as org.w3c.dom.Element).getAttributeNS(ANDROID, "name") } }
            assertEquals(name, listOf("android.appwidget.action.APPWIDGET_UPDATE"), actions)
            val meta = receiver.getElementsByTagName("meta-data").item(0) as org.w3c.dom.Element
            assertEquals("android.appwidget.provider", meta.getAttributeNS(ANDROID, "name"))
            assertEquals("@xml/$info", meta.getAttributeNS(ANDROID, "resource"))
            assertTrue(info, File("src/main/res/xml/$info.xml").readText().contains("<appwidget-provider"))
        }
    }

    /** An activity's intent filters: their actions, categories and data, each attribute's values. */
    private data class Filter(val actions: Set<String>, val categories: Set<String>, val data: Map<String, Set<String>>) {
        fun of(attribute: String): Set<String> = data[attribute].orEmpty()
    }

    private fun activity(name: String) = named("activity").single { it.getAttributeNS(ANDROID, "name") == name }

    private fun filters(activity: String): List<Filter> {
        val nodes = activity(activity).getElementsByTagName("intent-filter")
        return (0 until nodes.length).map { i ->
            val f = nodes.item(i) as org.w3c.dom.Element
            fun names(tag: String) = f.getElementsByTagName(tag).let { n -> (0 until n.length).map { (n.item(it) as org.w3c.dom.Element).getAttributeNS(ANDROID, "name") }.toSet() }
            val data = HashMap<String, MutableSet<String>>()
            val d = f.getElementsByTagName("data")
            for (j in 0 until d.length) {
                val attrs = (d.item(j) as org.w3c.dom.Element).attributes
                for (k in 0 until attrs.length) {
                    val a = attrs.item(k)
                    if (a.namespaceURI == ANDROID) data.getOrPut(a.localName) { mutableSetOf() } += a.nodeValue
                }
            }
            Filter(names("action"), names("category"), data)
        }
    }

    /**
     * Other apps' "add to calendar" finds xrncal's editor: CalendarContract's insert intent, and the
     * older edit form, for the events folder and one event. Exported, and not singleTask, so it runs
     * in the asking app's task and ✓ goes back there.
     */
    @Test
    fun otherAppsCanAddAnEvent() {
        val add = activity(".AddEventActivity")
        assertEquals("true", add.getAttributeNS(ANDROID, "exported"))
        assertTrue(add.getAttributeNS(ANDROID, "launchMode").isEmpty())
        val f = filters(".AddEventActivity").single { "android.intent.action.INSERT" in it.actions }
        assertEquals(setOf("android.intent.action.INSERT", "android.intent.action.EDIT"), f.actions)
        assertEquals(setOf("android.intent.category.DEFAULT"), f.categories)
        assertEquals(setOf("vnd.android.cursor.dir/event", "vnd.android.cursor.item/event"), f.of("mimeType"))
    }

    /**
     * An .ics file opens in xrncal by its type (the three calendars are sent with), or by its name
     * when it comes untyped or as plain bytes; from a content address only. No app can hand
     * another a file: address since Android 7, and taking one let any app have xrncal open a path
     * in xrncal's own storage.
     */
    @Test
    fun icsFilesOpenByTypeOrName() {
        val views = filters(".AddEventActivity").filter { "android.intent.action.VIEW" in it.actions }
        views.forEach {
            assertEquals(setOf("android.intent.action.VIEW"), it.actions)
            assertEquals(setOf("android.intent.category.DEFAULT"), it.categories)
            assertEquals(setOf("content"), it.of("scheme"))
        }
        val typed = views.single { "text/calendar" in it.of("mimeType") }
        assertEquals(setOf("text/calendar", "text/x-vcalendar", "application/ics"), typed.of("mimeType"))
        val named = views.filter { ".ics" in it.of("pathSuffix") }
        assertEquals(setOf(emptySet(), setOf("application/octet-stream")), named.map { it.of("mimeType") }.toSet())
        named.forEach {
            assertEquals(setOf(".ics", ".ICS"), it.of("pathSuffix"))
            assertEquals(setOf("*"), it.of("host"))
        }
    }

    /** A file is read with the grant its app hands over: no storage permission, nor the phone's calendar store. */
    @Test
    fun noStorageOrCalendarPermissionIsAsked() {
        val none = setOf(
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED", "android.permission.READ_CALENDAR", "android.permission.WRITE_CALENDAR",
        )
        assertEquals(emptySet<String>(), asked intersect none)
    }

    /**
     * A clock widget's "open the calendar at this time" reaches the calendar: typed as the calendar
     * provider types it (time/epoch), or untyped by its path.
     */
    @Test
    fun timeLinksOpenTheCalendar() {
        val views = filters(".MainActivity").filter { "android.intent.action.VIEW" in it.actions }
        assertEquals(2, views.size)
        views.forEach {
            assertEquals(setOf("android.intent.category.DEFAULT"), it.categories)
            assertEquals(setOf("content"), it.of("scheme"))
            assertEquals(setOf("com.android.calendar"), it.of("host"))
        }
        assertEquals(setOf(setOf("time/epoch"), emptySet()), views.map { it.of("mimeType") }.toSet())
        assertEquals(setOf("/time"), views.single { it.of("mimeType").isEmpty() }.of("pathPrefix"))
        assertEquals("singleTask", activity(".MainActivity").getAttributeNS(ANDROID, "launchMode"))
    }

    @Test
    fun nothingLeavesThePhoneInABackup() {
        val app = named("application").single()
        assertEquals("false", app.getAttributeNS(ANDROID, "allowBackup"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
