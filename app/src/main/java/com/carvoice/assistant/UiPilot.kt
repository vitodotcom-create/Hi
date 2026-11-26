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
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs
        var target = pkg
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
            val buttons = withContext(Dispatchers.Main) { svc.snapshot(target) }
            if (buttons == null) {
                // Приложение могло открыться под другим пакетом (например, Gemini внутри Google) —
                // в первые секунды принимаем то, что реально появилось на экране.
                val fg = withContext(Dispatchers.Main) { svc.foregroundPackage() }
                if (fg != null && fg != target && target == pkg &&
                    System.currentTimeMillis() - started < 15000 && !isExcluded(fg)
                ) {
                    DebugLog.add(context, "🤖 Автопилот: $pkg открылся как $fg")
                    target = fg
                    continue
                }
                delay(700)
                continue
            }
            if (buttons.isEmpty()) {
                delay(700)
                continue
            }

            // 1. Быстрые правила.
            val fast = fastPath(buttons, clicked)
            if (fast != null) {
                val ok = withContext(Dispatchers.Main) { svc.click(fast.button.node) }
                Log.i(TAG, "Fast click '${fast.button.label}' ok=$ok")
                DebugLog.add(context, "🤖 Автопилот $target: нажал «${fast.button.label}» ok=$ok")
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
                    val brain = ClaudeBrain(prefs.apiKey, prefs.model)
                    try {
                        brain.chooseButton(goal, buttons.take(70).map { it.label }, history)
                    } catch (e: com.anthropic.errors.AnthropicIoException) {
                        // Сбой сети — одна повторная попытка.
                        Thread.sleep(1500)
                        brain.chooseButton(goal, buttons.take(70).map { it.label }, history)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "chooseButton failed", e)
                DebugLog.add(context, "🤖 Автопилот: ошибка запроса к Claude: ${e.message?.take(150)}")
                return false
            }
            Log.i(TAG, "Claude decision: $decision")
            DebugLog.add(context, "🤖 Автопилот $target: кнопки=${buttons.map { it.label }} → $decision")
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

    /** Рабочий стол, системная шторка и сам CarVoice — это не «открывшееся приложение». */
    private fun isExcluded(fg: String): Boolean {
        if (fg == context.packageName || fg == "com.android.systemui" || fg == "android") return true
        val home = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_HOME)
        val homes = context.packageManager.queryIntentActivities(home, 0).map { it.activityInfo.packageName }
        return fg in homes || fg.contains("launcher")
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
            // Подписи сравниваем без знаков и лишних пробелов: «Едем!» = «едем».
            fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim()
            fun find(labels: List<String>) = labels.firstNotNullOfOrNull { l ->
                buttons.firstOrNull { norm(it.label) == l && it.label.lowercase() !in clicked }
            }
            // «Поехали»/«Едем» бывает с временем в пути: «Едем 25 мин».
            fun findStart() = START_LABELS.firstNotNullOfOrNull { l ->
                buttons.firstOrNull { val n = norm(it.label); (n == l || n.startsWith("$l ")) && it.label.lowercase() !in clicked }
            }
            val stepLabels = if (via) VIA_LABELS else NEW_ROUTE_LABELS
            // «Поехали» — финальный шаг; но если ещё есть шаг выбора маршрута, сначала он.
            find(stepLabels)?.let { FastClick(it, final = false) }
                ?: findStart()?.let { FastClick(it, final = true) }
        }

        /** Кнопки голоса по приоритету: сначала голосовой режим/чат, потом поиск/микрофон. */
        private val VOICE_PRIORITY = listOf(
            "открыть gemini live", "gemini live", "use voice mode", "voice mode", "голосовой режим",
            "голосовой чат", "голосовой поиск", "voice search", "search with your voice", "голосовой ввод",
            "микрофон", "microphone",
        )
        private val VOICE_EXCLUDE = listOf("выключ", "отключ", "выйти", "exit", "turn off", "stop", "останов")

        /** Быстрое правило для голосового режима: известная кнопка голоса — нажать и закончить. */
        val voiceFastPath: (List<UiButton>, Set<String>) -> FastClick? = { buttons, clicked ->
            VOICE_PRIORITY.firstNotNullOfOrNull { hint ->
                buttons.firstOrNull { b ->
                    val l = b.label.lowercase()
                    l.contains(hint) && l !in clicked && VOICE_EXCLUDE.none { l.contains(it) }
                }
            }?.let { FastClick(it, final = true) }
        }

        /** Быстрое правило «нажать Play» (Spotify и др.); кнопки паузы не трогаем. */
        val playFastPath: (List<UiButton>, Set<String>) -> FastClick? = { buttons, clicked ->
            val play = listOf("воспроизвести", "слушать", "play")
            buttons.firstOrNull { b ->
                val l = b.label.lowercase()
                play.any { l.contains(it) } && l !in clicked &&
                    // Перемешивание (shuffle) не трогаем — у водителя оно включено.
                    listOf("пауз", "pause", "далее", "next", "назад", "previous", "перемеш", "случайн", "shuffle", "smart")
                        .none { l.contains(it) }
            }?.let { FastClick(it, final = true) }
        }

        fun toast(context: Context, text: String) {
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }
}
