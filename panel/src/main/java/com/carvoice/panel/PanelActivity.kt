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
    private lateinit var temp: TextView
    private lateinit var weather: TextView
    private lateinit var weatherIcon: android.widget.ImageView
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
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_panel)
        time = findViewById(R.id.time)
        date = findViewById(R.id.date)
        temp = findViewById(R.id.temp)
        weather = findViewById(R.id.weather)
        weatherIcon = findViewById(R.id.weather_icon)
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
        click(R.id.btn_prev) { run("media", value = "previous") }
        click(R.id.btn_play) { run("media", value = "toggle") }
        click(R.id.btn_next) { run("media", value = "next") }
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
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
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
                temp.text = ""
                weather.text = "укажите дом в CarVoice"
            } else {
                Thread {
                    val w = loadWeather(coords)
                    handler.post {
                        if (w != null) {
                            temp.text = w.first
                            weather.text = w.second
                            weatherIcon.setImageResource(w.third)
                            weatherAt = System.currentTimeMillis()
                        } else if (temp.text.isNullOrBlank()) {
                            weather.text = "нет связи"
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

    private fun loadWeather(c: Pair<String, String>): Triple<String, String, Int>? = try {
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
        Triple("${if (t > 0) "+" else ""}$t°", "${describe(code)} · $min…$max°", icon(code, day))
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
        private val NUMBER = Regex("""-?\d{1,3}[.,]\d{3,}""")

        fun parseCoords(s: String): Pair<String, String>? {
            val nums = NUMBER.findAll(s).map { it.value.replace(',', '.') }.toList()
            if (nums.size != 2) return null
            return nums[0] to nums[1]
        }
    }
}
