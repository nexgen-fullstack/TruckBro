package com.truckbro.app.util

import android.content.Context
import com.truckbro.app.BuildConfig
import com.truckbro.app.road.Http
import com.truckbro.app.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Перевіряє на GitHub, чи вийшла нова версія. Встановлює [Updater] — після натискання водія. */
object UpdateChecker {
    data class Update(val version: String, val apkUrl: String, val pageUrl: String, val notes: String = "")

    suspend fun check(ctx: Context, force: Boolean = false): Update? = withContext(Dispatchers.IO) {
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.lastUpdateCheck < 3600_000L) return@withContext cached(prefs)
        prefs.lastUpdateCheck = now
        val json = JSONObject(Http.get("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest", 8_000))
        val tag = json.getString("tag_name").trimStart('v', 'V')
        val assets = json.getJSONArray("assets")
        var apk: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) { apk = a.getString("browser_download_url"); break }
        }
        prefs.latestVersion = tag
        prefs.latestApkUrl = apk ?: json.getString("html_url")
        prefs.latestNotes = json.optString("body").takeIf { it != "null" }.orEmpty()
        cached(prefs)
    }

    fun cached(prefs: Prefs): Update? {
        val v = prefs.latestVersion ?: return null
        val url = prefs.latestApkUrl ?: return null
        return if (isNewer(v, BuildConfig.VERSION_NAME)) Update(v, url, url, prefs.latestNotes.orEmpty()) else null
    }

    fun isNewer(a: String, b: String): Boolean {
        val pa = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }; val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
