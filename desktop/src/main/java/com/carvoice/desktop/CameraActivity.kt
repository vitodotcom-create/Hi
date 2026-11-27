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
    private var urls = listOf<String>()
    private var fix: RtspPlayFix? = null
    private var rear = false
    private lateinit var swap: TextView
    private val notes = mutableListOf<String>()
    private val errors = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        surface = SurfaceView(this)
        root.addView(surface, FrameLayout.LayoutParams(1280, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
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

        // Передняя / задняя камера регистратора (у A810 два потока: /livestream/12 и /livestream/11).
        swap = TextView(this).apply {
            text = "⇄  ЗАДНЯЯ"
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(200, 40, 44, 52))
            setOnClickListener { switchCamera() }
        }
        root.addView(swap, FrameLayout.LayoutParams(260, 80, Gravity.TOP or Gravity.START).apply { leftMargin = 320; topMargin = 30 })

        val label = TextView(this).apply {
            text = "● КАМЕРА 70MAI   v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" })
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

    /** Если бокс уже на Wi-Fi регистратора — берём его, иначе находим сеть регистратора и просим Android подключиться. */
    private fun connect() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ssid = wm.connectionInfo?.ssid.orEmpty()
        if (ssid.contains("70mai", true)) {
            @Suppress("DEPRECATION")
            cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
                ?.let { onCamera(it); return }
        }
        say("Ищу Wi-Fi регистратора…")
        val found = findCamera(wm)
        if (found != null) { request(found, wpa3 = false); return }
        // Списка сетей ещё нет — просим свежий поиск и смотрим ещё раз.
        @Suppress("DEPRECATION")
        try { wm.startScan() } catch (_: Exception) {}
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            val again = findCamera(wm)
            // Список сетей бокс может не отдать — тогда подключаемся к нашему регистратору по известному имени.
            request(again ?: KNOWN_SSID, wpa3 = false)
        }, 4000)
    }

    /**
     * Сеть нашего регистратора. Рядом бывают чужие (например 70mai_A800S_5G у соседа),
     * поэтому сначала ищем свою модель A810, иначе — самую сильную сеть 70mai на 2,4 ГГц.
     */
    private fun findCamera(wm: WifiManager): String? {
        val list = try { wm.scanResults } catch (_: SecurityException) { emptyList() }
            .filter { it.SSID.contains("70mai", true) }
        return (list.filter { it.SSID.contains(MODEL, true) }.maxByOrNull { it.level }
            ?: list.filter { it.frequency < 4900 }.maxByOrNull { it.level })?.SSID
    }

    /** Подключение к сети регистратора по точному имени. Сначала WPA2, не вышло — WPA3. */
    private fun request(camSsid: String, wpa3: Boolean) {
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        say("Подключаюсь к $camSsid…\nЕсли появится окно «Подключиться?» — нажмите ПОДКЛЮЧИТЬ")
        try {
            val b = WifiNetworkSpecifier.Builder().setSsid(camSsid)
            if (wpa3) b.setWpa3Passphrase(PASSWORD) else b.setWpa2Passphrase(PASSWORD)
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(b.build())
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { handler.post { onCamera(network) } }
                override fun onUnavailable() {
                    handler.post {
                        if (isFinishing) return@post
                        if (!wpa3) request(camSsid, wpa3 = true)
                        else say("Не удалось подключиться к $camSsid.\nПроверьте пароль Wi-Fi регистратора (по умолчанию 12345678).")
                    }
                }
                override fun onLost(network: Network) { say("Связь с регистратором пропала") }
            }
            callback = cb
            cm.requestNetwork(req, cb, 30_000)
        } catch (e: Throwable) {
            say("Ошибка: ${e.message}")
        }
    }

    private fun onCamera(net: Network) {
        if (isFinishing) return
        host = cm.getLinkProperties(net)?.routes
            ?.firstOrNull { it.gateway != null && !it.gateway!!.isAnyLocalAddress }?.gateway?.hostAddress ?: DEFAULT_HOST
        pathIndex = 0
        errors.clear()
        // Регистратор принимает PLAY только по своему адресу сеанса (Content-Base), а плеер шлёт PLAY по исходному
        // адресу и получает 404. Посредник на 127.0.0.1 подставляет правильный адрес; к регистратору он ходит
        // через сеть его Wi-Fi (net.socketFactory), поэтому весь процесс к этой сети не привязываем.
        fix?.close()
        Thread {
            val f = try {
                RtspPlayFix(host, 554, net.socketFactory, log = { m -> handler.post { notes += m } }).start()
            } catch (e: Exception) { say("Ошибка посредника: ${e.message}"); return@Thread }
            handler.post {
                if (isFinishing) { f.close(); return@post }
                fix = f
                urls = PATHS.map { "rtsp://127.0.0.1:${f.port}$it" }
                play()
            }
        }.start()
    }

    /** Переключить переднюю/заднюю камеру: тот же посредник, другой поток. */
    private fun switchCamera() {
        val f = fix ?: return
        rear = !rear
        swap.text = if (rear) "⇄  ПЕРЕДНЯЯ" else "⇄  ЗАДНЯЯ"
        urls = listOf("rtsp://127.0.0.1:${f.port}${if (rear) REAR else FRONT}")
        pathIndex = 0
        errors.clear()
        play()
    }

    private fun play() {
        player?.release()
        val url = urls[pathIndex]
        val path = url.substringAfter("://").substringAfter('/', "").let { "/$it" }
        val tcp = true                        // через посредника — только TCP (видео в том же соединении)
        say("Загружаю видео… ($path, ${if (tcp) "TCP" else "UDP"})")
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
                // Запоминаем настоящую причину — по ней видно, что ответил регистратор.
                val why = generateSequence(error as Throwable) { it.cause }.mapNotNull { it.message }.lastOrNull().orEmpty().take(90)
                errors += "$path ${if (tcp) "TCP" else "UDP"}: ${error.errorCodeName.removePrefix("ERROR_CODE_")} $why"
                if (pathIndex < urls.size - 1) {
                    pathIndex++
                    handler.postDelayed({ if (!isFinishing) play() }, 500)
                } else {
                    say("Видео не идёт:\n" + (errors + notes.takeLast(4)).joinToString("\n") + "\nСфотографируйте этот экран")
                }
            }
        })
        p.setMediaSource(
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(tcp)
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
        fix?.close()
        fix = null
        callback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        callback = null
        super.onDestroy()
    }

    companion object {
        private const val PASSWORD = "12345678"
        private const val DEFAULT_HOST = "192.168.0.1"
        private const val MODEL = "A810"
        private const val KNOWN_SSID = "70mai_A810_5019"   // Wi-Fi регистратора пользователя
        private const val FRONT = "/livestream/12"
        private const val REAR = "/livestream/13"   // так в Kino (просмотрщик A810): перед 12, зад 13
        private val PATHS = listOf(FRONT, REAR, "/")
    }
}
