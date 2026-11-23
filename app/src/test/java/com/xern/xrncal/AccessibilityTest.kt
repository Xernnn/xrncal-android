package com.xern.xrncal

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every icon button says what it does (docs/spec.md, phase 2 item 14): TalkBack reads it, and the
 * tests and the phone's UI dump can find it. 0.1.0's had no names.
 */
class AccessibilityTest {
    private val ui = File("src/main/java/com/xern/xrncal/ui").listFiles()!!.filter { it.extension == "kt" }

    @Test
    fun everyIconButtonIsNamed() {
        for (f in ui) {
            f.readLines().forEachIndexed { i, line ->
                if ("IconButton(" in line) {
                    assertTrue("${f.name}:${i + 1} $line", "contentDescription = \"" in line || "contentDescription = description" in line)
                }
            }
        }
    }

    @Test
    fun aBarIconCannotBeLeftUnnamed() {
        val bar = File("src/main/java/com/xern/xrncal/ui/Components.kt").readText()
        assertTrue(bar.contains("fun BarIcon(icon: ImageVector, onClick: () -> Unit, tint: Color = LocalInk.current.text, description: String)"))
        for (f in ui) {
            Regex("""BarIcon\(([^\n]*)\)""").findAll(f.readText()).forEach { m ->
                if (!m.value.startsWith("BarIcon(icon")) assertTrue("${f.name}: ${m.value}", m.groupValues[1].count { it == '"' } >= 2)
            }
        }
    }
}
