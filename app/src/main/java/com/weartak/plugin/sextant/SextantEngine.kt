package com.weartak.plugin.sextant

import android.content.Context
import android.location.Location
import android.net.wifi.ScanResult
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Prototype: Wi-Fi fingerprint + barometer floor survey. Local only, never sent as CoT. */
class SextantPoint(
    val id: String,
    var name: String,
    var floor: Int,
    val pressure: Float?,
    var lat: Double?,
    var lon: Double?,
    var acc: Float?,
    var posSource: String,
    val stepsFromPrev: Long?,
    val time: Long,
    val scans: MutableList<Map<String, Int>>,
)

class SextantAddress(val id: String, var name: String, val points: MutableList<SextantPoint>)

data class Guess(val point: SextantPoint, val prob: Double, val distDb: Double)

object SextantEngine {
    private const val TAG = "Sextant"
    const val MISSING_DBM = -95.0
    const val HPA_PER_FLOOR = 0.42f
    const val MIN_APS = 5

    private lateinit var app: Context
    private var loaded = false

    val addresses = mutableListOf<SextantAddress>()
    var version by mutableIntStateOf(0)
        private set

    var recording by mutableStateOf(false)
    var activeAddressId by mutableStateOf<String?>(null)
    var lastScan by mutableStateOf<Map<String, Int>>(emptyMap())
    var lastScanAtMs by mutableStateOf(0L)
    var scanCount by mutableIntStateOf(0)
    var pressure by mutableStateOf<Float?>(null)
    var stepCounter by mutableStateOf<Long?>(null)
    var stepsAtRecordStart: Long? = null
    var gps by mutableStateOf<Location?>(null)
    var throttled by mutableStateOf(true)

    val ssidByBssid = HashMap<String, String>()
    val scanListeners = mutableListOf<(Map<String, Int>) -> Unit>()

    fun init(context: Context) {
        if (loaded) return
        app = context.applicationContext
        load()
        loaded = true
    }

    fun touch() { version++ }

    fun dir(): File = (app.getExternalFilesDir("sextant") ?: File(app.filesDir, "sextant")).apply { mkdirs() }

    fun address(id: String?) = addresses.firstOrNull { it.id == id }

    fun addAddress(name: String): SextantAddress {
        val a = SextantAddress(UUID.randomUUID().toString(), name, mutableListOf())
        addresses.add(a); save(); touch(); return a
    }

    fun setManualPosition(a: SextantAddress, p: SextantPoint, lat: Double, lon: Double) {
        p.lat = lat; p.lon = lon; p.acc = null; p.posSource = "manual"; save(); touch()
        log("set_position", JSONObject().put("address", a.id).put("point", p.id).put("lat", lat).put("lon", lon))
    }

    /** Best-known map position for the address: last manual/GPS point, else live GPS. */
    fun anchor(a: SextantAddress?): Pair<Double, Double>? {
        a?.points?.lastOrNull { it.lat != null && it.lon != null }?.let { return it.lat!! to it.lon!! }
        return gps?.let { it.latitude to it.longitude }
    }

    fun deletePoint(a: SextantAddress, p: SextantPoint) {
        a.points.remove(p); save(); touch()
        log("delete_point", JSONObject().put("address", a.id).put("point", p.id))
    }

    fun onScanResults(results: List<ScanResult>) {
        val nowUs = SystemClock.elapsedRealtime() * 1000
        val fresh = results.filter { nowUs - it.timestamp < 30_000_000L }
        if (fresh.isEmpty()) return
        val map = HashMap<String, Int>()
        for (r in fresh) {
            @Suppress("DEPRECATION") val bssid = r.BSSID ?: continue
            map[bssid] = r.level
            @Suppress("DEPRECATION") val ssid = r.SSID
            if (!ssid.isNullOrEmpty()) ssidByBssid[bssid] = ssid
        }
        lastScan = map
        lastScanAtMs = System.currentTimeMillis()
        scanCount++
        val aps = JSONObject(); map.forEach { (k, v) -> aps.put(k, v) }
        log("scan", JSONObject().put("aps", aps).put("hpa", pressure).put("steps", stepCounter))
        scanListeners.toList().forEach { it(map) }
    }

    fun stepsSinceStart(): Long? {
        val s = stepCounter ?: return null; val b = stepsAtRecordStart ?: return null; return s - b
    }

    // ---- floors -------------------------------------------------------------------------
    fun estimateFloor(a: SextantAddress, hpa: Float?): Int? {
        if (hpa == null) return null
        val base = a.points.firstOrNull { it.pressure != null } ?: return null
        return base.floor + ((base.pressure!! - hpa) / HPA_PER_FLOOR).roundToInt()
    }

