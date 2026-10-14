package com.truckbro.app.service

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import com.truckbro.app.data.VoiceLang
import com.truckbro.app.road.HazardAlert
import com.truckbro.app.road.METERS_PER_MILE
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Голос. YouTube та інші програми НЕ стишуються — підказка звучить поверх них
 * (так попросив водій: він і так її чує).
 */
class Speaker(context: Context) : TextToSpeech.OnInitListener {
    private val ctx = context.applicationContext
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var tts: TextToSpeech? = TextToSpeech(ctx, this)
    private var ready = false
    private val pending = ArrayList<Pair<String, VoiceLang>>()

    /** null — ще невідомо; true — українського голосу на телефоні немає. */
    var ukrainianMissing: Boolean? = null
        private set

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        t.setAudioAttributes(attrs)
        t.setSpeechRate(1.05f)
        ukrainianMissing = t.isLanguageAvailable(UK) < TextToSpeech.LANG_AVAILABLE
        pending.forEach { (s, l) -> speak(s, l, flush = false) }
        pending.clear()
    }

    /** Мова, якою реально говоримо (якщо української немає — англійська). */
    fun effectiveLang(wanted: VoiceLang): VoiceLang =
        if (wanted == VoiceLang.UK && ukrainianMissing == true) VoiceLang.EN else wanted

    /** [flush] — перебити те, що зараз говориться (для важливих попереджень). */
    fun speak(text: String, lang: VoiceLang, flush: Boolean = true) {
        if (text.isBlank()) return
        val t = tts ?: return
        if (!ready) { pending += text to lang; return }
        t.language = if (effectiveLang(lang) == VoiceLang.UK) UK else Locale.US
        t.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "tb-${System.nanoTime()}")
    }

    fun stop() { tts?.stop() }

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null
    }

    companion object {
        val UK: Locale = Locale("uk", "UA")
    }
}

