package com.truckbro.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.truckbro.app.service.TruckAccessibilityService
import kotlinx.coroutines.delay

/**
 * Імітація екрана Waze: показує картки «Police 2.0 mi», потім «0.9 mi», потім «Object on road».
 * TruckBro читає цей екран так само, як справжній Waze, — так можна перевірити весь ланцюжок.
 */
class DemoWazeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TruckTheme {
                var step by remember { mutableIntStateOf(0) }
                LaunchedEffect(Unit) { while (step < STEPS.size - 1) { delay(7_000); step++ } }
                val (title, dist) = STEPS[step]
                Column(Modifier.fillMaxSize().background(Color(0xFF2B3A4A)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(TruckAccessibilityService.DEMO_NAV_MARKER, fontSize = 1.sp, color = Color.Transparent)
                    Text("Імітація Waze (крок ${step + 1} з ${STEPS.size})", color = Color.White, fontSize = 15.sp)
                    if (title.isNotEmpty()) Column(
                        Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(16.dp)).padding(16.dp),
                    ) {
                        Text(title, color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(dist, color = Color.DarkGray, fontSize = 20.sp)
                        Text("Reported 4 min ago", color = Color.Gray, fontSize = 14.sp)
                    }
                    Text("Кожні 7 секунд картка змінюється. Очікується: 🚨 за 2 милі → 🚨 за 1 милю → ⚠ на лівій смузі.",
                        color = Color(0xFFB4BEC8), fontSize = 14.sp)
                }
            }
        }
    }

    companion object {
        private val STEPS = listOf(
            "Police" to "2.0 mi",
            "Police" to "1.4 mi",
            "Police" to "0.9 mi",
            "" to "",
            "Object on road" to "Left lane · 0.8 mi",
            "Object on road" to "Left lane · 0.3 mi",
        )
    }
}
