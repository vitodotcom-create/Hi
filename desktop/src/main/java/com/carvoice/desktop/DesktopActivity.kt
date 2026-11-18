package com.carvoice.desktop

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Рабочий стол» на весь экран 1920×720: дизайн — HTML в assets/desk, данные и действия — отсюда.
 * Команды выполняет CarVoice (как у панели), поэтому всё открывается на весь экран.
 */
class DesktopActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private var pageReady = false
    private var weatherAt = 0L
    private val data = JSONObject()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageReady = true
                    push()
                }
            }
            addJavascriptInterface(Bridge(), "Android")
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/desk/index.html")
        hideBars()
        askPermissionsOnce()
    }

    private fun hideBars() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** Один раз: «поверх других приложений» (для автозапуска) и местоположение (скорость). */
    private fun askPermissionsOnce() {
        val sp = getSharedPreferences("desk", MODE_PRIVATE)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        }
        if (!Settings.canDrawOverlays(this) && !sp.getBoolean("asked_overlay", false)) {
            sp.edit().putBoolean("asked_overlay", true).apply()
            Toast.makeText(this, "Включите «Поверх других приложений» — тогда стол будет открываться сам при включении", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        startSpeed()
    }

    override fun onResume() {
        super.onResume()
        hideBars()
        bindMedia()
        startSpeed()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        stopSpeed()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // «Назад» на столе — просто уйти на системный рабочий стол, стол остаётся в памяти.
        moveTaskToBack(true)
    }

    private val tick = object : Runnable {
        override fun run() {
            readStatus()
            refreshWeather()
            pushMedia()
            push()
            handler.postDelayed(this, 2000)
        }
    }

    private fun push() {
        if (!pageReady) return
        val js = "window.cv&&cv.update(" + JSONObject.quote(data.toString()) + ")"
        web.evaluateJavascript(js, null)
    }

    // ---------- Данные из CarVoice: последняя команда, адрес дома, название избранного ----------

    private var homeCoords: Pair<String, String>? = null

    private fun readStatus() {
        try {
            contentResolver.query(Uri.parse("content://com.carvoice.assistant.status/info"), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun col(n: String) = c.getColumnIndex(n).takeIf { it >= 0 }?.let { c.getString(it) }.orEmpty()
                    data.put("heard", col("last_heard"))
                    data.put("say", col("last_say"))
                    data.put("fav", col("fav_name"))
                    homeCoords = parseCoords(col("home"))
                }
            }
        } catch (_: Exception) {
        }
    }

    // ---------- Погода (Open-Meteo, без ключа) ----------

    private fun refreshWeather() {
        val c = homeCoords ?: return
        if (System.currentTimeMillis() - weatherAt < 15 * 60_000) return
        weatherAt = System.currentTimeMillis()
        Thread {
            try {
                val conn = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=${c.first}&longitude=${c.second}" +
                        "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
                ).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val cur = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")
                val t = Math.round(cur.getDouble("temperature_2m"))
                val code = cur.getInt("weather_code")
                handler.post {
                    data.put("temp", "${if (t > 0) "+" else ""}$t°")
                    data.put("wdesc", describe(code))
                    data.put("wicon", icon(code))
                    data.put("tmin", Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)))
                    data.put("tmax", Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)))
                    push()
                }
            } catch (_: Exception) {
                weatherAt = 0L
            }
        }.start()
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

    private fun icon(code: Int) = when (code) {
        0 -> "☀"
        1, 2 -> "⛅"
        3, 45, 48 -> "☁"
        71, 73, 75, 77, 85, 86 -> "❄"
        95, 96, 99 -> "⛈"
        else -> "🌧"
    }

    // ---------- Скорость и курс (GPS) ----------

    private var lm: LocationManager? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            data.put("speed", if (loc.hasSpeed()) Math.round(loc.speed * 3.6f) else 0)
            if (loc.hasBearing()) data.put("bearing", Math.round(loc.bearing))
            push()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private fun startSpeed() {
        if (lm != null) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val m = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (m.allProviders.contains(p)) m.requestLocationUpdates(p, 1000L, 0f, locationListener, Looper.getMainLooper())
            }
            lm = m
        } catch (_: SecurityException) {
        }
    }

    private fun stopSpeed() {
        lm?.removeUpdates(locationListener)
        lm = null
    }

    // ---------- Что играет ----------

    private var controller: MediaController? = null
    private var mediaAccess = false
    private var lastArtKey = ""

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { pushMedia(); push() }
        override fun onPlaybackStateChanged(state: PlaybackState?) { pushMedia(); push() }
        override fun onSessionDestroyed() { controller = null; pushMedia(); push() }
    }

    private fun bindMedia() {
        val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(this, MediaListenerService::class.java)
        try {
            pick(msm.getActiveSessions(cn))
            if (!mediaAccess) msm.addOnActiveSessionsChangedListener({ pick(it ?: emptyList()) }, cn)
            mediaAccess = true
        } catch (_: SecurityException) {
            mediaAccess = false
        }
        data.put("mediaAccess", mediaAccess)
    }

    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(mediaCallback)
        controller = best
        best?.registerCallback(mediaCallback, handler)
        pushMedia(); push()
    }

    private fun pushMedia() {
        val c = controller
        val md = c?.metadata
        val st = c?.playbackState
        data.put("playing", st?.state == PlaybackState.STATE_PLAYING)
        if (md == null) {
            data.put("title", ""); data.put("artist", ""); data.put("app", ""); data.put("duration", 0)
            return
        }
        data.put("title", md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "")
        data.put(
            "artist",
            md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
        )
        data.put(
            "app",
            try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(c.packageName, 0)).toString() } catch (_: Exception) { "" },
        )
        data.put("duration", md.getLong(MediaMetadata.METADATA_KEY_DURATION))
        var pos = st?.position ?: 0L
        if (st?.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
            pos += ((android.os.SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        data.put("position", pos)
        // Обложка — только когда сменилась песня (картинка в base64).
        val key = data.optString("title") + "|" + data.optString("artist")
        if (key != lastArtKey) {
            lastArtKey = key
            val bmp: Bitmap? = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            data.put("art", bmp?.let {
                val small = Bitmap.createScaledBitmap(it, 160, 160 * it.height / it.width.coerceAtLeast(1), true)
                val out = ByteArrayOutputStream()
                small.compress(Bitmap.CompressFormat.JPEG, 85, out)
                "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } ?: "")
        }
    }

    // ---------- Действия ----------

    private fun launch(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть", Toast.LENGTH_SHORT).show()
        }
    }

    /** Команда в CarVoice (выполняется там, без запроса к Claude). */
    private fun run(type: String, target: String = "", query: String = "", value: String = "") {
        try {
            startActivity(
                Intent("com.carvoice.assistant.RUN").setPackage(CARVOICE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("type", type).putExtra("target", target).putExtra("query", query).putExtra("value", value)
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Обновите CarVoice до последней версии", Toast.LENGTH_LONG).show()
        }
    }

    private fun openInstalled(packages: List<String>, words: List<String>, title: String) {
        val pm = packageManager
        val pkg = packages.firstOrNull { pm.getLaunchIntentForPackage(it) != null }
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .firstOrNull { ri ->
                    val label = ri.loadLabel(pm).toString().lowercase()
                    words.any { label.contains(it) || ri.activityInfo.packageName.lowercase().contains(it) }
                }?.activityInfo?.packageName
        if (pkg == null) Toast.makeText(this, "$title не установлено", Toast.LENGTH_LONG).show()
        else run("open_app", target = pkg)
    }

    /** Системный рабочий стол (CarPlay, Android Auto, все приложения). */
    private fun systemHome() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, 0).firstOrNull { it.activityInfo.packageName != packageName }
        if (other != null) home.setClassName(other.activityInfo.packageName, other.activityInfo.name)
        launch(home)
    }

    private fun media(cmd: String) {
        val c = controller
        when (cmd) {
            "prev" -> c?.transportControls?.skipToPrevious() ?: run("media", value = "previous")
            "next" -> c?.transportControls?.skipToNext() ?: run("media", value = "next")
            else -> when {
                c == null -> run("media", value = "toggle")
                c.playbackState?.state == PlaybackState.STATE_PLAYING -> c.transportControls.pause()
                else -> c.transportControls.play()
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun action(name: String) {
            handler.post {
                when (name) {
                    "claude" -> launch(Intent("com.carvoice.assistant.LISTEN").setPackage(CARVOICE))
                    "split" -> run("split_screen", target = "ru.yandex.yandexnavi", query = "com.google.android.youtube")
                    "chrome" -> run("open_app", target = "com.android.chrome")
                    "wifi" -> run("wifi")
                    "70mai" -> openInstalled(listOf("com.mi.carlife", "com.mi.dashcam", "com.mi.car.dvr"), listOf("70mai"), "70mai")
                    "home" -> run("navigate", query = "@home")
                    "work" -> run("navigate", query = "@work")
                    "fav" -> run("navigate", query = "@fav")
                    "gemini" -> run("app_task", target = "com.google.android.apps.bard", query = "включить Gemini Live")
                    "music" -> run("play_music", query = "@liked")
                    "radio" -> openInstalled(listOf("tunein.player", "radiotime.player"), listOf("tunein"), "TuneIn Radio")
                    "settings" -> launch(packageManager.getLaunchIntentForPackage("com.suding.settings") ?: Intent(Settings.ACTION_SETTINGS))
                    "system", "menu", "all" -> systemHome()
                    "back" -> moveTaskToBack(true)
                    "volume" -> (getSystemService(AUDIO_SERVICE) as AudioManager)
                        .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
                    "phone" -> launch(Intent(Intent.ACTION_DIAL))
                    "prev", "play", "next" -> media(name)
                    "song" -> if (!mediaAccess) {
                        Toast.makeText(this@DesktopActivity, "Включите «Рабочий стол» — тогда здесь будет песня", Toast.LENGTH_LONG).show()
                        launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    } else {
                        controller?.packageName?.let { p -> packageManager.getLaunchIntentForPackage(p)?.let { launch(it) } }
                    }
                }
            }
        }
    }

    companion object {
        private const val CARVOICE = "com.carvoice.assistant"
        private val NUMBER = Regex("""-?\d{1,3}[.,]\d{3,}""")

        fun parseCoords(s: String): Pair<String, String>? {
            val nums = NUMBER.findAll(s).map { it.value.replace(',', '.') }.toList()
            return if (nums.size == 2) nums[0] to nums[1] else null
        }
    }
}