    // ---- locate -------------------------------------------------------------------------
    fun fingerprint(p: SextantPoint): Map<String, Double> {
        val keys = p.scans.flatMap { it.keys }.toSet()
        return keys.associateWith { k -> p.scans.map { (it[k] ?: MISSING_DBM.toInt()).toDouble() }.average() }
    }

    fun locate(a: SextantAddress, scan: Map<String, Int>, hpa: Float?): List<Guess> {
        if (scan.isEmpty()) return emptyList()
        val floorEst = estimateFloor(a, hpa)
        val raw = a.points.filter { it.scans.isNotEmpty() }.map { pt ->
            val fp = fingerprint(pt)
            val keys = fp.keys + scan.keys
            var sum = 0.0
            for (k in keys) {
                val x = fp[k] ?: MISSING_DBM
                val y = scan[k]?.toDouble() ?: MISSING_DBM
                sum += (x - y) * (x - y)
            }
            var d = sqrt(sum / keys.size)
            if (floorEst != null) d += 6.0 * abs(floorEst - pt.floor)
            pt to d
        }
        if (raw.isEmpty()) return emptyList()
        val dMin = raw.minOf { it.second }
        val sigma2 = 2 * 5.0 * 5.0
        val w = raw.map { Triple(it.first, exp(-(it.second * it.second - dMin * dMin) / sigma2), it.second) }
        val tot = w.sumOf { it.second }
        return w.map { Guess(it.first, it.second / tot, it.third) }.sortedByDescending { it.prob }
    }

    fun correct(a: SextantAddress, p: SextantPoint, scan: Map<String, Int>, guessed: SextantPoint?) {
        if (scan.isEmpty()) return
        p.scans.add(scan); save(); touch()
        log("correct", JSONObject().put("address", a.id).put("truth", p.id).put("guessed", guessed?.id))
    }

    // ---- persistence --------------------------------------------------------------------
    private fun storeFile() = File(dir(), "sextant.json")

    fun save() {
        val arr = JSONArray()
        for (a in addresses) {
            val pts = JSONArray()
            for (p in a.points) {
                val scans = JSONArray()
                p.scans.forEach { s -> val o = JSONObject(); s.forEach { (k, v) -> o.put(k, v) }; scans.put(o) }
                pts.put(JSONObject().put("id", p.id).put("name", p.name).put("floor", p.floor)
                    .put("hpa", p.pressure).put("lat", p.lat).put("lon", p.lon).put("acc", p.acc).put("pos", p.posSource)
                    .put("stepsFromPrev", p.stepsFromPrev).put("time", p.time).put("scans", scans))
            }
            arr.put(JSONObject().put("id", a.id).put("name", a.name).put("points", pts))
        }
        val ss = JSONObject(); ssidByBssid.forEach { (k, v) -> ss.put(k, v) }
        runCatching { storeFile().writeText(JSONObject().put("addresses", arr).put("ssids", ss).toString(1)) }
            .onFailure { Log.w(TAG, "save failed", it) }
    }

    private fun load() {
        val f = storeFile(); if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            root.optJSONObject("ssids")?.let { o -> o.keys().forEach { ssidByBssid[it] = o.getString(it) } }
            val arr = root.getJSONArray("addresses")
            for (i in 0 until arr.length()) {
                val ao = arr.getJSONObject(i)
                val pts = mutableListOf<SextantPoint>()
                val pa = ao.getJSONArray("points")
                for (j in 0 until pa.length()) {
                    val po = pa.getJSONObject(j)
                    val scans = mutableListOf<Map<String, Int>>()
                    val sa = po.getJSONArray("scans")
                    for (k in 0 until sa.length()) {
                        val so = sa.getJSONObject(k); scans.add(so.keys().asSequence().associateWith { so.getInt(it) })
                    }
                    pts.add(SextantPoint(po.getString("id"), po.getString("name"), po.getInt("floor"),
                        po.optDoubleOrNull("hpa")?.toFloat(), po.optDoubleOrNull("lat"), po.optDoubleOrNull("lon"),
                        po.optDoubleOrNull("acc")?.toFloat(), po.optString("pos", "none"), po.optDoubleOrNull("stepsFromPrev")?.toLong(),
                        po.getLong("time"), scans))
                }
                addresses.add(SextantAddress(ao.getString("id"), ao.getString("name"), pts))
            }
        }.onFailure { Log.w(TAG, "load failed", it) }
    }

    private fun JSONObject.optDoubleOrNull(k: String): Double? = if (has(k) && !isNull(k)) getDouble(k) else null

    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun log(type: String, data: JSONObject) {
        if (!loaded) return
        runCatching {
            data.put("t", System.currentTimeMillis()).put("type", type).put("address", data.opt("address") ?: activeAddressId)
            File(dir(), "walk-${dayFmt.format(Date())}.jsonl").appendText(data.toString() + "\n")
        }
    }
}
