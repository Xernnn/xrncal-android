package com.xern.xrncal.domain

/** A place handed on to a maps app (One Calendar's "Navigation"). */
object Places {
    /**
     * `geo:0,0?q=<place>`, Android's "show this place" for whichever maps app the owner has; the
     * place's spaces and line breaks as single spaces, everything but letters, digits and `-_.~`
     * percent-encoded as UTF-8. Null for an empty place.
     */
    fun geoUri(location: String): String? {
        val q = location.trim().replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() } ?: return null
        return "geo:0,0?q=" + encode(q)
    }

    private fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c.toChar() in "-_.~") append(c.toChar())
            else append('%').append("%02X".format(c))
        }
    }
}
