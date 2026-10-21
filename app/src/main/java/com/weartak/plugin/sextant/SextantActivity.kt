package com.weartak.plugin.sextant

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.wifi.WifiManager
import androidx.compose.foundation.Image
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.ui.platform.LocalView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
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
    data class Rescan(val id: String, val pointId: String) : Screen
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
    var editing by remember { mutableStateOf<NameEdit?>(null) }
    // In-app editor so the current name is pre-filled and the Samsung keyboard opens in typing mode.
    val listen: (String, String, (String) -> Unit) -> Unit = { prompt, initial, cb -> editing = NameEdit(prompt, initial, cb) }
    @Suppress("UNUSED_VARIABLE") val v = SextantEngine.version

    val goBack = {
        screen = when (val s = screen) {
            is Screen.Address -> Screen.Home
            is Screen.MapPlace -> s.next
            is Screen.FloorName, is Screen.Capture, is Screen.Locate, is Screen.PointDetail -> Screen.Address(addrIdOf(s))
            is Screen.CorrectPick -> Screen.Locate(s.id)
            is Screen.Rescan -> Screen.PointDetail(s.id, s.pointId)
            Screen.Home -> Screen.Home
        }
    }
    BackHandler(enabled = screen != Screen.Home, onBack = goBack)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = screen) {
            Screen.Home -> HomeScreen(
                onOpen = { screen = Screen.Address(it) },
                onAdd = {
                    val a = SextantEngine.addAddress("Address ${SextantEngine.addresses.size + 1}")
                    screen = Screen.Address(a.id)
                    listen("Address name", a.name) { name -> SextantEngine.renameAddress(a, name) }
                },
            )
            is Screen.Address -> AddressScreen(s.id,
                onDrop = { screen = Screen.Capture(s.id) },
                onLocate = { screen = Screen.Locate(s.id) },
                onRename = {
                    SextantEngine.address(s.id)?.let { address ->
                        listen("Address name", address.name) { name -> SextantEngine.renameAddress(address, name) }
                    }
                },
                onPoint = { screen = Screen.PointDetail(s.id, it) },
                onDeleted = { screen = Screen.Home })
            is Screen.Capture -> CaptureScreen(s.id,
                onDone = { screen = Screen.FloorName(s.id, it) },
                onCancel = { screen = Screen.Address(s.id) })
            is Screen.FloorName -> FloorNameScreen(s.id, s.cap, listen,
                onSaved = { pointId, name, pin ->
                    screen = if (pin) Screen.MapPlace(s.id, pointId, name, Screen.Address(s.id)) else Screen.Address(s.id)
                },
                onBack = { screen = Screen.Address(s.id) })
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
                onPlaceNew = { pointId -> screen = Screen.MapPlace(s.id, pointId, "?", Screen.Locate(s.id)) })
            is Screen.PointDetail -> PointDetailScreen(s.id, s.pointId, listen,
                onMap = { screen = Screen.MapPlace(s.id, s.pointId, SextantEngine.address(s.id)?.points?.firstOrNull { it.id == s.pointId }?.name ?: "", s) },
                onRescan = { screen = Screen.Rescan(s.id, s.pointId) },
                onDeleted = { screen = Screen.Address(s.id) })
            is Screen.Rescan -> CaptureScreen(s.id, trackSteps = false,
                onDone = { cap ->
                    SextantEngine.address(s.id)?.let { a ->
                        a.points.firstOrNull { it.id == s.pointId }?.let { p -> SextantEngine.rescanPoint(a, p, cap.scans, cap.hpa) }
                    }
                    screen = Screen.PointDetail(s.id, s.pointId)
                },
                onCancel = { screen = Screen.PointDetail(s.id, s.pointId) })
        }
        if (screen != Screen.Home) {
            SmallRoundButton(R.drawable.sextant_back, "Back", Modifier.align(Alignment.TopCenter).padding(top = 6.dp), onClick = goBack)
        }
        editing?.let { NameEditor(it) { editing = null } }
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

