package com.xern.xrncal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The laptop app's title-emoji.test.ts, with the same titles and the same answers. */
class TitleEmojiTest {
    private val all = TitleEmoji.GROUPS.flatMap { it.emojis }

    private fun cps(vararg codePoints: Int): String = codePoints.joinToString("") { String(Character.toChars(it)) }

    // ─── The emojis the picker offers ────────────────────────────────────────────

    @Test
    fun isACompactSetInTheSevenGroupsInTheOrderShown() {
        assertEquals(
            listOf(
                EmojiGroupId.PEOPLE,
                EmojiGroupId.ACTIVITIES,
                EmojiGroupId.FOOD,
                EmojiGroupId.TRAVEL,
                EmojiGroupId.OBJECTS,
                EmojiGroupId.SYMBOLS,
                EmojiGroupId.NATURE,
            ),
            TitleEmoji.GROUPS.map { it.id },
        )
        assertTrue(all.size in 200..300)
        for (group in TitleEmoji.GROUPS) assertTrue(group.id.name, group.emojis.size >= 24)
    }

    @Test
    fun listsEachEmojiOnce() {
        val seen = mutableSetOf<String>()
        assertEquals(emptyList<String>(), all.filter { !seen.add(it) })
    }

    /**
     * A stray space, a missing variation selector (which draws ✈ as a black glyph) or two emojis
     * run together would each show as a broken cell. The laptop holds each to `\p{RGI_Emoji}`, which
     * Java's regex has no name for; here each must be one emoji as [TitleEmoji.leading] reads one,
     * with U+FE0F after every pictograph that is text unless asked (✈, ⛷, ⚕) and nowhere else.
     */
    @Test
    fun writesEachAsExactlyOneFullyQualifiedEmoji() {
        fun qualified(emoji: String): Boolean {
            val cps = emoji.codePoints().toArray()
            return cps.indices.all { i ->
                val cp = cps[i]
                val textByDefault = TitleEmoji.isExtendedPictographic(cp) && !TitleEmoji.isEmojiPresentation(cp)
                when {
                    cp == 0xFE0F -> i > 0 && TitleEmoji.isExtendedPictographic(cps[i - 1]) && !TitleEmoji.isEmojiPresentation(cps[i - 1])
                    textByDefault -> i + 1 < cps.size && cps[i + 1] == 0xFE0F
                    else -> true
                }
            }
        }
        val broken = all.filter { emoji ->
            val found = TitleEmoji.leading(emoji)
            emoji.any { JsText.isSpace(it) } || found == null || found.emoji != emoji || found.rest != "" || !qualified(emoji)
        }
        assertEquals(emptyList<String>(), broken)
    }

    @Test
    fun offersNothingTheTitleFunctionsWouldNotTakeOffAgain() {
        val kept = all.filter { emoji ->
            val found = TitleEmoji.leading("$emoji Lunch")
            found?.emoji != emoji || found.rest != "Lunch"
        }
        assertEquals(emptyList<String>(), kept)
    }

    // ─── leading ─────────────────────────────────────────────────────────────────

    @Test
    fun findsTheEmojiATitleStartsWithAndTheTitleAfterIt() {
        assertEquals(LeadingEmoji("🍴", "Lunch at Pablo's"), TitleEmoji.leading("🍴 Lunch at Pablo's"))
        assertEquals(LeadingEmoji("🎂", "Birthday"), TitleEmoji.leading("🎂Birthday"))
        assertEquals(LeadingEmoji("☕", "Cà phê"), TitleEmoji.leading("  ☕  Cà phê"))
        assertEquals(LeadingEmoji("🎂", ""), TitleEmoji.leading("🎂"))
    }

    @Test
    fun takesAWholeSequenceAsOneEmoji() {
        // A variation selector, a skin tone, people joined into one, a flag, a keycap.
        val sequences = listOf(
            "❤\uFE0F",
            "👍🏽",
            "🧑\u200D💻",
            "👨\u200D👩\u200D👧",
            "🏳\uFE0F\u200D🌈",
            "❤\uFE0F\u200D🔥",
            "🇻🇳",
            "1\uFE0F\u20E3",
            "🏴" + cps(0xE0067, 0xE0062, 0xE0065, 0xE006E, 0xE0067, 0xE007F),
        )
        for (emoji in sequences) assertEquals(emoji, LeadingEmoji(emoji, "Trip"), TitleEmoji.leading("$emoji Trip"))
    }

