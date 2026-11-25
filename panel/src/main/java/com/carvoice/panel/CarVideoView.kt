package com.carvoice.panel

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import kotlin.math.abs
import kotlin.math.max

/**
 * Живой фон панели: настоящий ролик CLS (res/raw/car_loop.mp4, петля 1280×720).
 * Скорость ролика плавно идёт за скоростью GPS (60 км/ч — как снято), стоим — пауза.
 * Ролик заполняет всю панель, по ширине — с центром на машине.
 */
class CarVideoView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private val ui = Handler(Looper.getMainLooper())

    private var kmh = 0f          // скорость машины
    private var rate = 0f         // текущая скорость ролика
    private var applied = -1f     // что уже отдано плееру

    /** Центр машины в ролике (доля ширины) — чтобы на узкой панели машина не уходила за край. */
    private val focusX = 0.66f
    private val vw = 1280f
    private val vh = 720f

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    /** Реальная скорость, км/ч; null — неизвестна (нет GPS) — стоим. */
    fun setSpeedKmh(v: Float?) {
        kmh = v ?: 0f
    }

    // 10 раз в секунду подтягиваем скорость ролика к скорости машины — без рывков.
    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                if (kmh < 3f) {
                    rate = 0f
                    if (p.isPlaying) p.pause()
                    applied = 0f
                } else {
                    val target = (kmh / 60f).coerceIn(0.1f, 3f)
                    rate = if (rate == 0f) target else rate + (target - rate) * 0.1f
                    if (applied <= 0f || abs(rate - applied) > 0.02f) {
                        try {
                            p.playbackParams = p.playbackParams.setSpeed(rate)   // запускает и паузу
                            if (!p.isPlaying) p.start()
                            applied = rate
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            ui.postDelayed(this, 100)
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val s = Surface(st)
        surface = s
        try {
            val p = MediaPlayer()
            context.resources.openRawResourceFd(R.raw.car_loop).use { fd ->
                p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            }
            p.setSurface(s)
            p.isLooping = true
            p.setVolume(0f, 0f)
            p.setOnPreparedListener {
                it.seekTo(0)
                applied = -1f
                fit(w, h)
            }
            p.prepareAsync()
            player = p
        } catch (_: Exception) {
        }
        ui.removeCallbacks(tick)
        ui.post(tick)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit(w, h)

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        ui.removeCallbacks(tick)
        player?.release(); player = null
        surface?.release(); surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Заполнить панель целиком (как center-crop), по горизонтали — центр на машине. */
    private fun fit(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val s = max(w / vw, h / vh)
        val sw = vw * s
        val sh = vh * s
        val tx = (w / 2f - focusX * sw).coerceIn(w - sw, 0f)
        val ty = (h - sh) / 2f
        val m = Matrix()
        m.setScale(sw / w, sh / h)
        m.postTranslate(tx, ty)
        setTransform(m)
    }
}