private fun roomsLabel(n: Int) = if (n == 1) "1 Room" else "$n Rooms"

/** Floor, scan count and position source for a room, as icon stats. */
private fun roomStats(p: SextantPoint): List<Stat> = listOfNotNull(
    R.drawable.sextant_floor to floorLabel(p.floor),
    R.drawable.sextant_wifi to "${p.scans.size}",
    when (p.posSource) {
        "manual" -> R.drawable.sextant_pin_map to ""
        "gps" -> R.drawable.sextant_gps to "±${p.acc?.roundToInt() ?: "?"}m"
        else -> null
    },
)

private fun addrIdOf(s: Screen): String = when (s) {
    is Screen.Address -> s.id; is Screen.Capture -> s.id; is Screen.FloorName -> s.id; is Screen.MapPlace -> s.id
    is Screen.Locate -> s.id; is Screen.CorrectPick -> s.id; is Screen.PointDetail -> s.id; is Screen.Rescan -> s.id; Screen.Home -> ""
}

// ---------------------------------------------------------------------------------------------
@Composable
private fun Title(text: String, color: Color = Color(0xFFAAAAAA)) =
    Text(text.uppercase(), fontSize = 12.sp, color = color, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp))

@Composable
private fun Small(text: String, color: Color = Color(0xFF999999)) =
    Text(text, fontSize = 12.sp, color = color, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

/** One icon + value pair, e.g. Wi-Fi icon + "12". An empty value shows the icon alone. */
private typealias Stat = Pair<Int, String>

@Composable
private fun StatRow(stats: List<Stat>, color: Color = Color(0xFF999999), center: Boolean = true, size: Int = 12) = Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp, if (center) Alignment.CenterHorizontally else Alignment.Start),
    modifier = if (center) Modifier.fillMaxWidth() else Modifier,
) {
    stats.forEach { (icon, value) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size((size + 2).dp))
            if (value.isNotEmpty()) Text(value, fontSize = size.sp, color = color, modifier = Modifier.padding(start = 2.dp))
        }
    }
}

@Composable
private fun ActionChip(label: String, icon: Int? = null, secondary: String? = null, stats: List<Stat>? = null, onClick: () -> Unit) = Chip(
    onClick = onClick,
    label = { Text(label, fontWeight = FontWeight.Medium) },
    secondaryLabel = stats?.let { { StatRow(it, Amber, center = false, size = 11) } } ?: secondary?.let { { Text(it, fontSize = 11.sp) } },
    icon = icon?.let { { Icon(painterResource(it), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(22.dp)) } },
    colors = ChipDefaults.chipColors(backgroundColor = AmberDark, contentColor = Color.White, secondaryContentColor = Amber),
    modifier = Modifier.fillMaxWidth(),
)

