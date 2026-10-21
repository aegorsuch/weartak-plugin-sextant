package com.weartak.plugin.sextant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SextantEngineTest {
    private fun point(name: String, floor: Int, hpa: Float? = null, vararg scans: Map<String, Int>) =
        SextantPoint(name, name, floor, hpa, 41.0, -87.0, null, "manual", null, 0L, scans.toMutableList())

    private fun store() = File(SextantEngine.dir(), "sextant.json")

    @Before fun setUp() {
        SextantEngine.init(RuntimeEnvironment.getApplication())
        store().delete()
        SextantEngine.reloadForTest()
    }

    @Test fun renameAddressTrimsAndIgnoresBlank() {
        val a = SextantEngine.addAddress("Address 1")
        SextantEngine.renameAddress(a, "  125 S Jefferson St  ")
        assertEquals("125 S Jefferson St", a.name)
        SextantEngine.renameAddress(a, "   ")
        assertEquals("125 S Jefferson St", a.name)
    }

    @Test fun loadMigratesLongJeffersonName() {
        val root = JSONObject().put("addresses", JSONArray().put(
            JSONObject().put("id", "a1").put("name", "125 South Jefferson Street").put("points", JSONArray())))
        store().writeText(root.toString())
        SextantEngine.reloadForTest()
        assertEquals("125 S Jefferson St", SextantEngine.address("a1")?.name)
        assertTrue(store().readText().contains("125 S Jefferson St"))
    }

    @Test fun rescanReplacesScansAndKeepsRoomDetails() {
        val a = SextantEngine.addAddress("Home")
        val p = point("Stairs", 6, 1000f)
        a.points.add(p)
        assertTrue(SextantEngine.rescanPoint(a, p, listOf(mapOf("ap1" to -50), mapOf("ap1" to -52)), 999f))
        assertEquals(2, p.scans.size)
        assertEquals(999f, p.pressure)
        assertEquals("Stairs", p.name); assertEquals(6, p.floor); assertEquals("manual", p.posSource)
        assertEquals(41.0, p.lat)
    }

    @Test fun rescanWithNoScansKeepsExistingData() {
        val a = SextantEngine.addAddress("Home")
        val p = point("Office", 2, 1000f, mapOf("ap1" to -60))
        a.points.add(p)
        assertFalse(SextantEngine.rescanPoint(a, p, emptyList(), 990f))
        assertEquals(1, p.scans.size)
        assertEquals(1000f, p.pressure)
    }

    @Test fun rescanSurvivesReload() {
        val a = SextantEngine.addAddress("Home")
        val p = point("Parking", 0)
        a.points.add(p); SextantEngine.save()
        SextantEngine.rescanPoint(a, p, listOf(mapOf("ap9" to -70)), null)
        SextantEngine.reloadForTest()
        val reloaded = SextantEngine.address(a.id)!!.points.single()
        assertEquals(listOf(mapOf("ap9" to -70)), reloaded.scans)
    }

    @Test fun estimateFloorUsesBarometerBaseline() {
        val a = SextantAddress("a", "Home", mutableListOf(point("Lobby", 1, 1000f)))
        assertEquals(1, SextantEngine.estimateFloor(a, 1000f))
        assertEquals(3, SextantEngine.estimateFloor(a, 1000f - 2 * SextantEngine.HPA_PER_FLOOR))
        assertNull(SextantEngine.estimateFloor(a, null))
        assertNull(SextantEngine.estimateFloor(SextantAddress("b", "Empty", mutableListOf()), 1000f))
    }

    @Test fun locatePicksClosestFingerprintAndSkipsZeroScanRooms() {
        val kitchen = point("Kitchen", 1, null, mapOf("a" to -40, "b" to -80))
        val office = point("Office", 1, null, mapOf("a" to -80, "b" to -40))
        val empty = point("Yard", 1)
        val a = SextantAddress("a", "Home", mutableListOf(kitchen, office, empty))
        val guesses = SextantEngine.locate(a, mapOf("a" to -42, "b" to -78), null)
        assertEquals(2, guesses.size)
        assertEquals("Kitchen", guesses.first().point.name)
        assertEquals(1.0, guesses.sumOf { it.prob }, 1e-9)
        assertTrue(SextantEngine.locate(a, emptyMap(), null).isEmpty())
    }
}
