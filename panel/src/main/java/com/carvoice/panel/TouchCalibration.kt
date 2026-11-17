package com.carvoice.panel

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * Калибровка нажатий. Рабочий стол Carlinkit показывает панель растянутой/сдвинутой
 * и передаёт нажатия без поправки — они уходят мимо кнопок. Пользователь один раз
 * нажимает на две точки, и мы вычисляем поправку: x' = sx·x + ox, y' = sy·y + oy.
 */
class TouchCalibration(context: Context) {
    private val sp = context.getSharedPreferences("panel", Context.MODE_PRIVATE)

    var sx = sp.getFloat("sx", 1f); private set
    var ox = sp.getFloat("ox", 0f); private set
    var sy = sp.getFloat("sy", 1f); private set
    var oy = sp.getFloat("oy", 0f); private set

    val isCalibrated get() = sp.getBoolean("calibrated", false)

    /** Поправка для событий нажатия/наведения. */
    fun matrix(): Matrix = Matrix().apply {
        setScale(sx, sy)
        postTranslate(ox, oy)
    }

    /** По двум парам точек (куда целились → что пришло) вычислить поправку. */
    fun solve(t1x: Float, t1y: Float, r1x: Float, r1y: Float, t2x: Float, t2y: Float, r2x: Float, r2y: Float): Boolean {
        if (kotlin.math.abs(r2x - r1x) < 20 || kotlin.math.abs(r2y - r1y) < 20) return false
        val nsx = (t2x - t1x) / (r2x - r1x)
        val nsy = (t2y - t1y) / (r2y - r1y)
        if (nsx !in 0.3f..3f || nsy !in 0.3f..3f) return false
        sx = nsx; sy = nsy
        ox = t1x - nsx * r1x
        oy = t1y - nsy * r1y
        sp.edit().putFloat("sx", sx).putFloat("ox", ox).putFloat("sy", sy).putFloat("oy", oy)
            .putBoolean("calibrated", true).apply()
        return true
    }

    fun reset() {
        sx = 1f; ox = 0f; sy = 1f; oy = 0f
        sp.edit().clear().apply()
    }
}

/** Экран калибровки: две точки по очереди. */
class CalibrationOverlay(context: Context, private val onDone: (Boolean) -> Unit, private val calib: TouchCalibration) :
    View(context) {

    private var step = 0
    private var r1x = 0f
    private var r1y = 0f
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FF7FDBFF") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = 34f
    }

    private fun target(): Pair<Float, Float> =
        if (step == 0) width * T1X to height * T1Y else width * T2X to height * T2Y

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.parseColor("#E6050709"))
        val (x, y) = target()
        canvas.drawCircle(x, y, 26f, dot)
        canvas.drawCircle(x, y, 44f, ring)
        canvas.drawText(
            "Калибровка: наведите кончик стрелки на голубую точку и нажмите (${step + 1} из 2)",
            width / 2f, height * 0.85f, text,
        )
    }

    /** Сырые (неисправленные) координаты нажатия. */
    fun onRawTap(x: Float, y: Float) {
        if (step == 0) {
            r1x = x; r1y = y
            step = 1
            invalidate()
        } else {
            // Цели — в координатах окна (как и сами нажатия), а не этого слоя.
            val loc = IntArray(2)
            getLocationInWindow(loc)
            val t1x = loc[0] + width * T1X
            val t1y = loc[1] + height * T1Y
            val t2x = loc[0] + width * T2X
            val t2y = loc[1] + height * T2Y
            onDone(calib.solve(t1x, t1y, r1x, r1y, t2x, t2y, x, y))
        }
    }

    override fun onTouchEvent(event: MotionEvent) = true

    companion object {
        // Точки не у краёв: сдвинутое нажатие не должно уйти за пределы панели.
        private const val T1X = 0.18f
        private const val T1Y = 0.22f
        private const val T2X = 0.72f
        private const val T2Y = 0.55f
    }
}

/** Прозрачный слой: голубая точка там, куда пришло нажатие (после поправки). */
class TapDotView(context: Context) : View(context) {
    private var dotX = -1f
    private var dotY = -1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC7FDBFF") }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f
    }

    /** Координаты окна. */
    fun show(windowX: Float, windowY: Float) {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        dotX = windowX - loc[0]
        dotY = windowY - loc[1]
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, 900)
    }

    private val hide = Runnable { dotX = -1f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (dotX < 0) return
        canvas.drawCircle(dotX, dotY, 14f, paint)
        canvas.drawCircle(dotX, dotY, 22f, ring)
    }

    override fun onTouchEvent(event: MotionEvent) = false
}
