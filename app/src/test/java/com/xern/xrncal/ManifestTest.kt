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
        )
        needed.forEach { (permission, why) -> assertTrue("$permission, for $why", permission in asked) }
    }

    /** The receiver hears what it acts on: the alarm is explicit, the rest are system broadcasts. */
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

    @Test
    fun nothingLeavesThePhoneInABackup() {
        val app = named("application").single()
        assertEquals("false", app.getAttributeNS(ANDROID, "allowBackup"))
    }

    private companion object {
        const val ANDROID = "http://schemas.android.com/apk/res/android"
    }
}
