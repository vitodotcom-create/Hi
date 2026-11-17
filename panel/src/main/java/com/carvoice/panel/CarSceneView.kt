package com.carvoice.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Живая сцена с машиной за плитками: CLS едет, задняя часть смазана скоростью,
 * из-под задних колёс стелется дым, асфальт бежит, колёса крутятся, фары горят.
 * Сила эффектов зависит от реальной скорости (GPS). Без GPS — умеренная «демо-скорость».
 *
 * Всё рисуется в координатах исходного фото 640×480; точка схода позади машины — VP.
 */
class CarSceneView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private fun bmp(id: Int): Bitmap = BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

    private val car = bmp(R.drawable.scene_car)
    private val smear = bmp(R.drawable.scene_smear)
    private val reflect = bmp(R.drawable.scene_reflect)
    private val smoke = bmp(R.drawable.scene_smoke)
    private val wheelFront = bmp(R.drawable.scene_wheel_front)
    private val wheelRear = bmp(R.drawable.scene_wheel_rear)

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()
    private val rnd = Random(7)

    /** Скорость 0..1 (сглаженная) и цель. */
    private var speed = 0.35f
    private var target = 0.35f
    private var lastFrame = 0L
    private var started = 0L
    private var wheelAngle = 0f
    private var running = false

    /** Реальная скорость, км/ч; null — неизвестна (нет GPS). */
    fun setSpeedKmh(kmh: Float?) {
        target = if (kmh == null) 0.35f else (kmh / 120f).coerceIn(0f, 1f)
    }

    // ---------- Частицы дыма ----------
    private class Puff(var t: Float, val life: Float, val sx: Float, val sy: Float, val size: Float, val rot: Float, val spin: Float, val drift: Float)
    private val puffs = ArrayList<Puff>()
    private var emitAcc = 0f
    private val sources = arrayOf(floatArrayOf(600f, 345f, 1f), floatArrayOf(612f, 315f, 0.8f), floatArrayOf(560f, 360f, 0.6f), floatArrayOf(470f, 455f, 0.35f))

    // ---------- Полосы асфальта ----------
    private class Streak(val x0: Float, val y0: Float, var u: Float, val rate: Float, val w: Float, val a: Float)
    private val streaks = List(26) {
        Streak(rnd.nextFloat() * 640f - 80f, 440f + rnd.nextFloat() * 60f, rnd.nextFloat(), 0.6f + rnd.nextFloat() * 0.8f, 0.8f + rnd.nextFloat() * 1.8f, 0.1f + rnd.nextFloat() * 0.2f)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        started = SystemClock.uptimeMillis()
        lastFrame = started
        running = true
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        running = visibility == VISIBLE
        if (running) {
            lastFrame = SystemClock.uptimeMillis()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastFrame) / 1000f).coerceIn(0f, 0.1f)
        lastFrame = now
        val time = (now - started) / 1000f
        speed += (target - speed) * min(1f, dt * 1.2f)
        step(dt)

        // Как в макете: панель 900×744, машина сдвинута и уменьшена.
        val s = height / 744f
        canvas.save()
        canvas.translate(70f * width / 900f, 125f * s)
        canvas.scale(0.95f * s, 0.95f * s)

        drawRoadSurface(canvas)
        drawRoad(canvas)
        p.alpha = 255
        p.shader = null
        canvas.drawBitmap(reflect, 0f, 428f, p)
        canvas.drawBitmap(car, 0f, 0f, p)
        drawWheel(canvas, wheelFront, 455f, 378f, 33f, 80f, wheelAngle)
        drawWheel(canvas, wheelRear, 597f, 317f, 20f, 40f, wheelAngle * 1.1f)
        p.alpha = (255 * (0.25f + 0.75f * speed)).toInt()
        canvas.drawBitmap(smear, 0f, 0f, p)
        p.alpha = 255
        drawSmoke(canvas)
        drawTaillight(canvas, time)
        drawHeadlights(canvas, time)
        canvas.restore()

        if (running) postInvalidateDelayed(33)
    }

    private fun step(dt: Float) {
        wheelAngle = (wheelAngle - dt * (60f + 900f * speed)) % 360f
        // дым: чем быстрее, тем больше
        emitAcc += dt * 16f * speed * speed
        while (emitAcc >= 1f && puffs.size < 60) {
            emitAcc -= 1f
            val src = sources[rnd.nextInt(sources.size)]
            puffs += Puff(0f, 2.2f + rnd.nextFloat() * 1.2f, src[0], src[1], (0.7f + rnd.nextFloat() * 0.6f) * src[2], rnd.nextFloat() * 360f, rnd.nextFloat() * 40f - 20f, rnd.nextFloat() * 40f - 20f)
        }
        if (emitAcc > 1f) emitAcc = 0f
        val it = puffs.iterator()
        while (it.hasNext()) {
            val pf = it.next()
            pf.t += dt / pf.life
            if (pf.t >= 1f) it.remove()
        }
        dashPhase = (dashPhase + dt * (0.03f + 0.6f * speed)) % 1f
        for (st in streaks) {
            st.u += dt * st.rate * (0.15f + 1.4f * speed)
            if (st.u >= 1f) st.u -= 1f
        }
    }

    /** Асфальт под машиной: тёмная полоса дороги с краями и разметкой, уходящими к точке схода. */
    private fun drawRoadSurface(c: Canvas) {
        val top = 430f
        val bottom = 760f
        val road = Path().apply {
            moveTo(VPX - 40f, VPY + 40f); lineTo(VPX + 40f, VPY + 40f)
            lineTo(1400f, bottom); lineTo(-500f, bottom); close()
        }
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, VPY + 40f, 0f, bottom, intArrayOf(0x00101214, 0x8C16181C.toInt(), 0xB30C0D10.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(road, p)
        p.shader = null
        // края дороги и разметка
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        fun edge(x: Float, a: Int, w: Float) {
            p.shader = LinearGradient(x, bottom, VPX, VPY + 40f, intArrayOf(Color.argb(a, 230, 232, 236), 0), null, Shader.TileMode.CLAMP)
            p.strokeWidth = w
            c.drawLine(x, bottom, VPX, VPY + 40f, p)
        }
        edge(-500f, 90, 3f)
        edge(1400f, 90, 3f)
        // прерывистая разметка по центру, «едет» к точке схода
        p.shader = null
        val cx0 = 450f
        for (i in 0 until 6) {
            var t = (i / 6f + dashPhase) % 1f
            val t2 = min(1f, t + 0.07f)
            fun pt(tt: Float) = floatArrayOf(cx0 + (VPX - cx0) * tt, bottom + (VPY + 40f - bottom) * tt)
            val a = pt(t)
            val b = pt(t2)
            p.color = Color.WHITE
            p.alpha = (120 * (1f - t)).toInt()
            p.strokeWidth = 6f * (1f - t) + 1f
            c.drawLine(a[0], a[1], b[0], b[1], p)
        }
        p.style = Paint.Style.FILL
        p.alpha = 255
        // тень и отсвет под машиной
        c.save()
        c.translate(330f, 455f)
        c.scale(1f, 0.07f)
        p.shader = RadialGradient(0f, 0f, 340f, intArrayOf(0xCC000000.toInt(), 0x59FF1E28, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(0f, 0f, 340f, p)
        p.shader = null
        c.restore()
    }

    private var dashPhase = 0f

    private fun drawRoad(c: Canvas) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.shader = null
        for (st in streaks) {
            val t1 = st.u
            val t2 = min(1f, st.u + 0.22f)
            val x1 = st.x0 + (VPX - st.x0) * t1
            val y1 = st.y0 + (VPY + 40f - st.y0) * t1
            val x2 = st.x0 + (VPX - st.x0) * t2
            val y2 = st.y0 + (VPY + 40f - st.y0) * t2
            p.color = Color.WHITE
            p.alpha = (255 * st.a * max(0.25f, speed) * (1f - st.u)).toInt()
            p.strokeWidth = st.w * (1.2f - st.u * 0.6f)
            c.drawLine(x1, y1, x2, y2, p)
        }
        p.style = Paint.Style.FILL
        p.alpha = 255
    }

    private fun drawWheel(c: Canvas, b: Bitmap, cx: Float, cy: Float, rx: Float, ry: Float, angle: Float) {
        val r = b.width / 2f
        m.reset()
        m.setTranslate(-r, -r)
        m.postRotate(angle)
        m.postScale(rx / r, ry / r)
        m.postTranslate(cx, cy)
        p.alpha = 230
        c.drawBitmap(b, m, p)
        p.alpha = 255
    }

    private fun drawSmoke(c: Canvas) {
        val r = smoke.width / 2f
        for (pf in puffs) {
            val t = pf.t
            val k = t * 0.78f
            val x = pf.sx + (VPX + 60f - pf.sx) * k + pf.drift * t
            val y = pf.sy + (VPY - 40f * t - pf.sy) * k - t * 80f * (1f - t * 0.3f)
            val size = (24f + t * 150f) * pf.size
            val a = 0.6f * (1f - t).pow(1.3f) * min(1f, t * 6f)
            m.reset()
            m.setTranslate(-r, -r)
            m.postRotate(pf.rot + pf.spin * t * 3f)
            m.postScale(size / r, size / r * 0.8f)
            m.postTranslate(x, y)
            p.alpha = (255 * a).toInt().coerceIn(0, 255)
            c.drawBitmap(smoke, m, p)
        }
        p.alpha = 255
    }

    /** След фонаря как от пули: красное облако, бело-голубое ядро, сужается и тает. */
    private fun drawTaillight(c: Canvas, time: Float) {
        val fx = 604f
        val fy = 236f
        val len = 0.3f + 0.7f * speed
        val ex = fx + (VPX - fx) * len
        val ey = fy + (VPY - fy) * len
        val pulse = 1f + 0.08f * sin(time * 7f)
        val dx = ex - fx
        val dy = ey - fy
        val l = kotlin.math.sqrt(dx * dx + dy * dy)
        val nx = -dy / l
        val ny = dx / l
        fun wedge(w0: Float, w1: Float): Path = Path().apply {
            moveTo(fx + nx * w0, fy + ny * w0)
            lineTo(ex + nx * w1, ey + ny * w1)
            lineTo(ex - nx * w1, ey - ny * w1)
            lineTo(fx - nx * w0, fy - ny * w0)
            close()
        }
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(fx, fy, ex, ey, intArrayOf(0xF2FF2A2A.toInt(), 0x73FF1A1A, 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        p.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(wedge(16f * pulse, 3f), p)
        p.maskFilter = null
        p.shader = LinearGradient(fx, fy, ex, ey, intArrayOf(0xFFFFFFFF.toInt(), 0xE6D6F0FF.toInt(), 0x409AD8FF, 0), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(wedge(3.5f * pulse, 0.5f), p)
        p.shader = RadialGradient(fx, fy, 26f, intArrayOf(0xFFFFFFFF.toInt(), 0xE6FF3030.toInt(), 0), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(fx, fy, 26f, p)
        p.shader = null
    }

    /** Фары: при запуске загораются с миганием, как приветственная подсветка, потом горят ровно. */
    private fun drawHeadlights(c: Canvas, time: Float) {
        var on = ((time - 0.3f) / 0.9f).coerceIn(0f, 1f)
        if (time < 1.2f && rnd.nextFloat() < 0.35f) on *= 0.4f
        if (on <= 0f) return
        val glow = on * (0.92f + 0.08f * sin(time * 2.3f))
        // свечение
        fun blob(cx: Float, cy: Float, rx: Float, ry: Float) {
            c.save()
            c.translate(cx, cy)
            c.scale(1f, ry / rx)
            p.shader = RadialGradient(0f, 0f, rx, intArrayOf(0xF2FFFFFF.toInt(), 0x99D6F0FF.toInt(), 0), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP)
            p.alpha = (255 * glow * 0.85f).toInt()
            c.drawCircle(0f, 0f, rx, p)
            c.restore()
        }
        blob(370f, 305f, 120f, 42f)
        blob(50f, 283f, 40f, 34f)
        p.shader = null
        // линза
        p.color = 0x8CE1F2FF.toInt()
        p.alpha = (140 * glow).toInt()
        c.drawPath(Path().apply {
            moveTo(296f, 312f); lineTo(330f, 302f); lineTo(400f, 292f); lineTo(424f, 290f)
            lineTo(410f, 303f); lineTo(360f, 318f); lineTo(318f, 334f); lineTo(305f, 330f); close()
        }, p)
        // LED-«брови»
        val brow = Path().apply { moveTo(297f, 310f); cubicTo(320f, 302f, 350f, 296f, 400f, 291f); lineTo(421f, 290f) }
        val brow2 = Path().apply { moveTo(52f, 265f); cubicTo(48f, 278f, 45f, 290f, 44f, 300f) }
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.color = 0xFF9AD8FF.toInt()
        p.alpha = (200 * glow).toInt()
        p.strokeWidth = 9f
        p.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(brow, p)
        c.drawPath(brow2, p)
        p.maskFilter = null
        p.color = Color.WHITE
        p.alpha = (255 * glow).toInt()
        p.strokeWidth = 4f
        c.drawPath(brow, p)
        c.drawPath(brow2, p)
        p.style = Paint.Style.FILL
        // линзы-точки
        p.maskFilter = BlurMaskFilter(5f, BlurMaskFilter.Blur.SOLID)
        c.drawCircle(345f, 308f, 6f, p)
        c.drawCircle(372f, 302f, 6f, p)
        p.maskFilter = null
        // горизонтальная вспышка
        p.shader = LinearGradient(90f, 0f, 380f, 0f, intArrayOf(0, 0xF2FFFFFF.toInt(), 0), null, Shader.TileMode.CLAMP)
        p.alpha = (255 * glow).toInt()
        c.drawRect(90f, 300f, 380f, 303f, p)
        p.shader = null
        p.alpha = 255
    }

    companion object {
        private const val VPX = 1010f
        private const val VPY = 268f
    }
}
