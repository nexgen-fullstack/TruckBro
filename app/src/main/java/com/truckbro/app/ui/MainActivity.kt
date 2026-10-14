package com.truckbro.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truckbro.app.BuildConfig
import com.truckbro.app.data.Prefs
import com.truckbro.app.road.HazardType
import com.truckbro.app.service.DriveState
import com.truckbro.app.service.Speaker
import com.truckbro.app.service.TruckAccessibilityService
import com.truckbro.app.util.Sos
import com.truckbro.app.util.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val tab = mutableIntStateOf(0)
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TruckTheme { App(tab.intValue, { tab.intValue = it }, resumeTick.intValue) }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }
}

@Composable
private fun App(tab: Int, setTab: (Int) -> Unit, resumeTick: Int) {
    val items = listOf("Головна" to Icons.Filled.Home, "Ваги" to Icons.Filled.Warning,
        "Тракстопи" to Icons.Filled.LocationOn, "Журнал" to Icons.Filled.List, "Налашт." to Icons.Filled.Settings)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF181C20)) {
                items.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(selected = tab == i, onClick = { setTab(i) },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp) })
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad).fillMaxSize()
        when (tab) {
            0 -> HomeScreen(m, resumeTick)
            1 -> WeighScreen(m)
            2 -> StopsScreen(m)
            3 -> JournalScreen(m)
            else -> SettingsScreen(m)
        }
    }
}

