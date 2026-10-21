package com.weartak.plugin.sextant

import android.Manifest
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.wear.input.RemoteInputIntentHelper
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.weartak.plugin.sextant.R
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.Feature.get
import org.maplibre.compose.expressions.dsl.asString
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.value.SymbolAnchor
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position
import java.util.UUID
import kotlin.math.roundToInt

private const val INPUT_KEY = "sextant_name"
private val Amber = Color(0xFFFFB300)
private val AmberDark = Color(0xFF3A2A00)
private val Good = Color(0xFF34A853)
private val Weak = Color(0xFFFBBC04)
private val Blue = Color(0xFF4285F4)

/** Captured sensor snapshot for one dropped point (or one Locate correction). */
class Captured(
    val scans: List<Map<String, Int>>,
    val hpa: Float?,
    val lat: Double?,
    val lon: Double?,
    val acc: Float?,
    val stepsFromPrev: Long?,
)

private sealed interface Screen {
    data object Home : Screen
    data class Address(val id: String) : Screen
    data class Capture(val id: String) : Screen
    data class FloorName(val id: String, val cap: Captured) : Screen
    data class MapPlace(val id: String, val pointId: String, val title: String, val next: Screen) : Screen
    data class Locate(val id: String) : Screen
    data class CorrectPick(val id: String, val scan: Map<String, Int>, val guessed: String?) : Screen
    data class PointDetail(val id: String, val pointId: String) : Screen
}

class SextantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SextantEngine.init(this)
        // Recover if a previous recording was killed before it could restore the scan throttle.
        if (!SextantEngine.recording) ScanThrottle.restore(this)
        val needed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), 1)
        setContent { MaterialTheme { SextantApp() } }
    }
}

private var lastDropStepCounter: Long? = null

@Composable
private fun SextantApp() {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var speechCallback by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(INPUT_KEY)?.toString()
        if (!text.isNullOrBlank()) speechCallback?.invoke(text.trim().replaceFirstChar { it.uppercase() })
        speechCallback = null
    }
    // System Wear text input (Samsung keyboard with its own mic), no app chooser.
    val listen: (String, (String) -> Unit) -> Unit = { prompt, cb ->
        speechCallback = cb
        try {
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder(INPUT_KEY).setLabel(prompt).build()))
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) { speechCallback = null }
    }
    @Suppress("UNUSED_VARIABLE") val v = SextantEngine.version

    BackHandler(enabled = screen != Screen.Home) {
        screen = when (val s = screen) {
            is Screen.Address -> Screen.Home
            is Screen.MapPlace -> s.next
            is Screen.FloorName, is Screen.Capture, is Screen.Locate, is Screen.PointDetail -> Screen.Address(addrIdOf(s))
            is Screen.CorrectPick -> Screen.Locate(s.id)
            Screen.Home -> Screen.Home
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                onOpen = { screen = Screen.Address(it) },
                onAdd = {
                    val a = SextantEngine.addAddress("Address ${SextantEngine.addresses.size + 1}")
                    screen = Screen.Address(a.id)
                    listen("Address name") { name -> a.name = name; SextantEngine.save(); SextantEngine.touch() }
                },
            )
            is Screen.Address -> AddressScreen(s.id,
                onDrop = { screen = Screen.Capture(s.id) },
                onLocate = { screen = Screen.Locate(s.id) },
                onPoint = { screen = Screen.PointDetail(s.id, it) },
                onDeleted = { screen = Screen.Home })
            is Screen.Capture -> CaptureScreen(s.id,
                onDone = { screen = Screen.FloorName(s.id, it) },
                onCancel = { screen = Screen.Address(s.id) })
            is Screen.FloorName -> FloorNameScreen(s.id, s.cap, listen) { pointId, name ->
                screen = Screen.MapPlace(s.id, pointId, "Where is $name?", Screen.Address(s.id))
            }
            is Screen.MapPlace -> MapPlaceScreen(s.id, s.pointId, s.title,
                onSet = { lat, lon ->
                    SextantEngine.address(s.id)?.let { a ->
                        a.points.firstOrNull { it.id == s.pointId }?.let { p -> SextantEngine.setManualPosition(a, p, lat, lon) }
                    }
                    screen = s.next
                },
                onSkip = { screen = s.next })
            is Screen.Locate -> LocateScreen(s.id,
                onCorrect = { scan, guessed -> screen = Screen.CorrectPick(s.id, scan, guessed) },
                onBack = { screen = Screen.Address(s.id) })
            is Screen.CorrectPick -> CorrectPickScreen(s.id, s.scan, s.guessed,
                onPicked = { screen = Screen.Locate(s.id) },
                onPlaceNew = { pointId -> screen = Screen.MapPlace(s.id, pointId, "Where are you?", Screen.Locate(s.id)) })
            is Screen.PointDetail -> PointDetailScreen(s.id, s.pointId, listen,
                onMap = { screen = Screen.MapPlace(s.id, s.pointId, "Adjust position", s) },
                onDeleted = { screen = Screen.Address(s.id) })
        }
    }

    LaunchedEffect(screen) {
        val id = (screen as? Screen.Address)?.id ?: return@LaunchedEffect
        SextantEngine.activeAddressId = id
        if (!SextantEngine.recording &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            SextantService.start(ctx)
        }
    }
}

