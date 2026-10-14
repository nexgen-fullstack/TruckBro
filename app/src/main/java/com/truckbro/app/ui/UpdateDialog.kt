package com.truckbro.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truckbro.app.util.UpdateChecker
import com.truckbro.app.util.Updater
import kotlinx.coroutines.launch

/** Віконце «Є нова версія → Оновити»: завантажує й відкриває системне встановлення. */
@Composable
fun UpdateDialog(u: UpdateChecker.Update, resumeTick: Int, onLater: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updater.state.collectAsState()
    // Повернулись із налаштувань дозволу — продовжуємо самі.
    LaunchedEffect(resumeTick) { Updater.onResume(ctx) }

    AlertDialog(
        onDismissRequest = { if (state !is Updater.State.Downloading) { Updater.reset(); onLater() } },
        title = { Text("Нова версія TruckBro ${u.version}", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                when (val s = state) {
                    is Updater.State.Downloading -> {
                        Text("Завантажую… ${(s.progress * 100).toInt()}%", fontSize = 16.sp)
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    Updater.State.NeedPermission -> Text(
                        "Один раз дозволь TruckBro встановлювати оновлення:\n" +
                            "увімкни перемикач «Дозволити з цього джерела» і натисни «Назад» ←.\n" +
                            "Далі все продовжиться саме.", fontSize = 16.sp)
                    Updater.State.Installing -> Text(
                        "У вікні Android натисни «Оновити» (або «Встановити»).\n" +
                            "Налаштування й журнал збережуться.", fontSize = 16.sp)
                    is Updater.State.Failed -> Text(s.message, color = Color(0xFFFF8A80), fontSize = 16.sp)
                    Updater.State.Idle -> {
                        Text("Натисни «Оновити» — все завантажиться і встановиться саме. Налаштування збережуться.",
                            fontSize = 16.sp)
                        val notes = u.notes.trim()
                        if (notes.isNotEmpty()) Text("Що нового:\n" + notes.take(700), fontSize = 14.sp, color = Color(0xFFB0BEC5))
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                is Updater.State.Downloading -> {}
                Updater.State.NeedPermission -> Button(onClick = { Updater.openPermissionSettings(ctx) }) {
                    Text("Відкрити дозвіл")
                }
                else -> Button(
                    onClick = { scope.launch { Updater.start(ctx, u) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32), contentColor = Color.White),
                    modifier = Modifier.height(52.dp),
                ) {
                    Text(if (state == Updater.State.Idle) "Оновити" else "Ще раз", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (state !is Updater.State.Downloading) TextButton(onClick = { Updater.reset(); onLater() }) { Text("Пізніше") }
        },
    )
}
