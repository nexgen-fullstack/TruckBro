package com.truckbro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truckbro.app.data.Journal
import com.truckbro.app.data.MapData
import com.truckbro.app.road.GeoMath
import com.truckbro.app.road.LatLon
import com.truckbro.app.road.METERS_PER_MILE
import com.truckbro.app.road.TruckStop
import com.truckbro.app.road.WeighStatus
import com.truckbro.app.road.Zone
import com.truckbro.app.service.DriveState
import com.truckbro.app.util.Sos
import kotlinx.coroutines.launch

private fun miles(a: LatLon?, b: LatLon): String =
    a?.let { "%.1f mi".format(java.util.Locale.US, GeoMath.distance(it, b) / METERS_PER_MILE) } ?: ""

@Composable
private fun DataHeader(title: String, count: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val here by DriveState.here.collectAsState()
    val err by MapData.lastError.collectAsState()
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { MapData.load(ctx) }
    SectionCard {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Hint(if (here == null) "Місце ще невідоме — потрібні GPS і дозвіл геолокації." else "Поруч (до ~70 миль): $count. Дані оновлюються самі, коли їдеш.")
        err?.let { Text(it, color = Color(0xFFFF8A80), fontSize = 14.sp) }
        OutlinedButton(enabled = here != null && !loading, onClick = {
            val h = here ?: return@OutlinedButton
            loading = true
            scope.launch { MapData.ensureAround(ctx, h, force = true); loading = false }
        }) { Text(if (loading) "Завантажую…" else "Оновити зараз") }
    }
}

@Composable
fun WeighScreen(modifier: androidx.compose.ui.Modifier) {
    val ctx = LocalContext.current
    val list by MapData.weigh.collectAsState()
    val statuses by MapData.statuses.collectAsState()
    val here by DriveState.here.collectAsState()
    val near = remember(list, here) {
        val h = here
        if (h == null) list.take(30) else list.map { it to GeoMath.distance(h, it.pos) }
            .filter { it.second < 115_000 }.sortedBy { it.second }.map { it.first }
    }
    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DataHeader("⚖ Ваги поруч", near.size) }
        item { Hint("Статус «відкрито/закрито» ставиш ти (табло на дорозі) — він діє 3 години. Безкоштовного джерела статусів у реальному часі немає.") }
        items(near, key = { it.id }) { s ->
            statuses.size // перемальовка при зміні статусу
            val st = MapData.status(s.id)
            SectionCard {
                Text(s.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Hint("${miles(here, s.pos)} · ${when (st) { WeighStatus.OPEN -> "ВІДКРИТІ"; WeighStatus.CLOSED -> "закриті"; else -> "статус невідомий" }}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { MapData.setStatus(ctx, s.id, WeighStatus.OPEN) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                        modifier = Modifier.weight(1f)) { Text("Відкриті") }
                    Button(onClick = { MapData.setStatus(ctx, s.id, WeighStatus.CLOSED) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                        modifier = Modifier.weight(1f)) { Text("Закриті") }
                }
            }
        }
    }
}

@Composable
fun StopsScreen(modifier: androidx.compose.ui.Modifier) {
    val ctx = LocalContext.current
    val list by MapData.stops.collectAsState()
    val marks by MapData.marks.collectAsState()
    val here by DriveState.here.collectAsState()
    var editing by remember { mutableStateOf<TruckStop?>(null) }
    val near = remember(list, here, marks) {
        val h = here
        val sorted = if (h == null) list.take(40) else list.map { it to GeoMath.distance(h, it.pos) }
            .filter { it.second < 115_000 }.sortedBy { it.second }.map { it.first }
        sorted.map { it to MapData.rating(ctx, it) }
    }
    editing?.let { s -> MarkDialog(s) { editing = null } }
    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { DataHeader("🅿 Тракстопи поруч", near.size) }
        item {
            Hint("Колір — за рівнем насильницьких злочинів у місті (дані ФБР): зелена — спокійно, жовта — обережно, " +
                "червона — краще не зупинятися. За містом статистики немає (сірий). Твоя позначка важливіша за статистику — натисни «Позначити».")
        }
        items(near, key = { it.first.id }) { (s, r) ->
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ZoneDot(r.zone, 18); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.name, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Hint("${miles(here, s.pos)}${if (s.city.isNotBlank()) " · ${s.city}" else ""} · ${r.zone.title.lowercase()}: ${r.reason}")
                        r.note?.let { Text("📝 $it", fontSize = 14.sp) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = s }, modifier = Modifier.weight(1f)) { Text("Позначити") }
                    OutlinedButton(onClick = { Sos.openInWaze(ctx, s.pos) }, modifier = Modifier.weight(1f)) { Text("Їхати (Waze)") }
                }
            }
        }
    }
}

@Composable
private fun MarkDialog(s: TruckStop, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val cur = MapData.marks.value[s.id]
    var note by remember { mutableStateOf(cur?.second.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("Твоя оцінка цього тракстопу. Її побачиш тільки ти, вона важливіша за статистику.")
                OutlinedTextField(note, { note = it }, label = { Text("Нотатка (напр. «крадуть каталізатори»)") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Zone.GREEN, Zone.YELLOW, Zone.RED).forEach { z ->
                        Button(onClick = { MapData.setMark(ctx, s.id, z, note); onClose() },
                            colors = ButtonDefaults.buttonColors(containerColor = z.color(), contentColor = z.onColor()),
                            modifier = Modifier.weight(1f).height(52.dp), contentPadding = PaddingValues(2.dp)) {
                            Text(z.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрити") } },
        dismissButton = { if (cur != null) TextButton(onClick = { MapData.setMark(ctx, s.id, null, ""); onClose() }) { Text("Прибрати позначку") } },
    )
}

@Composable
fun JournalScreen(modifier: androidx.compose.ui.Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Journal.load(ctx) }
    val entries by Journal.entries.collectAsState()
    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Журнал подій", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { Journal.clear(ctx) }) { Text("Очистити") }
            }
            Hint("Що TruckBro побачив і що сказав. Якщо щось спрацювало не так — тут видно, що саме.")
        }
        if (entries.isEmpty()) item { Hint("Поки порожньо.") }
        items(entries) { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text(fmtTime(e.time), fontSize = 13.sp, color = Color(0xFF8A96A3), modifier = Modifier.width(92.dp))
                Text(icon(e.kind) + " " + e.text, fontSize = 14.sp)
            }
        }
    }
}

private fun icon(kind: String) = when (kind) {
    "police" -> "🚨"; "hazard" -> "⚠"; "weigh" -> "⚖"; "bypass" -> "↪"; "stop" -> "🅿"; "waze" -> "📵"; else -> "•"
}
