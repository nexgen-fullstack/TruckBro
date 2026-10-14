package com.truckbro.app.road

/** Шматок тексту з екрана Waze. */
data class ScreenText(val text: String, val top: Int = 0)

/**
 * Що попереду на дорозі. Порядок = пріоритет: якщо одночасно кілька подій, оголошуємо першу за списком
 * (небезпека на дорозі важливіша за поліцію). [uk]/[en] — як назвати голосом.
 */
enum class HazardType(val title: String, val uk: String, val en: String, val police: Boolean = false) {
    CRASH("АВАРІЯ", "аварія", "crash"),
    OBJECT("ПРЕДМЕТ НА ДОРОЗІ", "предмет на дорозі", "object on road"),
    POTHOLE("ВЕЛИКА ЯМА", "велика яма", "large pothole"),
    STOPPED_VEHICLE("МАШИНА НА УЗБІЧЧІ", "машина на узбіччі", "stopped vehicle"),
    ANIMAL("ТВАРИНА", "тварина на дорозі", "animal on road"),
    CLOSURE("ПЕРЕКРИТТЯ", "перекрита дорога", "road closed"),
    CONSTRUCTION("РЕМОНТ ДОРОГИ", "ремонт дороги", "road work"),
    WEATHER("ПОГОДА", "небезпечна погода", "bad weather"),
    TRAFFIC("ЗАТОР", "затор", "traffic jam"),
    HAZARD("НЕБЕЗПЕКА", "небезпека на дорозі", "road hazard"),
    POLICE("ПОЛІЦІЯ", "поліція", "police", police = true);

    /** Дуже небезпечні події — до голосу додаємо «Slow down». */
    val severe: Boolean get() = this == CRASH || this == OBJECT || this == ANIMAL
}

/** Одне попередження, побачене на екрані Waze. [meters] — відстань, якщо Waze її показує. */
data class HazardSighting(val type: HazardType, val meters: Double?, val lane: String? = null)

/**
 * Читає з екрана Waze його власні попередження («Police 1.2 mi», «Object on road», «Crash ahead»).
 * Ми НЕ беремо дані з серверів Waze — тільки те, що Waze уже показав водієві на його телефоні.
 */
object HazardParser {
    private fun re(s: String) = Regex(s, RegexOption.IGNORE_CASE)

    /** Порядок перевірки: від конкретного до загального («Hazard: object on road» — це предмет, а не «небезпека»). */
    private val KEYWORDS: List<Pair<HazardType, Regex>> = listOf(
        HazardType.STOPPED_VEHICLE to re("stopped vehicle|vehicle stopped|car stopped|stalled|on (the )?shoulder|на узбіч"),
        HazardType.ANIMAL to re("\\banimal|\\bdeer\\b|тварин"),
        HazardType.OBJECT to re("object|debris|lost load|fallen|cargo|на дорозі предмет|предмет"),
        HazardType.POTHOLE to re("pothole|\\bpit\\b|яма|вибоїн"),
        HazardType.CRASH to re("\\bcrash|\\baccident|collision|аварі|дтп"),
        HazardType.CLOSURE to re("road closed|lane closed|closure|blocked lane|перекрит"),
        HazardType.CONSTRUCTION to re("construction|road ?work|ремонт"),
        HazardType.WEATHER to re("\\bfog|\\bice\\b|\\bicy\\b|flood|\\bhail|\\bsnow|slippery|туман|ожеледиц|повін"),
        HazardType.TRAFFIC to re("heavy traffic|standstill|stand-still|traffic jam|\\bjam\\b|затор"),
        HazardType.HAZARD to re("\\bhazard|небезпек"),
        HazardType.POLICE to re("\\bpolice\\b|speed trap|\\bpolicia\\b|поліці|полици|\\bcops?\\b|state trooper"),
    )
    private val DIST = re("(\\d{1,4}(?:[.,]\\d{1,2})?)\\s*(mi|miles?|ft|feet|km|m|м|км)\\b")
    private val AHEAD = re("ahead|reported|in \\d|попереду|впереди")
    private val LANE = re("(left|right|middle|center|centre) lane|\\bshoulder\\b")

    /** Після проїзду Waze питає «Still there?» — це вже запізно, такі ігноруємо. */
    private val AFTER_PASS = re("still there|not there|is it still|ще там|все ще")

    /** Найбільша відстань, яку вважаємо попередженням (а не, наприклад, довжиною всього маршруту). */
    const val MAX_METERS = 6_000.0

