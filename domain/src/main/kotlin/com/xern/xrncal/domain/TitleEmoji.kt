package com.xern.xrncal.domain

import kotlin.math.floor

/** The emoji picker's groups, in the order shown. */
enum class EmojiGroupId { PEOPLE, ACTIVITIES, FOOD, TRAVEL, OBJECTS, SYMBOLS, NATURE }

data class EmojiGroup(val id: EmojiGroupId, val emojis: List<String>)

/** The emoji a title starts with, and the title after it. */
data class LeadingEmoji(val emoji: String, val rest: String)

/**
 * An emoji in front of an event's title: "🍴 Lunch at Pablo's" (the laptop app's title-emoji.ts).
 *
 * The emoji is part of the title, not a field of its own, so it syncs to every provider, shows
 * wherever the title shows and needs no migration. What this knows is how to find one leading a
 * title, put one there or take it off, which emojis the editor's picker offers, and which of them
 * the user picks most.
 */
object TitleEmoji {
    private fun each(row: String): List<String> = row.split(' ')

    /**
     * What the picker offers: a compact set for a calendar (meals, travel, sport, appointments,
     * birthdays), not all of Unicode, the same as the laptop's. Written out as characters so the
     * app carries no emoji data package; all of them are from Unicode 13 or earlier. The tests hold
     * each to one fully-qualified emoji, once.
     */
    val GROUPS: List<EmojiGroup> = listOf(
        EmojiGroup(
            EmojiGroupId.PEOPLE,
            each(
                "😀 😄 😊 🙂 😉 😍 🥰 😎 🤔 😴 😷 🥳 😭 😱 🙏 👍 👏 🙌 💪 👋 🤝 ✌️ 👀 👶 🧒 👦 👧 🧑 👨 👩 👴 👵 👪 👫 🧑‍💻 🧑‍🏫 🧑‍⚕️ 🧑‍🍳 💇 💆",
            ),
        ),
        EmojiGroup(
            EmojiGroupId.ACTIVITIES,
            each(
                "⚽ 🏀 ⚾ 🎾 🏐 🏓 🏸 🥊 ⛳ 🏹 🎣 🎿 ⛷️ 🏋️ 🤸 🧘 🏃 🚶 🚴 🏊 🏄 🚣 🧗 🏆 🥇 🏅 🎯 🎮 🎲 🧩 🎨 🎭 🎤 🎧 🎸 🎹 🥁 🎻 🎬 🎳",
            ),
        ),
        EmojiGroup(
            EmojiGroupId.FOOD,
            each(
                "🍎 🍌 🍊 🍉 🍇 🍓 🍍 🥑 🥕 🥦 🍅 🥗 🍞 🥐 🧀 🍳 🍔 🍟 🍕 🌮 🍜 🍲 🍛 🍣 🍱 🥟 🍚 🍝 🍦 🍰 🎂 🍪 🍫 🍩 ☕ 🍵 🧋 🥤 🍺 🍻 🍷 🥂 🍹 🍴",
            ),
        ),
        EmojiGroup(
            EmojiGroupId.TRAVEL,
            each(
                "🚗 🚕 🚌 🚑 🚚 🚲 🛵 🏍️ 🚆 🚄 🚇 ✈️ 🛫 🚁 🚀 ⛵ 🚢 ⛽ 🚦 🗺️ 🧭 🧳 🏠 🏡 🏢 🏫 🏥 🏦 🏨 🏪 ⛪ 🕌 ⛩️ 🏛️ 🏖️ ⛰️ 🏕️ ⛺ 🗼 🎡 🎢 🌉",
            ),
        ),
        EmojiGroup(
            EmojiGroupId.OBJECTS,
            each(
                "📱 💻 ⌨️ 🖥️ 📷 ⏰ ⌚ 📅 🗓️ 📌 📍 📝 ✏️ 📚 📖 📦 ✉️ 📧 📞 💡 🔋 🔑 🔒 🔧 🧰 🧹 🛒 🛏️ 🛁 🪑 💊 💉 🩺 🦷 👓 👔 👟 🎒 🎁 🎈 🎉 💰 💳 🧾",
            ),
        ),
        EmojiGroup(
            EmojiGroupId.SYMBOLS,
            each(
                "❤️ 🧡 💛 💚 💙 💜 💔 ⭐ ✨ ⚡ 🔥 💧 ❗ ❓ ‼️ ✅ ❌ 🚫 ⚠️ ♻️ 💯 🔔 🔕 🎵 🎶 ➕ ✔️ 🔴 🟠 🟡 🟢 🔵 🟣 ⚫ ⚪ 🆕 🆘 💤 💬 🔁",
            ),
        ),
        EmojiGroup(
            EmojiGroupId.NATURE,
            each(
                "☀️ ⛅ ☁️ 🌧️ ⛈️ ❄️ 🌈 ☔ 🌙 🌊 🌍 🌱 🌿 🍀 🍂 🍁 🌳 🌴 🌵 🌷 🌹 🌺 🌸 🌻 💐 🐶 🐱 🐰 🐻 🐼 🦁 🐮 🐷 🐸 🐔 🐧 🐦 🐴 🦋 🐝 🐢 🐟 🐬 🐘",
            ),
        ),
    )

