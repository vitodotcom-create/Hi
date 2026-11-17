package com.carvoice.panel

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/**
 * Плитка-иконка: высота = доступная высота, ширина = высота × ASPECT (но не шире колонки).
 * Плитка шире квадрата — меньше пустого места между иконками; значок внутри по центру.
 */
class SquareTile @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val availW = MeasureSpec.getSize(widthMeasureSpec)
        val availH = MeasureSpec.getSize(heightMeasureSpec)
        // Колонку целиком не занимаем: остаётся промежуток, чтобы правый край был досягаем.
        val maxW = (availW * 0.82f).toInt()
        val h = minOf(availH, (maxW / ASPECT).toInt())
        val w = minOf(maxW, (h * ASPECT).toInt())
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val v = (h * 0.22f).toInt()
        val side = (w - (h - 2 * v)) / 2
        setPadding(side, v, side, v)
    }

    companion object {
        private const val ASPECT = 1.45f
    }
}

/** Квадратная картинка: ширина = высоте (обложка альбома). */
class SquareImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(h, h)
    }
}

/**
 * Круглая кнопка плеера: всегда квадратная (по высоте), значок строго по центру кружка
 * и занимает ~44% его размера (кружок рисуется с отступом 6dp внутри фона).
 */
class RoundIconButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val d = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) h else minOf(h, w)
        setMeasuredDimension(d, d)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = (w * 0.28f).toInt()
        setPadding(pad, pad, pad, pad)
    }
}