private fun addrIdOf(s: Screen): String = when (s) {
    is Screen.Address -> s.id; is Screen.Capture -> s.id; is Screen.FloorName -> s.id; is Screen.MapPlace -> s.id
    is Screen.Locate -> s.id; is Screen.CorrectPick -> s.id; is Screen.PointDetail -> s.id; Screen.Home -> ""
}

// ---------------------------------------------------------------------------------------------
@Composable
private fun Title(text: String, color: Color = Color(0xFFAAAAAA)) =
    Text(text.uppercase(), fontSize = 12.sp, color = color, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp))

@Composable
private fun Small(text: String, color: Color = Color(0xFF999999)) =
    Text(text, fontSize = 12.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable
private fun ActionChip(label: String, icon: Int? = null, secondary: String? = null, onClick: () -> Unit) = Chip(
    onClick = onClick,
    label = { Text(label, fontWeight = FontWeight.Medium) },
    secondaryLabel = secondary?.let { { Text(it, fontSize = 11.sp) } },
    icon = icon?.let { { Icon(painterResource(it), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(22.dp)) } },
    colors = ChipDefaults.chipColors(backgroundColor = AmberDark, contentColor = Color.White, secondaryContentColor = Amber),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun RowChip(label: String, secondary: String? = null, icon: Int? = null, onClick: () -> Unit) = Chip(
    onClick = onClick,
    label = { Text(label) },
    secondaryLabel = secondary?.let { { Text(it, fontSize = 11.sp) } },
    icon = icon?.let { { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp)) } },
    colors = ChipDefaults.secondaryChipColors(),
    modifier = Modifier.fillMaxWidth(),
)

// ---------------------------------------------------------------------------------------------
@Composable
private fun HomeScreen(onOpen: (String) -> Unit, onAdd: () -> Unit) {
    SextantEngine.version // recompose when survey data changes
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Title("Sextant") }
        item { ActionChip("Add address", secondary = "name it after", onClick = onAdd) }
        items(SextantEngine.addresses.toList()) { a ->
            RowChip(a.name, "${a.points.size} points") { onOpen(a.id) }
        }
        item { Small("Local only · nothing is sent as CoT") }
    }
}

@Composable
private fun AddressScreen(id: String, onDrop: () -> Unit, onLocate: () -> Unit, onPoint: (String) -> Unit, onDeleted: () -> Unit) {
    SextantEngine.version // recompose when survey data changes
    val ctx = LocalContext.current
    val a = SextantEngine.address(id) ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    val aps = SextantEngine.lastScan.size
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Title("${a.name} · ${a.points.size} points") }
        item {
            val steps = SextantEngine.stepsSinceStart()
            val rec = if (SextantEngine.recording) "● Recording · ${SextantEngine.scanCount} scans" +
                (steps?.let { " · $it steps" } ?: "") else "Not recording"
            Small(rec, if (SextantEngine.recording) Blue else Color.Gray)
        }
        if (SextantEngine.recording && aps in 1 until SextantEngine.MIN_APS) {
            item { Small("⚠ Only $aps Wi-Fi networks visible. Rooms may blur together.", Weak) }
        }
        item { ActionChip("Drop point", icon = R.drawable.markerdrop, secondary = "$aps Wi-Fi networks", onClick = onDrop) }
        if (a.points.size >= 2) item { RowChip("Locate", "Which point am I nearest?") { onLocate() } }
        items(a.points.toList()) { p ->
            val pos = when (p.posSource) { "manual" -> "pinned"; "gps" -> "GPS ±${p.acc?.roundToInt() ?: "?"} m"; else -> "no position" }
            RowChip(p.name, "L${p.floor} · ${p.scans.size} scans · $pos", R.drawable.sextant_pin) { onPoint(p.id) }
        }
        item {
            CompactChip(
                onClick = { if (SextantEngine.recording) SextantService.stop(ctx) else SextantService.start(ctx) },
                label = { Text(if (SextantEngine.recording) "Stop recording" else "Start recording") },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
        item {
            CompactChip(
                onClick = {
                    if (confirmDelete) {
                        if (SextantEngine.recording) SextantService.stop(ctx)
                        SextantEngine.deleteAddress(a); onDeleted()
                    } else confirmDelete = true
                },
                label = { Text(if (confirmDelete) "Tap again to delete" else "Delete address") },
                colors = ChipDefaults.chipColors(backgroundColor = if (confirmDelete) Color(0xFF8B1A1A) else Color(0xFF33363B)),
            )
        }
        if (confirmDelete) item { Small("Removes ${a.points.size} points. Walk log is kept.", Weak) }
        item { Small("Export: adb pull …/files/sextant") }
    }
}