/** Text-free chip: a row of icons (e.g. + room) with an optional stats line. [label] is for accessibility. */
@Composable
private fun IconChip(icons: List<Int>, label: String, stats: List<Stat>? = null, primary: Boolean = true, showLabel: Boolean = false, onClick: () -> Unit) = Chip(
    onClick = onClick,
    label = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            icons.forEachIndexed { i, icon ->
                Icon(painterResource(icon), contentDescription = if (i == 0 && !showLabel) label else null,
                    tint = if (icon == R.drawable.markerdrop) Color.Unspecified else Color.White, modifier = Modifier.size(22.dp))
            }
            if (showLabel) Text(label, maxLines = 2, fontSize = 14.sp)
        }
    },
    secondaryLabel = stats?.let { { StatRow(it, if (primary) Amber else Color(0xFFBBBBBB), center = false, size = 11) } },
    colors = if (primary) ChipDefaults.chipColors(backgroundColor = AmberDark, contentColor = Color.White)
        else ChipDefaults.secondaryChipColors(),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun DeleteChip(label: String, confirm: Boolean, subject: Int? = null, showIcon: Boolean = true, onClick: () -> Unit) = Chip(
    onClick = onClick,
    label = {
        if (confirm || subject == null) Text(if (confirm) "Tap again to delete" else label, fontWeight = FontWeight.Medium)
        else Icon(painterResource(subject), contentDescription = label, modifier = Modifier.size(22.dp))
    },
    icon = if (showIcon) {
        { Icon(painterResource(R.drawable.sextant_delete), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp)) }
    } else null,
    colors = ChipDefaults.chipColors(backgroundColor = if (confirm) Color(0xFFB3261E) else Color(0xFF5C1F1F), contentColor = Color.White),
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun SmallRoundButton(icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) = Box(
    modifier = modifier.size(48.dp).clickable(role = Role.Button, onClick = onClick),
    contentAlignment = Alignment.Center,
) {
    Box(Modifier.size(28.dp).background(Color(0xFF33363B), CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = label, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}

private class NameEdit(val prompt: String, val initial: String, val onDone: (String) -> Unit)

@Composable
private fun NameEditor(edit: NameEdit, onClose: () -> Unit) {
    var value by remember(edit) { mutableStateOf(TextFieldValue(edit.initial, TextRange(edit.initial.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val save = {
        cleanName(value.text).takeIf { it.isNotEmpty() }?.let { edit.onDone(it.replaceFirstChar { c -> c.uppercase() }) }
        keyboard?.hide()
        onClose()
    }
    val cancel = { keyboard?.hide(); onClose() }
    BackHandler(onBack = cancel)
    LaunchedEffect(edit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).clickable(enabled = false) {}.padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(edit.prompt, color = Color(0xFFB0B0B0), fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = value,
            onValueChange = { value = it },
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Amber),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { save() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .background(Color(0xFF202124), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SmallRoundButton(R.drawable.sextant_close, "Cancel", onClick = cancel)
            SmallRoundButton(R.drawable.sextant_check, "Save", onClick = save)
        }
    }
}

@Composable
private fun WearTakHomeButton() {
    val ctx = LocalContext.current
    val host = remember {
        (listOfNotNull(HostTarget.hostPackageName) + listOf("com.aitech.weartak_civ", "com.aitech.weartak_civ.dev"))
            .firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }
    } ?: return
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SmallRoundButton(
            R.drawable.sextant_back,
            "Back to WearTAK",
            onClick = {
                ctx.packageManager.getLaunchIntentForPackage(host)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            },
        )
    }
}

@Composable
private fun IconAction(icon: Int, label: String, background: Color, tint: Color, onClick: () -> Unit) = Button(
    onClick = onClick,
    colors = ButtonDefaults.buttonColors(backgroundColor = background),
    modifier = Modifier.size(ButtonDefaults.DefaultButtonSize),
) {
    Icon(painterResource(icon), contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
}

@Composable
private fun RowChip(label: String, secondary: String? = null, icon: Int? = null, stats: List<Stat>? = null, onClick: () -> Unit) = Chip(
    onClick = onClick,
    label = { Text(label) },
    secondaryLabel = stats?.let { { StatRow(it, Color(0xFFBBBBBB), center = false, size = 11) } } ?: secondary?.let { { Text(it, fontSize = 11.sp) } },
    icon = icon?.let { { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(18.dp)) } },
    colors = ChipDefaults.secondaryChipColors(),
    modifier = Modifier.fillMaxWidth(),
)

// ---------------------------------------------------------------------------------------------
@Composable
private fun HomeScreen(onOpen: (String) -> Unit, onAdd: () -> Unit) {
    SextantEngine.version // recompose when survey data changes
    ScalingLazyColumn(
        Modifier.fillMaxSize(),
        state = rememberScalingLazyListState(initialCenterItemIndex = 0),
        autoCentering = null,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
        contentPadding = PaddingValues(start = 10.dp, top = 6.dp, end = 10.dp, bottom = 10.dp),
    ) {
        item { WearTakHomeButton() }
        item { IconChip(listOf(R.drawable.sextant_add, R.drawable.sextant_home), "Add address", onClick = onAdd) }
        items(SextantEngine.addresses.toList()) { a ->
            RowChip(a.name, stats = listOf(R.drawable.sextant_pin to "${a.points.size}")) { onOpen(a.id) }
        }
    }
}

@Composable
private fun AddressScreen(
    id: String,
    onDrop: () -> Unit,
    onLocate: () -> Unit,
    onRename: () -> Unit,
    onPoint: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    SextantEngine.version // recompose when survey data changes
    val ctx = LocalContext.current
    val a = SextantEngine.address(id) ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    val aps = SextantEngine.lastScan.size
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${a.name} · ${roomsLabel(a.points.size)}".uppercase(),
                    fontSize = 12.sp,
                    color = Color(0xFFAAAAAA),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                CompactChip(
                    onClick = onRename,
                    label = {
                        Icon(
                            painterResource(R.drawable.sextant_edit),
                            contentDescription = "Edit address name",
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
        item {
            // Tap the record/stop indicator to toggle background recording.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val rec = SextantEngine.recording
                Button(
                    onClick = { if (rec) SextantService.stop(ctx) else SextantService.start(ctx) },
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF33363B)),
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(painterResource(if (rec) R.drawable.sextant_stop else R.drawable.sextant_record),
                        contentDescription = if (rec) "Stop recording" else "Start recording",
                        tint = if (rec) Color.White else Color(0xFFE53935), modifier = Modifier.size(16.dp))
                }
                if (rec) {
                    StatRow(listOfNotNull(R.drawable.sextant_wifi to "${SextantEngine.scanCount}",
                        SextantEngine.stepsSinceStart()?.let { R.drawable.sextant_steps to "$it" }), Blue, center = false)
                }
            }
        }
        if (SextantEngine.recording && aps in 1 until SextantEngine.MIN_APS) {
            item { StatRow(listOf(R.drawable.sextant_wifi to "$aps ⚠"), Weak) }
        }
        item { IconChip(emptyList(), "Add New Room", showLabel = true,
            stats = listOf(R.drawable.sextant_wifi to "$aps"), onClick = onDrop) }
        if (a.points.size >= 2) item { IconChip(listOf(R.drawable.sextant_locate), "Estimate Location", primary = false, showLabel = true) { onLocate() } }
        items(a.points.sortedWith(ROOM_ORDER)) { p ->
            RowChip(roomLabel(a, p), icon = R.drawable.sextant_pin, stats = roomStats(p)) { onPoint(p.id) }
        }
        item {
            DeleteChip("Delete Address", confirmDelete, showIcon = false) {
                if (confirmDelete) {
                    if (SextantEngine.recording) SextantService.stop(ctx)
                    SextantEngine.deleteAddress(a); onDeleted()
                } else confirmDelete = true
            }
        }
        if (confirmDelete) item { Small("Removes ${roomsLabel(a.points.size)}. Walk log is kept.", Weak) }
    }
}

@SuppressLint("MissingPermission")
private fun buzzDone(ctx: Context) = runCatching {
    @Suppress("DEPRECATION")
    ctx.getSystemService(Vibrator::class.java)
        .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 150, 200), -1))
}

