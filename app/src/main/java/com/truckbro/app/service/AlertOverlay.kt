package com.truckbro.app.service

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import com.truckbro.app.road.HazardType

/** Налаштування мигалки (з Prefs). */
data class FlashStyle(val opacity: Float, val seconds: Int, val rate: Float)

/**
 * Мигалка поверх усіх програм (YouTube, Waze, будь-що).
 * Вікно типу «accessibility overlay» ПРОПУСКАЄ ВСІ ДОТИКИ і напівпрозоре — під ним усе видно.
 */
class AlertOverlay(private val ctx: Context) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var lights: LightsView? = null
    private val hideLights = Runnable { removeLights() }

    /** Поліція — червоно-синя мигалка; усе інше — жовта, як аварійка. */
    fun showLights(type: HazardType, subtitle: String, style: FlashStyle) {
        val v = lights ?: LightsView(ctx).also {
            runCatching { wm.addView(it, params()) }.onFailure { return }
            lights = it
        }
        v.start(type, subtitle, style)
        handler.removeCallbacks(hideLights)
        // Після миготіння плашка ще ~3 с, потім усе зникає саме.
        handler.postDelayed(hideLights, style.seconds * 1000L + 3_000L)
    }

    /** Ваги попереду — фіолетово-жовта мигалка. */
    fun showWeighLights(subtitle: String, style: FlashStyle) {
        val v = lights ?: LightsView(ctx).also {
            runCatching { wm.addView(it, params()) }.onFailure { return }
            lights = it
        }
        v.start(HazardType.HAZARD, subtitle, style, weigh = true)
        handler.removeCallbacks(hideLights)
        handler.postDelayed(hideLights, style.seconds * 1000L + 3_000L)
    }

    private fun removeLights() {
        lights?.let { it.stop(); runCatching { wm.removeView(it) } }
        lights = null
    }

    fun hideAll() { handler.removeCallbacks(hideLights); removeLights() }

    private fun params() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        title = "TruckBro lights"
    }

    private class LightsView(ctx: Context) : View(ctx) {
        private val density = ctx.resources.displayMetrics.density
        private var type = HazardType.POLICE
        private var subtitle = ""
        private var weigh = false
        private var style = FlashStyle(0.3f, 5, 2.5f)
        private var phases = 12
        private var phase = -1 // -1 — миготіння закінчилось, лишилась тільки рамка й плашка
        private var animator: ValueAnimator? = null
        private val paint = Paint()
        private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 26 * density
        }
        private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 17 * density }
        private val rect = RectF()

        fun start(t: HazardType, sub: String, s: FlashStyle, weigh: Boolean = false) {
            stop()
            this.weigh = weigh
            type = t; subtitle = sub; style = s
            // Не частіше 3 перемикань на секунду — безпечно для очей.
            val stepMs = (1000f / s.rate.coerceIn(1f, 3f)).toLong()
            phases = ((s.seconds * 1000L) / stepMs).toInt().coerceAtLeast(2)
            animator = ValueAnimator.ofInt(0, phases).apply {
                duration = phases * stepMs
                interpolator = LinearInterpolator()
                addUpdateListener { val ph = it.animatedValue as Int; if (ph != phase) { phase = ph; invalidate() } }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) { phase = -1; invalidate() }
                })
                start()
            }
        }

        fun stop() { animator?.cancel(); animator = null }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val stroke = 12 * density
            val flashing = phase in 0 until phases
            val swap = flashing && phase % 2 == 1
            val a = (style.opacity.coerceIn(0.05f, 0.7f) * 255).toInt()
            if (weigh) {
                // Ваги: рамка зліва фіолетова, справа жовта, половини екрана навпаки; кольори міняються місцями.
                val leftFrame = if (swap) AMBER else PURPLE
                val rightFrame = if (swap) PURPLE else AMBER
                if (flashing) {
                    paint.color = rightFrame; paint.alpha = a; canvas.drawRect(0f, 0f, w / 2, h, paint)
                    paint.color = leftFrame; paint.alpha = a; canvas.drawRect(w / 2, 0f, w, h, paint)
                }
                paint.alpha = 255
                paint.color = leftFrame
                canvas.drawRect(0f, 0f, stroke, h, paint); canvas.drawRect(0f, 0f, w / 2, stroke, paint)
                canvas.drawRect(0f, h - stroke, w / 2, h, paint)
                paint.color = rightFrame
                canvas.drawRect(w - stroke, 0f, w, h, paint); canvas.drawRect(w / 2, 0f, w, stroke, paint)
                canvas.drawRect(w / 2, h - stroke, w, h, paint)
            } else if (type.police) {
                // Рамка: зліва синя, справа червона. Половини екрана — навпаки: зліва червона, справа синя.
                // На кожному кроці все міняється місцями — як справжня мигалка.
                val leftFrame = if (swap) RED else BLUE
                val rightFrame = if (swap) BLUE else RED
                if (flashing) {
                    paint.color = rightFrame; paint.alpha = a; canvas.drawRect(0f, 0f, w / 2, h, paint)
                    paint.color = leftFrame; paint.alpha = a; canvas.drawRect(w / 2, 0f, w, h, paint)
                }
                paint.alpha = 255
                paint.color = leftFrame
                canvas.drawRect(0f, 0f, stroke, h, paint); canvas.drawRect(0f, 0f, w / 2, stroke, paint)
                canvas.drawRect(0f, h - stroke, w / 2, h, paint)
                paint.color = rightFrame
                canvas.drawRect(w - stroke, 0f, w, h, paint); canvas.drawRect(w / 2, 0f, w, stroke, paint)
                canvas.drawRect(w / 2, h - stroke, w, h, paint)
            } else {
                // Перешкода: увесь екран блимає жовтим, рамка — жовта / біла по черзі.
                if (flashing && !swap) { paint.color = AMBER; paint.alpha = a; canvas.drawRect(0f, 0f, w, h, paint) }
                paint.color = if (swap) Color.WHITE else AMBER; paint.alpha = 255
                canvas.drawRect(0f, 0f, w, stroke, paint); canvas.drawRect(0f, h - stroke, w, h, paint)
                canvas.drawRect(0f, 0f, stroke, h, paint); canvas.drawRect(w - stroke, 0f, w, h, paint)
            }

            // Плашка на третині висоти — не закриває підказку повороту Waze угорі й кнопки внизу.
            val title = if (weigh) "⚖ ВАГИ" else (if (type.police) "🚨 " else "⚠ ") + type.title
            val pad = 16 * density
            val tw = maxOf(titlePaint.measureText(title), subPaint.measureText(subtitle)) + 2 * pad
            val ph = if (subtitle.isEmpty()) 50 * density else 78 * density
            val top = h * 0.30f
            rect.set(((w - tw) / 2).coerceAtLeast(8 * density), top, ((w + tw) / 2).coerceAtMost(w - 8 * density), top + ph)
            pill.color = when { weigh -> PURPLE; type.police -> Color.parseColor("#0D2A6B"); else -> AMBER }
            pill.alpha = 240
            canvas.drawRoundRect(rect, 20 * density, 20 * density, pill)
            val tc = if (type.police || weigh) Color.WHITE else Color.BLACK
            titlePaint.color = tc; subPaint.color = tc
            canvas.drawText(title, rect.centerX() - titlePaint.measureText(title) / 2, rect.top + 34 * density, titlePaint)
            if (subtitle.isNotEmpty()) canvas.drawText(subtitle, rect.centerX() - subPaint.measureText(subtitle) / 2, rect.top + 62 * density, subPaint)
        }

        companion object {
            val RED = Color.parseColor("#E53935")
            val BLUE = Color.parseColor("#1E64FF")
            val AMBER = Color.parseColor("#FFB300")
            val PURPLE = Color.parseColor("#8E24AA")
        }
    }
}