@Composable
private fun CaptureScreen(id: String, onDone: (Captured) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val total = 15
    var left by remember { mutableIntStateOf(total) }
    val scans = remember { mutableListOf<Map<String, Int>>() }
    var scanN by remember { mutableIntStateOf(0) }
    val hpa = remember { mutableListOf<Float>() }
    DisposableEffect(Unit) {
        val l: (Map<String, Int>) -> Unit = { scans.add(it); scanN = scans.size }
        SextantEngine.scanListeners.add(l)
        onDispose { SextantEngine.scanListeners.remove(l) }
    }
    LaunchedEffect(Unit) {
        SextantService.scanNow(ctx)
        while (left > 0) {
            SextantEngine.pressure?.let { hpa.add(it) }
            delay(1000); left--
        }
        val all = scans.toMutableList()
        if (all.isEmpty() && SextantEngine.lastScan.isNotEmpty() &&
            System.currentTimeMillis() - SextantEngine.lastScanAtMs < 45_000) all.add(SextantEngine.lastScan)
        val g = SextantEngine.gps?.takeIf { System.currentTimeMillis() - it.time < 20_000 }
        val counter = SextantEngine.stepCounter
        val fromPrev = if (counter != null && lastDropStepCounter != null) counter - lastDropStepCounter!! else null
        lastDropStepCounter = counter
        onDone(Captured(all, hpa.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            g?.latitude, g?.longitude, g?.accuracy, fromPrev))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = 1f - left / total.toFloat(), modifier = Modifier.fillMaxSize().padding(2.dp),
            indicatorColor = Amber, strokeWidth = 6.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("STAND STILL", fontSize = 12.sp, color = Color.Gray)
            Text("$left s", fontSize = 44.sp, fontWeight = FontWeight.Medium)
            val g = SextantEngine.gps
            Small(if (g != null && System.currentTimeMillis() - g.time < 20_000) "GPS ±${g.accuracy.roundToInt()} m" else "GPS: none")
            Small("Wi-Fi scans $scanN · ${SextantEngine.lastScan.size} networks")
            Small(SextantEngine.pressure?.let { "%.2f hPa".format(it) } ?: "no barometer")
            Spacer(Modifier.height(6.dp))
            CompactChip(onClick = onCancel, label = { Text("Cancel") }, colors = ChipDefaults.secondaryChipColors())
        }
    }
}

