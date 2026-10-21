package com.weartak.plugin.sextant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SextantLabelsTest {
    private fun point(name: String, floor: Int) =
        SextantPoint(name + floor, name, floor, null, null, null, null, "none", null, 0L, mutableListOf())

    @Test fun floorLabelIsTwoDigitsWithoutL() {
        assertEquals("00", floorLabel(0))
        assertEquals("06", floorLabel(6))
        assertEquals("08", floorLabel(8))
        assertEquals("09", floorLabel(9))
        assertEquals("10", floorLabel(10))
        assertEquals("11", floorLabel(11))
        assertEquals("-01", floorLabel(-1))
        assertEquals("-12", floorLabel(-12))
    }

    @Test fun duplicateRoomNamesGetFloorSuffix() {
        val s6 = point("Stairs", 6); val s7 = point("stairs", 7); val k = point("Kitchen", 1)
        val a = SextantAddress("a", "Home", mutableListOf(s6, s7, k))
        assertEquals("Stairs 06", roomLabel(a, s6))
        assertEquals("stairs 07", roomLabel(a, s7))
        assertEquals("Kitchen", roomLabel(a, k))
    }

    @Test fun roomOrderIsNameThenFloor() {
        val pts = listOf(point("Stairs", 8), point("kitchen", 1), point("Stairs", 6), point("Attic", 3))
        assertEquals(listOf("Attic 3", "kitchen 1", "Stairs 6", "Stairs 8"),
            pts.sortedWith(ROOM_ORDER).map { "${it.name} ${it.floor}" })
    }

    @Test fun presetsIncludeOfficeAndParkingAlphabetically() {
        assertTrue("Office" in ROOM_PRESETS)
        assertTrue("Parking" in ROOM_PRESETS)
        assertEquals(ROOM_PRESETS.sortedWith(String.CASE_INSENSITIVE_ORDER), ROOM_PRESETS)
        assertEquals(ROOM_PRESETS.size, ROOM_PRESETS.toSet().size)
    }
}