    @Test
    fun takesOnlyTheFirstOfSeveral() {
        assertEquals(LeadingEmoji("🎉", "🎂 Party"), TitleEmoji.leading("🎉🎂 Party"))
    }

    @Test
    fun findsNoneInATitleThatStartsWithText() {
        assertNull(TitleEmoji.leading("Lunch 🍴"))
        assertNull(TitleEmoji.leading("Lunch"))
        assertNull(TitleEmoji.leading(""))
        assertNull(TitleEmoji.leading("Họp nhóm"))
    }

    @Test
    fun doesNotTakeADigitASignOrATextSymbolForAnEmoji() {
        for (title in listOf("3 things to buy", "#1 priority", "* starred", "© 2026 report", "™ review", "↔ swap desks")) {
            assertNull(title, TitleEmoji.leading(title))
        }
    }

    // ─── withLeading / withoutLeading ────────────────────────────────────────────

    @Test
    fun putsTheEmojiInFrontWithASpace() {
        assertEquals("🍴 Lunch", TitleEmoji.withLeading("Lunch", "🍴"))
        assertEquals("🍴 Lunch", TitleEmoji.withLeading("  Lunch", "🍴"))
    }

    @Test
    fun makesAnEmptyTitleTheEmojiAndASpaceReadyForTheTitle() {
        assertEquals("🍴 ", TitleEmoji.withLeading("", "🍴"))
        assertEquals("🍴 ", TitleEmoji.withLeading("   ", "🍴"))
    }

    @Test
    fun replacesAnEmojiAlreadyLeadingTheTitle() {
        assertEquals("🍴 Lunch", TitleEmoji.withLeading("🥗 Lunch", "🍴"))
        assertEquals("☕ Standup", TitleEmoji.withLeading("🧑\u200D💻Standup", "☕"))
        assertEquals("🍴 ", TitleEmoji.withLeading("🥗 ", "🍴"))
        // Only the leading one: an emoji further in is part of the title.
        assertEquals("🍴 Lunch 🥗", TitleEmoji.withLeading("Lunch 🥗", "🍴"))
    }

    @Test
    fun takesALeadingEmojiOffAndLeavesATitleWithoutOneAlone() {
        assertEquals("Lunch at Pablo's", TitleEmoji.withoutLeading("🍴 Lunch at Pablo's"))
        assertEquals("", TitleEmoji.withoutLeading("🍴"))
        assertEquals("Lunch 🍴", TitleEmoji.withoutLeading("Lunch 🍴"))
        assertEquals(" Lunch", TitleEmoji.withoutLeading(" Lunch"))
    }

    // ─── hasTitleText ────────────────────────────────────────────────────────────

    @Test
    fun hasNoTitleTextUntilSomethingBesidesALeadingEmojiIsThere() {
        assertFalse(TitleEmoji.hasTitleText(""))
        assertFalse(TitleEmoji.hasTitleText("   "))
        // What the emoji button leaves in an empty field.
        assertFalse(TitleEmoji.hasTitleText("🍴 "))
        assertFalse(TitleEmoji.hasTitleText("🧑\u200D💻"))
    }

    @Test
    fun hasTitleTextForTextWithOrWithoutAnEmojiInFront() {
        assertTrue(TitleEmoji.hasTitleText("L"))
        assertTrue(TitleEmoji.hasTitleText("🍴 L"))
        assertTrue(TitleEmoji.hasTitleText("🍴L"))
        // A second emoji is something typed.
        assertTrue(TitleEmoji.hasTitleText("🍴 🥗"))
    }

    // ─── fromSuggestion ──────────────────────────────────────────────────────────

    @Test
    fun isTheSuggestionWhenNoEmojiWasPicked() {
        assertEquals("Lunch at Pablo's", TitleEmoji.fromSuggestion("lun", "Lunch at Pablo's"))
    }