@Composable
private fun FloorNameScreen(id: String, cap: Captured, listen: (String, (String) -> Unit) -> Unit, onSaved: (String, String) -> Unit) {
    val a = SextantEngine.address(id) ?: return
    val suggested = SextantEngine.estimateFloor(a, cap.hpa) ?: a.points.lastOrNull()?.floor ?: 1
    var floor by remember { mutableIntStateOf(suggested) }
    var name by remember { mutableStateOf("Point ${a.points.size + 1}") }
    var acc by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save() {
        val p = SextantPoint(UUID.randomUUID().toString(), name, floor, cap.hpa, cap.lat, cap.lon, cap.acc,
            if (cap.lat != null) "gps" else "none", cap.stepsFromPrev, System.currentTimeMillis(), cap.scans.toMutableList())
        a.points.add(p); SextantEngine.save(); SextantEngine.touch()
        SextantEngine.log("drop", JSONObject().put("address", a.id).put("point", p.id).put("name", name)
            .put("floor", floor).put("hpa", cap.hpa).put("scans", cap.scans.size).put("stepsFromPrev", cap.stepsFromPrev))
        onSaved(p.id, name)
    }
    ScalingLazyColumn(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e -> acc += e.verticalScrollPixels; if (acc > 60) { floor++; acc = 0f } else if (acc < -60) { floor--; acc = 0f }; true }
            .focusRequester(focus).focusable()
    ) {
        item { Title("Point dropped · ${cap.scans.size} scans", Good) }
        item { Small("Which floor?") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                CompactChip(onClick = { floor-- }, label = { Text("−") }, colors = ChipDefaults.secondaryChipColors())
                Text("L$floor", fontSize = 36.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.width(90.dp).background(AmberDark, RoundedCornerShape(14.dp)))
                CompactChip(onClick = { floor++ }, label = { Text("+") }, colors = ChipDefaults.secondaryChipColors())
            }
        }
        item {
            val base = a.points.firstOrNull { it.pressure != null }
            Small(if (base == null) "First point sets the floor baseline" else "Barometer suggests L$suggested (vs ${base.name})")
        }
        item { ActionChip("Name: $name", secondary = "tap to rename") { listen("Point name") { name = it } } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Door", "Elevator", "Stairs").forEach { n -> CompactChip(onClick = { name = n }, label = { Text(n, fontSize = 11.sp) }, colors = ChipDefaults.secondaryChipColors()) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Kitchen", "Bedroom", "Balcony").forEach { n -> CompactChip(onClick = { name = n }, label = { Text(n, fontSize = 11.sp) }, colors = ChipDefaults.secondaryChipColors()) }
            }
        }
        item {
            Chip(onClick = { save() }, label = { Text("Save · set on map", fontWeight = FontWeight.Medium, color = Color.Black) },
                colors = ChipDefaults.chipColors(backgroundColor = Amber), modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun MapPlaceScreen(id: String, pointId: String, title: String, onSet: (Double, Double) -> Unit, onSkip: () -> Unit) {
    val ctx = LocalContext.current
    val a = SextantEngine.address(id)
    val p = a?.points?.firstOrNull { it.id == pointId }
    val start = (p?.lat?.let { lat -> p.lon?.let { lat to it } }) ?: SextantEngine.anchor(a) ?: (0.0 to 0.0)
    val camera = rememberCameraState(firstPosition = CameraPosition(target = Position(start.second, start.first), zoom = 19.0))
    val style = remember { ctx.assets.open("style.json").bufferedReader().use { it.readText() } }
    val others = remember(SextantEngine.version) {
        FeatureCollection(a?.points.orEmpty().filter { it.id != pointId && it.lat != null && it.lon != null }.map {
            Feature(geometry = Point(Position(it.lon!!, it.lat!!)),
                properties = JsonObject(mapOf("label" to JsonPrimitive("${it.name} L${it.floor}"))))
        })
    }
    Box(Modifier.fillMaxSize()) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            baseStyle = BaseStyle.Json(style),
            cameraState = camera,
            options = MapOptions(ornamentOptions = OrnamentOptions.AllDisabled, gestureOptions = GestureOptions.RotationLocked),
            zoomRange = 3f..22f,
        ) {
            val src = rememberGeoJsonSource(data = GeoJsonData.Features(others))
            SymbolLayer(
                id = "sextant-points", source = src,
                iconImage = image(painterResource(R.drawable.sextant_pin_map)),
                iconAllowOverlap = const(true), iconIgnorePlacement = const(true),
                textField = get("label").asString(), textSize = const(0.8.em),
                textAnchor = const(SymbolAnchor.Top), textOffset = offset(0f.em, 0.8f.em),
                textColor = const(Color.White), textHaloColor = const(Color.Black), textHaloWidth = const(2.dp),
            )
        }
        // Fixed crosshair: pan the map so the spot sits under it.
        Box(Modifier.align(Alignment.Center).size(28.dp)) {
            Box(Modifier.align(Alignment.Center).width(2.dp).height(28.dp).background(Amber))
            Box(Modifier.align(Alignment.Center).width(28.dp).height(2.dp).background(Amber))
        }
        Text(title, fontSize = 13.sp, color = Color.White, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 22.dp)
                .background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactChip(onClick = onSkip, label = { Text("Skip") }, colors = ChipDefaults.secondaryChipColors())
            CompactChip(onClick = {
                val t = camera.position.target
                onSet(t.latitude, t.longitude)
            }, label = { Text("Set here", color = Color.Black, fontWeight = FontWeight.Medium) },
                colors = ChipDefaults.chipColors(backgroundColor = Amber))
        }
    }
}

