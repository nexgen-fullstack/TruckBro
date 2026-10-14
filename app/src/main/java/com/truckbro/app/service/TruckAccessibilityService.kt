package com.truckbro.app.service

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.truckbro.app.data.Journal
import com.truckbro.app.data.MapData
import com.truckbro.app.data.Prefs
import com.truckbro.app.data.VoiceLang
import com.truckbro.app.road.AvoidKind
import com.truckbro.app.road.BypassPlan
import com.truckbro.app.road.BypassPlanner
import com.truckbro.app.road.Decision
import com.truckbro.app.road.GeoMath
import com.truckbro.app.road.HazardAlert
import com.truckbro.app.road.HazardParser
import com.truckbro.app.road.HazardTracker
import com.truckbro.app.road.HazardType
import com.truckbro.app.road.LatLon
import com.truckbro.app.road.METERS_PER_MILE
import com.truckbro.app.road.NavEngine
import com.truckbro.app.road.NavEvent
import com.truckbro.app.road.PlanResult
import com.truckbro.app.road.Routing
import com.truckbro.app.road.ScreenText
import com.truckbro.app.road.StationAhead
import com.truckbro.app.road.WeighStatus
import com.truckbro.app.road.WeighWatcher
import com.truckbro.app.road.Zone
import com.truckbro.app.ui.NavActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Серце програми. Працює у фоні, поки водій дивиться YouTube чи будь-що інше:
 * - читає попередження на екрані Waze (поліція, аварії, перешкоди) → голос і мигалка;
 * - за GPS стежить за вагами й тракстопами попереду;
 * - будує й веде об'їзд відкритих ваг.
 * Сервіс нічого не натискає в інших програмах.
 */
class TruckAccessibilityService : AccessibilityService(), LocationListener {

    companion object {
        private const val TAG = "TruckBro"
        const val WAZE = "com.waze"
        const val GMAPS = "com.google.android.apps.maps"
        /** Маркер тестового екрана в самій програмі: його текст читається як екран Waze. */
        const val DEMO_NAV_MARKER = "TRUCKBRO_DEMO_NAV"
        private val NAV_APPS = setOf(WAZE, GMAPS)
        private const val CHANNEL = "truckbro_alerts"

        @Volatile
        var instance: TruckAccessibilityService? = null
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: Prefs
    private lateinit var lights: AlertOverlay
    private lateinit var card: CardOverlay
    private lateinit var speaker: Speaker
    private lateinit var lm: LocationManager

    // ---------- Читання Waze ----------
    private val tracker = HazardTracker()
    private val scanRunnable = Runnable { scan() }
    private var firstPendingAt = 0L
    private var lastWazeVisible = 0L
    private var wazeLostAnnounced = false

    // ---------- GPS ----------
    private var gpsMode = 0 // 0 — вимкнено, 1 — економно, 2 — у дорозі
    private var last: Location? = null
    private var odo = 0.0
    private var movingAt = 0L
    private var lastDataCheck = 0L
    private var lastStopsCheck = 0L

    // ---------- Ваги й об'їзд ----------
    private val weighWatcher = WeighWatcher()
    private var cardStation: String? = null
    private val plannedFor = HashMap<String, WeighStatus>()
    private var planJob: Job? = null
    private var pendingAsk: BypassPlan? = null
    private var pendingAskOdo = 0.0
    private var engine: NavEngine? = null
    private var activePlan: BypassPlan? = null
    private var rerouteJob: Job? = null
    private var lastRerouteAt = 0L
    private var demo = false
    private var simJob: Job? = null
    private val hideCard = Runnable { if (engine == null && pendingAsk == null) { card.hide(); cardStation = null } }

    // ---------- Тракстопи ----------
    private val stopAnnounced = HashMap<String, Long>()

    private val lang: VoiceLang get() = speaker.effectiveLang(prefs.voiceLang)

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        lights = AlertOverlay(this)
        card = CardOverlay(this)
        speaker = Speaker(this)
        lm = getSystemService(LOCATION_SERVICE) as LocationManager
        instance = this
        DriveState._running.value = true
        Journal.load(this)
        scope.launch { MapData.load(this@TruckAccessibilityService) }
        setGps(1)
        handler.post(tick)
        scope.launch { DriveState.navVisible.collect { card.setHidden(it) } }
        Journal.add(this, "service", "TruckBro увімкнено")
    }

