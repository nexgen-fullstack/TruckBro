package com.truckbro.app.road

import org.json.JSONArray
import org.json.JSONObject

/** Колір тракстопу. [own] — позначка самого водія важливіша за статистику. */
enum class Zone(val argb: Long, val title: String, val word: String) {
    GREEN(0xFF43A047, "Зелена", "безпечно"),
    YELLOW(0xFFFDD835, "Жовта", "обережно"),
    RED(0xFFE53935, "Червона", "краще не зупинятися"),
    UNKNOWN(0xFF78909C, "Немає даних", "немає даних");

    val colorInt: Int get() = argb.toInt()
}

data class TruckStop(val id: String, val lat: Double, val lon: Double, val name: String, val city: String) {
    val pos get() = LatLon(lat, lon)
}

/** Місто з даних ФБР: насильницьких злочинів на 100 000 жителів за рік (середнє по США ≈ 380). */
data class Town(val name: String, val state: String, val lat: Double, val lon: Double, val pop: Int, val rate: Int, val radiusKm: Double)

data class StopRating(val zone: Zone, val reason: String, val note: String?)

object TruckStopParser {
    private const val CHAINS = "Pilot|Flying J|Love's|Loves|TA |TA Express|TravelCenters|Petro|Sapp Bros|Road Ranger|Kwik Trip|" +
        "Buc-ee|Bosselman|Roady|AmBest|Iowa 80|Little America|One9|Speedway Truck|Stuckey|Truck ?Stop|Travel Plaza|Travel Center"

    fun overpassQuery(s: Double, w: Double, n: Double, e: Double): String =
        "[out:json][timeout:40];(" +
            "nwr[\"amenity\"=\"fuel\"][\"hgv\"~\"yes|designated\"]($s,$w,$n,$e);" +
            "nwr[\"amenity\"=\"fuel\"][\"name\"~\"$CHAINS\",i]($s,$w,$n,$e);" +
            "nwr[\"amenity\"=\"fuel\"][\"brand\"~\"$CHAINS\",i]($s,$w,$n,$e);" +
            "nwr[\"highway\"=\"services\"][\"hgv\"~\"yes|designated\"]($s,$w,$n,$e);" +
            ");out center tags;"

    fun parse(json: String): List<TruckStop> {
        val arr = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<TruckStop>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val t = o.optJSONObject("tags") ?: JSONObject()
            val lat = if (o.has("lat")) o.getDouble("lat") else o.optJSONObject("center")?.optDouble("lat") ?: continue
            val lon = if (o.has("lon")) o.getDouble("lon") else o.optJSONObject("center")?.optDouble("lon") ?: continue
            val name = t.optString("name").ifBlank { t.optString("brand") }.ifBlank { "Truck stop" }
            val city = t.optString("addr:city")
            out += TruckStop("${o.getString("type")}/${o.getLong("id")}", lat, lon, name, city)
        }
        return out.fold(ArrayList()) { acc, s -> if (acc.none { GeoMath.distance(it.pos, s.pos) < 150 }) acc += s; acc }
    }

    fun toJson(list: List<TruckStop>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("lat", it.lat).put("lon", it.lon).put("name", it.name).put("city", it.city)) }
    }.toString()

    fun fromJson(s: String): List<TruckStop> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { a.getJSONObject(it).let { o ->
            TruckStop(o.getString("id"), o.getDouble("lat"), o.getDouble("lon"), o.getString("name"), o.optString("city"))
        } }
    }.getOrDefault(emptyList())

    fun parseTowns(tsv: String): List<Town> = tsv.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("town\t") }
        .mapNotNull { line ->
            val c = line.split('\t')
            if (c.size < 7) null else runCatching {
                Town(c[0], c[1], c[2].toDouble(), c[3].toDouble(), c[4].toInt(), c[5].toInt(), c[6].toDouble())
            }.getOrNull()
        }.toList()
}

/**
 * Колір тракстопу: власна позначка водія → інакше рівень злочинності міста, в якому стоїть тракстоп.
 * Тракстопи за містом (немає міста поруч) — «немає даних»: статистики там просто нема.
 */
object StopRater {
    const val RED_RATE = 900
    const val YELLOW_RATE = 450

    fun rate(stop: TruckStop, towns: List<Town>, own: Zone?, note: String?): StopRating {
        if (own != null && own != Zone.UNKNOWN) return StopRating(own, "твоя позначка", note)
        val town = towns.asSequence()
            .map { it to GeoMath.distance(stop.lat, stop.lon, it.lat, it.lon) }
            .filter { (t, d) -> d <= t.radiusKm * 1000 * 1.6 }
            .minByOrNull { it.second }?.first
            ?: return StopRating(Zone.UNKNOWN, "за містом, статистики немає", note)
        val zone = when {
            town.rate >= RED_RATE -> Zone.RED
            town.rate >= YELLOW_RATE -> Zone.YELLOW
            else -> Zone.GREEN
        }
        return StopRating(zone, "${town.name}, ${town.state}: ${town.rate} насильницьких злочинів на 100 тис./рік", note)
    }
}
