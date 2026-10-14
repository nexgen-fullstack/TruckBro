package com.truckbro.app.ui

import android.content.Intent
import android.graphics.Color as AColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.truckbro.app.data.Prefs
import com.truckbro.app.road.LatLon
import com.truckbro.app.service.DriveState
import com.truckbro.app.service.NavUi
import com.truckbro.app.service.Phrases
import com.truckbro.app.service.TruckAccessibilityService
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import kotlin.math.roundToInt

/**
 * Екран навігації по об'їзду. Голосові підказки йдуть із фонового сервісу (працюють і без цього екрана),
 * тут — карта, наступний поворот великими літерами й кнопки. Маленьке вікно Waze лишається поверх.
 */
class NavActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            TruckTheme {
                val nav by DriveState.nav.collectAsState()
                val here by DriveState.here.collectAsState()
                val n = nav
                if (n == null) {
                    // Об'їзду немає (скасували або закінчився давно) — повертаємось туди, звідки прийшли.
                    LaunchedEffect(Unit) { delay(1_500); moveTaskToBack(true); finish() }
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Об'їзду зараз немає", fontSize = 22.sp, color = Color.White)
                    }
                } else NavScreen(n, here)
            }
        }
    }

    @Composable
    private fun NavScreen(n: NavUi, here: LatLon?) {
        val prefs = remember { Prefs(this) }
        Column(Modifier.fillMaxSize().background(Color(0xFF111315)).systemBarsPadding()) {
            // Наступний маневр — великими літерами.
            val p = n.progress
            Column(Modifier.fillMaxWidth().background(if (n.finished) Color(0xFF1B5E20) else Color(0xFF0D47A1)).padding(14.dp)) {
                if (n.finished) {
                    Text("✓ Об'їзд закінчено", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Ти знову на основному маршруті. Далі веде Waze.", fontSize = 17.sp, color = Color.White)
                } else {
                    Text(p?.toManeuver?.let { Phrases.short(it) } ?: "Об'їзд", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(p?.nextInstruction ?: n.route.maneuvers.firstOrNull()?.instruction.orEmpty(),
                        fontSize = 20.sp, color = Color.White, lineHeight = 24.sp)
                    if (p?.offRoute == true) Text("Не на маршруті — перераховую…", fontSize = 16.sp, color = Color(0xFFFFCC80))
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) { RouteMap(n, here) }
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val extra = n.plan.extraMinutes.roundToInt()
                Text(
                    "${if (n.demo) "ТЕСТ · " else ""}+$extra хв до часу в дорозі · об'їжджаємо: ${n.plan.avoidName}" +
                        (p?.let { " · до основного маршруту ${Phrases.short(it.toRejoin)}" } ?: ""),
                    fontSize = 15.sp, color = Color(0xFFB4BEC8),
                )
                if (n.finished) {
                    var left by remember { mutableIntStateOf(prefs.returnSeconds) }
                    if (prefs.returnSeconds > 0) LaunchedEffect(Unit) {
                        while (left > 0) { delay(1_000); left-- }
                        moveTaskToBack(true)
                    }
                    BigButton(if (prefs.returnSeconds > 0) "Повернутись назад ($left)" else "Повернутись назад", Color(0xFF2E7D32)) { moveTaskToBack(true) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BigButton("Назад (YouTube)", Color(0xFF455A64), Modifier.weight(1f)) { moveTaskToBack(true) }
                        BigButton("Скасувати об'їзд", Color(0xFFC62828), Modifier.weight(1f)) {
                            TruckAccessibilityService.instance?.cancelNav(true)
                        }
                    }
                    BigButton("Відкрити Waze", Color(0xFF1565C0)) {
                        packageManager.getLaunchIntentForPackage("com.waze")?.let { startActivity(it) }
                    }
                }
            }
        }
    }

    @Composable
    private fun BigButton(text: String, color: Color, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) {
        Button(onClick = onClick, modifier = modifier.height(58.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White)) {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        }
    }

    @Composable
    private fun RouteMap(n: NavUi, here: LatLon?) {
        val map = remember {
            MapView(this).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            }
        }
        DisposableEffect(Unit) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
        var fitted by remember { mutableIntStateOf(0) }
        AndroidView({ map }, Modifier.fillMaxSize()) { m ->
            m.overlays.clear()
            fun line(pts: List<LatLon>, color: Int, w: Float) = Polyline(m).apply {
                setPoints(pts.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = color; outlinePaint.strokeWidth = w
            }
            m.overlays += line(n.plan.main.points, AColor.argb(200, 120, 130, 140), 10f)
            m.overlays += line(n.route.points, AColor.parseColor("#2F8CFF"), 16f)
            m.overlays += Marker(m).apply {
                position = GeoPoint(n.plan.avoid.lat, n.plan.avoid.lon)
                title = n.plan.avoidName
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                    paint.color = AColor.parseColor("#E53935"); intrinsicWidth = 44; intrinsicHeight = 44
                }
            }
            here?.let {
                m.overlays += Marker(m).apply {
                    position = GeoPoint(it.lat, it.lon)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.OvalShape()).apply {
                        paint.color = AColor.WHITE; intrinsicWidth = 40; intrinsicHeight = 40
                    }
                }
            }
            if (fitted == 0 && n.route.points.size > 1) {
                fitted = 1
                val pts = n.route.points + n.plan.avoid
                val box = BoundingBox.fromGeoPoints(pts.map { GeoPoint(it.lat, it.lon) })
                m.post { runCatching { m.zoomToBoundingBox(box.increaseByScale(1.25f), false) } }
            }
            m.invalidate()
        }
    }

    override fun onResume() {
        super.onResume()
        DriveState.navVisible.value = true
    }

    override fun onPause() {
        DriveState.navVisible.value = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
