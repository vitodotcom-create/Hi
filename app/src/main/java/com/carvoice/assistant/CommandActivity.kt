package com.carvoice.assistant

import android.app.Activity
import android.os.Bundle
import kotlinx.coroutines.launch

/**
 * Невидимый «вход» для «CarVoice Панели»: выполняет одно действие без запроса к Claude
 * (маршрут домой, музыка, медиакнопки, разделение экрана) и сразу закрывается.
 * Доступен только приложениям с той же подписью (см. permission в манифесте).
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = Action(
            type = intent.getStringExtra("type").orEmpty(),
            target = intent.getStringExtra("target").orEmpty(),
            query = intent.getStringExtra("query").orEmpty(),
            value = intent.getStringExtra("value").orEmpty(),
        )
        DebugLog.add(this, "🎛 Панель (экран ${display?.displayId}): ${action.type} ${action.target} ${action.query} ${action.value}")
        val appContext = applicationContext
        // Остаёмся открытыми (невидимо), пока действие выполняется: Android разрешает запускать
        // другие приложения только «видимому» приложению. Потом закрываемся.
        AppScope.scope.launch {
            val error = ActionExecutor(appContext).executeAll(listOf(action))
            if (error != null) UiPilot.toast(appContext, error)
            kotlinx.coroutines.delay(if (action.type == "split_screen") 6000 else 1500)
            finish()
        }
    }
}
