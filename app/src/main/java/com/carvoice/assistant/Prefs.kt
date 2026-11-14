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

    data class ModelOption(val id: String, val title: String)

    companion object {
        val MODELS = listOf(
            ModelOption("claude-opus-5-5", "Claude Opus 5.5 — самый умный"),
            ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5 — быстрее"),
            ModelOption("claude-haiku-5-5", "Claude Haiku 5.5 — самый быстрый и дешёвый"),
        )
    }
}
