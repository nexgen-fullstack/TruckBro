package com.truckbro.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.truckbro.app.road.LatLon
import kotlin.math.abs
import kotlin.math.cos

/** Кнопка на картці: текст, колір, дія. */
data class CardButton(val text: String, val color: Int, val action: () -> Unit)

/** Що показати на маленькій картці поверх YouTube. */
data class CardContent(
    val title: String,
    val line: String,
    val accent: Int,
    val buttons: List<CardButton>,
    /** Лінії для мініатюри: основний маршрут, об'їзд, місце, яке об'їжджаємо, і де машина. */
    val main: List<LatLon>? = null,
    val bypass: List<LatLon>? = null,
    val avoid: LatLon? = null,
    val here: LatLon? = null,
    /** Натискання на саму картку (не на кнопки). */
    val onTap: (() -> Unit)? = null,
)

/**
 * Маленька плаваюча картка в кутку екрана (приблизно чверть екрана). Її можна перетягнути пальцем.
 * Дотики поза карткою проходять у YouTube / Waze як звичайно.
 */
class CardOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var titleView: TextView? = null
    private var lineView: TextView? = null
    private var mapView: MiniRouteView? = null
    private var buttonsRow: LinearLayout? = null
    private var content: CardContent? = null
    /** Куди водій перетягнув картку — запам'ятовуємо до кінця роботи сервісу. */
    private var savedX: Int? = null
    private var savedY: Int? = null

    val isShowing get() = root != null

    /** Тимчасово сховати (поки відкритий екран навігації) і показати знову. */
    fun setHidden(h: Boolean) { root?.visibility = if (h) View.GONE else View.VISIBLE }

    fun show(c: CardContent) {
        content = c
        val r = root ?: create() ?: return
        (r.background as GradientDrawable).setStroke((3 * density).toInt(), c.accent)
        titleView?.text = c.title
        titleView?.setTextColor(c.accent)
        lineView?.text = c.line
        val mv = mapView!!
        mv.visibility = if (c.bypass != null) View.VISIBLE else View.GONE
        mv.set(c.main, c.bypass, c.avoid, c.here)
        val row = buttonsRow!!
        row.removeAllViews()
        c.buttons.forEach { b ->
            row.addView(Button(ctx).apply {
                text = b.text
                isAllCaps = false
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(if (b.color == YELLOW) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply { setColor(b.color); cornerRadius = 12 * density }
                minHeight = (48 * density).toInt()
                setOnClickListener { b.action() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (3 * density).toInt(); marginEnd = (3 * density).toInt()
            })
        }
    }

    /** Оновити тільки рядок і позицію машини (без перебудови кнопок). */
    fun update(line: String, here: LatLon?) {
        if (root == null) return
        lineView?.text = line
        mapView?.setHere(here)
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; content = null
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun create(): LinearLayout? {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val w = (screenW * 0.55f).toInt().coerceIn((260 * density).toInt(), (420 * density).toInt())
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val p = (10 * density).toInt()
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { setColor(Color.argb(235, 17, 19, 21)); cornerRadius = 18 * density }
            elevation = 12 * density
        }
        titleView = TextView(ctx).apply { textSize = 17f; setTypeface(typeface, Typeface.BOLD) }
        lineView = TextView(ctx).apply { textSize = 15f; setTextColor(Color.WHITE) }
        mapView = MiniRouteView(ctx)
        buttonsRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, (6 * density).toInt(), 0, 0) }
        r.addView(titleView)
        r.addView(lineView)
        r.addView(mapView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (w * 0.5f).toInt()).apply {
            topMargin = (6 * density).toInt()
        })
        r.addView(buttonsRow)

        val lp = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX ?: (screenW - w - (8 * density).toInt())
            y = savedY ?: (ctx.resources.displayMetrics.heightPixels * 0.42f).toInt()
            title = "TruckBro card"
        }

        // Перетягування картки; короткий дотик без руху — «натиснули на картку».
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        r.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (abs(dx) > 12 * density || abs(dy) > 12 * density) moved = true
                    if (moved) {
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        savedX = lp.x; savedY = lp.y
                        runCatching { wm.updateViewLayout(r, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) content?.onTap?.invoke(); true }
                else -> false
            }
        }
        if (com.truckbro.app.service.DriveState.navVisible.value) r.visibility = View.GONE
        if (runCatching { wm.addView(r, lp) }.isFailure) return null
        root = r; params = lp
        return r
    }

    /** Мініатюра маршруту: сіра лінія — звичайний шлях, синя — об'їзд, червоне коло — те, що об'їжджаємо. */
    private class MiniRouteView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private var main: List<LatLon>? = null
        private var bypass: List<LatLon>? = null
        private var avoid: LatLon? = null
        private var here: LatLon? = null
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bg = Paint().apply { color = Color.argb(255, 32, 38, 46) }

        fun set(m: List<LatLon>?, b: List<LatLon>?, a: LatLon?, h: LatLon?) { main = m; bypass = b; avoid = a; here = h; invalidate() }
        fun setHere(h: LatLon?) { here = h; invalidate() }

        override fun onDraw(c: Canvas) {
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12 * d, 12 * d, bg)
            val b = bypass ?: return
            val pts = b + listOfNotNull(avoid, here)
            val minLat = pts.minOf { it.lat }; val maxLat = pts.maxOf { it.lat }
            val minLon = pts.minOf { it.lon }; val maxLon = pts.maxOf { it.lon }
            val k = cos(Math.toRadians((minLat + maxLat) / 2))
            val spanX = ((maxLon - minLon) * k).coerceAtLeast(1e-4); val spanY = (maxLat - minLat).coerceAtLeast(1e-4)
            val pad = 14 * d
            val scale = minOf((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
            val ox = (width - spanX * scale) / 2; val oy = (height - spanY * scale) / 2
            fun x(p: LatLon) = (ox + (p.lon - minLon) * k * scale).toFloat()
            fun y(p: LatLon) = (oy + (maxLat - p.lat) * scale).toFloat()
            fun draw(l: List<LatLon>, color: Int, w: Float) {
                if (l.size < 2) return
                val path = Path().apply { moveTo(x(l[0]), y(l[0])); l.drop(1).forEach { lineTo(x(it), y(it)) } }
                line.color = color; line.strokeWidth = w * d; c.drawPath(path, line)
            }
            main?.let { draw(it, Color.argb(255, 120, 130, 140), 4f) }
            draw(b, Color.parseColor("#2F8CFF"), 5f)
            avoid?.let { dot.color = Color.parseColor("#E53935"); c.drawCircle(x(it), y(it), 7 * d, dot) }
            here?.let {
                dot.color = Color.WHITE; c.drawCircle(x(it), y(it), 7 * d, dot)
                dot.color = Color.parseColor("#2F8CFF"); c.drawCircle(x(it), y(it), 4.5f * d, dot)
            }
        }
    }

    companion object {
        val GREEN = Color.parseColor("#2E7D32")
        val RED = Color.parseColor("#C62828")
        val BLUE = Color.parseColor("#1565C0")
        val GREY = Color.parseColor("#455A64")
        val YELLOW = Color.parseColor("#FFB300")
        val PURPLE = Color.parseColor("#8E24AA")
    }
}
