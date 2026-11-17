package com.carvoice.assistant

import android.content.Context

/** Настройки приложения (ключ API и модель хранятся только на устройстве). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("carvoice", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) = sp.edit().putString("api_key", v.trim()).apply()

    var model: String
        get() = sp.getString("model", MODELS.first().id) ?: MODELS.first().id
        set(v) = sp.edit().putString("model", v).apply()

    var speak: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    /** Адрес или координаты «Дом» (например «40.1772, 44.5035» или «ул. Абовяна 10»). */
    var home: String
        get() = sp.getString("home", "") ?: ""
        set(v) = sp.edit().putString("home", v.trim()).apply()

    /** Адрес или координаты «Работа». */
    var work: String
        get() = sp.getString("work", "") ?: ""
        set(v) = sp.edit().putString("work", v.trim()).apply()

    /** Избранное место: название (например «WB») и координаты или адрес. */
    var favName: String
        get() = sp.getString("fav_name", "") ?: ""
        set(v) = sp.edit().putString("fav_name", v.trim()).apply()

    var fav: String
        get() = sp.getString("fav", "") ?: ""
        set(v) = sp.edit().putString("fav", v.trim()).apply()

    /** Язык голосовых команд: ru, hy или hy_ru (армянский + русский). */
    var language: String
        get() = sp.getString("language", "ru") ?: "ru"
        set(v) = sp.edit().putString("language", v).apply()

    /** Выбранный голос озвучки (имя Voice), пусто — по умолчанию. */
    var voiceName: String
        get() = sp.getString("voice_name", "") ?: ""
        set(v) = sp.edit().putString("voice_name", v).apply()

    /** Скорость речи, 0.5–2.0. */
    var speechRate: Float
        get() = sp.getFloat("speech_rate", 1.1f)
        set(v) = sp.edit().putFloat("speech_rate", v).apply()

    /** Тон голоса, 0.5–2.0 (выше — бодрее). */
    var pitch: Float
        get() = sp.getFloat("pitch", 1.1f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    /** Последнее разделение экрана: какое приложение слева и какое справа. */
    var lastSplitLeft: String
        get() = sp.getString("split_left", "") ?: ""
        set(v) = sp.edit().putString("split_left", v).apply()

    var lastSplitRight: String
        get() = sp.getString("split_right", "") ?: ""
        set(v) = sp.edit().putString("split_right", v).apply()

    /** Последняя команда и ответ — для «CarVoice Панели». */
    var lastHeard: String
        get() = sp.getString("last_heard", "") ?: ""
        set(v) = sp.edit().putString("last_heard", v).apply()

    var lastSay: String
        get() = sp.getString("last_say", "") ?: ""
        set(v) = sp.edit().putString("last_say", v).apply()

    data class ModelOption(val id: String, val title: String)

    companion object {
        val LANGUAGES = listOf(
            "ru" to "Русский",
            "hy" to "Հայերեն (армянский)",
            "hy_ru" to "Հայերեն + русский",
        )

        val MODELS = listOf(
            ModelOption("claude-opus-5-5", "Claude Opus 5.5 — самый умный"),
            ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5 — быстрее"),
            ModelOption("claude-haiku-5-5", "Claude Haiku 5.5 — самый быстрый и дешёвый"),
        )
    }
}
