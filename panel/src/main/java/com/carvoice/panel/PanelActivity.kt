package com.carvoice.panel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
        click(R.id.btn_nav) { run("focus_app", target = "ru.yandex.yandexnavi") }
        click(R.id.btn_home) { run("navigate", query = "@home") }
        click(R.id.btn_work) { run("navigate", query = "@work") }
        click(R.id.btn_fav) { run("navigate", query = "@fav") }
        // Ряд 3 — голос и музыка
        click(R.id.btn_gemini) {
            run("app_task", target = "com.google.android.apps.bard", query = "включить Gemini Live")
        }
        click(R.id.btn_music) { run("play_music", query = "@liked") }
        click(R.id.btn_settings) {
            packageManager.getLaunchIntentForPackage(CARVOICE)?.let { launch(it) }
        }
        // Плеер
        click(R.id.btn_prev) { run("media", value = "previous") }
        click(R.id.btn_play) { run("media", value = "toggle") }
        click(R.id.btn_next) { run("media", value = "next") }
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
        findViewById<TextView>(R.id.btn_fav).text = favName.ifBlank { "Избранное" }
        val heard = status?.get("last_heard").orEmpty()
        last.text = if (heard.isBlank()) "Нажмите «Говорить» или шайбу" else "«$heard» — ${status?.get("last_say").orEmpty()}"

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