@SuppressLint("MissingPermission")
@Composable
private fun CaptureScreen(id: String, trackSteps: Boolean = true, onDone: (Captured) -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    val wifi = remember { ctx.applicationContext.getSystemService(WifiManager::class.java) }
    val target = 9
    var elapsed by remember { mutableIntStateOf(0) }
    var finishEarly by remember { mutableStateOf(false) }
    val scans = remember { mutableListOf<Map<String, Int>>() }
    var scanN by remember { mutableIntStateOf(0) }
    val hpa = remember { mutableListOf<Float>() }
    DisposableEffect(Unit) {
        val l: (Map<String, Int>) -> Unit = { scans.add(it); scanN = scans.size }
        SextantEngine.scanListeners.add(l)
        // With recording off there is no service receiver, so listen here or the room saves with 0 scans.
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!SextantEngine.recording) runCatching { SextantEngine.onScanResults(wifi.scanResults) }
            }
        }
        ctx.registerReceiver(r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        onDispose { SextantEngine.scanListeners.remove(l); runCatching { ctx.unregisterReceiver(r) } }
    }
    // Keeps the capture running if the watch is left on a desk and the screen would otherwise time out.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    fun requestScan() {
        @Suppress("DEPRECATION")
        if (SextantEngine.recording) SextantService.scanNow(ctx) else runCatching { wifi.startScan() }
    }
    LaunchedEffect(Unit) {
        // Recording lifts Android's scan throttle; without it the first scans can be silently rejected.
        if (!SextantEngine.recording &&
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            runCatching { SextantService.start(ctx) }
        }
        requestScan()
        // Wait for enough scans for a stable fingerprint; the user can save early once one has arrived.
        while (scans.size < target && !finishEarly) {
            SextantEngine.pressure?.let { hpa.add(it) }
            delay(1000); elapsed++
            // Retry quickly until the first scan lands, then settle into the normal cadence.
            if (elapsed % (if (scans.isEmpty()) 2 else 6) == 0) requestScan()
        }
        if (!finishEarly) buzzDone(ctx)
        val all = scans.toMutableList()
        if (all.isEmpty() && SextantEngine.lastScan.isNotEmpty() &&
            System.currentTimeMillis() - SextantEngine.lastScanAtMs < 45_000) all.add(SextantEngine.lastScan)
        val g = SextantEngine.gps?.takeIf { System.currentTimeMillis() - it.time < 20_000 }
        val counter = SextantEngine.stepCounter
        val fromPrev = if (counter != null && lastDropStepCounter != null) counter - lastDropStepCounter!! else null
        if (trackSteps) lastDropStepCounter = counter
        onDone(Captured(all, hpa.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
            g?.latitude, g?.longitude, g?.accuracy, fromPrev))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = scanN.coerceAtMost(target) / target.toFloat(),
            modifier = Modifier.fillMaxSize().padding(2.dp), indicatorColor = Amber, strokeWidth = 6.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.sextant_stand), contentDescription = "Stand still",
                tint = Color.Gray, modifier = Modifier.size(20.dp))
            Text("${scanN.coerceAtMost(target)}/$target", fontSize = 44.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { contentDescription = "$scanN of $target Wi-Fi scans" })
            val g = SextantEngine.gps
            val gpsOk = g != null && System.currentTimeMillis() - g.time < 20_000
            StatRow(listOf(R.drawable.sextant_wifi to "${SextantEngine.lastScan.size}",
                R.drawable.sextant_gps to (if (gpsOk) "±${g!!.accuracy.roundToInt()}m" else "—")))
            StatRow(listOf(R.drawable.sextant_gauge to (SextantEngine.pressure?.let { "%.1f".format(it) } ?: "—")))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                SmallRoundButton(R.drawable.sextant_close, "Cancel", onClick = onCancel)
                if (scanN > 0 && scanN < target) SmallRoundButton(R.drawable.sextant_check, "Save with $scanN scans") { finishEarly = true }
            }
        }
    }
}

