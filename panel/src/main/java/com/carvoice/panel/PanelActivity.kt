package com.carvoice.panel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «CarVoice Панель» — отдельное приложение для большой области рабочего стола Carlinkit.
 * Сама ничего не выполняет: передаёт команды в CarVoice, поэтому всё открывается
 * на весь экран, как при голосовой команде.
 */
class PanelActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var time: TextView
    private lateinit var date: TextView
    private lateinit var weather: TextView
    private lateinit var gauge: WeatherGaugeView
    private val tiles = mutableListOf<GlassTileDrawable>()
    private var playIconIsPause: Boolean? = null
    private var introPlayed = false
    private lateinit var last: TextView
    private var weatherAt = 0L
    private lateinit var calibration: TouchCalibration
    private var overlay: CalibrationOverlay? = null
    private lateinit var dots: TapDotView
    /** До какого времени показывать точки нажатий. */
    private var showDotsUntil = 0L

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panel)
        time = findViewById(R.id.time)
        date = findViewById(R.id.date)
        weather = findViewById(R.id.weather)
        gauge = findViewById(R.id.gauge)
        styleAmg()
        last = findViewById(R.id.last)
        calibration = TouchCalibration(this)

        // Калибровка: удерживать нажатие 2 секунды в любом месте панели (см. dispatchTouchEvent).
        dots = TapDotView(this)
        addContentView(dots, android.view.ViewGroup.LayoutParams(-1, -1))

        // Ряд 1 — общие
        click(R.id.btn_claude) {
            launch(Intent("com.carvoice.assistant.LISTEN").setPackage(CARVOICE))
        }
        click(R.id.btn_split) {
            run("split_screen", target = "ru.yandex.yandexnavi", query = "com.google.android.youtube")
        }
        click(R.id.btn_chrome) { run("open_app", target = "com.android.chrome") }
        click(R.id.btn_wifi) { run("wifi") }
        // Ряд 2 — навигация
        click(R.id.btn_cam) {
            openInstalled(listOf("com.mi.carlife", "com.mi.dashcam", "com.mi.car.dvr"), listOf("70mai"), "70mai")
        }
        click(R.id.btn_home) { run("navigate", query = "@home") }
        click(R.id.btn_work) { run("navigate", query = "@work") }
        click(R.id.btn_fav) { run("navigate", query = "@fav") }
        // Ряд 3 — голос и музыка
        click(R.id.btn_gemini) {
            run("app_task", target = "com.google.android.apps.bard", query = "включить Gemini Live")
        }
        click(R.id.btn_music) { run("play_music", query = "@liked") }
        click(R.id.btn_radio) {
            openInstalled(listOf("tunein.player", "radiotime.player"), listOf("tunein"), "TuneIn Radio")
        }
        click(R.id.btn_settings) {
            // Настройки T-Box (Carlinkit), иначе — обычные настройки Android.
            val tbox = packageManager.getLaunchIntentForPackage("com.suding.settings")
            launch(tbox ?: Intent(android.provider.Settings.ACTION_SETTINGS))
        }
        // Плеер
        click(R.id.btn_prev) {
            controller?.transportControls?.skipToPrevious() ?: run("media", value = "previous")
        }
        click(R.id.btn_play) {
            val c = controller
            if (c == null) run("media", value = "toggle")
            else if (c.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING) c.transportControls.pause()
            else c.transportControls.play()
        }
        click(R.id.btn_next) {
            controller?.transportControls?.skipToNext() ?: run("media", value = "next")
        }
        click(R.id.now_playing) {
            if (!mediaAccess) {
                Toast.makeText(this, "Включите «CarVoice Панель» — тогда здесь будет песня и исполнитель", Toast.LENGTH_LONG).show()
                launch(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } else {
                controller?.packageName?.let { pm -> packageManager.getLaunchIntentForPackage(pm)?.let { launch(it) } }
            }
        }
        bindMedia()
        startSpeed()
    }

    /**
     * Открыть приложение на весь экран через CarVoice: ищем по известным пакетам,
     * затем по названию среди установленных программ.
     */
    private fun openInstalled(packages: List<String>, labelWords: List<String>, title: String) {
        val pm = packageManager
        val pkg = packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0,
            ).firstOrNull { ri ->
                val label = ri.loadLabel(pm).toString().lowercase()
                labelWords.any { label.contains(it) || ri.activityInfo.packageName.lowercase().contains(it) }
            }?.activityInfo?.packageName
        if (pkg == null) {
            Toast.makeText(this, "$title не установлено", Toast.LENGTH_LONG).show()
            return
        }
        run("open_app", target = pkg)
    }

    // ---------- Калибровка нажатий ----------

    private val cancelCalibration = Runnable {
        overlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        overlay = null
        Toast.makeText(this, "Калибровка отменена. Удерживайте нажатие 2 секунды, чтобы начать снова", Toast.LENGTH_LONG).show()
    }

    private fun startCalibration() {
        if (overlay != null) return
        handler.removeCallbacks(cancelCalibration)
        handler.postDelayed(cancelCalibration, 40_000)
        val o = CalibrationOverlay(this, { ok ->
            handler.removeCallbacks(cancelCalibration)
            (overlay?.parent as? android.view.ViewGroup)?.removeView(overlay)
            overlay = null
            if (ok) showDotsUntil = System.currentTimeMillis() + 120_000
            Toast.makeText(
                this,
                if (ok) "Калибровка сохранена. 2 минуты будут видны точки нажатий" else "Не получилось, попробуйте ещё раз (удерживайте нажатие 2 секунды)",
                Toast.LENGTH_LONG,
            ).show()
        }, calibration)
        overlay = o
        addContentView(o, android.view.ViewGroup.LayoutParams(-1, -1))
    }

    private var downX = 0f
    private var downY = 0f
    /** Жест, которым запустили калибровку, — его отпускание не считать нажатием на точку. */
    private var swallowGesture = false
    private val holdToCalibrate = Runnable {
        swallowGesture = true
        cancelPressedButton()
        startCalibration()
    }

    /** Отменить нажатие на кнопку под пальцем/курсором. */
    private fun cancelPressedButton() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, downX, downY, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Удержание 2 секунды в любом месте — калибровка (не зависит от сдвига нажатий).
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y
                handler.removeCallbacks(holdToCalibrate)
                if (overlay == null) handler.postDelayed(holdToCalibrate, 2000)
            }
            MotionEvent.ACTION_MOVE ->
                if (kotlin.math.hypot(ev.x - downX, ev.y - downY) > 40) handler.removeCallbacks(holdToCalibrate)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(holdToCalibrate)
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowGesture = false
            }
            return true
        }
        val o = overlay
        if (o != null) {
            // Во время калибровки берём «сырые» координаты, без поправки.
            if (ev.actionMasked == MotionEvent.ACTION_UP) o.onRawTap(ev.x, ev.y)
            return true
        }
        val fixed = MotionEvent.obtain(ev)
        if (calibration.isCalibrated) {
            fixed.transform(calibration.matrix())
            // Для мыши/тачпада Android ищет кнопку по «позиции курсора», а не по X/Y события,
            // и нашу поправку не учитывает. Помечаем событие как касание — тогда кнопка
            // выбирается по исправленным координатам (там же, где голубая точка).
            if (fixed.isFromSource(android.view.InputDevice.SOURCE_MOUSE)) {
                fixed.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            }
        }
        if (fixed.actionMasked == MotionEvent.ACTION_UP && System.currentTimeMillis() < showDotsUntil) {
            dots.show(fixed.x, fixed.y)
        }
        val handled = super.dispatchTouchEvent(fixed)
        fixed.recycle()
        return handled
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (overlay != null || !calibration.isCalibrated) return super.dispatchGenericMotionEvent(ev)
        val fixed = MotionEvent.obtain(ev)
        fixed.transform(calibration.matrix())
        val handled = super.dispatchGenericMotionEvent(fixed)
        fixed.recycle()
        return handled
    }

    private fun click(id: Int, action: () -> Unit) {
        findViewById<View>(id).setOnClickListener { action() }
    }

    /** Команда в CarVoice (выполняется там, без запроса к Claude). */
    private fun run(type: String, target: String = "", query: String = "", value: String = "") {
        launch(
            Intent("com.carvoice.assistant.RUN").setPackage(CARVOICE)
                .putExtra("type", type).putExtra("target", target)
                .putExtra("query", query).putExtra("value", value)
        )
    }

    private fun launch(intent: Intent) {
        try {
            // На основном экране, а не в окне панели на рабочем столе.
            val options = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY)
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Обновите CarVoice до последней версии", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
        handler.removeCallbacks(sheenLoop)
        handler.postDelayed(sheenLoop, 4_000)
        if (!introPlayed) {
            introPlayed = true
            playIntro()
        }
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(sheenLoop)
        super.onPause()
    }

    // ---------- Что играет ----------

    private var controller: android.media.session.MediaController? = null
    private var mediaAccess = false

    private val mediaCallback = object : android.media.session.MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = updateNowPlaying()
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) = updateNowPlaying()
        override fun onSessionDestroyed() {
            controller = null
            updateNowPlaying()
        }
    }

    private fun bindMedia() {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager
        val cn = android.content.ComponentName(this, MediaListenerService::class.java)
        try {
            pickController(msm.getActiveSessions(cn))
            msm.addOnActiveSessionsChangedListener({ list -> pickController(list ?: emptyList()) }, cn)
            mediaAccess = true
        } catch (e: SecurityException) {
            mediaAccess = false
        }
        updateNowPlaying()
    }

    private fun pickController(list: List<android.media.session.MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(mediaCallback)
        controller = best
        best?.registerCallback(mediaCallback, handler)
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        val title = findViewById<TextView>(R.id.np_title)
        val artist = findViewById<TextView>(R.id.np_artist)
        val art = findViewById<android.widget.ImageView>(R.id.np_art)
        val progress = findViewById<TrackProgressView>(R.id.np_progress)
        val eq = findViewById<EqualizerView>(R.id.np_eq)
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        val playing = st?.state == android.media.session.PlaybackState.STATE_PLAYING
        if (!mediaAccess) {
            title.text = "Нажмите — показывать песню"
            artist.text = "нужен доступ к уведомлениям"
        } else if (md == null) {
            title.text = "Музыка не играет"
            artist.text = "нажмите «Музыка» или ▶"
        } else {
            title.text = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val who = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString()
            } catch (e: Exception) {
                ""
            }
            artist.text = listOf(who, app).filter { it.isNotBlank() }.joinToString("  ·  ")
        }
        val bmp = md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) art.setImageBitmap(bmp) else art.setImageResource(R.drawable.ic_music)
        art.clipToOutline = true
        art.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) {
                o.setRoundRect(0, 0, v.width, v.height, v.height * 0.18f)
            }
        }
        art.background = if (bmp == null) GlassTileDrawable(this, 0xFFFF4F8B.toInt(), GlassShape.CARD) else null
        val duration = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        progress.set(st?.position ?: 0L, duration, playing, st?.playbackSpeed ?: 1f, st?.lastPositionUpdateTime ?: 0L)
        eq.playing = playing
        playIconIsPause = null
        updatePlayIcon()
    }

    // ---------- Скорость (GPS) для живой сцены ----------

    private var locationManager: android.location.LocationManager? = null
    private var lastFix = 0L

    private val locationListener = object : android.location.LocationListener {
        override fun onLocationChanged(loc: android.location.Location) {
            lastFix = System.currentTimeMillis()
            val kmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            findViewById<CarSceneView>(R.id.scene).setSpeedKmh(kmh)
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            findViewById<CarSceneView>(R.id.scene).setSpeedKmh(null)
        }
    }

    private fun startSpeed() {
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(fine) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(fine, android.Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
        locationManager = lm
        try {
            for (provider in listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)) {
                if (lm.allProviders.contains(provider)) {
                    lm.requestLocationUpdates(provider, 1000L, 0f, locationListener, Looper.getMainLooper())
                }
            }
        } catch (e: SecurityException) {
            // нет доступа — сцена в «демо-скорости»
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.any { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
            startSpeed()
        }
    }

    override fun onDestroy() {
        locationManager?.removeUpdates(locationListener)
        controller?.unregisterCallback(mediaCallback)
        super.onDestroy()
    }

    // ---------- Оформление AMG ----------

    private fun styleAmg() {
        // Плитки из цветного стекла: цвет кнопки → стекло с неоном.
        val colors = mapOf(
            R.id.btn_claude to 0xFF4FC3F7, R.id.btn_split to 0xFF6A7BFF, R.id.btn_chrome to 0xFF3D8BFF,
            R.id.btn_wifi to 0xFF22C3E6, R.id.btn_cam to 0xFFFF3B3B, R.id.btn_home to 0xFF1DE9A0,
            R.id.btn_work to 0xFF4CE070, R.id.btn_fav to 0xFFB4F04A, R.id.btn_gemini to 0xFFB48CFF,
            R.id.btn_music to 0xFFFF4F8B, R.id.btn_radio to 0xFFFFAA33, R.id.btn_settings to 0xFFD0D6E0,
        )
        for ((id, c) in colors) {
            val cell = findViewById<android.view.ViewGroup>(id)
            val d = GlassTileDrawable(this, c.toInt())
            cell.getChildAt(0).background = d
            tiles += d
        }
        findViewById<View>(R.id.btn_prev).background = GlassTileDrawable(this, 0xFF9AA4B2.toInt(), GlassShape.CIRCLE)
        findViewById<View>(R.id.btn_play).background = GlassTileDrawable(this, AMG_RED, GlassShape.CIRCLE)
        findViewById<View>(R.id.btn_next).background = GlassTileDrawable(this, 0xFF9AA4B2.toInt(), GlassShape.CIRCLE)
        findViewById<View>(R.id.now_playing).background = GlassTileDrawable(this, 0xFF9AA4B2.toInt(), GlassShape.CARD)
        // Значки плеера крупные: отступ = 28% от размера кружка.
        listOf(R.id.btn_prev, R.id.btn_play, R.id.btn_next).forEach { id ->
            findViewById<View>(id).addOnLayoutChangeListener { v, l, t, r, b, ol, ot, or2, ob ->
                if (r - l == or2 - ol && b - t == ob - ot) return@addOnLayoutChangeListener
                val d = minOf(r - l, b - t)
                val padV = (d * 0.28f).toInt()
                val padH = ((r - l) - d) / 2 + padV
                v.setPadding(padH, padV, padH, padV)
            }
        }
        listOf(R.id.btn_prev, R.id.btn_play, R.id.btn_next, R.id.now_playing).forEach {
            findViewById<View>(it).defaultFocusHighlightEnabled = false
        }

        // Часы: жирный курсив с хромированным переливом.
        time.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD_ITALIC)
        time.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val tv = v as TextView
            tv.paint.shader = android.graphics.LinearGradient(
                0f, tv.height * 0.15f, 0f, tv.height.toFloat(),
                intArrayOf(0xFFFFFFFF.toInt(), 0xFFC8CED8.toInt(), 0xFF8A929E.toInt()),
                floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP,
            )
        }

        // MERCEDES-BENZ (тонко, серебро)  CLS 450 (жирный курсив)  AMG (красный).
        val brand = "MERCEDES-BENZ  "
        val model = "CLS 450 "
        val badge = android.text.SpannableStringBuilder(brand + model + "AMG")
        badge.setSpan(android.text.style.ForegroundColorSpan(0xFFC9CED6.toInt()), 0, brand.length, 0)
        badge.setSpan(android.text.style.RelativeSizeSpan(0.72f), 0, brand.length, 0)
        badge.setSpan(android.text.style.TypefaceSpan("sans-serif-light"), 0, brand.length, 0)
        badge.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD_ITALIC), brand.length, badge.length, 0)
        badge.setSpan(android.text.style.ForegroundColorSpan(AMG_RED), brand.length + model.length, badge.length, 0)
        findViewById<TextView>(R.id.badge).text = badge

        // Подсветка «дышит».
        android.animation.ObjectAnimator.ofFloat(findViewById<View>(R.id.light), "alpha", 1f, 0.55f).apply {
            duration = 2400
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.REVERSE
            start()
        }
    }

    /** Приветствие при запуске: подсветка растягивается, плитки загораются по очереди, стрелка датчика проходит круг. */
    private fun playIntro() {
        val light = findViewById<View>(R.id.light)
        light.scaleX = 0f
        light.animate().scaleX(1f).setDuration(900).setStartDelay(150).start()
        tiles.forEachIndexed { i, d ->
            val cell = findViewById<android.view.ViewGroup>(
                listOf(
                    R.id.btn_claude, R.id.btn_split, R.id.btn_chrome, R.id.btn_wifi,
                    R.id.btn_cam, R.id.btn_home, R.id.btn_work, R.id.btn_fav,
                    R.id.btn_gemini, R.id.btn_music, R.id.btn_radio, R.id.btn_settings,
                )[i]
            )
            cell.alpha = 0f
            cell.scaleX = 0.85f
            cell.scaleY = 0.85f
            cell.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(300L + (i % 4) * 90L + (i / 4) * 140L).setDuration(380).start()
        }
        handler.postDelayed({ gauge.sweep() }, 400)
        handler.postDelayed({ runSheen() }, 1600)
    }

    /** Световой отблеск пробегает по плиткам слева направо. */
    private fun runSheen() {
        android.animation.ValueAnimator.ofFloat(0f, 1.6f).apply {
            duration = 1300
            addUpdateListener { a ->
                val v = a.animatedValue as Float
                tiles.forEachIndexed { i, d ->
                    val t = v - (i % 4) * 0.12f - (i / 4) * 0.05f
                    d.sheen = if (t in 0f..1f) t else -1f
                }
            }
            start()
        }
    }

    private val sheenLoop = object : Runnable {
        override fun run() {
            runSheen()
            handler.postDelayed(this, 8_000)
        }
    }

    /** Значок Play / Пауза — по тому, играет ли сейчас музыка. */
    private fun updatePlayIcon() {
        val am = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val playing = controller?.playbackState?.state?.let { it == android.media.session.PlaybackState.STATE_PLAYING }
            ?: am.isMusicActive
        if (playing == playIconIsPause) return
        playIconIsPause = playing
        findViewById<android.widget.ImageView>(R.id.btn_play)
            .setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play_only)
    }

    private fun refresh() {
        updatePlayIcon()
        if (lastFix != 0L && System.currentTimeMillis() - lastFix > 30_000) {
            findViewById<CarSceneView>(R.id.scene).setSpeedKmh(null)
            lastFix = 0L
        }
        if (!mediaAccess) bindMedia()
        val now = Date()
        time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now)

        val status = readStatus()
        val favName = status?.get("fav_name").orEmpty()
        findViewById<TextView>(R.id.btn_fav_label).text = favName.ifBlank { "Избранное" }
        val heard = status?.get("last_heard").orEmpty()
        last.text = if (!calibration.isCalibrated) "Удерживайте нажатие 2 секунды в любом месте — калибровка нажатий" else if (heard.isBlank()) "Нажмите «Claude» или шайбу" else "«$heard» — ${status?.get("last_say").orEmpty()}"

        if (System.currentTimeMillis() - weatherAt > 15 * 60_000) {
            val coords = parseCoords(status?.get("home").orEmpty())
            if (coords == null) {
                gauge.set("", "нет адреса", null, null)
                weather.text = "УКАЖИТЕ ДОМ\nВ CARVOICE"
            } else {
                Thread {
                    val w = loadWeather(coords)
                    handler.post {
                        if (w != null) {
                            gauge.set(w.temp, w.desc, w.icon, w.celsius)
                            weather.text = "СЕГОДНЯ\n${w.min}…${w.max}°"
                            weatherAt = System.currentTimeMillis()
                        } else if (weatherAt == 0L) {
                            weather.text = "НЕТ СВЯЗИ"
                        }
                    }
                }.start()
            }
        }
    }

    /** Последняя команда и адрес дома из CarVoice. */
    private fun readStatus(): Map<String, String>? = try {
        contentResolver.query(Uri.parse("content://com.carvoice.assistant.status/info"), null, null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) null
                else (0 until c.columnCount).associate { c.getColumnName(it) to (c.getString(it) ?: "") }
            }
    } catch (e: Exception) {
        null
    }

    private data class Weather(val temp: String, val celsius: Int, val desc: String, val min: Long, val max: Long, val icon: Int)

    private fun loadWeather(c: Pair<String, String>): Weather? = try {
        val conn = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=${c.first}&longitude=${c.second}" +
                "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                "&timezone=auto&forecast_days=1"
        ).openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val cur = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val t = Math.round(cur.getDouble("temperature_2m"))
        val max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0))
        val min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))
        val code = cur.getInt("weather_code")
        val day = cur.optInt("is_day", 1) == 1
        Weather("${if (t > 0) "+" else ""}$t°", t.toInt(), describe(code), min, max, icon(code, day))
    } catch (e: Exception) {
        null
    }

    /** Картинка погоды: солнце/луна, облака, дождь, снег, гроза, туман. */
    private fun icon(code: Int, day: Boolean) = when (code) {
        0 -> if (day) R.drawable.w_sun else R.drawable.w_moon
        1, 2 -> if (day) R.drawable.w_partly else R.drawable.w_partly_night
        3 -> R.drawable.w_cloud
        45, 48 -> R.drawable.w_fog
        51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.w_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.w_snow
        95, 96, 99 -> R.drawable.w_storm
        else -> R.drawable.w_cloud
    }

    private fun describe(code: Int) = when (code) {
        0 -> "ясно"
        1, 2 -> "малооблачно"
        3 -> "пасмурно"
        45, 48 -> "туман"
        51, 53, 55, 56, 57 -> "морось"
        61, 63, 65, 66, 67, 80, 81, 82 -> "дождь"
        71, 73, 75, 77, 85, 86 -> "снег"
        95, 96, 99 -> "гроза"
        else -> ""
    }

    companion object {
        private const val CARVOICE = "com.carvoice.assistant"
        private const val REQ_LOCATION = 7
        private val NUMBER = Regex("""-?\d{1,3}[.,]\d{3,}""")

        fun parseCoords(s: String): Pair<String, String>? {
            val nums = NUMBER.findAll(s).map { it.value.replace(',', '.') }.toList()
            if (nums.size != 2) return null
            return nums[0] to nums[1]
        }
    }
}