@Composable
private fun LocateScreen(id: String, onCorrect: (Map<String, Int>, String?) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val a = SextantEngine.address(id) ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { SextantService.scanNow(ctx); while (true) { delay(1000); now = System.currentTimeMillis() } }
    val scan = SextantEngine.lastScan
    val guesses = remember(scan, SextantEngine.version) { SextantEngine.locate(a, scan, SextantEngine.pressure) }
    val top = guesses.firstOrNull()
    LaunchedEffect(top?.point?.id, scan) {
        if (top != null) SextantEngine.log("locate", JSONObject().put("address", a.id).put("guess", top.point.id)
            .put("p", top.prob).put("d", top.distDb).put("second", guesses.getOrNull(1)?.point?.id))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = (top?.prob ?: 0.0).toFloat(), modifier = Modifier.fillMaxSize().padding(2.dp),
            indicatorColor = Blue, strokeWidth = 6.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text("NEAREST POINT", fontSize = 12.sp, color = Color.Gray)
            Text(top?.point?.name ?: "Scanning…", fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (top != null) Text("${(top.prob * 100).roundToInt()}%", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            guesses.getOrNull(1)?.let { Small("2nd: ${it.point.name} ${(it.prob * 100).roundToInt()}%") }
            val age = ((now - SextantEngine.lastScanAtMs) / 1000).coerceAtLeast(0)
            val fl = SextantEngine.estimateFloor(a, SextantEngine.pressure)
            Small("${scan.size} networks · scan ${age}s ago" + (fl?.let { " · L$it" } ?: ""))
            Spacer(Modifier.height(6.dp))
            CompactChip(onClick = { onCorrect(scan, top?.point?.id) }, label = { Text("Correct position") },
                colors = ChipDefaults.chipColors(backgroundColor = AmberDark, contentColor = Color.White))
        }
    }
}

@Composable
private fun CorrectPickScreen(id: String, scan: Map<String, Int>, guessed: String?, onPicked: () -> Unit, onPlaceNew: (String) -> Unit) {
    SextantEngine.version // recompose when survey data changes
    val a = SextantEngine.address(id) ?: return
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Title("Where are you really?") }
        item {
            ActionChip("Place on map", icon = R.drawable.markerdrop, secondary = "new point here") {
                val fl = SextantEngine.estimateFloor(a, SextantEngine.pressure) ?: a.points.lastOrNull()?.floor ?: 1
                val p = SextantPoint(UUID.randomUUID().toString(), "Point ${a.points.size + 1}", fl, SextantEngine.pressure,
                    null, null, null, "none", null, System.currentTimeMillis(), mutableListOf(scan))
                a.points.add(p); SextantEngine.save(); SextantEngine.touch()
                SextantEngine.log("correct_new", JSONObject().put("address", a.id).put("point", p.id).put("guessed", guessed))
                onPlaceNew(p.id)
            }
        }
        items(a.points.toList()) { p ->
            RowChip(p.name, if (p.id == guessed) "watch guessed this · confirm" else "L${p.floor} · add this scan", R.drawable.sextant_pin) {
                SextantEngine.correct(a, p, scan, a.points.firstOrNull { it.id == guessed })
                onPicked()
            }
        }
    }
}

@Composable
private fun PointDetailScreen(id: String, pointId: String, listen: (String, (String) -> Unit) -> Unit, onMap: () -> Unit, onDeleted: () -> Unit) {
    SextantEngine.version // recompose when survey data changes
    val a = SextantEngine.address(id) ?: return
    val p = a.points.firstOrNull { it.id == pointId } ?: return
    var confirm by remember { mutableStateOf(false) }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Title(p.name, Color.White) }
        item { Small("L${p.floor} · ${p.scans.size} scans · ${SextantEngine.fingerprint(p).size} networks") }
        item {
            Small(when (p.posSource) {
                "manual" -> "Pinned on map: %.6f, %.6f".format(p.lat, p.lon)
                "gps" -> "GPS ±${p.acc?.roundToInt()} m (may be off indoors)"
                else -> "No position yet"
            })
        }
        p.stepsFromPrev?.let { item { Small("$it steps from previous point") } }
        item { RowChip("Rename", p.name) { listen("Point name") { SextantEngine.renamePoint(a, p, it) } } }
        item { ActionChip("Correct position", icon = R.drawable.markerdrop, secondary = "place on map", onClick = onMap) }
        item {
            CompactChip(onClick = { if (confirm) { SextantEngine.deletePoint(a, p); onDeleted() } else confirm = true },
                label = { Text(if (confirm) "Tap again to delete" else "Delete point") },
                colors = ChipDefaults.chipColors(backgroundColor = if (confirm) Color(0xFF8B1A1A) else Color(0xFF33363B)))
        }
    }
}
