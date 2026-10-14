package com.truckbro.app.service

import com.truckbro.app.road.BypassPlan
import com.truckbro.app.road.LatLon
import com.truckbro.app.road.NavProgress
import com.truckbro.app.road.NavRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Активний об'їзд — для екрана навігації. */
data class NavUi(
    val plan: BypassPlan,
    val route: NavRoute,
    val progress: NavProgress?,
    val finished: Boolean,
    val demo: Boolean,
)

/** Спільний стан між фоновим сервісом і екранами програми. */
object DriveState {
    internal val _here = MutableStateFlow<LatLon?>(null)
    val here: StateFlow<LatLon?> = _here
    internal val _heading = MutableStateFlow<Double?>(null)
    val heading: StateFlow<Double?> = _heading
    internal val _nav = MutableStateFlow<NavUi?>(null)
    val nav: StateFlow<NavUi?> = _nav
    /** Коли востаннє бачили вікно Waze (мс, uptime). 0 — ще не бачили. */
    internal val _wazeSeenAt = MutableStateFlow(0L)
    val wazeSeenAt: StateFlow<Long> = _wazeSeenAt
    /** Екран навігації відкритий — маленьку картку поверх нього не показуємо. */
    val navVisible = MutableStateFlow(false)
    internal val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
}
