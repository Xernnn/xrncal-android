package com.xern.xrncal.domain

/**
 * The colours calendars and events are given: One Calendar's choices, which are Google Calendar's
 * (its eleven event colours, then its calendar colours), so a colour picked here looks the same in
 * One Calendar, Google and the laptop app. Each is written to CalDAV as hex: a calendar's as Apple's
 * `calendar-color`, an event's as RFC 7986 `COLOR`.
 */
object Palette {
    data class Swatch(val name: String, val argb: Int) {
        /** `#rrggbb`, as the laptop app writes an event's COLOR. */
        val hex: String get() = hexOf(argb)
    }

    private fun s(name: String, rgb: Long) = Swatch(name, (0xFF000000 or rgb).toInt())

    /** Google's event colours: the ones an event can take whichever calendar it is in. */
    val EVENT = listOf(
        s("Tomato", 0xD50000), s("Flamingo", 0xE67C73), s("Tangerine", 0xF4511E), s("Banana", 0xF6BF26),
        s("Sage", 0x33B679), s("Basil", 0x0B8043), s("Peacock", 0x039BE5), s("Blueberry", 0x3F51B5),
        s("Lavender", 0x7986CB), s("Grape", 0x8E24AA), s("Graphite", 0x616161),
    )

    /** Google's further calendar colours. */
    val CALENDAR_EXTRA = listOf(
        s("Cherry blossom", 0xD81B60), s("Radicchio", 0xAD1457), s("Pumpkin", 0xEF6C00), s("Mango", 0xF09300),
        s("Citron", 0xE4C441), s("Avocado", 0xC0CA33), s("Pistachio", 0x7CB342), s("Eucalyptus", 0x009688),
        s("Cobalt", 0x4285F4), s("Wisteria", 0xB39DDB), s("Amethyst", 0x9E69AF), s("Cocoa", 0x795548), s("Birch", 0xA79B8E),
    )

    /** What the colour pickers offer, in the order they show it. */
    val ALL: List<Swatch> = EVENT + CALENDAR_EXTRA

    fun named(argb: Int): Swatch? = ALL.firstOrNull { it.argb or 0xFF000000.toInt() == argb or 0xFF000000.toInt() }

    /** A calendar with no colour on the server gets one of these, in turn. */
    fun pick(i: Int): Int = ALL[Math.floorMod(i, ALL.size)].argb

    /** `#rrggbb`, lower case, as the laptop writes an event's COLOR. */
    fun hexOf(argb: Int): String = "#%06x".format(argb and 0xFFFFFF)

    /** `#RRGGBBFF`, as Apple and DAVx⁵ write a calendar's `calendar-color`. */
    fun calendarColor(argb: Int): String = "#%06X".format(argb and 0xFFFFFF) + "FF"
}