@Composable
private fun FloorNameScreen(id: String, cap: Captured, listen: (String, String, (String) -> Unit) -> Unit, onSaved: (String, String, Boolean) -> Unit, onBack: () -> Unit) {
    val a = SextantEngine.address(id) ?: return
    val suggested = SextantEngine.estimateFloor(a, cap.hpa) ?: a.points.lastOrNull()?.floor ?: 1
    var floor by remember { mutableIntStateOf(suggested) }
    var name by remember { mutableStateOf("Room ${a.points.size + 1}") }
    var acc by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    fun save(pin: Boolean) {
        val p = SextantPoint(UUID.randomUUID().toString(), name, floor, cap.hpa, cap.lat, cap.lon, cap.acc,
            if (cap.lat != null) "gps" else "none", cap.stepsFromPrev, System.currentTimeMillis(), cap.scans.toMutableList())
        a.points.add(p); SextantEngine.save(); SextantEngine.touch()
        SextantEngine.log("drop", JSONObject().put("address", a.id).put("point", p.id).put("name", name)
            .put("floor", floor).put("hpa", cap.hpa).put("scans", cap.scans.size).put("stepsFromPrev", cap.stepsFromPrev))
        onSaved(p.id, name, pin)
    }
    ScalingLazyColumn(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e -> acc += e.verticalScrollPixels; if (acc > 60) { floor++; acc = 0f } else if (acc < -60) { floor--; acc = 0f }; true }
            .focusRequester(focus).focusable()
    ) {
        item { StatRow(listOf(R.drawable.sextant_check to "", R.drawable.sextant_wifi to "${cap.scans.size}"), Good) }
        items(ROOM_PRESETS.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
                row.forEach { n ->
                    CompactChip(onClick = { name = n }, label = { Text(n, fontSize = 11.sp) },
                        colors = if (name == n) ChipDefaults.chipColors(backgroundColor = AmberDark, contentColor = Color.White) else ChipDefaults.secondaryChipColors())
                }
            }
        }
        item { ActionChip(name, icon = R.drawable.sextant_edit) { listen("Room name", name) { name = it } } }
        item { StatRow(listOf(R.drawable.sextant_floor to "")) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                CompactChip(onClick = { floor-- }, label = { Text("−") }, colors = ChipDefaults.secondaryChipColors())
                Text(floorLabel(floor), fontSize = 36.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.width(90.dp).background(AmberDark, RoundedCornerShape(14.dp)))
                CompactChip(onClick = { floor++ }, label = { Text("+") }, colors = ChipDefaults.secondaryChipColors())
            }
        }
        item {
            val base = a.points.firstOrNull { it.pressure != null }
            if (base == null) StatRow(listOf(R.drawable.sextant_gauge to "base"))
            else StatRow(listOf(R.drawable.sextant_gauge to floorLabel(suggested)))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                IconAction(R.drawable.sextant_back, "Back", Color(0xFF33363B), tint = Color.White, onClick = onBack)
                IconAction(R.drawable.sextant_save, "Save", AmberDark, tint = Color.White) { save(pin = false) }
                IconAction(R.drawable.markerdrop, "Pin on map", Amber, tint = Color.Unspecified) { save(pin = true) }
            }
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
                properties = JsonObject(mapOf("label" to JsonPrimitive("${it.name} ${floorLabel(it.floor)}"))))
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
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 38.dp)
                .background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Icon(painterResource(R.drawable.markerdrop), contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(14.dp))
            Text(title, fontSize = 13.sp, color = Color.White, modifier = Modifier.padding(start = 4.dp))
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconAction(R.drawable.sextant_close, "Skip", Color(0xFF33363B), tint = Color.White, onClick = onSkip)
            IconAction(R.drawable.sextant_check, "Set here", Amber, tint = Color.Black) {
                val t = camera.position.target
                onSet(t.latitude, t.longitude)
            }
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
            Icon(painterResource(R.drawable.sextant_locate), contentDescription = "Nearest room", tint = Color.Gray, modifier = Modifier.size(16.dp))
            Text(top?.point?.let { roomLabel(a, it) } ?: "Scanning…", fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (top != null) Text("${(top.prob * 100).roundToInt()}%", fontSize = 30.sp, fontWeight = FontWeight.Medium)
            guesses.getOrNull(1)?.let { Small("2nd: ${roomLabel(a, it.point)} ${(it.prob * 100).roundToInt()}%") }
            val age = ((now - SextantEngine.lastScanAtMs) / 1000).coerceAtLeast(0)
            val fl = SextantEngine.estimateFloor(a, SextantEngine.pressure)
            StatRow(listOfNotNull(R.drawable.sextant_wifi to "${scan.size} · ${age}s", fl?.let { R.drawable.sextant_floor to floorLabel(it) }))
            Spacer(Modifier.height(6.dp))
            CompactChip(onClick = { onCorrect(scan, top?.point?.id) },
                label = {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(painterResource(R.drawable.markerdrop), contentDescription = "Correct position", tint = Color.Unspecified, modifier = Modifier.size(16.dp))
                        Icon(painterResource(R.drawable.sextant_edit), contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                colors = ChipDefaults.chipColors(backgroundColor = AmberDark, contentColor = Color.White))
        }
    }
}

