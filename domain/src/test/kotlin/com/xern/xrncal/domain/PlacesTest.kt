package com.xern.xrncal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An event's place sent to the maps app as a `geo:` address (docs/spec.md, "Other apps"). */
class PlacesTest {
    @Test
    fun aPlaceGoesToTheMapsAppEncoded() {
        assertEquals("geo:0,0?q=12%20Made-up%20Street%2C%20District%203", Places.geoUri("12 Made-up Street, District 3"))
        assertEquals("geo:0,0?q=Ph%E1%BB%9F%20Made-up%20%26%20Co", Places.geoUri("Phở Made-up & Co"))
        assertEquals("geo:0,0?q=Level%202%20Made-up%20Hall", Places.geoUri("  Level 2\nMade-up   Hall "))
        assertNull(Places.geoUri("   "))
    }
}
