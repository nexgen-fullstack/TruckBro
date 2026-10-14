package com.truckbro.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.truckbro.app.road.LatLon
import java.util.Locale

/** SOS: надіслати своє місце в Telegram (або в будь-яку іншу програму) і дзвінок 911. */
object Sos {
    fun message(here: LatLon?): String {
        val where = here?.let {
            "https://maps.google.com/?q=${"%.6f".format(Locale.US, it.lat)},${"%.6f".format(Locale.US, it.lon)}"
        } ?: "(не вдалося визначити місце — увімкни GPS)"
        return "🆘 Мені потрібна допомога! Моє місце зараз: $where"
    }

    fun telegram(ctx: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        val tg = Intent(send).setPackage("org.telegram.messenger")
        val ok = runCatching { ctx.startActivity(tg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (!ok) ctx.startActivity(Intent.createChooser(send, "Надіслати SOS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun call911(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Відкрити точку в Waze (наприклад, безпечний тракстоп). */
    fun openInWaze(ctx: Context, p: LatLon) {
        val uri = Uri.parse("https://waze.com/ul?ll=${p.lat},${p.lon}&navigate=yes")
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
