package com.truckbro.app.road

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Http {
    const val UA = "TruckBro/1.0 (driver safety helper; personal use; github.com/nexgen-fullstack/TruckBro)"

    fun get(url: String, timeoutMs: Int = 15_000): String = request(url, null, null, timeoutMs)

    fun postJson(url: String, body: String, timeoutMs: Int = 20_000): String =
        request(url, body, "application/json", timeoutMs)

    fun postForm(url: String, fields: Map<String, String>, timeoutMs: Int = 60_000): String =
        request(url, fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" },
            "application/x-www-form-urlencoded", timeoutMs)

    private fun request(url: String, body: String?, type: String?, timeoutMs: Int): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", type)
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val err = runCatching { c.errorStream?.bufferedReader()?.readText() }.getOrNull().orEmpty()
                throw HttpException(code, err.take(300))
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }
}

class HttpException(val code: Int, val body: String) : Exception("HTTP $code $body")