fun isIgnoringBattery(ctx: Context): Boolean =
    (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

fun hasPerm(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

fun fmtTime(ms: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale("uk")).format(Date(ms))

fun needService(ctx: Context) =
    Toast.makeText(ctx, "Спершу увімкни TruckBro у «Спеціальних можливостях» (крок 1)", Toast.LENGTH_LONG).show()

private var updatePromptShown = false

@Composable
fun HomeScreen(modifier: Modifier, resumeTick: Int) {
    val ctx = LocalContext.current
    val running by DriveState.running.collectAsState()
    val wazeSeen by DriveState.wazeSeenAt.collectAsState()
    val nav by DriveState.nav.collectAsState()
    val battery = remember(resumeTick) { isIgnoringBattery(ctx) }
    val fine = remember(resumeTick) { hasPerm(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val bg = remember(resumeTick) { Build.VERSION.SDK_INT < 29 || hasPerm(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) }
    val notifOk = remember(resumeTick) { Build.VERSION.SDK_INT < 33 || hasPerm(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val wazeInstalled = remember(resumeTick) { ctx.packageManager.getLaunchIntentForPackage("com.waze") != null }
    var update by remember { mutableStateOf(UpdateChecker.cached(Prefs(ctx))) }
    var updateOpen by remember { mutableStateOf(false) }
    update?.let { u -> if (updateOpen) UpdateDialog(u, resumeTick) { updateOpen = false } }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        TruckAccessibilityService.instance?.onLocationPermission()
    }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TruckAccessibilityService.instance?.onLocationPermission()
    }

    // Тестовий голос працює навіть без увімкненого сервісу.
    val testSpeaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { testSpeaker.shutdown() } }
    var missing by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(resumeTick) {
        repeat(50) { if (testSpeaker.ukrainianMissing != null) return@repeat; kotlinx.coroutines.delay(200) }
        missing = testSpeaker.ukrainianMissing
    }
    LaunchedEffect(Unit) {
        update = runCatching { UpdateChecker.check(ctx) }.getOrNull()
        if (update != null && !updatePromptShown) { updatePromptShown = true; updateOpen = true }
    }

    val ready = running && fine && bg
    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(
                containerColor = if (ready) Color(0xFF1B5E20) else Color(0xFF8E1B1B))) {
                Column(Modifier.padding(18.dp).fillMaxWidth()) {
                    Text(if (ready) "🛻 TruckBro працює" else "⚠ TruckBro ще не готовий", fontSize = 24.sp,
                        fontWeight = FontWeight.Bold, color = Color.White)
                    Text(
                        if (ready) "Відкрий Waze маленьким вікном, проклади маршрут і дивись YouTube. Поліцію, перешкоди, ваги й небезпечні тракстопи TruckBro скаже голосом і покаже мигалкою."
                        else "Виконай кроки нижче (з червоним знаком). Це потрібно зробити лише один раз.",
                        color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                    if (running) Text(
                        if (wazeSeen > 0 && android.os.SystemClock.uptimeMillis() - wazeSeen < 30_000) "Waze: бачу ✓" else "Waze: зараз не бачу",
                        color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
        nav?.let { n ->
            item {
                Button(onClick = { TruckAccessibilityService.instance?.openNav() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(60.dp), shape = RoundedCornerShape(18.dp)) {
                    Text(if (n.finished) "Об'їзд закінчено" else "↪ Відкрити об'їзд", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        update?.let { u ->
            item {
                SectionCard {
                    Text("Є нова версія ${u.version}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = { updateOpen = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Оновити", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
        item {
            SectionCard {
                StepRow(running, "1. Дозвіл «Спеціальні можливості»")
                if (!running) {
                    Hint("Через нього TruckBro читає попередження Waze і показує мигалку поверх YouTube. Натисни кнопку → «Встановлені програми» → «TruckBro» → увімкни → «Дозволити».")
                    Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                        modifier = Modifier.fillMaxWidth()) { Text("Відкрити Спеціальні можливості") }
                    Hint("Якщо перемикач сірий («Обмежене налаштування»): кнопка нижче → три крапки ⋮ угорі справа → «Дозволити обмежені налаштування» → повернись і ввімкни ще раз.")
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Відкрити сторінку програми") }
                } else Hint("Готово. TruckBro читає тільки Waze і Google Maps.")
            }
        }
        item {
            SectionCard {
                StepRow(fine && bg, "2. Геолокація «Дозволяти завжди»")
                if (!fine) {
                    Hint("Потрібна для ваг, тракстопів і об'їзду, коли на екрані YouTube.")
                    Button(onClick = {
                        fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    }, modifier = Modifier.fillMaxWidth()) { Text("Дозволити геолокацію") }
                } else if (!bg) {
                    Hint("Ще один крок: у вікні вибери «Дозволяти завжди» (Allow all the time).")
                    Button(onClick = {
                        if (Build.VERSION.SDK_INT >= 29) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Дозволяти завжди") }
                } else Hint("Готово.")
            }
        }
        item {
            SectionCard {
                StepRow(battery, "3. Робота без перерв")
                if (!battery) {
                    Hint("Щоб телефон не «присипляв» TruckBro у дорозі.")
                    Button(onClick = {
                        runCatching {
                            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                        }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Дозволити роботу у фоні") }
                    Hint("Samsung: також Налаштування → Батарея → Обмеження фонового використання → «Ніколи не присипляти» → додай TruckBro. Xiaomi: Безпека → Автозапуск → TruckBro.")
                } else Hint("Готово.")
            }
        }
        item {
            SectionCard {
                StepRow(wazeInstalled, "4. Waze маленьким вікном")
                Hint(
                    if (!wazeInstalled) "Встанови Waze з Google Play."
                    else "Samsung: відкрий Waze → кнопка «Останні програми» (☰ або ▢) → натисни іконку Waze угорі → «Відкрити у спливаючому вікні». Зменш вікно, потягнувши за кут, але так, щоб картка попередження Waze ще вміщалась. Потім відкривай YouTube — Waze лишиться поверх.\n" +
                        "Інші телефони: ті самі «Останні програми» → іконка Waze → «Вікно» / «Floating window» / «Розділити екран»."
                )
                if (wazeInstalled) OutlinedButton(onClick = {
                    ctx.packageManager.getLaunchIntentForPackage("com.waze")?.let { ctx.startActivity(it) }
                }) { Text("Відкрити Waze") }
            }
        }
        item {
            SectionCard {
                StepRow(missing == false, "5. Голос", optional = true)
                Hint(when (missing) {
                    true -> "Українського голосу на телефоні немає — поки що підказки будуть англійською. Щоб додати: кнопка нижче → Google → «Встановити голосові дані» → Українська."
                    false -> "Український голос є."
                    null -> "Перевіряю голос…"
                })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val p = Prefs(ctx)
                        testSpeaker.speak(if (testSpeaker.effectiveLang(p.voiceLang) == com.truckbro.app.data.VoiceLang.UK)
                            "Увага! Поліція попереду, за 2 милі." else "Police ahead, 2 miles.", p.voiceLang)
                    }) { Text("Перевірити голос") }
                    if (missing == true) OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
                    }) { Text("Додати голос") }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) item {
            SectionCard {
                StepRow(notifOk, "6. Сповіщення", optional = true)
                if (!notifOk) {
                    Hint("Щоб TruckBro міг сказати, якщо Waze закрився, і повідомити про нову версію.")
                    OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Дозволити") }
                } else Hint("Готово.")
            }
        }
        item { TestsCard() }
        item {
            SectionCard {
                Text("🆘 SOS", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Hint("Надіслати своє місце на карті в Telegram (вибери чат) або подзвонити 911.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { Sos.telegram(ctx, Sos.message(DriveState.here.value)) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828), contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(54.dp)) { Text("Telegram", fontWeight = FontWeight.Bold) }
                    Button(onClick = { Sos.call911(ctx) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8E1B1B), contentColor = Color.White),
                        modifier = Modifier.weight(1f).height(54.dp)) { Text("911", fontWeight = FontWeight.Bold) }
                }
            }
        }
        item { Hint("TruckBro ${BuildConfig.VERSION_NAME} · карти й дані: © OpenStreetMap contributors, FBI Crime Data Explorer · маршрути: Valhalla (FOSSGIS)") }
    }
}

@Composable
private fun TestsCard() {
    val ctx = LocalContext.current
    SectionCard {
        Text("Перевірити", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Hint("Натисни — побачиш і почуєш, як це буде в дорозі. Мигалка з'являється поверх усіх програм.")
        fun svc(block: (TruckAccessibilityService) -> Unit) {
            TruckAccessibilityService.instance?.let(block) ?: needService(ctx)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { svc { it.demoHazard(HazardType.POLICE, 2.0) } },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E64FF), contentColor = Color.White),
                modifier = Modifier.weight(1f).height(54.dp)) { Text("🚨 Поліція", fontWeight = FontWeight.Bold) }
            Button(onClick = { svc { it.demoHazard(HazardType.OBJECT, 1.0) } },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black),
                modifier = Modifier.weight(1f).height(54.dp)) { Text("⚠ Перешкода", fontWeight = FontWeight.Bold) }
        }
        Button(onClick = { svc { it.simulateBypass() } },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0), contentColor = Color.White),
            modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("⚖ Тест ваг і об'їзду (імітація поїздки)", fontWeight = FontWeight.Bold) }
        Hint("Імітація: машина «їде» по I-57 в Іллінойсі до відкритих ваг. TruckBro порахує справжній об'їзд (потрібен інтернет), покаже маленьке вікно і поведе голосом. Рух прискорений, займе 2–3 хвилини. Щоб зупинити — «Скасувати».")
        OutlinedButton(onClick = { ctx.startActivity(Intent(ctx, DemoWazeActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
            Text("Перевірити читання екрана (імітація Waze)")
        }
    }
}
