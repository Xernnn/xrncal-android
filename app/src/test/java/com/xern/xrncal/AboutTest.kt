package com.xern.xrncal

import com.xern.xrncal.ui.OptionPage
import com.xern.xrncal.ui.aboutLines
import org.junit.Assert.assertEquals
import org.junit.Test

/** Options, About: the version and the app id, figures only (docs/spec.md, "Other apps"). */
class AboutTest {
    @Test
    fun aboutIsTheLastOptionAndShowsTheVersionAndTheAppId() {
        assertEquals(OptionPage.ABOUT, OptionPage.entries.last())
        assertEquals(listOf("Version" to "1.4.0 (12)", "App id" to "com.example.cal"), aboutLines("1.4.0", 12, "com.example.cal"))
        // What the page reads: the build's own version, and the id the phone knows the app by.
        assertEquals("com.xern.xrncal", BuildConfig.APPLICATION_ID)
        assertEquals("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", aboutLines(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.APPLICATION_ID).first().second)
    }
}