    // =====================================================================
    // Waze: читаємо екран
    // =====================================================================

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg !in NAV_APPS && pkg != packageName) return
        val now = SystemClock.uptimeMillis()
        if (firstPendingAt == 0L) firstPendingAt = now
        handler.removeCallbacks(scanRunnable)
        // Чекаємо, поки екран «заспокоїться», але не довше ~0,6 с від першої зміни.
        handler.postDelayed(scanRunnable, if (now - firstPendingAt > 600) 0L else 200L)
    }

    /** Кожні 3 секунди: перевірка Waze (картки оновлюються без подій), сторож «Waze зник». */
    private val tick = object : Runnable {
        override fun run() {
            scan()
            watchdog()
            handler.postDelayed(this, 3_000)
        }
    }

    private fun scan() {
        firstPendingAt = 0L
        val captured = try { collect() } catch (t: Throwable) { Log.w(TAG, "collect", t); return }
        val now = SystemClock.uptimeMillis()
        for ((pkg, items) in captured) {
            val isDemo = pkg == packageName
            if (isDemo && items.none { it.text == DEMO_NAV_MARKER }) continue
            if (!isDemo) {
                lastWazeVisible = now; DriveState._wazeSeenAt.value = now
                com.truckbro.app.road.WeighStatusParser.parse(items.map { it.text })?.let { onWazeWeighStatus(it) }
            }
            val sightings = HazardParser.parse(items.filter { it.text != DEMO_NAV_MARKER })
            if (com.truckbro.app.BuildConfig.DEBUG && isDemo) Log.d(TAG, "scan ${items.map { it.text }} -> $sightings")
            if (sightings.isEmpty()) continue
            onAlerts(tracker.onSightings(sightings, now, odo))
        }
        if (gpsMode < 2 && now - lastWazeVisible < 5_000) setGps(2)
    }

    private fun collect(): Map<String, List<ScreenText>> {
        val out = LinkedHashMap<String, MutableList<ScreenText>>()
        val roots = ArrayList<AccessibilityNodeInfo>()
        // Android кешує текст екрана; картка Waze може змінити відстань без події — читаємо наново.
        if (Build.VERSION.SDK_INT >= 34) runCatching { clearCache() }
        runCatching { for (w in windows) w.root?.let { roots += it } }
        if (roots.isEmpty()) rootInActiveWindow?.let { roots += it }
        val rect = android.graphics.Rect()
        for (root in roots) {
            val pkg = root.packageName?.toString() ?: continue
            if (pkg !in NAV_APPS && pkg != packageName) continue
            walk(root, out.getOrPut(pkg) { mutableListOf() }, 0, rect)
        }
        return out
    }

    private fun walk(node: AccessibilityNodeInfo, out: MutableList<ScreenText>, depth: Int, rect: android.graphics.Rect) {
        if (depth > 50 || out.size > 1500) return
        if (Build.VERSION.SDK_INT < 34 && depth > 0) node.refresh()
        if (!node.isVisibleToUser) return
        node.getBoundsInScreen(rect)
        val t = node.text?.toString()
        val d = node.contentDescription?.toString()
        if (!t.isNullOrBlank()) out += ScreenText(t, rect.top)
        if (!d.isNullOrBlank() && d != t) out += ScreenText(d, rect.top)
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, out, depth + 1, rect) }
    }

    private fun onAlerts(alerts: List<HazardAlert>) {
        // Якщо одночасно кілька — спершу найважливіше (перешкода → поліція), решту — голосом слідом.
        alerts.filter { prefs.hazardVoice(it.type) || prefs.hazardFlash(it.type) }.sortedBy { it.type.ordinal }
            .forEachIndexed { i, a -> announceHazard(a, first = i == 0) }
    }

    private fun announceHazard(a: HazardAlert, first: Boolean = true) {
        val sub = listOfNotNull(a.meters?.let { Phrases.short(it) }, a.lane).joinToString(" · ")
        if (first && prefs.hazardFlash(a.type)) lights.showLights(a.type, sub, flashStyle())
        if (prefs.voiceOn && prefs.hazardVoice(a.type)) speaker.speak(Phrases.hazard(a, lang), lang, flush = first)
        Journal.add(this, if (a.type.police) "police" else "hazard", "${a.type.title}${if (sub.isNotEmpty()) " · $sub" else ""}")
        // Об'їзд поліції — тільки якщо водій увімкнув, і тільки з підтвердженням.
        val here = DriveState.here.value; val heading = DriveState.heading.value
        if (a.type.police && prefs.bypassPolice && engine == null && pendingAsk == null && a.meters != null &&
            a.meters >= 1.3 * METERS_PER_MILE && here != null && heading != null
        ) {
            val spot = GeoMath.move(here, heading, a.meters)
            planBypass(AvoidKind.POLICE, "police", spot, "Police", here, heading)
        }
    }

    fun flashStyle() = FlashStyle(prefs.flashOpacity, prefs.flashSeconds, prefs.flashRate)

    /** Сторож: їдемо, а Waze не видно вже 45 с — кажемо один раз; коли повернувся — теж кажемо. */
    private fun watchdog() {
        val now = SystemClock.uptimeMillis()
        val driving = now - movingAt < 60_000
        val seenEver = lastWazeVisible > 0
        val lost = seenEver && now - lastWazeVisible > 45_000
        if (prefs.wazeLostAlert && driving && lost && !wazeLostAnnounced && !demo) {
            wazeLostAnnounced = true
            speaker.speak(Phrases.wazeLost(lang), lang)
            notifyWazeLost()
            Journal.add(this, "waze", "Waze не видно під час руху")
        } else if (wazeLostAnnounced && !lost) {
            wazeLostAnnounced = false
            cancelWazeLost()
            if (prefs.wazeLostAlert) speaker.speak(Phrases.wazeBack(lang), lang, flush = false)
        }
        // Waze давно не видно і машина стоїть — GPS на економний режим (батарея).
        if (gpsMode == 2 && engine == null && simJob == null && now - lastWazeVisible > 15 * 60_000 && now - movingAt > 10 * 60_000) setGps(1)
    }

    private fun notifyWazeLost() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Попередження TruckBro", NotificationManager.IMPORTANCE_HIGH))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = packageManager.getLaunchIntentForPackage(WAZE) ?: return
        val pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = android.app.Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Waze не видно")
            .setContentText("Натисни, щоб відкрити Waze знову. Без нього TruckBro не бачить поліцію й перешкоди.")
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        runCatching { nm.notify(7, n) }
    }

    private fun cancelWazeLost() = runCatching { getSystemService(NotificationManager::class.java).cancel(7) }

    // =====================================================================
    // GPS
    // =====================================================================

    private fun hasLocation() = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** 1 — економно (мережа, раз на хвилину), 2 — у дорозі (GPS щосекунди). */
    @SuppressLint("MissingPermission")
    private fun setGps(mode: Int) {
        if (mode == gpsMode || !hasLocation()) return
        runCatching { lm.removeUpdates(this) }
        gpsMode = mode
        runCatching {
            if (mode == 2) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this, Looper.getMainLooper())
            } else if (mode == 1) {
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 100f, this, Looper.getMainLooper())
                }
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30_000L, 50f, this, Looper.getMainLooper())
            }
        }.onFailure { Log.w(TAG, "gps", it) }
    }

    /** Після видачі дозволу на геолокацію (з екрана налаштування). */
    fun onLocationPermission() { gpsMode = 0; setGps(1) }

    override fun onLocationChanged(loc: Location) {
        if (simJob != null) return // під час симуляції справжній GPS не заважає
        onFix(loc)
    }

    private fun onFix(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > 80f && simJob == null) return
        val now = SystemClock.uptimeMillis()
        val here = LatLon(loc.latitude, loc.longitude)
        last?.let { odo += GeoMath.distance(LatLon(it.latitude, it.longitude), here).coerceAtMost(500.0) }
        last = loc
        DriveState._here.value = here
        val moving = loc.hasSpeed() && loc.speed > 4f
        if (moving) { movingAt = now; if (gpsMode < 2) setGps(2) }
        if (moving && loc.hasBearing()) DriveState._heading.value = loc.bearing.toDouble()
        val heading = DriveState.heading.value

        // Друге попередження про поліцію/перешкоду — навіть якщо картка Waze вже зникла.
        onAlerts(tracker.onTravel(odo, now))

        if (now - lastDataCheck > 60_000) {
            lastDataCheck = now
            scope.launch { MapData.ensureAround(applicationContext, here) }
        }

        engine?.let { navStep(it, here, loc.speed) }
        pendingAsk?.let { if (odo - pendingAskOdo > it.exitMeters - 150) expireAsk() }

        if (moving && heading != null) {
            weighStep(here, heading)
            if (now - lastStopsCheck > 10_000) { lastStopsCheck = now; stopsStep(here, heading) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    // =====================================================================
    // Ваги
    // =====================================================================

    private fun weighStep(here: LatLon, heading: Double) {
        if (!prefs.weighAlerts) return
        val ahead = weighWatcher.ahead(MapData.weigh.value, here, heading)
        // Проїхали станцію, про яку показували картку, — прибираємо.
        cardStation?.let { id -> if (ahead?.station?.id != id && engine == null && pendingAsk == null) { card.hide(); cardStation = null } }
        val due = weighWatcher.step(ahead)
        if (ahead != null && due != null) announceWeigh(ahead, due)
        if (ahead != null) maybePlanWeigh(ahead, here, heading)
    }

    private fun announceWeigh(a: StationAhead, miles: Int) {
        val st = MapData.status(a.station.id)
        if (prefs.voiceOn) speaker.speak(Phrases.weighAhead(miles, st, lang), lang)
        val statusShort = when (st) { WeighStatus.OPEN -> "ВІДКРИТІ"; WeighStatus.CLOSED -> "ЗАКРИТІ"; else -> "статус?" }
        lights.showWeighLights("$miles mi · $statusShort", flashStyle())
        Journal.add(this, "weigh", "${a.station.name}: ${"%.1f".format(a.meters / METERS_PER_MILE)} mi, ${st.name}")
        if (engine == null && pendingAsk == null) showWeighCard(a, st)
    }

    private fun showWeighCard(a: StationAhead, st: WeighStatus) {
        cardStation = a.station.id
        val accent = when (st) { WeighStatus.OPEN -> CardOverlay.RED; WeighStatus.CLOSED -> CardOverlay.GREEN; else -> CardOverlay.PURPLE }
        val statusText = when (st) { WeighStatus.OPEN -> "ВІДКРИТІ"; WeighStatus.CLOSED -> "ЗАКРИТІ"; else -> "статус невідомий — познач" }
        card.show(CardContent(
            "⚖ Ваги через ${"%.1f".format(a.meters / METERS_PER_MILE)} mi",
            "${a.station.name}\n$statusText",
            accent,
            listOf(
                CardButton("Відкриті", CardOverlay.RED) { markWeigh(a.station.id, WeighStatus.OPEN) },
                CardButton("Закриті", CardOverlay.GREEN) { markWeigh(a.station.id, WeighStatus.CLOSED) },
                CardButton("✕", CardOverlay.GREY) { card.hide(); cardStation = null },
            ),
        ))
        handler.removeCallbacks(hideCard)
        handler.postDelayed(hideCard, 30_000)
    }

    /** Waze сам написав, що ваги відкриті / закриті — ставимо статус найближчій станції попереду. */
    private fun onWazeWeighStatus(st: WeighStatus) {
        val here = DriveState.here.value ?: return
        val heading = DriveState.heading.value ?: return
        val a = weighWatcher.ahead(MapData.weigh.value, here, heading) ?: return
        if (MapData.status(a.station.id) == st) return
        MapData.setStatus(this, a.station.id, st)
        Journal.add(this, "weigh", "Статус з Waze: ${st.name}")
        if (prefs.voiceOn) speaker.speak(Phrases.weighAhead((a.meters / METERS_PER_MILE).roundToInt().coerceAtLeast(1), st, lang), lang)
        lights.showWeighLights(if (st == WeighStatus.OPEN) "ВІДКРИТІ" else "ЗАКРИТІ", flashStyle())
        maybePlanWeigh(a, here, heading)
    }

    /** Водій натиснув «Відкриті» / «Закриті» на картці. */
    fun markWeigh(id: String, st: WeighStatus) {
        MapData.setStatus(this, id, st)
        Journal.add(this, "weigh", "Позначено: ${if (st == WeighStatus.OPEN) "відкриті" else "закриті"}")
        card.hide(); cardStation = null
        val here = DriveState.here.value; val heading = DriveState.heading.value
        if (here != null && heading != null) {
            weighWatcher.ahead(MapData.weigh.value, here, heading)?.takeIf { it.station.id == id }?.let { maybePlanWeigh(it, here, heading) }
        }
    }

    private fun maybePlanWeigh(a: StationAhead, here: LatLon, heading: Double) {
        if (!prefs.bypassWeigh || !prefs.bypassLegalAccepted || engine != null || pendingAsk != null) return
        if (planJob?.isActive == true) return
        val st = MapData.status(a.station.id)
        val wanted = st == WeighStatus.OPEN || (st == WeighStatus.UNKNOWN && prefs.bypassUnknown)
        if (!wanted || a.meters < 1.0 * METERS_PER_MILE) return
        if (plannedFor[a.station.id] == st) return // уже рахували для цього статусу
        plannedFor[a.station.id] = st
        planBypass(AvoidKind.WEIGH, a.station.id, a.station.pos, a.station.name, here, heading)
    }

    private fun planBypass(kind: AvoidKind, key: String, spot: LatLon, name: String, here: LatLon, heading: Double) {
        planJob?.cancel()
        val startOdo = odo
        planJob = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    BypassPlanner.plan(kind, here, heading, spot, name, prefs.vehicle, prefs.voiceLang.routing, prefs.bypassAutoMinutes)
                }
            }
            val res = r.getOrElse {
                Log.w(TAG, "plan", it)
                Journal.add(applicationContext, "bypass", "Не вдалося порахувати об'їзд: ${it.message?.take(80)}")
                if (prefs.voiceOn && kind == AvoidKind.WEIGH) speaker.speak(Phrases.noBypass(true, lang), lang, flush = false)
                return@launch
            }
            onPlan(kind, key, res, startOdo)
        }
    }

    private fun onPlan(kind: AvoidKind, key: String, res: PlanResult, startOdo: Double) {
        when (res) {
            PlanResult.NotOnRoute -> Journal.add(this, "bypass", "Ваги не на нашому шляху — об'їзд не потрібен ($key)")
            PlanResult.NoBypass -> {
                Journal.add(this, "bypass", "Об'їзду немає ($key)")
                if (prefs.voiceOn) speaker.speak(Phrases.noBypass(kind == AvoidKind.WEIGH, lang), lang, flush = false)
            }
            is PlanResult.Ok -> {
                val p = res.plan
                // Поки рахували, машина проїхала далі — з'їзд міг уже залишитися позаду.
                val traveled = odo - startOdo
                if (p.exitMeters - traveled < 150) { Journal.add(this, "bypass", "Об'їзд запізно — з'їзд позаду"); return }
                val extra = p.extraMinutes.roundToInt()
                Journal.add(this, "bypass", "${p.avoidName}: об'їзд +$extra хв, з'їзд через ${Phrases.short(p.exitMeters - traveled)}, ${p.decision}")
                if (p.decision == Decision.AUTO) {
                    if (prefs.voiceOn) speaker.speak(Phrases.bypassAuto(p.exitMeters - traveled, extra, kind == AvoidKind.WEIGH, lang), lang)
                    startNav(p, quietStart = true)
                } else {
                    pendingAsk = p; pendingAskOdo = startOdo
                    if (prefs.voiceOn) speaker.speak(Phrases.bypassAsk(extra, kind == AvoidKind.WEIGH, lang), lang)
                    card.show(CardContent(
                        if (kind == AvoidKind.WEIGH) "⚖ Ваги відкриті — є об'їзд" else "🚨 Поліція — є об'їзд",
                        "+$extra хв · з'їзд через ${Phrases.short(p.exitMeters - traveled)}",
                        CardOverlay.YELLOW,
                        listOf(
                            CardButton("Об'їжджати", CardOverlay.BLUE) { pendingAsk?.let { pendingAsk = null; startNav(it, quietStart = false) } },
                            CardButton("Ні", CardOverlay.GREY) { pendingAsk = null; card.hide() },
                        ),
                        p.main.points, p.bypass.points, p.avoid, DriveState.here.value,
                    ))
                }
            }
        }
    }

    private fun expireAsk() {
        pendingAsk = null
        card.hide()
        Journal.add(this, "bypass", "Об'їзд не підтверджено до з'їзду — їдемо як є")
    }

    // =====================================================================
    // Навігація по об'їзду
    // =====================================================================

    private fun startNav(p: BypassPlan, quietStart: Boolean) {
        val e = NavEngine(p.bypass, p.rejoinIndex, lang == VoiceLang.UK)
        engine = e; activePlan = p
        DriveState._nav.value = NavUi(p, p.bypass, null, finished = false, demo = demo)
        if (gpsMode < 2) setGps(2)
        if (prefs.voiceOn) {
            if (!quietStart) speaker.speak(Phrases.bypassAuto(p.exitMeters, p.extraMinutes.roundToInt(), p.kind == AvoidKind.WEIGH, lang), lang)
            e.startPhrase()?.let { speaker.speak(it, lang, flush = false) }
        }
        showNavCard(p, e)
        Journal.add(this, "bypass", "Об'їзд почато: ${p.avoidName}")
    }

    private fun showNavCard(p: BypassPlan, e: NavEngine) {
        card.show(CardContent(
            if (p.kind == AvoidKind.WEIGH) "↪ Об'їзд ваг" else "↪ Об'їзд",
            navLine(p, e),
            CardOverlay.BLUE,
            listOf(
                CardButton("Відкрити", CardOverlay.BLUE) { openNav() },
                CardButton("Скасувати", CardOverlay.GREY) { cancelNav(true) },
            ),
            p.main.points, e.route.points, p.avoid, DriveState.here.value,
            onTap = { openNav() },
        ))
    }

    private fun navLine(p: BypassPlan, e: NavEngine): String {
        val pr = e.progress
        val next = pr?.toManeuver?.let { "${Phrases.short(it)} → ${pr.nextInstruction.orEmpty()}" }
            ?: "з'їзд через ${Phrases.short(p.exitMeters)}"
        return "+${p.extraMinutes.roundToInt()} хв · $next"
    }

    fun openNav() {
        startActivity(Intent(this, NavActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun navStep(e: NavEngine, here: LatLon, speed: Float) {
        val p = activePlan ?: return
        for (ev in e.onLocation(here, speed)) when (ev) {
            is NavEvent.Speak -> if (prefs.voiceOn) speaker.speak(ev.text, lang, flush = false)
            NavEvent.OffRoute -> reroute(here)
            NavEvent.Rejoined -> finishNav()
        }
        if (engine === e) {
            DriveState._nav.value = DriveState.nav.value?.copy(progress = e.progress)
            card.update(navLine(p, e), here)
        }
    }

    /** Водій з'їхав з об'їзду — перераховуємо від того місця, де він зараз (не частіше ніж раз на 20 с). */
    private fun reroute(here: LatLon) {
        val p = activePlan ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastRerouteAt < 20_000 || rerouteJob?.isActive == true) return
        lastRerouteAt = now
        if (prefs.voiceOn) speaker.speak(Phrases.rerouting(lang), lang, flush = false)
        val heading = DriveState.heading.value
        rerouteJob = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    Routing.route(here, p.target, prefs.vehicle, listOf(GeoMath.square(p.avoid, BypassPlanner.AVOID_HALF)),
                        prefs.voiceLang.routing, heading)
                }.getOrNull()
            } ?: return@launch
            if (activePlan !== p) return@launch
            if (GeoMath.distanceToLine(p.avoid, r.points) < BypassPlanner.AVOID_HALF * 0.6) {
                if (prefs.voiceOn) speaker.speak(Phrases.noBypass(p.kind == AvoidKind.WEIGH, lang), lang)
                cancelNav(false); return@launch
            }
            val lastOff = r.points.indexOfLast { GeoMath.distanceToLine(it, p.main.points) > BypassPlanner.DIVERGE }
            val e = NavEngine(r, (lastOff + 1).coerceIn(0, r.points.size - 1), lang == VoiceLang.UK)
            engine = e
            DriveState._nav.value = DriveState.nav.value?.copy(route = r, progress = null)
            e.startPhrase()?.let { if (prefs.voiceOn) speaker.speak(it, lang, flush = false) }
            showNavCard(p, e)
            Journal.add(applicationContext, "bypass", "Перераховано об'їзд")
        }
    }

    private fun finishNav() {
        if (prefs.voiceOn) speaker.speak(Phrases.backOnRoute(lang), lang, flush = false)
        Journal.add(this, "bypass", "Об'їзд закінчено — знову на основному маршруті")
        engine = null; activePlan = null
        DriveState._nav.value = DriveState.nav.value?.copy(finished = true)
        card.hide()
        handler.postDelayed({ if (engine == null) DriveState._nav.value = null }, (prefs.returnSeconds + 30) * 1000L)
        if (simJob != null) { simJob?.cancel(); simJob = null; demo = false }
    }

    fun cancelNav(say: Boolean) {
        if (engine == null && pendingAsk == null) return
        engine = null; activePlan = null; pendingAsk = null
        rerouteJob?.cancel()
        DriveState._nav.value = null
        card.hide()
        if (say && prefs.voiceOn) speaker.speak(Phrases.bypassCancelled(lang), lang)
        Journal.add(this, "bypass", "Об'їзд скасовано")
        simJob?.cancel(); simJob = null; demo = false
    }

    // =====================================================================
    // Тракстопи
    // =====================================================================

    private fun stopsStep(here: LatLon, heading: Double) {
        if (!prefs.stopAlerts) return
        val now = SystemClock.uptimeMillis()
        val stops = MapData.stops.value
        fun aheadWithin(maxM: Double, cone: Double) = stops.asSequence().map { it to GeoMath.distance(here, it.pos) }
            .filter { (s, d) -> d <= maxM && d > 50 && GeoMath.angleDiff(heading, GeoMath.bearing(here, s.pos)) <= cone }
        val red = aheadWithin(1.6 * METERS_PER_MILE, 35.0)
            .filter { (s, _) -> MapData.rating(this, s).zone == Zone.RED && now - (stopAnnounced[s.id] ?: -1_000_000_000L) > 2 * 3600_000L }
            .minByOrNull { it.second } ?: return
        val (s, d) = red
        stopAnnounced[s.id] = now
        if (prefs.voiceOn) speaker.speak(Phrases.redStop(s.name, d, lang), lang)
        val safe = aheadWithin(80 * METERS_PER_MILE, 30.0)
            .filter { (o, _) -> MapData.rating(this, o).zone == Zone.GREEN }.minByOrNull { it.second }
        safe?.let { (o, od) -> if (prefs.voiceOn) speaker.speak(Phrases.safeStop(o.name, od, lang), lang, flush = false) }
        Journal.add(this, "stop", "Червоний тракстоп: ${s.name}${safe?.let { ", безпечний: ${it.first.name}" } ?: ""}")
        if (engine == null && pendingAsk == null) {
            card.show(CardContent("⛔ Червона зона", "${s.name} · ${Phrases.short(d)}\n" +
                (safe?.let { "Безпечний: ${it.first.name}, ${Phrases.short(it.second)}" } ?: "Безпечного поруч не знайдено"),
                CardOverlay.RED, listOf(CardButton("OK", CardOverlay.GREY) { card.hide() })))
            handler.removeCallbacks(hideCard)
            handler.postDelayed(hideCard, 20_000)
        }
    }

    // =====================================================================
    // Тести з екрана програми
    // =====================================================================

    fun demoHazard(type: HazardType, miles: Double?) =
        announceHazard(HazardAlert(type, miles?.let { it * METERS_PER_MILE }, null, false))

    fun speakTest(text: String) = speaker.speak(text, lang)

    val ukrainianVoiceMissing: Boolean? get() = speaker.ukrainianMissing

    /**
     * Повна перевірка об'їзду: машина «їде» на північ по I-57 біля Peotone, IL, до справжніх ваг.
     * Програма показує картку ваг, рахує справжній об'їзд і веде по ньому голосом (рух прискорений).
     */
    fun simulateBypass() {
        cancelNav(false)
        simJob?.cancel()
        demo = true
        val start = LatLon(41.2950, -87.7960)
        val station = MapData.weigh.value.firstOrNull { GeoMath.distance(it.pos, LatLon(41.3657, -87.7999)) < 300 }
            ?: com.truckbro.app.road.WeighStation("demo/peotone", 41.3656705, -87.7998539, "Peotone Weigh Station (I-57)")
        DriveState._here.value = start; DriveState._heading.value = 0.0
        val miles = GeoMath.distance(start, station.pos) / METERS_PER_MILE
        if (prefs.voiceOn) speaker.speak(Phrases.weighAhead(miles.roundToInt(), WeighStatus.OPEN, lang), lang)
        lights.showWeighLights("${"%.1f".format(miles)} mi · ВІДКРИТІ", flashStyle())
        card.show(CardContent("⚖ Ваги через ${"%.1f".format(miles)} mi (тест)", "${station.name}\nВІДКРИТІ · рахую об'їзд…",
            CardOverlay.RED, listOf(CardButton("✕", CardOverlay.GREY) { cancelNav(false); card.hide(); simJob?.cancel(); simJob = null; demo = false })))
        simJob = scope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    BypassPlanner.plan(AvoidKind.WEIGH, start, 0.0, station.pos, station.name, prefs.vehicle,
                        prefs.voiceLang.routing, prefs.bypassAutoMinutes)
                }
            }
            val res = r.getOrElse {
                card.show(CardContent("Тест об'їзду", "Немає зв'язку з сервером маршрутів: ${it.message?.take(60)}", CardOverlay.RED,
                    listOf(CardButton("OK", CardOverlay.GREY) { card.hide() })))
                simJob = null; demo = false; return@launch
            }
            if (res !is PlanResult.Ok) {
                card.show(CardContent("Тест об'їзду", "Сервер не знайшов об'їзду ($res)", CardOverlay.RED,
                    listOf(CardButton("OK", CardOverlay.GREY) { card.hide() })))
                simJob = null; demo = false; return@launch
            }
            val p = res.plan
            odo = 0.0; last = null
            onPlan(AvoidKind.WEIGH, "demo", res, odo)
            // Чекаємо на підтвердження, якщо об'їзд довгий.
            while (engine == null && pendingAsk != null) kotlinx.coroutines.delay(500)
            val route = engine?.route ?: run { simJob = null; demo = false; return@launch }
            // «Їдемо» по об'їзду: ~80 миль/год × 3 (щоб тест тривав 2–3 хвилини).
            val cum = route.cumulative
            var dist = 0.0
            val step = 36.0 * 3
            while (engine != null && dist <= cum.last()) {
                val i = cum.indexOfFirst { it >= dist }.coerceAtLeast(1)
                val a = route.points[i - 1]; val b = route.points[i]
                val seg = (cum[i] - cum[i - 1]).coerceAtLeast(0.01)
                val f = ((dist - cum[i - 1]) / seg).coerceIn(0.0, 1.0)
                val pos = LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                val loc = Location("sim").apply {
                    latitude = pos.lat; longitude = pos.lon; speed = 36f; bearing = GeoMath.bearing(a, b).toFloat(); accuracy = 5f
                }
                onFix(loc)
                dist += step
                kotlinx.coroutines.delay(1_000)
            }
            if (p === activePlan) finishNav()
            simJob = null; demo = false
        }
    }

    override fun onInterrupt() {
        if (::speaker.isInitialized) speaker.stop()
    }

    override fun onDestroy() {
        instance = null
        DriveState._running.value = false
        handler.removeCallbacksAndMessages(null)
        runCatching { lm.removeUpdates(this) }
        if (::lights.isInitialized) lights.hideAll()
        if (::card.isInitialized) card.hide()
        if (::speaker.isInitialized) speaker.shutdown()
        scope.cancel()
        super.onDestroy()
    }
}
