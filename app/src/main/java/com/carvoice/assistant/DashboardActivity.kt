package com.carvoice.assistant

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * «Панель CarVoice» — экран для большой области на рабочем столе Carlinkit:
 * часы, погода у дома, последняя команда и крупные кнопки быстрых действий.
 * Быстрые кнопки выполняются сразу, без запроса к Claude.
 */
class DashboardActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherView: TextView
    private lateinit var lastView: TextView
    private var ticker: Job? = null
    private var weatherLoadedAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)
        prefs = Prefs(this)
        timeView = findViewById(R.id.dash_time)
        dateView = findViewById(R.id.dash_date)
        weatherView = findViewById(R.id.dash_weather)
        lastView = findViewById(R.id.dash_last)

        findViewById<View>(R.id.btn_talk).setOnClickListener {
            startActivity(Intent(this, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        findViewById<View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        bind(R.id.btn_home, Action("navigate", "", "@home", ""))
        bind(R.id.btn_work, Action("navigate", "", "@work", ""))
        bind(R.id.btn_music, Action("play_music", "", "", ""))
        bind(R.id.btn_prev, Action("media", "", "", "previous"))
        bind(R.id.btn_play, Action("media", "", "", "toggle"))
        bind(R.id.btn_next, Action("media", "", "", "next"))
        bind(
            R.id.btn_split,
            Action("split_screen", ActionExecutor.YANDEX_NAVI, ActionExecutor.YOUTUBE, ""),
        )
    }

    /** Кнопка → действие без Claude. */
    private fun bind(id: Int, action: Action) {
        findViewById<View>(id).setOnClickListener {
            lifecycleScope.launch {
                val error = ActionExecutor(this@DashboardActivity).executeAll(listOf(action))
                if (error != null) Toast.makeText(this@DashboardActivity, error, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ticker?.cancel()
        ticker = lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(10_000)
            }
        }
    }

    override fun onPause() {
        ticker?.cancel()
        super.onPause()
    }

    private suspend fun refresh() {
        val now = Date()
        timeView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        dateView.text = SimpleDateFormat("EEEE, d MMMM", Locale("ru")).format(now)
            .replaceFirstChar { it.uppercase() }

        val heard = prefs.lastHeard
        lastView.text = if (heard.isBlank()) {
            "Нажмите 🎤 или шайбу"
        } else {
            "«$heard»\n${prefs.lastSay}"
        }

        // Погода — раз в 15 минут.
        if (System.currentTimeMillis() - weatherLoadedAt > 15 * 60_000) {
            if (ActionExecutor.parseCoords(prefs.home) == null) {
                weatherView.text = "🌡 укажите координаты дома"
            } else {
                val text = withContext(Dispatchers.IO) { loadWeather() }
                if (text != null) {
                    weatherView.text = text
                    weatherLoadedAt = System.currentTimeMillis()
                } else if (weatherView.text.isNullOrBlank()) {
                    weatherView.text = "🌡 нет связи"
                }
            }
        }
    }

    /** Текущая погода у дома (Open-Meteo, без ключа). */
    private fun loadWeather(): String? {
        val coords = ActionExecutor.parseCoords(prefs.home) ?: return null
        return try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=${coords.first}&longitude=${coords.second}" +
                    "&current=temperature_2m,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val cur = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val temp = Math.round(cur.getDouble("temperature_2m"))
            val wind = Math.round(cur.getDouble("wind_speed_10m"))
            val max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0))
            val min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))
            val (icon, desc) = weatherText(cur.getInt("weather_code"))
            "$icon ${if (temp > 0) "+" else ""}$temp°\n$desc, $min…$max°"
        } catch (e: Exception) {
            DebugLog.add(this, "🌡 Погода не загрузилась: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }

    private fun weatherText(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "ясно"
        1, 2 -> "🌤" to "малооблачно"
        3 -> "☁️" to "пасмурно"
        45, 48 -> "🌫" to "туман"
        51, 53, 55, 56, 57 -> "🌦" to "морось"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧" to "дождь"
        71, 73, 75, 77, 85, 86 -> "❄️" to "снег"
        95, 96, 99 -> "⛈" to "гроза"
        else -> "🌡" to ""
    }
}
