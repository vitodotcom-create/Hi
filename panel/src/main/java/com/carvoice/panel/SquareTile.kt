package com.carvoice.panel

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView

/** Квадратная плитка-иконка: сторона = меньшее из доступных ширины и высоты, значок внутри с отступом. */
class SquareTile @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ImageView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = minOf(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val p = (w * 0.24f).toInt()
        setPadding(p, p, p, p)
    }
}