    private val OFFERED: Set<String> = GROUPS.flatMap { it.emojis }.toSet()

    // ─── An emoji leading a title ────────────────────────────────────────────────

    // Unicode 17.0's Extended_Pictographic and Emoji_Presentation, as first..last pairs. Java's
    // regex has no names for them before Java 21 (and Android's none of its own), so they are here.
    private val EXTENDED_PICTOGRAPHIC = intArrayOf(
        0xA9, 0xA9, 0xAE, 0xAE, 0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
        0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x2328, 0x23CF, 0x23CF, 0x23E9, 0x23F3,
        0x23F8, 0x23FA, 0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0, 0x25FB, 0x25FE,
        0x2600, 0x2604, 0x260E, 0x260E, 0x2611, 0x2611, 0x2614, 0x2615, 0x2618, 0x2618, 0x261D, 0x261D,
        0x2620, 0x2620, 0x2622, 0x2623, 0x2626, 0x2626, 0x262A, 0x262A, 0x262E, 0x262F, 0x2638, 0x263A,
        0x2640, 0x2640, 0x2642, 0x2642, 0x2648, 0x2653, 0x265F, 0x2660, 0x2663, 0x2663, 0x2665, 0x2666,
        0x2668, 0x2668, 0x267B, 0x267B, 0x267E, 0x267F, 0x2692, 0x2697, 0x2699, 0x2699, 0x269B, 0x269C,
        0x26A0, 0x26A1, 0x26A7, 0x26A7, 0x26AA, 0x26AB, 0x26B0, 0x26B1, 0x26BD, 0x26BE, 0x26C4, 0x26C5,
        0x26C8, 0x26C8, 0x26CE, 0x26CF, 0x26D1, 0x26D1, 0x26D3, 0x26D4, 0x26E9, 0x26EA, 0x26F0, 0x26F5,
        0x26F7, 0x26FA, 0x26FD, 0x26FD, 0x2702, 0x2702, 0x2705, 0x2705, 0x2708, 0x270D, 0x270F, 0x270F,
        0x2712, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716, 0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728,
        0x2733, 0x2734, 0x2744, 0x2744, 0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E, 0x2753, 0x2755,
        0x2757, 0x2757, 0x2763, 0x2764, 0x2795, 0x2797, 0x27A1, 0x27A1, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2934, 0x2935, 0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x3030, 0x3030,
        0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299, 0x1F004, 0x1F004, 0x1F02C, 0x1F02F, 0x1F094, 0x1F09F,
        0x1F0AF, 0x1F0B0, 0x1F0C0, 0x1F0C0, 0x1F0CF, 0x1F0D0, 0x1F0F6, 0x1F0FF, 0x1F170, 0x1F171, 0x1F17E, 0x1F17F,
        0x1F18E, 0x1F18E, 0x1F191, 0x1F19A, 0x1F1AE, 0x1F1E5, 0x1F201, 0x1F20F, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F,
        0x1F232, 0x1F23A, 0x1F23C, 0x1F23F, 0x1F249, 0x1F25F, 0x1F266, 0x1F321, 0x1F324, 0x1F393, 0x1F396, 0x1F397,
        0x1F399, 0x1F39B, 0x1F39E, 0x1F3F0, 0x1F3F3, 0x1F3F5, 0x1F3F7, 0x1F3FA, 0x1F400, 0x1F4FD, 0x1F4FF, 0x1F53D,
        0x1F549, 0x1F54E, 0x1F550, 0x1F567, 0x1F56F, 0x1F570, 0x1F573, 0x1F57A, 0x1F587, 0x1F587, 0x1F58A, 0x1F58D,
        0x1F590, 0x1F590, 0x1F595, 0x1F596, 0x1F5A4, 0x1F5A5, 0x1F5A8, 0x1F5A8, 0x1F5B1, 0x1F5B2, 0x1F5BC, 0x1F5BC,
        0x1F5C2, 0x1F5C4, 0x1F5D1, 0x1F5D3, 0x1F5DC, 0x1F5DE, 0x1F5E1, 0x1F5E1, 0x1F5E3, 0x1F5E3, 0x1F5E8, 0x1F5E8,
        0x1F5EF, 0x1F5EF, 0x1F5F3, 0x1F5F3, 0x1F5FA, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CB, 0x1F6D2, 0x1F6D5, 0x1F6E5,
        0x1F6E9, 0x1F6E9, 0x1F6EB, 0x1F6F0, 0x1F6F3, 0x1F6FF, 0x1F7DA, 0x1F7FF, 0x1F80C, 0x1F80F, 0x1F848, 0x1F84F,
        0x1F85A, 0x1F85F, 0x1F888, 0x1F88F, 0x1F8AE, 0x1F8AF, 0x1F8BC, 0x1F8BF, 0x1F8C2, 0x1F8CF, 0x1F8D9, 0x1F8FF,
        0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA58, 0x1FA5F, 0x1FA6E, 0x1FAFF, 0x1FC00, 0x1FFFD,
    )
    private val EMOJI_PRESENTATION = intArrayOf(
        0x231A, 0x231B, 0x23E9, 0x23EC, 0x23F0, 0x23F0, 0x23F3, 0x23F3, 0x25FD, 0x25FE, 0x2614, 0x2615,
        0x2648, 0x2653, 0x267F, 0x267F, 0x2693, 0x2693, 0x26A1, 0x26A1, 0x26AA, 0x26AB, 0x26BD, 0x26BE,
        0x26C4, 0x26C5, 0x26CE, 0x26CE, 0x26D4, 0x26D4, 0x26EA, 0x26EA, 0x26F2, 0x26F3, 0x26F5, 0x26F5,
        0x26FA, 0x26FA, 0x26FD, 0x26FD, 0x2705, 0x2705, 0x270A, 0x270B, 0x2728, 0x2728, 0x274C, 0x274C,
        0x274E, 0x274E, 0x2753, 0x2755, 0x2757, 0x2757, 0x2795, 0x2797, 0x27B0, 0x27B0, 0x27BF, 0x27BF,
        0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55, 0x1F004, 0x1F004, 0x1F0CF, 0x1F0CF, 0x1F18E, 0x1F18E,
        0x1F191, 0x1F19A, 0x1F1E6, 0x1F1FF, 0x1F201, 0x1F201, 0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F236,
        0x1F238, 0x1F23A, 0x1F250, 0x1F251, 0x1F300, 0x1F320, 0x1F32D, 0x1F335, 0x1F337, 0x1F37C, 0x1F37E, 0x1F393,
        0x1F3A0, 0x1F3CA, 0x1F3CF, 0x1F3D3, 0x1F3E0, 0x1F3F0, 0x1F3F4, 0x1F3F4, 0x1F3F8, 0x1F43E, 0x1F440, 0x1F440,
        0x1F442, 0x1F4FC, 0x1F4FF, 0x1F53D, 0x1F54B, 0x1F54E, 0x1F550, 0x1F567, 0x1F57A, 0x1F57A, 0x1F595, 0x1F596,
        0x1F5A4, 0x1F5A4, 0x1F5FB, 0x1F64F, 0x1F680, 0x1F6C5, 0x1F6CC, 0x1F6CC, 0x1F6D0, 0x1F6D2, 0x1F6D5, 0x1F6D8,
        0x1F6DC, 0x1F6DF, 0x1F6EB, 0x1F6EC, 0x1F6F4, 0x1F6FC, 0x1F7E0, 0x1F7EB, 0x1F7F0, 0x1F7F0, 0x1F90C, 0x1F93A,
        0x1F93C, 0x1F945, 0x1F947, 0x1F9FF, 0x1FA70, 0x1FA7C, 0x1FA80, 0x1FA8A, 0x1FA8E, 0x1FAC6, 0x1FAC8, 0x1FAC8,
        0x1FACD, 0x1FADC, 0x1FADF, 0x1FAEA, 0x1FAEF, 0x1FAF8,
    )

