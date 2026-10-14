package com.truckbro.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.truckbro.app.road.HazardType
import com.truckbro.app.road.Vehicle

enum class VoiceLang(val label: String, val routing: String) { UK("Українська", "uk-UA"), EN("English", "en-US") }

/**
 * Усі налаштування. Значення за замовчуванням — для Ford F-450 з причепом-автовозом:
 * повна маса понад 10 001 фунт, тому на відкритих вагах у більшості штатів треба зупинятися.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("truckbro", Context.MODE_PRIVATE)

    var voiceOn: Boolean
        get() = sp.getBoolean("voiceOn", true)
        set(v) = sp.edit { putBoolean("voiceOn", v) }

    var voiceLang: VoiceLang
        get() = enumOr(sp.getString("voiceLang", null), VoiceLang.UK)
        set(v) = sp.edit { putString("voiceLang", v.name) }

    // ---------- Мигалка ----------

    /** Прозорість половин екрана під час мигання, 0.1..0.6. */
    var flashOpacity: Float
        get() = sp.getFloat("flashOpacity", 0.30f)
        set(v) = sp.edit { putFloat("flashOpacity", v) }

    /** Скільки секунд мигає. */
    var flashSeconds: Int
        get() = sp.getInt("flashSeconds", 5)
        set(v) = sp.edit { putInt("flashSeconds", v) }

    /** Перемикань кольорів за секунду (не більше 3 — безпечно для очей). */
    var flashRate: Float
        get() = sp.getFloat("flashRate", 2.5f)
        set(v) = sp.edit { putFloat("flashRate", v) }

    // ---------- Події з Waze: окремо голос і мигалка для кожного типу ----------

    fun hazardVoice(t: HazardType): Boolean = sp.getBoolean("hv_${t.name}", true)
    fun setHazardVoice(t: HazardType, v: Boolean) = sp.edit { putBoolean("hv_${t.name}", v) }
    fun hazardFlash(t: HazardType): Boolean = sp.getBoolean("hf_${t.name}", true)
    fun setHazardFlash(t: HazardType, v: Boolean) = sp.edit { putBoolean("hf_${t.name}", v) }

    /** Попередити, якщо Waze закрився або перестав читатися під час руху. */
    var wazeLostAlert: Boolean
        get() = sp.getBoolean("wazeLostAlert", true)
        set(v) = sp.edit { putBoolean("wazeLostAlert", v) }

    // ---------- Ваги ----------

    /** Попереджати про ваги (вимкнути, якщо машина легка й зупинятися не треба). */
    var weighAlerts: Boolean
        get() = sp.getBoolean("weighAlerts", true)
        set(v) = sp.edit { putBoolean("weighAlerts", v) }

    /** Пропонувати об'їзд відкритих ваг. За замовчуванням ВИМКНЕНО — вмикає водій сам після попередження. */
    var bypassWeigh: Boolean
        get() = sp.getBoolean("bypassWeigh", false)
        set(v) = sp.edit { putBoolean("bypassWeigh", v) }

    var bypassLegalAccepted: Boolean
        get() = sp.getBoolean("bypassLegalAccepted", false)
        set(v) = sp.edit { putBoolean("bypassLegalAccepted", v) }

    /** Будувати об'їзд і тоді, коли статус ваг невідомий. */
    var bypassUnknown: Boolean
        get() = sp.getBoolean("bypassUnknown", false)
        set(v) = sp.edit { putBoolean("bypassUnknown", v) }

    /** Об'їзд, довший на стільки хвилин або менше, вмикається сам; довший — тільки після підтвердження. */
    var bypassAutoMinutes: Int
        get() = sp.getInt("bypassAutoMinutes", 10)
        set(v) = sp.edit { putInt("bypassAutoMinutes", v) }

    /** Пропонувати об'їзд поліції (завжди з підтвердженням). */
    var bypassPolice: Boolean
        get() = sp.getBoolean("bypassPolice", false)
        set(v) = sp.edit { putBoolean("bypassPolice", v) }

    /** Через скільки секунд після кінця об'їзду самому повернутися в попередню програму (0 — не повертатися). */
    var returnSeconds: Int
        get() = sp.getInt("returnSeconds", 10)
        set(v) = sp.edit { putInt("returnSeconds", v) }

    // ---------- Машина (для маршрутів) ----------

    var heightFt: Float
        get() = sp.getFloat("heightFt", 13.5f)
        set(v) = sp.edit { putFloat("heightFt", v) }

    var weightLbs: Int
        get() = sp.getInt("weightLbs", 30_000)
        set(v) = sp.edit { putInt("weightLbs", v) }

    var lengthFt: Float
        get() = sp.getFloat("lengthFt", 53f)
        set(v) = sp.edit { putFloat("lengthFt", v) }

    val vehicle: Vehicle get() = Vehicle(heightFt, weightLbs, lengthFt)

    // ---------- Тракстопи ----------

    var stopAlerts: Boolean
        get() = sp.getBoolean("stopAlerts", true)
        set(v) = sp.edit { putBoolean("stopAlerts", v) }

    // ---------- Службове ----------

    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit { putBoolean("setupDone", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit { putLong("lastUpdateCheck", v) }

    var latestVersion: String?
        get() = sp.getString("latestVersion", null)
        set(v) = sp.edit { putString("latestVersion", v) }

    var latestApkUrl: String?
        get() = sp.getString("latestApkUrl", null)
        set(v) = sp.edit { putString("latestApkUrl", v) }

    var latestNotes: String?
        get() = sp.getString("latestNotes", null)
        set(v) = sp.edit { putString("latestNotes", v) }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: def
}