    @Test
    fun keepsAnEmojiPickedBeforeTypingInFrontOfASuggestionThatHasNone() {
        assertEquals("🍴 Lunch at Pablo's", TitleEmoji.fromSuggestion("🍴 lun", "Lunch at Pablo's"))
    }

    @Test
    fun leavesASuggestionsOwnEmojiAlone() {
        assertEquals("🥗 Lunch with Sam", TitleEmoji.fromSuggestion("🍴 lun", "🥗 Lunch with Sam"))
        assertEquals("🥗 Lunch with Sam", TitleEmoji.fromSuggestion("lun", "🥗 Lunch with Sam"))
    }

    // ─── The emojis picked most ──────────────────────────────────────────────────

    private fun pick(setting: String, vararg emojis: String): String =
        emojis.fold(setting) { stored, emoji -> TitleEmoji.recordUse(stored, emoji) }

    @Test
    fun startsEmpty() {
        assertEquals(emptyList<String>(), TitleEmoji.favorites(""))
        assertEquals(emptyList<String>(), TitleEmoji.favorites(null))
    }

    @Test
    fun countsEachPickInOnePlainString() {
        val stored = pick("", "🍴", "🎂", "🍴")
        assertEquals("🍴:2 🎂:1", stored)
        assertEquals("🧑\u200D💻:1 🍴:2 🎂:1", pick(stored, "🧑\u200D💻"))
    }

    @Test
    fun listsTheMostPickedFirstTheLatestPickFirstAmongEquals() {
        val stored = pick("", "☕", "🍴", "🎂", "🍴", "⚽")
        assertEquals(listOf("🍴", "⚽", "🎂", "☕"), TitleEmoji.favorites(stored))
    }

    @Test
    fun showsAtMostSixteen() {
        val stored = pick("", *all.take(30).toTypedArray())
        assertEquals(TitleEmoji.FAVORITES_SHOWN, TitleEmoji.favorites(stored).size)
        assertEquals(16, TitleEmoji.favorites(stored).size)
        assertEquals(4, TitleEmoji.favorites(stored, 4).size)
        // The latest of thirty single picks.
        assertEquals(all[29], TitleEmoji.favorites(stored)[0])
    }

    @Test
    fun remembersABoundedNumberDroppingTheOnePickedLeastAndLongestAgo() {
        // Sixty different emojis: the first is picked three times, the rest once.
        var stored = pick("", all[0], all[0], all[0])
        stored = pick(stored, *all.subList(1, 60).toTypedArray())
        val kept = stored.split(' ')
        assertEquals(48, kept.size)
        assertEquals("${all[59]}:1", kept[0])
        assertTrue(stored.contains("${all[0]}:3"))
        // The earliest single picks are the ones that went.
        assertFalse(stored.contains("${all[1]}:"))
        assertEquals(all[0], TitleEmoji.favorites(stored)[0])
    }

    @Test
    fun ignoresAnythingInTheSettingThatIsNotAnOfferedEmojiWithACount() {
        val stored = "🍴:2 <b>:9 lunch:3 🎂 🎂:0 ☕:x ⚽:1.5 🍴:7 :4 🏁:3 🎉:2"
        assertEquals(listOf("🍴", "🎉"), TitleEmoji.favorites(stored))
        assertEquals(emptyList<String>(), TitleEmoji.favorites(42))
        assertEquals(emptyList<String>(), TitleEmoji.favorites(null))
        // Recording rewrites it clean.
        assertEquals("🎉:3 🍴:2", TitleEmoji.recordUse(stored, "🎉"))
    }

    @Test
    fun doesNotRecordAnEmojiThePickerDoesNotOffer() {
        assertEquals("🍴:2", TitleEmoji.recordUse("🍴:2", "x"))
        assertEquals("🍴:2", TitleEmoji.recordUse("🍴:2", "🍴:9 🎂"))
    }

    @Test
    fun stopsCountingAtANumberThatStaysShort() {
        assertEquals("🍴:9999", TitleEmoji.recordUse("🍴:9999", "🍴"))
        assertEquals(listOf("🍴"), TitleEmoji.favorites("🍴:123456789"))
    }
}
