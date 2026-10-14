package com.truckbro.app.road

import org.json.JSONArray
import org.json.JSONObject

/** Один маневр маршруту: де починається (індекс точки лінії) і що сказати. */
data class Maneuver(
    val shapeIndex: Int,
    val instruction: String,
    /** Коротко, заздалегідь: «Turn right onto Main Street». */
    val alert: String,
    /** Повністю, перед самим поворотом. */
    val pre: String,
    val type: Int,
)

data class NavRoute(val points: List<LatLon>, val maneuvers: List<Maneuver>, val seconds: Double, val meters: Double) {
    val cumulative: DoubleArray by lazy { GeoMath.cumulative(points) }
}

/** Розміри машини для маршрутизатора: щоб не вів під низькі мости і дорогами з забороною для траків. */
data class Vehicle(val heightFt: Float, val weightLbs: Int, val lengthFt: Float) {
    val heightM get() = heightFt * 0.3048
    val tons get() = weightLbs * 0.000453592
    val lengthM get() = lengthFt * 0.3048
}

/**
 * Маршрути через безкоштовний сервер Valhalla (OpenStreetMap, FOSSGIS). Профіль «truck» враховує
 * висоту, вагу й довжину, а також заборони для вантажівок. Ключ API не потрібен.
 */
object Routing {
    const val URL = "https://valhalla1.openstreetmap.de/route"

    fun request(from: LatLon, to: LatLon, v: Vehicle, avoid: List<List<LatLon>>, lang: String, heading: Double?): String {
        val loc = JSONObject().put("lat", from.lat).put("lon", from.lon)
        // Напрямок руху — щоб маршрут не починався з розвороту на автомагістралі.
        if (heading != null) loc.put("heading", heading.toInt()).put("heading_tolerance", 45)
        val body = JSONObject()
            .put("locations", JSONArray().put(loc).put(JSONObject().put("lat", to.lat).put("lon", to.lon)))
            .put("costing", "truck")
            .put("costing_options", JSONObject().put("truck", JSONObject()
                .put("height", v.heightM).put("weight", v.tons).put("length", v.lengthM)
                .put("hazmat", false)))
            .put("units", "miles")
            .put("directions_options", JSONObject().put("language", lang))
        if (avoid.isNotEmpty()) {
            body.put("exclude_polygons", JSONArray().apply {
                avoid.forEach { poly -> put(JSONArray().apply { poly.forEach { put(JSONArray().put(it.lon).put(it.lat)) } }) }
            })
        }
        return body.toString()
    }

    fun parse(json: String): NavRoute {
        val trip = JSONObject(json).getJSONObject("trip")
        val points = ArrayList<LatLon>()
        val mans = ArrayList<Maneuver>()
        val legs = trip.getJSONArray("legs")
        for (l in 0 until legs.length()) {
            val leg = legs.getJSONObject(l)
            val offset = points.size
            points += GeoMath.decodePolyline(leg.getString("shape"))
            val ms = leg.getJSONArray("maneuvers")
            for (i in 0 until ms.length()) {
                val m = ms.getJSONObject(i)
                val instr = m.optString("instruction")
                mans += Maneuver(
                    offset + m.optInt("begin_shape_index"), instr,
                    m.optString("verbal_transition_alert_instruction").ifBlank { m.optString("verbal_succinct_transition_instruction").ifBlank { instr } },
                    m.optString("verbal_pre_transition_instruction").ifBlank { instr },
                    m.optInt("type"),
                )
            }
        }
        val summary = trip.getJSONObject("summary")
        return NavRoute(points, mans, summary.getDouble("time"), summary.getDouble("length") * METERS_PER_MILE)
    }

    /** Синхронний виклик (з фонового потоку). Якщо мова недоступна на сервері — повтор англійською. */
    fun route(from: LatLon, to: LatLon, v: Vehicle, avoid: List<List<LatLon>> = emptyList(), lang: String = "en-US", heading: Double? = null): NavRoute {
        return try {
            parse(Http.postJson(URL, request(from, to, v, avoid, lang, heading)))
        } catch (e: HttpException) {
            if (lang != "en-US" && e.body.contains("language", ignoreCase = true)) route(from, to, v, avoid, "en-US", heading) else throw e
        }
    }
}
