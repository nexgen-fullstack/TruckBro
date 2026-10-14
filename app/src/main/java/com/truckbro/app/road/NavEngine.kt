package com.truckbro.app.road

/** Що зараз показати на екрані навігації. */
data class NavProgress(
    val along: Double,
    val toManeuver: Double?,
    val nextInstruction: String?,
    val maneuverType: Int,
    val toRejoin: Double,
    val offRoute: Boolean,
)

sealed interface NavEvent {
    data class Speak(val text: String) : NavEvent
    /** Водій з'їхав з об'їзду — треба перерахувати маршрут. */
    data object OffRoute : NavEvent
    /** Об'їзд закінчився — машина знову на основному маршруті. */
    data object Rejoined : NavEvent
}

/**
 * Покрокова навігація по об'їзду: стежить, де машина на лінії маршруту,
 * і вчасно каже, куди повертати (заздалегідь і перед самим поворотом).
 */
class NavEngine(val route: NavRoute, private val rejoinIndex: Int, private val uk: Boolean) {
    private var idx = 0
    private var nextMan = 1
    private val farDone = HashSet<Int>()
    private val nearDone = HashSet<Int>()
    private var offCount = 0
    private var finished = false
    var progress: NavProgress? = null
        private set

    private val cum = route.cumulative
    private val rejoinAlong = cum[rejoinIndex.coerceIn(0, cum.size - 1)]

    /** Перша фраза одразу після старту. */
    fun startPhrase(): String? = route.maneuvers.getOrNull(1)?.let { m ->
        val d = cum[m.shapeIndex.coerceIn(0, cum.size - 1)]
        if (d < 300) m.pre else "${inDistance(d)}${lowerFirst(m.alert)}"
    }

    fun onLocation(p: LatLon, speedMps: Float): List<NavEvent> {
        if (finished || route.points.size < 2) return emptyList()
        val out = ArrayList<NavEvent>()
        // Шукаємо найближчий відрізок трохи позаду й попереду поточного місця.
        var best = Double.MAX_VALUE; var bestI = idx; var bestT = 0.0
        val from = (idx - 3).coerceAtLeast(0); val to = (idx + 300).coerceAtMost(route.points.size - 2)
        for (i in from..to) {
            val (d, t) = GeoMath.toSegment(p, route.points[i], route.points[i + 1])
            if (d < best) { best = d; bestI = i; bestT = t }
        }
        val off = best > OFF_ROUTE_M
        if (off) {
            offCount++
            if (offCount == OFF_ROUTE_FIXES) out += NavEvent.OffRoute
        } else {
            offCount = 0
            idx = bestI
        }
        val segLen = cum[bestI + 1] - cum[bestI]
        val along = if (off) cum[idx] else cum[bestI] + bestT * segLen

        if (!off && along >= rejoinAlong - 30) {
            finished = true
            out += NavEvent.Rejoined
        }

        // Наступний маневр — перший, до якого ще не доїхали.
        while (nextMan < route.maneuvers.size && cum[route.maneuvers[nextMan].shapeIndex.coerceIn(0, cum.size - 1)] < along - 15) nextMan++
        val m = route.maneuvers.getOrNull(nextMan)
        var toMan: Double? = null
        if (m != null && !finished) {
            toMan = cum[m.shapeIndex.coerceIn(0, cum.size - 1)] - along
            // На трасі — за милю, у місті — за ~чверть милі.
            val far = if (speedMps > 20) METERS_PER_MILE else if (speedMps > 12) 800.0 else 400.0
            val near = (speedMps * 7.0).coerceIn(80.0, 250.0)
            if (!off && !isArrival(m)) {
                if (toMan <= near && nextMan !in nearDone) {
                    nearDone += nextMan; farDone += nextMan
                    out += NavEvent.Speak(m.pre)
                } else if (toMan <= far && toMan > near + 150 && nextMan !in farDone) {
                    farDone += nextMan
                    out += NavEvent.Speak("${inDistance(toMan)}${lowerFirst(m.alert)}")
                }
            }
        }
        progress = NavProgress(along, toMan, m?.instruction, m?.type ?: 0, (rejoinAlong - along).coerceAtLeast(0.0), off)
        return out
    }

    private fun isArrival(m: Maneuver) = m.type in 4..6

    private fun inDistance(m: Double): String {
        val mi = m / METERS_PER_MILE
        return if (uk) when {
            mi < 0.2 -> "Через ${((m / 0.3048) / 100).toInt().coerceAtLeast(1) * 100} футів: "
            mi < 0.65 -> "Через пів милі: "
            mi < 0.85 -> "Через три чверті милі: "
            else -> "Через ${"%.0f".format(mi.coerceAtLeast(1.0))} ${if (mi < 1.5) "милю" else if (mi < 4.5) "милі" else "миль"}: "
        } else when {
            mi < 0.2 -> "In ${((m / 0.3048) / 100).toInt().coerceAtLeast(1) * 100} feet, "
            mi < 0.65 -> "In half a mile, "
            mi < 0.85 -> "In three quarters of a mile, "
            mi < 1.5 -> "In 1 mile, "
            else -> "In ${"%.0f".format(mi)} miles, "
        }
    }

    private fun lowerFirst(s: String) = if (uk) s else s.replaceFirstChar { it.lowercase() }

    companion object {
        const val OFF_ROUTE_M = 70.0
        const val OFF_ROUTE_FIXES = 3
    }
}