    fun metersOf(m: MatchResult): Double? {
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "mi", "mile", "miles" -> v * METERS_PER_MILE
            "ft", "feet" -> v * 0.3048
            "km", "км" -> v * 1000
            else -> v
        }
    }

    fun typeOf(text: String): HazardType? = KEYWORDS.firstOrNull { (_, r) -> r.containsMatchIn(text) }?.first

    fun parse(items: List<ScreenText>): List<HazardSighting> {
        val texts = items.map { it.text.replace(' ', ' ').trim() }.filter { it.isNotEmpty() && it.length < 160 }
        if (texts.isEmpty()) return emptyList()
        // Меню «Повідомити» у Waze показує всі типи разом — це не попередження.
        val typesOnScreen = texts.mapNotNull { typeOf(it) }.toSet()
        if (typesOnScreen.size >= 4) return emptyList()

        val out = LinkedHashMap<HazardType, HazardSighting>()
        texts.forEachIndexed { i, t ->
            val type = typeOf(t) ?: return@forEachIndexed
            if (type in out) return@forEachIndexed
            // Відстань шукаємо в тому ж рядку або в сусідніх (у Waze назва і відстань — окремі написи).
            val window = (maxOf(0, i - 2)..minOf(texts.size - 1, i + 2)).map { texts[it] }
            val near = window.joinToString(" ")
            if (AFTER_PASS.containsMatchIn(near)) return@forEachIndexed
            val meters = window.asSequence()
                .flatMap { DIST.findAll(it) }
                .mapNotNull { metersOf(it) }
                .filter { it <= MAX_METERS }
                .minOrNull()
            if (meters == null && !AHEAD.containsMatchIn(near)) return@forEachIndexed
            val lane = LANE.find(near)?.value?.lowercase()
            out[type] = HazardSighting(type, meters, lane)
        }
        return out.values.toList()
    }
}

/** Голосове попередження: що, на якій відстані (якщо відома), смуга і чи це повторне. */
data class HazardAlert(val type: HazardType, val meters: Double?, val lane: String?, val repeat: Boolean)

/**
 * Вирішує, коли говорити. Поліція — за 2 і за 1 милю; перешкоди — за 1 і за 0,3 милі.
 * Між показами Waze відстань рахуємо самі за GPS (пробіг від моменту, коли бачили відстань на екрані),
 * тому друге попередження прозвучить вчасно, навіть якщо картка Waze вже зникла.
 * Одну й ту саму подію не оголошуємо двічі на тому самому порозі.
 */
class HazardTracker {
    private class State(
        var lastSeen: Long,
        var meters: Double?,
        var odoAtMeters: Double,
        var lane: String?,
        var nextThreshold: Int,
        var announcedNoDistance: Boolean,
    )

    private val states = HashMap<HazardType, State>()

    fun thresholds(t: HazardType): DoubleArray =
        if (t.police) doubleArrayOf(2.0 * METERS_PER_MILE, 1.0 * METERS_PER_MILE)
        else doubleArrayOf(1.0 * METERS_PER_MILE, 0.3 * METERS_PER_MILE)

    /** Нові картки з екрана. [odo] — пробіг машини в метрах (з GPS). */
    fun onSightings(list: List<HazardSighting>, now: Long, odo: Double): List<HazardAlert> {
        val out = ArrayList<HazardAlert>()
        for (s in list) {
            var st = states[s.type]
            val estimate = st?.let { estimate(it, odo) }
            // Нова подія: давно не бачили, або Waze показує відстань значно більшу, ніж ми очікували.
            val isNew = st == null || now - st.lastSeen > FORGET_MS ||
                (s.meters != null && estimate != null && s.meters > estimate + 800)
            if (isNew) {
                st = State(now, s.meters, odo, s.lane, 0, false)
                states[s.type] = st
            } else {
                st!!.lastSeen = now
                if (s.meters != null) { st.meters = s.meters; st.odoAtMeters = odo }
                if (s.lane != null) st.lane = s.lane
            }
            if (s.meters == null && st.meters == null) {
                // Waze не показав відстань — кажемо один раз «попереду».
                if (!st.announcedNoDistance) {
                    st.announcedNoDistance = true
                    out += HazardAlert(s.type, null, st.lane, repeat = false)
                }
                continue
            }
            check(s.type, st, odo)?.let { out += it }
        }
        return out
    }

    /** Машина проїхала ще трохи — може, вже пора друге попередження. */
    fun onTravel(odo: Double, now: Long): List<HazardAlert> {
        val out = ArrayList<HazardAlert>()
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val (type, st) = it.next()
            val est = estimate(st, odo)
            if (now - st.lastSeen > FORGET_MS || (est != null && est < -300)) { it.remove(); continue }
            check(type, st, odo)?.let { a -> out += a }
        }
        return out
    }

    private fun estimate(st: State, odo: Double): Double? = st.meters?.let { it - (odo - st.odoAtMeters) }

    private fun check(type: HazardType, st: State, odo: Double): HazardAlert? {
        val th = thresholds(type)
        val est = estimate(st, odo) ?: return null
        if (est < 0 || st.nextThreshold >= th.size) return null
        // Беремо найближчий поріг, який уже перетнули; попередні пропускаємо (не говоримо двічі підряд).
        var idx = -1
        for (i in st.nextThreshold until th.size) if (est <= th[i] + SLACK) idx = i
        if (idx < 0) return null
        val repeat = st.nextThreshold > 0 || st.announcedNoDistance
        st.nextThreshold = idx + 1
        return HazardAlert(type, est, st.lane, repeat)
    }

    fun clear() = states.clear()

    companion object {
        /** Якщо подію не видно 6 хвилин — наступна така ж вважається новою. */
        const val FORGET_MS = 6 * 60_000L
        /** Waze округлює відстань — даємо запас ~0,1 милі. */
        const val SLACK = 150.0
    }
}
