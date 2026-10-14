package com.truckbro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truckbro.app.data.Prefs
import com.truckbro.app.data.VoiceLang
import com.truckbro.app.road.HazardType
import com.truckbro.app.service.TruckAccessibilityService
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(modifier: Modifier) {
    val ctx = LocalContext.current
    val p = remember { Prefs(ctx) }
    var voiceOn by remember { mutableStateOf(p.voiceOn) }
    var lang by remember { mutableStateOf(p.voiceLang) }
    var opacity by remember { mutableFloatStateOf(p.flashOpacity) }
    var seconds by remember { mutableIntStateOf(p.flashSeconds) }
    var rate by remember { mutableFloatStateOf(p.flashRate) }
    var wazeLost by remember { mutableStateOf(p.wazeLostAlert) }
    var weigh by remember { mutableStateOf(p.weighAlerts) }
    var bypass by remember { mutableStateOf(p.bypassWeigh) }
    var bypassUnknown by remember { mutableStateOf(p.bypassUnknown) }
    var autoMin by remember { mutableIntStateOf(p.bypassAutoMinutes) }
    var bypassPolice by remember { mutableStateOf(p.bypassPolice) }
    var returnSec by remember { mutableIntStateOf(p.returnSeconds) }
    var height by remember { mutableFloatStateOf(p.heightFt) }
    var weight by remember { mutableIntStateOf(p.weightLbs) }
    var length by remember { mutableFloatStateOf(p.lengthFt) }
    var stops by remember { mutableStateOf(p.stopAlerts) }
    var legalOpen by remember { mutableStateOf(false) }
    var hazardTick by remember { mutableIntStateOf(0) }

    if (legalOpen) AlertDialog(
        onDismissRequest = { legalOpen = false },
        title = { Text("⚠ Перш ніж увімкнути") },
        text = {
            Text("У більшості штатів машина з повною масою понад 10 001 фунт (як F-450 з причепом) ЗОБОВ'ЯЗАНА заїжджати на відкриті ваги. " +
                "Навмисний об'їзд відкритої станції в багатьох штатах — порушення, і поліція спеціально чергує на об'їзних дорогах. " +
                "Штраф і перевірка машини можуть коштувати значно більше, ніж кілька хвилин на вагах.\n\n" +
                "Вмикаючи функцію, ти береш відповідальність на себе. TruckBro лише прокладає маршрут.", fontSize = 15.sp)
        },
        confirmButton = {
            Button(onClick = {
                p.bypassLegalAccepted = true; p.bypassWeigh = true; bypass = true; legalOpen = false
            }) { Text("Зрозумів, увімкнути") }
        },
        dismissButton = { TextButton(onClick = { legalOpen = false }) { Text("Скасувати") } },
    )

    LazyColumn(modifier.padding(horizontal = 16.dp), contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SectionTitle("ГОЛОС") }
        item {
            SectionCard {
                SwitchRow("Голосові попередження", voiceOn) { voiceOn = it; p.voiceOn = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoiceLang.entries.forEach { l ->
                        FilterChip(selected = lang == l, onClick = { lang = l; p.voiceLang = l }, label = { Text(l.label) })
                    }
                }
                Hint("YouTube не стишується — підказки звучать поверх.")
            }
        }
        item { SectionTitle("МИГАЛКА") }
        item {
            SectionCard {
                Text("Прозорість кольору: ${(opacity * 100).roundToInt()}%", fontSize = 16.sp)
                Slider(opacity, { opacity = it }, valueRange = 0.1f..0.6f, onValueChangeFinished = { p.flashOpacity = opacity })
                Text("Тривалість: $seconds с", fontSize = 16.sp)
                Slider(seconds.toFloat(), { seconds = it.roundToInt() }, valueRange = 2f..10f, steps = 7,
                    onValueChangeFinished = { p.flashSeconds = seconds })
                Text("Швидкість: ${"%.1f".format(rate)} перемикань/с", fontSize = 16.sp)
                Slider(rate, { rate = it }, valueRange = 1f..3f, onValueChangeFinished = { p.flashRate = rate })
                Hint("Не швидше 3 разів на секунду — безпечно для очей.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { TruckAccessibilityService.instance?.demoHazard(HazardType.POLICE, 2.0) ?: needService(ctx) },
                        modifier = Modifier.weight(1f)) { Text("Тест поліції") }
                    Button(onClick = { TruckAccessibilityService.instance?.demoHazard(HazardType.CRASH, 1.0) ?: needService(ctx) },
                        modifier = Modifier.weight(1f)) { Text("Тест перешкоди") }
                }
            }
        }
        item { SectionTitle("ПОДІЇ З WAZE (голос / мигалка)") }
        item {
            SectionCard {
                Hint("Для кожного типу окремо. Наприклад, «Машина на узбіччі» можна вимкнути зовсім.")
                Row(Modifier.fillMaxWidth()) {
                    Text("", Modifier.weight(1f))
                    Text("Голос", fontSize = 13.sp, modifier = Modifier.padding(end = 10.dp))
                    Text("Мигалка", fontSize = 13.sp)
                }
            }
        }
        items(HazardType.entries.toList()) { t ->
            hazardTick.let { }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(t.uk.replaceFirstChar { it.uppercase() }, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Checkbox(p.hazardVoice(t), { p.setHazardVoice(t, it); hazardTick++ })
                Checkbox(p.hazardFlash(t), { p.setHazardFlash(t, it); hazardTick++ })
            }
        }
        item {
            SectionCard {
                SwitchRow("Попереджати, якщо Waze закрився", wazeLost, "Під час руху, якщо вікна Waze не видно 45 секунд.") {
                    wazeLost = it; p.wazeLostAlert = it
                }
            }
        }
        item { SectionTitle("ВАГИ") }
        item {
            SectionCard {
                SwitchRow("Попереджати про ваги", weigh, "За 10, 5 і 1 милю, зі статусом.") { weigh = it; p.weighAlerts = it }
                SwitchRow("Об'їзд відкритих ваг", bypass, "Вимкнено за замовчуванням. Прочитай попередження.") {
                    if (it && !p.bypassLegalAccepted) legalOpen = true else { bypass = it; p.bypassWeigh = it }
                }
                if (bypass) {
                    SwitchRow("Будувати об'їзд і при невідомому статусі", bypassUnknown) { bypassUnknown = it; p.bypassUnknown = it }
                    Text("Сам вмикати об'їзд, якщо він довший не більше ніж на: $autoMin хв", fontSize = 16.sp)
                    Slider(autoMin.toFloat(), { autoMin = it.roundToInt() }, valueRange = 0f..30f, steps = 29,
                        onValueChangeFinished = { p.bypassAutoMinutes = autoMin })
                    Hint("Довший об'їзд TruckBro теж запропонує, але ввімкне тільки після натискання «Об'їжджати».")
                }
                SwitchRow("Пропонувати об'їзд поліції", bypassPolice, "Завжди з підтвердженням. Місце поліції — приблизне.") {
                    bypassPolice = it; p.bypassPolice = it
                }
                Text("Після об'їзду повернутись у YouTube через: ${if (returnSec == 0) "не повертатись" else "$returnSec с"}", fontSize = 16.sp)
                Slider(returnSec.toFloat(), { returnSec = it.roundToInt() }, valueRange = 0f..30f, steps = 5,
                    onValueChangeFinished = { p.returnSeconds = returnSec })
            }
        }
        item { SectionTitle("МАШИНА (для маршрутів об'їзду)") }
        item {
            SectionCard {
                Hint("Ford F-450 з причепом-автовозом. Маршрутизатор не поведе під нижчі мости й дорогами, заборонені для такої ваги чи довжини.")
                Text("Висота з вантажем: ${"%.1f".format(height)} ft", fontSize = 16.sp)
                Slider(height, { height = (it * 2).roundToInt() / 2f }, valueRange = 8f..14.5f, onValueChangeFinished = { p.heightFt = height })
                Text("Повна маса з причепом: ${"%,d".format(weight)} lbs", fontSize = 16.sp)
                Slider(weight.toFloat(), { weight = (it / 500).roundToInt() * 500 }, valueRange = 10_000f..40_000f,
                    onValueChangeFinished = { p.weightLbs = weight })
                Text("Довжина з причепом: ${length.roundToInt()} ft", fontSize = 16.sp)
                Slider(length, { length = it.roundToInt().toFloat() }, valueRange = 20f..75f, onValueChangeFinished = { p.lengthFt = length })
            }
        }
        item { SectionTitle("ТРАКСТОПИ") }
        item {
            SectionCard {
                SwitchRow("Попереджати про червоні тракстопи", stops, "За ~1,5 милі, з підказкою найближчого зеленого.") {
                    stops = it; p.stopAlerts = it
                }
            }
        }
        item {
            Column(Modifier.padding(top = 8.dp)) {
                Text("Про програму", fontWeight = FontWeight.Bold)
                Hint("TruckBro читає тільки попередження, які Waze уже показав на цьому телефоні, нічого не натискає й нікуди не надсилає вміст екрана. Маршрути об'їзду рахує безкоштовний сервер Valhalla (OpenStreetMap).")
            }
        }
    }
}
