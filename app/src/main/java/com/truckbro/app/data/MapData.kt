package com.truckbro.app.data

import android.content.Context
import android.util.Log
import com.truckbro.app.road.GeoMath
import com.truckbro.app.road.Http
import com.truckbro.app.road.LatLon
import com.truckbro.app.road.StopRater
import com.truckbro.app.road.StopRating
import com.truckbro.app.road.Town
import com.truckbro.app.road.TruckStop
import com.truckbro.app.road.TruckStopParser
import com.truckbro.app.road.WeighParser
import com.truckbro.app.road.WeighStation
import com.truckbro.app.road.WeighStatus
import com.truckbro.app.road.Zone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.cos

/**
 * Ваги й тракстопи навколо машини (з OpenStreetMap, кеш на телефоні — працює й без інтернету),
 * статуси ваг «відкрито/закрито» та власні позначки тракстопів.
 */
object MapData {
    private const val TAG = "TruckBroData"
    private val OVERPASS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )
    /** Радіус завантаження навколо машини і як далеко треба від'їхати, щоб довантажити. */
    private const val FETCH_KM = 110.0
    private const val REFETCH_KM = 45.0
    /** Статус «відкрито/закрито» дійсний 3 години — потім ваги могли відкрити чи закрити. */
    const val STATUS_TTL = 3 * 3600_000L

    private val _weigh = MutableStateFlow<List<WeighStation>>(emptyList())
    val weigh: StateFlow<List<WeighStation>> = _weigh
    private val _stops = MutableStateFlow<List<TruckStop>>(emptyList())
    val stops: StateFlow<List<TruckStop>> = _stops
    private val _statuses = MutableStateFlow<Map<String, Pair<WeighStatus, Long>>>(emptyMap())
    val statuses: StateFlow<Map<String, Pair<WeighStatus, Long>>> = _statuses
    private val _marks = MutableStateFlow<Map<String, Pair<Zone, String>>>(emptyMap())
    val marks: StateFlow<Map<String, Pair<Zone, String>>> = _marks
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    @Volatile private var towns: List<Town>? = null
    private var loaded = false
    private var fetchCenter: LatLon? = null
    private var fetchAt = 0L
    private val mutex = Mutex()

    private fun file(ctx: Context, name: String) = File(ctx.filesDir, name)

    suspend fun load(ctx: Context) = mutex.withLock {
        if (loaded) return@withLock
        withContext(Dispatchers.IO) {
            runCatching { _weigh.value = WeighParser.fromJson(file(ctx, "weigh.json").readText()) }
            runCatching { _stops.value = TruckStopParser.fromJson(file(ctx, "stops.json").readText()) }
            runCatching {
                val o = JSONObject(file(ctx, "statuses.json").readText())
                _statuses.value = o.keys().asSequence().associateWith { k ->
                    o.getJSONObject(k).let { WeighStatus.valueOf(it.getString("s")) to it.getLong("t") }
                }
            }
            runCatching {
                val o = JSONObject(file(ctx, "marks.json").readText())
                _marks.value = o.keys().asSequence().associateWith { k ->
                    o.getJSONObject(k).let { Zone.valueOf(it.getString("z")) to it.optString("n") }
                }
            }
            runCatching {
                val meta = JSONObject(file(ctx, "fetch.json").readText())
                fetchCenter = LatLon(meta.getDouble("lat"), meta.getDouble("lon")); fetchAt = meta.getLong("t")
            }
            towns(ctx)
        }
        loaded = true
    }

    fun towns(ctx: Context): List<Town> = towns ?: runCatching {
        TruckStopParser.parseTowns(ctx.assets.open("towns.tsv").bufferedReader().readText())
    }.getOrDefault(emptyList()).also { towns = it }

    /** Довантажує ваги й тракстопи, якщо машина від'їхала далеко від місця попереднього завантаження. */
    suspend fun ensureAround(ctx: Context, here: LatLon, force: Boolean = false): Boolean {
        load(ctx)
        val c = fetchCenter
        val stale = System.currentTimeMillis() - fetchAt > 14 * 24 * 3600_000L
        if (!force && c != null && !stale && GeoMath.distance(c, here) < REFETCH_KM * 1000) return false
        return mutex.withLock { fetch(ctx, here) }
    }

    private suspend fun fetch(ctx: Context, here: LatLon): Boolean = withContext(Dispatchers.IO) {
        val dLat = FETCH_KM / 111.0
        val dLon = FETCH_KM / (111.0 * cos(Math.toRadians(here.lat)))
        val s = here.lat - dLat; val n = here.lat + dLat; val w = here.lon - dLon; val e = here.lon + dLon
        try {
            val weigh = WeighParser.parse(overpass(WeighParser.overpassQuery(s, w, n, e)))
            val stops = TruckStopParser.parse(overpass(TruckStopParser.overpassQuery(s, w, n, e)))
            // Зберігаємо нове плюс старе в межах ~800 км (щоб працювало без інтернету на маршруті назад).
            fun <T> merge(old: List<T>, new: List<T>, id: (T) -> String, pos: (T) -> LatLon): List<T> {
                val ids = new.map(id).toSet()
                return new + old.filter { id(it) !in ids && GeoMath.distance(pos(it), here) < 800_000 }
            }
            _weigh.value = merge(_weigh.value, weigh, { it.id }, { it.pos })
            _stops.value = merge(_stops.value, stops, { it.id }, { it.pos })
            file(ctx, "weigh.json").writeText(WeighParser.toJson(_weigh.value))
            file(ctx, "stops.json").writeText(TruckStopParser.toJson(_stops.value))
            fetchCenter = here; fetchAt = System.currentTimeMillis()
            file(ctx, "fetch.json").writeText(JSONObject().put("lat", here.lat).put("lon", here.lon).put("t", fetchAt).toString())
            _lastError.value = null
            Log.i(TAG, "fetched ${weigh.size} weigh, ${stops.size} stops")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "fetch", t)
            _lastError.value = "Не вдалося завантажити ваги й тракстопи (${t.message?.take(80)}). Працюю з тим, що вже є на телефоні."
            false
        }
    }

    private fun overpass(query: String): String {
        var last: Throwable? = null
        for (url in OVERPASS) {
            try { return Http.postForm(url, mapOf("data" to query), 60_000) } catch (t: Throwable) { last = t }
        }
        throw last ?: IllegalStateException("overpass")
    }

    // ---------- Статуси ваг ----------

    fun status(id: String, now: Long = System.currentTimeMillis()): WeighStatus {
        val (s, t) = _statuses.value[id] ?: return WeighStatus.UNKNOWN
        return if (now - t < STATUS_TTL) s else WeighStatus.UNKNOWN
    }

    fun setStatus(ctx: Context, id: String, s: WeighStatus) {
        _statuses.value = _statuses.value + (id to (s to System.currentTimeMillis()))
        saveStatuses(ctx)
    }

    private fun saveStatuses(ctx: Context) {
        val o = JSONObject()
        _statuses.value.forEach { (k, v) -> o.put(k, JSONObject().put("s", v.first.name).put("t", v.second)) }
        runCatching { file(ctx, "statuses.json").writeText(o.toString()) }
    }

    // ---------- Позначки тракстопів ----------

    fun setMark(ctx: Context, id: String, zone: Zone?, note: String) {
        _marks.value = if (zone == null) _marks.value - id else _marks.value + (id to (zone to note.trim()))
        val o = JSONObject()
        _marks.value.forEach { (k, v) -> o.put(k, JSONObject().put("z", v.first.name).put("n", v.second)) }
        runCatching { file(ctx, "marks.json").writeText(o.toString()) }
    }

    private val ratingCache = java.util.concurrent.ConcurrentHashMap<String, StopRating>()

    /** Колір тракстопу (рахується один раз — перебір тисяч міст не на кожен сигнал GPS). */
    fun rating(ctx: Context, stop: TruckStop): StopRating {
        val m = _marks.value[stop.id]
        val key = "${stop.id}|${m?.first}|${m?.second}"
        return ratingCache.getOrPut(key) { StopRater.rate(stop, towns(ctx), m?.first, m?.second?.ifBlank { null }) }
    }
}
