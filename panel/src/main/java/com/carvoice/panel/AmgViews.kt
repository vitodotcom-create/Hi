package com.carvoice.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import java.util.Calendar

/* Оформление панели «в стиле AMG»: карбон, красная подсветка, цветное стекло. */

private fun Context.dp(v: Float) = v * resources.displayMetrics.density

private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

const val AMG_RED = 0xFFFF1A2A.toInt()

/** Фон: карбон, красный луч, свечение, турбина-дефлектор, виньетка, подсветка снизу. */
class AmgBackgroundView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val carbon = Paint().apply { shader = BitmapShader(carbonTile(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun carbonTile(): Bitmap {
        // Плетение карбона 8×8: светлые и тёмные «нити» по диагонали.
        val s = 8
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        for (y in 0 until s) for (x in 0 until s) {
            val cell = ((x / 4) + (y / 4)) % 2 == 0
            val stripe = if (cell) (x + y) % 4 else (x - y + 8) % 4
            val v = when (stripe) { 0 -> 34; 1 -> 26; 2 -> 18; else -> 12 }
            b.setPixel(x, y, Color.rgb(v, v + 1, v + 3))
        }
        return b
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // Карбон
        canvas.drawRect(0f, 0f, w, h, carbon)
        // Затемнение к низу
        p.shader = LinearGradient(0f, 0f, 0f, h, 0x33000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        // Красный диагональный луч
        p.shader = LinearGradient(
            0f, 0f, w, w * 0.47f,
            intArrayOf(0, 0, 0x47E30613, 0), floatArrayOf(0f, 0.52f, 0.6f, 0.7f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, p)
        // Красное свечение справа сверху
        p.shader = RadialGradient(w, 0f, w * 0.55f, 0x59E30613, 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        // Холодный отсвет снизу
        p.shader = RadialGradient(w / 2, h * 1.2f, w * 0.6f, 0x2E788CAA, 0, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        drawTurbine(canvas, w * 1.02f, h * 0.15f, minOf(w, h) * 0.34f)
        // Виньетка
        p.shader = RadialGradient(
            w / 2, h * 0.45f, maxOf(w, h) * 0.75f,
            intArrayOf(0, 0, 0x8C000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null
        // Подсветка по нижнему краю
        val lineY = h - context.dp(1.5f)
        val line = LinearGradient(
            0f, 0f, w, 0f, intArrayOf(0, AMG_RED, AMG_RED, 0), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        glow.shader = line
        glow.strokeWidth = context.dp(5f)
        glow.maskFilter = BlurMaskFilter(context.dp(6f), BlurMaskFilter.Blur.NORMAL)
        glow.alpha = 150
        canvas.drawLine(0f, lineY, w, lineY, glow)
        glow.maskFilter = null
        glow.alpha = 255
        glow.strokeWidth = context.dp(2f)
        canvas.drawLine(0f, lineY, w, lineY, glow)
    }

    /** Турбина-дефлектор, как в салоне: кольцо, лопатки, ступица, красное свечение. */
    private fun drawTurbine(c: Canvas, cx: Float, cy: Float, r: Float) {
        c.save()
        c.translate(cx, cy)
        p.shader = RadialGradient(0f, 0f, r * 1.12f, intArrayOf(0x47FF2A2A, 0x0AFF2A2A, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, r * 1.12f, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.054f
        p.color = 0x2DDCE2EA
        c.drawCircle(0f, 0f, r, p)
        p.strokeWidth = context.dp(1.5f)
        p.color = 0x99FF2828.toInt()
        c.drawCircle(0f, 0f, r, p)
        p.strokeWidth = r * 0.027f
        p.color = 0x45FF3C3C
        val blade = Path().apply {
            moveTo(0f, -r * 0.23f)
            cubicTo(r * 0.125f, -r * 0.45f, r * 0.27f, -r * 0.62f, r * 0.2f, -r * 0.96f)
        }
        for (i in 0 until 15) {
            c.save()
            c.rotate(i * 24f)
            c.drawPath(blade, p)
            c.restore()
        }
        p.style = Paint.Style.FILL
        p.color = 0x80141619.toInt()
        c.drawCircle(0f, 0f, r * 0.23f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = r * 0.027f
        p.color = 0x40DCE2EA
        c.drawCircle(0f, 0f, r * 0.23f, p)
        p.style = Paint.Style.FILL
        c.restore()
    }
}

/** Шкала секунд как тахометр: 60 делений, ведущие деления — красные. */
class SecondsBarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AMG_RED
        maskFilter = BlurMaskFilter(context.dp(3f), BlurMaskFilter.Blur.NORMAL)
    }
    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            postDelayed(this, 1000L - System.currentTimeMillis() % 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val sec = Calendar.getInstance().get(Calendar.SECOND)
        val step = width / 60f
        val tw = maxOf(1f, step * 0.45f)
        val mid = height / 2f
        for (i in 0 until 60) {
            val big = i % 5 == 0
            val hh = if (big) height * 0.9f else height * 0.45f
            val x = i * step
            val filled = i <= sec
            val red = filled && i > sec - 8
            p.color = when {
                red -> AMG_RED
                filled -> Color.WHITE
                else -> 0x26FFFFFF
            }
            val r = RectF(x, mid - hh / 2, x + tw, mid + hh / 2)
            if (red) canvas.drawRoundRect(r, tw, tw, glow)
            canvas.drawRoundRect(r, tw / 2, tw / 2, p)
        }
    }
}

/** Погода в круглом датчике, как на приборке. */
class WeatherGaugeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private var tempText = "—"
    private var desc = ""
    private var icon: Drawable? = null
    /** Доля шкалы 0..1 (−20°…+40°) и показанная сейчас доля (для анимации). */
    private var target = 0f
    var shown = 0f
        set(v) {
            field = v
            invalidate()
        }

    fun set(temp: String, description: String, iconRes: Int?, celsius: Int?) {
        tempText = temp.ifBlank { "—" }
        desc = description.uppercase()
        icon = iconRes?.let { context.getDrawable(it)?.mutate() }
        target = if (celsius == null) 0f else ((celsius + 20) / 60f).coerceIn(0.02f, 1f)
        if (shown != 0f) shown = target else invalidate()
    }

    /** Приветствие: стрелка проходит всю шкалу и возвращается к значению. */
    fun sweep() {
        android.animation.ValueAnimator.ofFloat(0f, 1f, target).apply {
            duration = 1600
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { shown = it.animatedValue as Float }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        if (size <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2 - context.dp(4f)
        // Стеклянный круг
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, 0x24FFFFFF, 0x05FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = context.dp(1.2f)
        p.color = 0x73FFFFFF
        canvas.drawCircle(cx, cy, r, p)
        // Шкала 270°
        val arc = RectF(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f)
        p.strokeWidth = r * 0.1f
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0x1FFFFFFF
        canvas.drawArc(arc, 135f, 270f, false, p)
        if (shown > 0f) {
            val sweep = SweepGradient(
                cx, cy, intArrayOf(0xFF4FC3F7.toInt(), Color.WHITE, AMG_RED, 0xFF4FC3F7.toInt()),
                floatArrayOf(0f, 0.45f, 0.75f, 1f),
            )
            val m = android.graphics.Matrix().apply { setRotate(135f, cx, cy) }
            sweep.setLocalMatrix(m)
            p.shader = sweep
            canvas.drawArc(arc, 135f, 270f * shown, false, p)
            p.shader = null
        }
        p.strokeCap = Paint.Cap.BUTT
        p.style = Paint.Style.FILL
        // Значок, температура, описание
        icon?.let {
            val s = (r * 0.42f).toInt()
            it.setBounds((cx - s / 2).toInt(), (cy - r * 0.68f).toInt(), (cx + s / 2).toInt(), (cy - r * 0.68f + s).toInt())
            it.draw(canvas)
        }
        text.typeface = Typeface.create("sans-serif", Typeface.BOLD_ITALIC)
        text.textSize = r * 0.5f
        text.color = Color.WHITE
        canvas.drawText(tempText, cx, cy + r * 0.2f, text)
        text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        text.textSize = r * 0.16f
        text.letterSpacing = 0.08f
        text.color = 0xFFAAB2BE.toInt()
        canvas.drawText(desc, cx, cy + r * 0.52f, text)
        text.letterSpacing = 0f
    }
}

/** Красная светящаяся линия (контурная подсветка). */
class LightLineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val g = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val y = height / 2f
        val shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(0, AMG_RED, 0xFFFF5A3A.toInt(), AMG_RED, 0),
            floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP,
        )
        g.shader = shader
        g.strokeWidth = context.dp(6f)
        g.alpha = 140
        g.maskFilter = BlurMaskFilter(context.dp(5f), BlurMaskFilter.Blur.NORMAL)
        canvas.drawLine(0f, y, w, y, g)
        p.shader = shader
        p.strokeWidth = context.dp(2.5f)
        p.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(0f, y, w, y, p)
    }
}

/**
 * Плитка из цветного стекла: оттенок, большой изогнутый блик, пробегающий отблеск,
 * неоновая полоска снизу, светлая кромка. При наведении — красная светящаяся рамка.
 */
class GlassTileDrawable(private val context: Context, private val color: Int, private val pill: Boolean = false) : Drawable() {

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var hovered = false
    private var pressed = false
    /** Положение пробегающего отблеска: 0..1 — идёт по плитке, иначе не виден. */
    var sheen = -1f
        set(v) {
            field = v
            invalidateSelf()
        }

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val h = state.contains(android.R.attr.state_hovered) || state.contains(android.R.attr.state_focused)
        val pr = state.contains(android.R.attr.state_pressed)
        val changed = h != hovered || pr != pressed
        hovered = h
        pressed = pr
        if (changed) invalidateSelf()
        return changed
    }

    override fun draw(canvas: Canvas) {
        val pad = context.dp(6f)
        val r = RectF(bounds).apply { inset(pad, pad) }
        if (r.width() <= 0 || r.height() <= 0) return
        val rad = if (pill) r.height() / 2 else minOf(context.dp(18f), r.height() / 3)
        path.reset()
        path.addRoundRect(r, rad, rad, Path.Direction.CW)

        // Тень и (при наведении) красное свечение вокруг
        p.style = Paint.Style.FILL
        p.shader = null
        if (hovered) {
            p.color = withAlpha(AMG_RED, 170)
            p.maskFilter = BlurMaskFilter(pad * 1.3f, BlurMaskFilter.Blur.OUTER)
            canvas.drawPath(path, p)
        } else {
            p.color = 0x99000000.toInt()
            p.maskFilter = BlurMaskFilter(pad, BlurMaskFilter.Blur.OUTER)
            canvas.save()
            canvas.translate(0f, pad * 0.4f)
            canvas.drawPath(path, p)
            canvas.restore()
        }
        p.maskFilter = null

        canvas.save()
        canvas.clipPath(path)
        // Тёмное стекло + цветной оттенок по диагонали
        p.color = 0xB30B0D11.toInt()
        canvas.drawRect(r, p)
        val boost = if (hovered) 1.35f else if (pressed) 0.7f else 1f
        fun a(v: Int) = (v * boost).toInt().coerceIn(0, 255)
        p.shader = LinearGradient(
            r.left, r.top, r.right, r.bottom,
            intArrayOf(withAlpha(color, a(0x70)), withAlpha(color, a(0x24)), 0x590A0A0E, withAlpha(color, a(0x45))),
            floatArrayOf(0f, 0.5f, 0.52f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawRect(r, p)
        // Внутренняя тень снизу
        p.shader = LinearGradient(0f, r.bottom, 0f, r.bottom - r.height() * 0.4f, 0x59000000, 0, Shader.TileMode.CLAMP)
        canvas.drawRect(r, p)
        // Большой изогнутый блик сверху
        val gloss = RectF(r.left - r.width() * 0.2f, r.top - r.height() * 0.6f, r.right + r.width() * 0.2f, r.top + r.height() * 0.42f)
        p.shader = LinearGradient(0f, gloss.top, 0f, gloss.bottom, intArrayOf(0x6BFFFFFF, 0x1AFFFFFF, 0), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        canvas.drawOval(gloss, p)
        // Пробегающий отблеск
        if (sheen in 0f..1f) {
            val x = r.left - r.width() * 0.3f + sheen * r.width() * 1.6f
            val bw = r.width() * 0.16f
            canvas.save()
            canvas.rotate(20f, x, r.centerY())
            p.shader = LinearGradient(x - bw, 0f, x + bw, 0f, intArrayOf(0, 0x40FFFFFF, 0), null, Shader.TileMode.CLAMP)
            canvas.drawRect(x - bw, r.top - r.height(), x + bw, r.bottom + r.height(), p)
            canvas.restore()
        }
        p.shader = null
        canvas.restore()

        // Неоновая полоска снизу
        if (!pill) {
            val y = r.bottom - context.dp(1.5f)
            val x0 = r.left + r.width() * 0.1f
            val x1 = r.right - r.width() * 0.1f
            p.style = Paint.Style.STROKE
            p.strokeCap = Paint.Cap.ROUND
            p.color = color
            p.strokeWidth = context.dp(5f)
            p.alpha = 150
            p.maskFilter = BlurMaskFilter(context.dp(5f), BlurMaskFilter.Blur.NORMAL)
            canvas.drawLine(x0, y, x1, y, p)
            p.maskFilter = null
            p.alpha = 255
            p.strokeWidth = context.dp(2f)
            canvas.drawLine(x0, y, x1, y, p)
            p.strokeCap = Paint.Cap.BUTT
        }

        // Кромка стекла
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (hovered) context.dp(2f) else context.dp(1.3f)
        p.color = if (hovered) Color.WHITE else 0x99FFFFFF.toInt()
        canvas.drawPath(path, p)
        // Яркая линия по верхнему краю
        p.strokeWidth = context.dp(1f)
        p.color = 0xEEFFFFFF.toInt()
        canvas.drawLine(r.left + rad, r.top + context.dp(1f), r.right - rad, r.top + context.dp(1f), p)
        p.style = Paint.Style.FILL
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
