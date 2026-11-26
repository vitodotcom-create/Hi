package com.carvoice.desktop

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.view.Gravity
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * Живое видео с регистратора 70mai.
 * Открыли — бокс подключается к Wi-Fi регистратора (интернет на это время пропадает).
 * Закрыли или ушли с экрана — регистратор отпускаем, Android сам возвращается на Wi-Fi телефона.
 */
class CameraActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var cm: ConnectivityManager
    private lateinit var surface: SurfaceView
    private lateinit var status: TextView
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var player: ExoPlayer? = null
    private var host = DEFAULT_HOST
    private var pathIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(1280, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Кнопка слева сверху: у правого края экрана нажатия не доходят.
        val close = TextView(this).apply {
            text = "✕  ЗАКРЫТЬ"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(200, 200, 30, 30))
            setOnClickListener { finish() }
        }
        root.addView(close, FrameLayout.LayoutParams(260, 80, Gravity.TOP or Gravity.START).apply { leftMargin = 40; topMargin = 30 })

        val label = TextView(this).apply {
            text = "● КАМЕРА 70MAI"
            setTextColor(Color.rgb(255, 60, 60))
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            letterSpacing = 0.15f
        }
        root.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply { leftMargin = 40; bottomMargin = 30 })

        setContentView(root)
        hideBars()
        connect()
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun say(text: String) = handler.post { status.text = text; status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE }

    /** Если бокс уже на Wi-Fi регистратора — берём его, иначе просим Android подключиться. */
    private fun connect() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid.orEmpty()
        if (ssid.contains("70mai", true)) {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                ?.let { onCamera(it); return }
        }
        say("Подключаюсь к регистратору…\nЕсли появится окно «Подключиться?» — нажмите ПОДКЛЮЧИТЬ")
        try {
            val spec = WifiNetworkSpecifier.Builder()
                .setSsidPattern(PatternMatcher("70mai", PatternMatcher.PATTERN_PREFIX))
                .setWpa2Passphrase(PASSWORD)
                .build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { handler.post { onCamera(network) } }
                override fun onUnavailable() { say("Регистратор не найден.\nПроверьте, что он включён и его Wi-Fi работает.") }
                override fun onLost(network: Network) { say("Связь с регистратором пропала") }
            }
            callback = cb
            cm.requestNetwork(req, cb, 60_000)
        } catch (e: Throwable) {
            say("Ошибка: ${e.message}")
        }
    }

    private fun onCamera(net: Network) {
        if (isFinishing) return
        // Пока камера открыта, всё приложение ходит через регистратор (видео идёт по TCP через эту сеть).
        cm.bindProcessToNetwork(net)
        host = cm.getLinkProperties(net)?.routes
            ?.firstOrNull { it.gateway != null && !it.gateway!!.isAnyLocalAddress }?.gateway?.hostAddress ?: DEFAULT_HOST
        pathIndex = 0
        play()
    }

    private fun play() {
        player?.release()
        val url = "rtsp://$host:554${PATHS[pathIndex]}"
        say("Загружаю видео…")
        val p = ExoPlayer.Builder(this).build()
        player = p
        p.setVideoSurfaceView(surface)
        p.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { say("") }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width <= 0 || videoSize.height <= 0) return
                val h = surface.rootView.height.takeIf { it > 0 } ?: 720
                val w = (h * videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio / videoSize.height).toInt()
                surface.layoutParams = (surface.layoutParams as FrameLayout.LayoutParams).apply { width = w }
                surface.requestLayout()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (pathIndex < PATHS.size - 1) {
                    pathIndex++
                    handler.postDelayed({ if (!isFinishing) play() }, 500)
                } else {
                    say("Видео не идёт: ${error.errorCodeName}\nНажмите ЗАКРЫТЬ и попробуйте ещё раз")
                }
            }
        })
        p.setMediaSource(
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(url))
        )
        p.playWhenReady = true
        p.prepare()
    }

    /** Ушли с экрана камеры — сразу отпускаем регистратор, чтобы вернулся интернет. */
    override fun onStop() {
        super.onStop()
        // Пока ждём подключения, поверх может быть системное окно «Подключиться?» — тогда не закрываемся.
        if (player != null) finish()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        try { cm.bindProcessToNetwork(null) } catch (_: Exception) {}
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        super.onDestroy()
    }

    companion object {
        private const val PASSWORD = "12345678"
        private const val DEFAULT_HOST = "192.168.0.1"
        private val PATHS = listOf("/livestream/12", "/livestream/11", "/")
    }
}
