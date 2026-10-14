package com.truckbro.app.road

/** Що об'їжджаємо. */
enum class AvoidKind { WEIGH, POLICE }

enum class Decision {
    /** Об'їзд додає не більше порогу — вмикаємо самі, з повідомленням і кнопкою «Скасувати». */
    AUTO,
    /** Об'їзд довший за поріг (або це поліція) — пропонуємо, вмикаємо тільки після «Об'їжджати». */
    ASK,
}

sealed interface PlanResult {
    /** Звичайний маршрут не проходить через це місце — об'їжджати не треба. */
    data object NotOnRoute : PlanResult
    /** Без цього місця проїхати не можна (або об'їзд абсурдно довгий). */
    data object NoBypass : PlanResult
    data class Ok(val plan: BypassPlan) : PlanResult
}

data class BypassPlan(
    val kind: AvoidKind,
    val avoid: LatLon,
    val avoidName: String,
    val target: LatLon,
    val main: NavRoute,
    val bypass: NavRoute,
    val extraMinutes: Double,
    /** Скільки метрів від початку до з'їзду з основної дороги. */
    val exitMeters: Double,
    /** Індекс точки об'їзду, після якої машина знову на основному маршруті. */
    val rejoinIndex: Int,
    val decision: Decision,
)

/**
 * Будує об'їзд: маршрут «як зазвичай» і маршрут, якому заборонено проїжджати повз [avoid].
 * Ціль — точка на тій самій дорозі за кілька миль після місця, щоб об'їзд повертав на основний шлях.
 */
object BypassPlanner {
    /** Точка «не їхати тут»: квадрат ±450 м — накриває саму станцію та її з'їзд і виїзд. */
    const val AVOID_HALF = 450.0
    const val ON_ROUTE = 300.0
    const val DIVERGE = 60.0
    /** Об'їзд довший за це — вже не об'їзд, а інша поїздка. */
    const val MAX_EXTRA_MIN = 60.0

    fun plan(
        kind: AvoidKind, here: LatLon, heading: Double?, avoid: LatLon, avoidName: String,
        v: Vehicle, lang: String, thresholdMin: Int,
    ): PlanResult {
        // 1) Звичайний маршрут далеко вперед (~25 миль за місцем) — щоб знати, якою дорогою ми їдемо.
        val far = Routing.route(here, target(here, avoid, 40_000.0), v, emptyList(), lang, heading)
        if (GeoMath.distanceToLine(avoid, far.points) > ON_ROUTE) return PlanResult.NotOnRoute
        // 2) Ціль — точка на ЦІЙ САМІЙ дорозі після місця: об'їзд має повернути саме сюди.
        // На автомагістралі заїзд назад може бути лише через кілька миль — пробуємо все далі.
        var last: PlanResult = PlanResult.NoBypass
        for (after in AFTER_METERS) {
            val target = pointAfter(far.points, avoid, after)
            val bypass = runCatching {
                Routing.route(here, target, v, listOf(GeoMath.square(avoid, AVOID_HALF)), lang, heading)
            }.getOrNull() ?: continue
            val main = Routing.route(here, target, v, emptyList(), lang, heading)
            last = analyze(kind, avoid, avoidName, target, main, bypass, thresholdMin)
            if (last is PlanResult.Ok) return last
        }
        return last
    }

    private val AFTER_METERS = listOf(5_000.0, 12_000.0, 25_000.0)

    fun target(here: LatLon, avoid: LatLon, meters: Double = 6_000.0): LatLon =
        GeoMath.move(avoid, GeoMath.bearing(here, avoid), meters)

    /** Точка на лінії маршруту на [meters] далі від місця, найближчого до [p]. */
    fun pointAfter(line: List<LatLon>, p: LatLon, meters: Double): LatLon {
        val cum = GeoMath.cumulative(line)
        val i = line.indices.minByOrNull { GeoMath.distance(line[it], p) } ?: return line.last()
        val goal = cum[i] + meters
        val j = cum.indexOfFirst { it >= goal }
        return if (j < 0) line.last() else line[j]
    }

    /** Чиста логіка (без інтернету) — окремо, щоб її можна було перевірити тестами. */
    fun analyze(
        kind: AvoidKind, avoid: LatLon, avoidName: String, target: LatLon,
        main: NavRoute, bypass: NavRoute, thresholdMin: Int,
    ): PlanResult {
        if (GeoMath.distanceToLine(avoid, bypass.points) < AVOID_HALF * 0.6) return PlanResult.NoBypass
        val extra = (bypass.seconds - main.seconds) / 60.0
        if (extra > MAX_EXTRA_MIN) return PlanResult.NoBypass
        val off = BooleanArray(bypass.points.size) { GeoMath.distanceToLine(bypass.points[it], main.points) > DIVERGE }
        val firstOff = off.indexOfFirst { it }
        if (firstOff < 0) return PlanResult.NoBypass
        val lastOff = off.indexOfLast { it }
        val exitIdx = (firstOff - 1).coerceAtLeast(0)
        val decision = if (kind == AvoidKind.WEIGH && extra <= thresholdMin) Decision.AUTO else Decision.ASK
        return PlanResult.Ok(BypassPlan(kind, avoid, avoidName, target, main, bypass, extra.coerceAtLeast(0.0),
            bypass.cumulative[exitIdx], (lastOff + 1).coerceAtMost(bypass.points.size - 1), decision))
    }
}