/** Тексти голосових підказок. Короткі, щоб не відволікати від дороги. */
object Phrases {
    private fun plural(n: Int, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n)
        return when {
            a % 10 == 1 && a % 100 != 11 -> one
            a % 10 in 2..4 && a % 100 !in 12..14 -> few
            else -> many
        }
    }

    /** «за 2 милі», «за пів милі», «за 500 футів» / «2 miles», «half a mile». */
    fun distance(m: Double, lang: VoiceLang): String {
        val mi = m / METERS_PER_MILE
        return when (lang) {
            VoiceLang.UK -> when {
                mi < 0.2 -> "за ${roundTo(m / 0.3048, 100)} футів"
                mi < 0.4 -> "за третину милі"
                mi < 0.75 -> "за пів милі"
                else -> { val n = mi.roundToInt().coerceAtLeast(1); "за $n ${plural(n, "милю", "милі", "миль")}" }
            }
            VoiceLang.EN -> when {
                mi < 0.2 -> "${roundTo(m / 0.3048, 100)} feet"
                mi < 0.4 -> "a third of a mile"
                mi < 0.75 -> "half a mile"
                else -> { val n = mi.roundToInt().coerceAtLeast(1); "$n mile${if (n == 1) "" else "s"}" }
            }
        }
    }

    /** Коротко для плашки: «1.2 mi», «500 ft». */
    fun short(m: Double): String =
        if (m < 300) "${roundTo(m / 0.3048, 50)} ft" else "%.1f mi".format(Locale.US, m / METERS_PER_MILE)

    private fun roundTo(v: Double, step: Int): Int = ((v / step).roundToInt() * step).coerceAtLeast(step)

    private fun laneUk(l: String) = when {
        l.startsWith("left") -> "ліва смуга"
        l.startsWith("right") -> "права смуга"
        l.startsWith("middle") || l.startsWith("center") || l.startsWith("centre") -> "середня смуга"
        l.contains("shoulder") -> "на узбіччі"
        else -> l
    }

    fun hazard(a: HazardAlert, lang: VoiceLang): String {
        val t = a.type
        return when (lang) {
            VoiceLang.UK -> buildString {
                val what = t.uk.replaceFirstChar { it.uppercase() }
                if (t.police) append("Увага! Поліція") else append("Увага! $what")
                append(" попереду")
                a.meters?.let { append(", ").append(distance(it, lang)) }
                append('.')
                a.lane?.let { if (!(t == com.truckbro.app.road.HazardType.STOPPED_VEHICLE && it.contains("shoulder"))) append(" ${laneUk(it).replaceFirstChar { c -> c.uppercase() }}.") }
                if (t.severe) append(" Зменш швидкість.")
            }
            VoiceLang.EN -> buildString {
                append(t.en.replaceFirstChar { it.uppercase() }).append(" ahead")
                a.meters?.let { append(", ").append(distance(it, lang)) }
                append('.')
                a.lane?.let { if (!(t == com.truckbro.app.road.HazardType.STOPPED_VEHICLE && it.contains("shoulder"))) append(" ${it.replaceFirstChar { c -> c.uppercase() }}.") }
                if (t.severe) append(" Slow down.")
            }
        }
    }

    fun statusWord(open: com.truckbro.app.road.WeighStatus, lang: VoiceLang) = when (lang) {
        VoiceLang.UK -> when (open) {
            com.truckbro.app.road.WeighStatus.OPEN -> "відкриті"
            com.truckbro.app.road.WeighStatus.CLOSED -> "закриті"
            else -> "статус невідомий"
        }
        VoiceLang.EN -> when (open) {
            com.truckbro.app.road.WeighStatus.OPEN -> "status: open"
            com.truckbro.app.road.WeighStatus.CLOSED -> "status: closed"
            else -> "status unknown"
        }
    }

    fun weighAhead(miles: Int, status: com.truckbro.app.road.WeighStatus, lang: VoiceLang): String = when (lang) {
        VoiceLang.UK -> "Ваги попереду, ${distance(miles * METERS_PER_MILE, lang)}. ${statusWord(status, lang).replaceFirstChar { it.uppercase() }}." +
            if (status == com.truckbro.app.road.WeighStatus.UNKNOWN) " Познач на екрані, відкриті чи закриті." else ""
        VoiceLang.EN -> "Weigh station ahead in ${distance(miles * METERS_PER_MILE, lang)}, ${statusWord(status, lang)}."
    }

    fun bypassAuto(exitM: Double, extraMin: Int, kindWeigh: Boolean, lang: VoiceLang): String = when (lang) {
        VoiceLang.UK -> (if (kindWeigh) "Ваги відкриті. " else "") +
            "Починаю об'їзд. З'їзд ${distance(exitM, lang)}, плюс $extraMin ${plural(extraMin, "хвилина", "хвилини", "хвилин")}."
        VoiceLang.EN -> (if (kindWeigh) "Weigh station open. " else "") +
            "Bypass started, exit in ${distance(exitM, lang)}, plus $extraMin minute${if (extraMin == 1) "" else "s"}."
    }

    fun bypassAsk(extraMin: Int, kindWeigh: Boolean, lang: VoiceLang): String = when (lang) {
        VoiceLang.UK -> (if (kindWeigh) "Ваги відкриті. " else "Поліція попереду. ") +
            "Є об'їзд, плюс $extraMin ${plural(extraMin, "хвилина", "хвилини", "хвилин")}. Підтверди на екрані."
        VoiceLang.EN -> (if (kindWeigh) "Weigh station open. " else "Police ahead. ") +
            "Bypass plus $extraMin minute${if (extraMin == 1) "" else "s"}. Confirm?"
    }

    fun noBypass(kindWeigh: Boolean, lang: VoiceLang): String = when (lang) {
        VoiceLang.UK -> if (kindWeigh) "Ваги відкриті. Об'їзду немає." else "Об'їзду немає."
        VoiceLang.EN -> if (kindWeigh) "No bypass available, weigh station open." else "No bypass available."
    }

    fun rerouting(lang: VoiceLang) = if (lang == VoiceLang.UK) "Перераховую об'їзд." else "Recalculating bypass."
    fun backOnRoute(lang: VoiceLang) = if (lang == VoiceLang.UK) "Об'їзд закінчено. Ти знову на основному маршруті." else "Back on main route."
    fun bypassCancelled(lang: VoiceLang) = if (lang == VoiceLang.UK) "Об'їзд скасовано." else "Bypass cancelled."
    fun wazeLost(lang: VoiceLang) = if (lang == VoiceLang.UK) "Увага! Waze не видно. Відкрий Waze знову." else "Waze connection lost."
    fun wazeBack(lang: VoiceLang) = if (lang == VoiceLang.UK) "Waze знову на зв'язку." else "Waze connected."

    fun redStop(name: String, m: Double, lang: VoiceLang): String = when (lang) {
        VoiceLang.UK -> "Увага! Тракстоп $name ${distance(m, lang)} — червона зона. Не рекомендую зупинятися."
        VoiceLang.EN -> "High-risk area ahead: $name. Not recommended to stop."
    }

    fun safeStop(name: String, m: Double, lang: VoiceLang): String = when (lang) {
        VoiceLang.UK -> "Найближчий безпечний: $name, ${distance(m, lang)}."
        VoiceLang.EN -> "Nearest safe stop: $name, ${distance(m, lang)}."
    }
}
