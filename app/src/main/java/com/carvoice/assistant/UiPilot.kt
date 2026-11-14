package com.carvoice.assistant

import android.content.Context
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Фоновые задачи, которые живут дольше окна «Слушаю…». */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}

/** Кнопка на экране другого приложения. */
data class UiButton(val index: Int, val label: String, val node: android.view.accessibility.AccessibilityNodeInfo)

/** Что сделать на текущем экране по быстрым правилам (без запроса к Claude). */
data class FastClick(val button: UiButton, val final: Boolean)

/**
 * «Автопилот» интерфейса: смотрит на кнопки в приложении и нажимает нужные,
 * пока цель не достигнута. Сначала — быстрые правила (известные подписи),
 * если они не подходят — спрашивает Claude, какую кнопку нажать.
 */
class UiPilot(private val context: Context) {

    suspend fun run(
        pkg: String,
        goal: String,
        fastPath: (List<UiButton>, Set<String>) -> FastClick? = { _, _ -> null },
        maxSteps: Int = 6,
        timeoutMs: Long = 45000,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val clicked = mutableSetOf<String>()
        val history = mutableListOf<String>()
        var lastAskedScreen = ""
        var steps = 0
        var idleChecks = 0
        delay(1500)
        while (System.currentTimeMillis() < deadline && steps < maxSteps) {
            val svc = VoiceAccessibilityService.instance ?: run {
                DebugLog.add(context, "🤖 Автопилот: спецвозможности выключены")
                return false
            }
            val buttons = withContext(Dispatchers.Main) { svc.snapshot(pkg) }
            if (buttons == null || buttons.isEmpty()) {
                delay(700)
                continue
            }

            // 1. Быстрые правила.
            val fast = fastPath(buttons, clicked)
            if (fast != null) {
                val ok = withContext(Dispatchers.Main) { svc.click(fast.button.node) }
                Log.i(TAG, "Fast click '${fast.button.label}' ok=$ok")
                DebugLog.add(context, "🤖 Автопилот $pkg: нажал «${fast.button.label}» ok=$ok")
                if (ok) {
                    clicked += fast.button.label.lowercase()
                    history += "нажата «${fast.button.label}»"
                    steps++
                    idleChecks = 0
                    if (fast.final) return true
                    delay(1500)
                    continue
                }
            }

            // Дадим экрану немного «устояться», прежде чем звать Claude.
            if (idleChecks < 2) {
                idleChecks++
                delay(1000)
                continue
            }

            // 2. Спрашиваем Claude (один раз на каждый новый экран).
            val screenKey = buttons.joinToString("|") { it.label }
            if (screenKey == lastAskedScreen) {
                delay(1000)
                continue
            }
            lastAskedScreen = screenKey
            val prefs = Prefs(context)
            if (prefs.apiKey.isBlank()) return false
            val decision = try {
                withContext(Dispatchers.IO) {
                    ClaudeBrain(prefs.apiKey, prefs.model).chooseButton(goal, buttons.map { it.label }, history)
                }
            } catch (e: Exception) {
                Log.e(TAG, "chooseButton failed", e)
                DebugLog.add(context, "🤖 Автопилот: ошибка запроса к Claude: ${e.message?.take(150)}")
                return false
            }
            Log.i(TAG, "Claude decision: $decision")
            DebugLog.add(context, "🤖 Автопилот $pkg: кнопки=${buttons.map { it.label }} → $decision")
            when (decision.decision) {
                "done" -> return true
                "wait" -> delay(1500)
                "click" -> {
                    val b = buttons.firstOrNull { it.index == decision.index } ?: return false
                    val ok = withContext(Dispatchers.Main) { svc.click(b.node) }
                    if (ok) {
                        clicked += b.label.lowercase()
                        history += "нажата «${b.label}»"
                        steps++
                        idleChecks = 0
                        delay(1500)
                    }
                }
                else -> return false
            }
        }
        return false
    }

    companion object {
        private const val TAG = "CarVoicePilot"

        val START_LABELS = listOf("поехали", "едем", "в путь", "начать", "старт", "начать движение", "go", "start")

        /** Новый маршрут (вместо текущего). Порядок = приоритет. */
        val NEW_ROUTE_LABELS = listOf(
            "заменить", "заменить маршрут", "новый маршрут", "сюда", "поехали сюда", "маршрут сюда",
            "построить маршрут", "маршрут", "route", "directions",
        )

        /** Заехать по пути (добавить остановку). */
        val VIA_LABELS = listOf(
            "заехать", "заехать сюда", "через", "добавить точку", "добавить остановку", "добавить в маршрут",
        )

        /** Быстрые правила для Яндекс Навигатора / Карт. */
        fun navigationFastPath(via: Boolean): (List<UiButton>, Set<String>) -> FastClick? = { buttons, clicked ->
            fun find(labels: List<String>) = labels.firstNotNullOfOrNull { l ->
                buttons.firstOrNull { it.label.lowercase() == l && l !in clicked }
            }
            val stepLabels = if (via) VIA_LABELS else NEW_ROUTE_LABELS
            // «Поехали» — финальный шаг; но если ещё есть шаг выбора маршрута, сначала он.
            find(stepLabels)?.let { FastClick(it, final = false) }
                ?: find(START_LABELS)?.let { FastClick(it, final = true) }
        }

        fun toast(context: Context, text: String) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
