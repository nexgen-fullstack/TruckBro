package com.truckbro.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class JournalEntry(val time: Long, val kind: String, val text: String)

/** Журнал подій: що програма побачила і що сказала. Щоб розібратися, якщо щось спрацювало не так. */
object Journal {
    private const val MAX = 400
    private val _entries = MutableStateFlow<List<JournalEntry>>(emptyList())
    val entries: StateFlow<List<JournalEntry>> = _entries
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "journal.json")

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _entries.value = runCatching {
            val a = JSONArray(file(ctx).readText())
            (0 until a.length()).map { a.getJSONObject(it).let { o -> JournalEntry(o.getLong("t"), o.getString("k"), o.getString("x")) } }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(ctx: Context, kind: String, text: String) {
        load(ctx)
        _entries.value = (listOf(JournalEntry(System.currentTimeMillis(), kind, text)) + _entries.value).take(MAX)
        val a = JSONArray()
        _entries.value.forEach { a.put(JSONObject().put("t", it.time).put("k", it.kind).put("x", it.text)) }
        runCatching { file(ctx).writeText(a.toString()) }
    }

    @Synchronized
    fun clear(ctx: Context) {
        _entries.value = emptyList()
        runCatching { file(ctx).delete() }
    }
}
