package com.truckbro.app.road

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sin

enum class WeighStatus { OPEN, CLOSED, UNKNOWN }

data class WeighStation(val id: String, val lat: Double, val lon: Double, val name: String) {
    val pos get() = LatLon(lat, lon)
}

/** Станція попереду: на якій відстані (м) і з якого боку дороги (+ праворуч). */
data class StationAhead(val station: WeighStation, val meters: Double, val rightOffset: Double)

/**
 * Зважувальні станції з OpenStreetMap. У США їх позначають `highway=weigh_station` або `amenity=weighbridge`.
 * Приватні ваги на тракстопах (CAT Scale тощо) відкидаємо — там зупинятися не треба.
 */
object WeighParser {
    private val PRIVATE = Regex("cat scale|pilot|flying j|love'?s|petro|\\bta\\b|travelcenters|truck ?stop|scrap|recycl|quarry|grain|elevator|co-?op|landfill|transfer station|concrete|gravel|sand",
        RegexOption.IGNORE_CASE)
    private val OFFICIAL = Regex("weigh|port of entry|inspection|scale house|enforcement|\\bdot\\b|department|state police|highway patrol|trooper",
        RegexOption.IGNORE_CASE)

    fun overpassQuery(s: Double, w: Double, n: Double, e: Double): String =
        "[out:json][timeout:40];(nwr[\"highway\"=\"weigh_station\"]($s,$w,$n,$e);" +
            "nwr[\"amenity\"=\"weighbridge\"]($s,$w,$n,$e););out center tags;"

    fun parse(json: String): List<WeighStation> {
        val arr = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<WeighStation>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val tags = o.optJSONObject("tags") ?: JSONObject()
            val lat = if (o.has("lat")) o.getDouble("lat") else o.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (o.has("lon")) o.getDouble("lon") else o.optJSONObject("center")?.optDouble("lon") ?: continue
            val text = listOf("name", "operator", "brand", "description", "access").joinToString(" ") { tags.optString(it) }
            val highway = tags.optString("highway") == "weigh_station"
            if (!highway) {
                if (PRIVATE.containsMatchIn(text)) continue
                if (tags.optString("access") in setOf("private", "customers")) continue
                // Безіменні ваги біля автомагістралей — найчастіше державні станції; з назвою — тільки «офіційні».
                val name = tags.optString("name")
                if (name.isNotBlank() && !OFFICIAL.containsMatchIn(text)) continue
            }
            out += WeighStation("${o.getString("type")}/${o.getLong("id")}", lat, lon,
                tags.optString("name").ifBlank { "Weigh Station" })
        }
        // Вузол і контур тієї самої станції в OSM — залишаємо одну точку.
        return out.fold(ArrayList()) { acc, s ->
            if (acc.none { GeoMath.distance(it.pos, s.pos) < 120 }) acc += s
            acc
        }
    }

    fun toJson(list: List<WeighStation>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("name", it.name)) }
    }.toString()

    fun fromJson(s: String): List<WeighStation> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            WeighStation(o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getString("name"))
        } }
    }.getOrDefault(emptyList())
}

/**
 * Вирішує, яка станція «попереду» і коли про неї говорити: за 15, 10, 5, 2 і 1 милю.
 * Станції часто парні — по одній на кожен бік автомагістралі. У США їдуть праворуч,
 * тож із пари беремо ту, що правіше від напрямку руху.
 */
class WeighWatcher {
    private val fired = HashMap<String, MutableSet<Int>>()
    private var current: String? = null

    fun ahead(stations: List<WeighStation>, here: LatLon, heading: Double): StationAhead? {
        val cands = stations.mapNotNull { s ->
            val d = GeoMath.distance(here, s.pos)
            if (d > MAX_AHEAD || d < 30) return@mapNotNull null
            val brg = GeoMath.bearing(here, s.pos)
            val diff = GeoMath.angleDiff(heading, brg)
            // Далеко — вузький конус (дорога майже пряма); ближче — ширший (дорога може повертати).
            val cone = when { d > 8_000 -> 15.0; d > 2_500 -> 25.0; else -> 45.0 }
            if (diff > cone) return@mapNotNull null
            val signed = ((brg - heading + 540) % 360) - 180
            StationAhead(s, d, d * sin(Math.toRadians(signed)))
        }.sortedBy { it.meters }
        val first = cands.firstOrNull() ?: return null
        // Пара на іншому боці дороги (до 800 м від найближчої) — вибираємо правішу.
        return cands.filter { GeoMath.distance(it.station.pos, first.station.pos) < 800 }.maxByOrNull { it.rightOffset }
    }

    /** Повертає поріг (15/10/5/2/1 миля), який щойно перетнули, або null. */
    fun step(a: StationAhead?): Int? {
        if (a == null) return null
        val id = a.station.id
        if (current != id) { current = id }
        val set = fired.getOrPut(id) { mutableSetOf() }
        val miles = a.meters / METERS_PER_MILE
        // Найменший поріг, до якого вже доїхали і про який ще не казали.
        val due = THRESHOLDS.filter { miles <= it + 0.15 && it !in set }.minOrNull() ?: return null
        set += THRESHOLDS.filter { it >= due }
        return due
    }

    fun forget(id: String) { fired.remove(id); if (current == id) current = null }

    companion object {
        val THRESHOLDS = listOf(15, 10, 5, 2, 1)
        const val MAX_AHEAD = 15.5 * METERS_PER_MILE
    }
}

/**
 * Якщо Waze (або інша навігація) сам пише на екрані, що ваги відкриті чи закриті, беремо це звідти.
 * Зазвичай Waze цього НЕ показує, тоді статус лишається з кнопки водія.
 */
object WeighStatusParser {
    private val STATION = Regex("weigh ?station|\\bscales?\\b|port of entry|inspection station|ваги|зважуван", RegexOption.IGNORE_CASE)
    private val OPEN = Regex("\\bopen(ed)?\\b|відкрит", RegexOption.IGNORE_CASE)
    private val CLOSED = Regex("\\bclosed\\b|закрит", RegexOption.IGNORE_CASE)

    /**
     * [loose] — для Trucker Path: там назва станції і статус («Open» / «Closed») — окремі написи поруч,
     * тож шукаємо статус у кількох сусідніх рядках після назви.
     */
    fun parse(texts: List<String>, loose: Boolean = false): WeighStatus? {
        for ((i, t) in texts.withIndex()) {
            if (t.length > 160 || !STATION.containsMatchIn(t)) continue
            if (CLOSED.containsMatchIn(t)) return WeighStatus.CLOSED
            if (OPEN.containsMatchIn(t)) return WeighStatus.OPEN
            if (!loose) continue
            for (n in texts.subList(i + 1, minOf(texts.size, i + 5))) {
                if (n.length > 30) continue
                if (CLOSED.containsMatchIn(n)) return WeighStatus.CLOSED
                if (OPEN.containsMatchIn(n)) return WeighStatus.OPEN
            }
        }
        return null
    }
}