@Composable
private fun CorrectPickScreen(id: String, scan: Map<String, Int>, guessed: String?, onPicked: () -> Unit, onPlaceNew: (String) -> Unit) {
    SextantEngine.version // recompose when survey data changes
    val a = SextantEngine.address(id) ?: return
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { StatRow(listOf(R.drawable.sextant_locate to "?"), size = 14) }
        item {
            IconChip(listOf(R.drawable.sextant_add, R.drawable.sextant_room, R.drawable.markerdrop), "New room here") {
                val fl = SextantEngine.estimateFloor(a, SextantEngine.pressure) ?: a.points.lastOrNull()?.floor ?: 1
                val p = SextantPoint(UUID.randomUUID().toString(), "Room ${a.points.size + 1}", fl, SextantEngine.pressure,
                    null, null, null, "none", null, System.currentTimeMillis(), mutableListOf(scan))
                a.points.add(p); SextantEngine.save(); SextantEngine.touch()
                SextantEngine.log("correct_new", JSONObject().put("address", a.id).put("point", p.id).put("guessed", guessed))
                onPlaceNew(p.id)
            }
        }
        items(a.points.sortedWith(ROOM_ORDER)) { p ->
            RowChip(roomLabel(a, p), icon = if (p.id == guessed) R.drawable.sextant_locate else R.drawable.sextant_pin,
                stats = listOf(R.drawable.sextant_floor to floorLabel(p.floor))) {
                SextantEngine.correct(a, p, scan, a.points.firstOrNull { it.id == guessed })
                onPicked()
            }
        }
    }
}

