package com.truckbro.app.road

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double)

const val METERS_PER_MILE = 1609.344

/** Геометрія на поверхні Землі. Точність кількох метрів — для навігації більше не треба. */
object GeoMath {
    private const val R = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double = distance(a.lat, a.lon, b.lat, b.lon)

    fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Напрямок з [a] на [b], градуси 0..360 (0 — північ). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val f1 = Math.toRadians(a.lat); val f2 = Math.toRadians(b.lat)
        val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Найменша різниця між двома напрямками, 0..180. */
    fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    /** Точка на відстані [meters] від [p] у напрямку [bearingDeg]. */
    fun move(p: LatLon, bearingDeg: Double, meters: Double): LatLon {
        val d = meters / R
        val b = Math.toRadians(bearingDeg)
        val f1 = Math.toRadians(p.lat); val l1 = Math.toRadians(p.lon)
        val f2 = asin(sin(f1) * cos(d) + cos(f1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(f1), cos(d) - sin(f1) * sin(f2))
        return LatLon(Math.toDegrees(f2), Math.toDegrees(l2))
    }

    /** Квадрат зі стороною 2×[halfMeters] навколо точки — для «не їхати тут» у маршрутизаторі. */
    fun square(c: LatLon, halfMeters: Double): List<LatLon> {
        val dLat = halfMeters / 111_320.0
        val dLon = halfMeters / (111_320.0 * cos(Math.toRadians(c.lat)))
        return listOf(
            LatLon(c.lat - dLat, c.lon - dLon), LatLon(c.lat - dLat, c.lon + dLon),
            LatLon(c.lat + dLat, c.lon + dLon), LatLon(c.lat + dLat, c.lon - dLon),
            LatLon(c.lat - dLat, c.lon - dLon),
        )
    }

    /** Розшифровка лінії маршруту Valhalla (polyline, точність 6 знаків). */
    fun decodePolyline(s: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>()
        var i = 0; var lat = 0L; var lon = 0L
        while (i < s.length) {
            var shift = 0; var result = 0L; var b: Int
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            shift = 0; result = 0
            do { b = s[i++].code - 63; result = result or ((b and 0x1f).toLong() shl shift); shift += 5 } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out += LatLon(lat / factor, lon / factor)
        }
        return out
    }

    /** Відстань від точки [p] до відрізка [a]-[b] (метри) і частка 0..1 — де на відрізку найближча точка. */
    fun toSegment(p: LatLon, a: LatLon, b: LatLon): Pair<Double, Double> {
        // Локальна плоска проєкція навколо [a] — на відрізках у сотні метрів похибка мізерна.
        val kx = 111_320.0 * cos(Math.toRadians(a.lat)); val ky = 110_540.0
        val bx = (b.lon - a.lon) * kx; val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx; val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 < 1e-9) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val dx = px - t * bx; val dy = py - t * by
        return sqrt(dx * dx + dy * dy) to t
    }

    /** Найменша відстань від точки до ламаної лінії. */
    fun distanceToLine(p: LatLon, line: List<LatLon>): Double {
        if (line.isEmpty()) return Double.MAX_VALUE
        if (line.size == 1) return distance(p, line[0])
        var best = Double.MAX_VALUE
        for (i in 0 until line.size - 1) best = min(best, toSegment(p, line[i], line[i + 1]).first)
        return best
    }

    /** Накопичена відстань від початку лінії до кожної її точки. */
    fun cumulative(line: List<LatLon>): DoubleArray {
        val out = DoubleArray(line.size)
        for (i in 1 until line.size) out[i] = out[i - 1] + distance(line[i - 1], line[i])
        return out
    }
}