    private fun inRanges(ranges: IntArray, cp: Int): Boolean {
        var lo = 0
        var hi = ranges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            when {
                cp < ranges[2 * mid] -> hi = mid - 1
                cp > ranges[2 * mid + 1] -> lo = mid + 1
                else -> return true
            }
        }
        return false
    }

    internal fun isExtendedPictographic(cp: Int): Boolean = inRanges(EXTENDED_PICTOGRAPHIC, cp)

    internal fun isEmojiPresentation(cp: Int): Boolean = inRanges(EMOJI_PRESENTATION, cp)

    /** A skin tone. */
    private fun isModifier(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    private fun isRegionalIndicator(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF

    private const val VS16 = 0xFE0F
    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3

    /** Past the variation selectors and skin tones from [i]. */
    private fun tail(s: String, from: Int): Int {
        var i = from
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp != VS16 && !isModifier(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    /**
     * Where the one emoji starting at [start] ends, or null when none starts there. One emoji as
     * people type them, which is wider than the picker's set: titles come from phones and other
     * calendars too.
     *
     * - a flag: two regional indicators
     * - a keycap: 1️⃣, #️⃣
     * - a pictograph, with what may follow it: a variation selector, a skin tone, the tags of a
     *   subdivision flag, and further pictographs joined to it (👩‍💻, ❤️‍🔥)
     *
     * A character that is only an emoji when asked to be (©, ™, ↔, a digit) counts when it carries
     * the selector that asks, U+FE0F, and not otherwise: "© 2026 report" does not start with an
     * emoji.
     */
    private fun emojiEnd(s: String, start: Int): Int? {
        if (start >= s.length) return null
        val first = s.codePointAt(start)
        var i = start + Character.charCount(first)
        val next = if (i < s.length) s.codePointAt(i) else -1
        if (isRegionalIndicator(first) && isRegionalIndicator(next)) return i + Character.charCount(next)
        if (first in '0'.code..'9'.code || first == '#'.code || first == '*'.code) {
            var k = i
            if (k < s.length && s[k].code == VS16) k++
            return if (k < s.length && s[k].code == KEYCAP) k + 1 else null
        }
        val pictograph = isEmojiPresentation(first) ||
            (isExtendedPictographic(first) && (next == VS16 || isModifier(next) || next == ZWJ))
        if (!pictograph) return null
        i = tail(s, i)
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp !in 0xE0020..0xE007F) break
            i += Character.charCount(cp)
        }
        while (i + 1 < s.length && s[i].code == ZWJ) {
            val cp = s.codePointAt(i + 1)
            if (!isExtendedPictographic(cp)) break
            i = tail(s, i + 1 + Character.charCount(cp))
        }
        return i
    }

    /** The emoji a title starts with and the title after it, or null when it starts with none. */
    fun leading(title: String): LeadingEmoji? {
        var start = 0
        while (start < title.length && JsText.isSpace(title[start])) start++
        val end = emojiEnd(title, start) ?: return null
        var rest = end
        while (rest < title.length && JsText.isSpace(title[rest])) rest++
        return LeadingEmoji(title.substring(start, end), title.substring(rest))
    }

    /** The title without the emoji it starts with (as it was when it starts with none). */
    fun withoutLeading(title: String): String = leading(title)?.rest ?: title

    /**
     * Whether anything has been typed in a title field: an emoji picked in front of the title is
     * not typing. The title suggestions wait for this, so picking an emoji does not bring their list
     * up over an otherwise empty field.
     */
    fun hasTitleText(title: String): Boolean = JsText.trim(withoutLeading(title)).isNotEmpty()

    /**
     * The title with [emoji] in front, a space after it, in place of an emoji that was leading it
     * already. An empty title becomes the emoji and the space, so the next thing typed is the title.
     */
    fun withLeading(title: String, emoji: String): String = "$emoji ${JsText.trimStart(withoutLeading(title))}"

    /**
     * The title a suggestion gives the field. A suggestion replaces what was typed; an emoji picked
     * before typing is not something typed, so it stays in front - unless the suggestion comes with
     * an emoji of its own.
     */
    fun fromSuggestion(typed: String, suggested: String): String {
        val picked = leading(typed)
        if (picked == null || leading(suggested) != null) return suggested
        return withLeading(suggested, picked.emoji)
    }

    // ─── The emojis picked most ──────────────────────────────────────────────────

    /** How many the picker's "frequently used" row shows. */
    const val FAVORITES_SHOWN = 16

    /**
     * How many are remembered: enough that one outside the row can work its way in. Past that, the
     * one picked least goes (the longest ago among equals).
     */
    private const val FAVORITES_KEPT = 48

    /** A count large enough to be "always", small enough to stay a short number. */
    private const val MAX_COUNT = 9999

    private data class EmojiUse(val emoji: String, val count: Int)

    /**
     * The favourites setting read back: "🍴:5 🎂:2", most recently picked first. A setting holds
     * only plain values, hence one string. Anything in it that is not an emoji the picker offers with
     * a count is left out - the row is drawn from this, and the setting is whatever was last written.
     */
    private fun parseUses(setting: Any?): List<EmojiUse> {
        if (setting !is String) return emptyList()
        val uses = mutableListOf<EmojiUse>()
        for (entry in setting.split(' ')) {
            val colon = entry.lastIndexOf(':')
            if (colon <= 0) continue
            val emoji = entry.substring(0, colon)
            val count = JsText.number(entry.substring(colon + 1))
            if (emoji !in OFFERED || !count.isFinite() || floor(count) != count || count < 1) continue
            if (uses.any { it.emoji == emoji }) continue
            uses += EmojiUse(emoji, minOf(count, MAX_COUNT.toDouble()).toInt())
        }
        return uses
    }

    /** The emojis picked before, most used first (the latest pick first among equals). */
    fun favorites(setting: Any?, limit: Int = FAVORITES_SHOWN): List<String> =
        // The stored order is latest first, and the sort is stable.
        parseUses(setting).sortedByDescending { it.count }.take(limit.coerceAtLeast(0)).map { it.emoji }

    /** The setting after [emoji] was picked once more. One the picker does not offer changes nothing. */
    fun recordUse(setting: Any?, emoji: String): String {
        val uses = parseUses(setting)
        if (emoji !in OFFERED) return serialize(uses)
        val before = uses.firstOrNull { it.emoji == emoji }
        val next = mutableListOf(EmojiUse(emoji, minOf((before?.count ?: 0) + 1, MAX_COUNT)))
        next += uses.filter { it.emoji != emoji }
        while (next.size > FAVORITES_KEPT) {
            // Never the one just picked, which is first.
            var drop = next.size - 1
            for (i in drop - 1 downTo 1) if (next[i].count < next[drop].count) drop = i
            next.removeAt(drop)
        }
        return serialize(next)
    }

    private fun serialize(uses: List<EmojiUse>): String = uses.joinToString(" ") { "${it.emoji}:${it.count}" }
}