@Composable
private fun PointDetailScreen(id: String, pointId: String, listen: (String, String, (String) -> Unit) -> Unit, onMap: () -> Unit, onRescan: () -> Unit, onDeleted: () -> Unit) {
    SextantEngine.version // recompose when survey data changes
    val a = SextantEngine.address(id) ?: return
    val p = a.points.firstOrNull { it.id == pointId } ?: return
    var confirm by remember { mutableStateOf(false) }
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Title(roomLabel(a, p), Color.White) }
        item { StatRow(roomStats(p)) }
        p.stepsFromPrev?.let { item { StatRow(listOf(R.drawable.sextant_steps to "$it")) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                RoomAction(R.drawable.sextant_edit, "Change name") {
                    confirm = false; listen("Room name", p.name) { SextantEngine.renamePoint(a, p, it) }
                }
                RoomAction(R.drawable.sextant_scan, "Rescan room") { confirm = false; onRescan() }
                RoomAction(R.drawable.sextant_map_pin, "Pin on map", tint = Color.Unspecified) { confirm = false; onMap() }
                RoomAction(R.drawable.sextant_delete, if (confirm) "Tap again to delete room" else "Delete room",
                    background = if (confirm) Color(0xFFB3261E) else Color(0xFF5C1F1F)) {
                    if (confirm) { SextantEngine.deletePoint(a, p); onDeleted() } else confirm = true
                }
            }
        }
        if (p.scans.isEmpty()) item { StatRow(listOf(R.drawable.sextant_wifi to "0 ⚠"), Weak) }
        if (confirm) item { Small("Tap the trash again to delete.", Weak) }
    }
}

@Composable
private fun RoomAction(icon: Int, label: String, tint: Color = Color.White, background: Color = Color(0xFF33363B), onClick: () -> Unit) = Box(
    modifier = Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
        .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
) {
    Box(Modifier.size(42.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
