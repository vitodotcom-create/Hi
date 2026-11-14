package com.carvoice.assistant

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Выполняет действия, которые вернул Claude. */
class ActionExecutor(private val context: Context) {

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Выполняет действия по очереди. Возвращает текст ошибки, если что-то не получилось. */
    suspend fun executeAll(actions: List<Action>): String? {
        var error: String? = null
        for ((i, a) in actions.withIndex()) {
            val launchedApp = try {
                execute(a)
            } catch (e: UserMessage) {
                error = e.message
                false
            } catch (e: Exception) {
                Log.e(TAG, "Action failed: $a", e)
                error = "Не получилось: ${a.type}"
                false
            }
            if (i < actions.lastIndex) delay(if (launchedApp) 1800 else 400)
        }
        return error
    }

    /** @return true, если было запущено приложение (тогда перед следующим действием нужна пауза). */
    private fun execute(a: Action): Boolean {
        Log.i(TAG, "Execute $a")
        return when (a.type) {
            "volume" -> { volume(a.value); false }
            "media" -> { media(a.value); false }
            "play_music" -> playMusic(a.target, a.query)
            "navigate" -> navigate(a.target, a.query, via = a.value == "via")
            "open_app" -> openApp(a.target, a.value == "voice")
            "search_in_app" -> searchInApp(a.target, a.query)
            "system" -> { system(a.value); false }
            else -> false
        }
    }

    private fun volume(value: String) {
        val stream = AudioManager.STREAM_MUSIC
        val flags = AudioManager.FLAG_SHOW_UI
        when (value.lowercase()) {
            "up" -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, flags) }
            "down" -> repeat(2) { audio.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, flags) }
            "mute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, flags)
            "unmute" -> audio.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, flags)
            else -> value.filter { it.isDigit() }.toIntOrNull()?.let { pct ->
                val max = audio.getStreamMaxVolume(stream)
                audio.setStreamVolume(stream, (max * pct.coerceIn(0, 100) + 50) / 100, flags)
            }
        }
    }

    private fun media(value: String) {
        val code = when (value.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    private fun playMusic(target: String, query: String): Boolean {
        val pkg = target.ifBlank { if (AppCatalog.isInstalled(context, SPOTIFY)) SPOTIFY else "" }
        if (query.isBlank()) {
            // Просто «включи музыку/Спотифай»: открываем приложение и жмём Play.
            if (pkg.isNotBlank() && launchPackage(pkg)) {
                Thread { Thread.sleep(2500); media("play") }.start()
                return true
            }
            media("play")
            return false
        }
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(SearchManager.QUERY, query)
            if (pkg.isNotBlank()) setPackage(pkg)
        }
        if (start(intent)) return true
        // Запасной вариант для Spotify — открыть поиск.
        return start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(query))))
    }

    private fun navigate(target: String, rawQuery: String, via: Boolean): Boolean {
        val prefs = Prefs(context)
        val query = when (rawQuery.trim().lowercase()) {
            "@home" -> prefs.home.ifBlank { throw UserMessage("Адрес дома не указан в настройках CarVoice") }
            "@work" -> prefs.work.ifBlank { throw UserMessage("Адрес работы не указан в настройках CarVoice") }
            else -> rawQuery
        }
        val coords = parseCoords(query)
        val q = Uri.encode(query)
        val pkg = target.ifBlank {
            NAV_APPS.firstOrNull { AppCatalog.isInstalled(context, it) } ?: ""
        }
        val candidates = buildList {
            if (coords != null) {
                val (lat, lon) = coords
                when (pkg) {
                    YANDEX_NAVI -> add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"))
                    )
                    YANDEX_MAPS -> add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto"))
                    )
                    GOOGLE_MAPS -> add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$lat,$lon")).setPackage(GOOGLE_MAPS)
                    )
                }
                add(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")))
            }
            when (pkg) {
                YANDEX_NAVI -> add(Intent(Intent.ACTION_VIEW, Uri.parse("yandexnavi://map_search?text=$q")))
                YANDEX_MAPS -> add(Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?text=$q")))
                GOOGLE_MAPS -> add(
                    Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$q")).setPackage(GOOGLE_MAPS)
                )
                "" -> {}
                else -> add(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")).setPackage(pkg))
            }
            add(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$q")))
        }
        candidates.firstOrNull { start(it) } ?: return false
        // Дальше «автопилот» сам нажимает нужные кнопки: «Сюда»/«Маршрут»/«Заменить»/«Поехали»
        // или «Заехать» — а на незнакомом экране спрашивает Claude.
        if (pkg.isNotBlank() && pkg != GOOGLE_MAPS && VoiceAccessibilityService.isEnabled) {
            val place = if (rawQuery.trim().lowercase() == "@home") "дом" else if (rawQuery.trim().lowercase() == "@work") "работа" else query
            val goal = if (via) {
                "Добавить промежуточную остановку «$place» в текущий маршрут (заехать по пути) и продолжить ведение."
            } else {
                "Построить новый маршрут до «$place» (вместо текущего, если он есть) и запустить ведение по маршруту кнопкой «Поехали». Если найдено несколько мест — выбрать первое подходящее."
            }
            val appContext = context.applicationContext
            AppScope.scope.launch {
                UiPilot(appContext).run(pkg, goal, UiPilot.navigationFastPath(via))
            }
        }
        return true
    }

    private fun openApp(pkg: String, voice: Boolean): Boolean {
        // Gemini: голосовой режим открывается напрямую (как в flow Automate пользователя).
        if (pkg == GEMINI && voice &&
            start(Intent(Intent.ACTION_VIEW, Uri.parse("googleassistant://gemini-live")).setPackage(GEMINI))
        ) return true
        if (!launchPackage(pkg)) return false
        if (voice) {
            val svc = VoiceAccessibilityService.instance
            val appContext = context.applicationContext
            svc?.clickVoiceButton(pkg) { ok ->
                if (!ok) AppScope.scope.launch {
                    val goal = "Включить в этом приложении голосовой режим или голосовой чат (разговор голосом). " +
                        "Если такого режима нет — включить голосовой ввод или голосовой поиск (микрофон)."
                    val done = UiPilot(appContext).run(pkg, goal, maxSteps = 3, timeoutMs = 20000)
                    if (!done) VoiceAccessibilityService.instance?.reportButtons(pkg)
                }
            }
        }
        return true
    }

    private fun searchInApp(pkg: String, query: String): Boolean {
        val search = Intent(Intent.ACTION_SEARCH).setPackage(pkg).putExtra(SearchManager.QUERY, query)
        if (start(search)) return true
        if (pkg == YOUTUBE) {
            val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
            if (start(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(YOUTUBE))) return true
        }
        // Не поддерживает поиск — открываем и включаем голосовой ввод.
        return openApp(pkg, voice = true)
    }

    private fun system(value: String) {
        val svc = VoiceAccessibilityService.instance
        when (value) {
            "back" -> svc?.back()
            else -> if (svc?.home() != true) {
                start(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
            }
        }
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return start(intent)
    }

    private fun start(intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "Not allowed: $intent", e)
        false
    }

    /** Ошибка, текст которой показываем водителю. */
    class UserMessage(message: String) : Exception(message)

    companion object {
        private const val TAG = "CarVoiceExec"

        private val COORDS = Regex("""^\s*(-?\d{1,2}(?:\.\d+)?)\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*$""")

        /** «40.1772, 44.5035» → (40.1772, 44.5035); адрес → null. */
        fun parseCoords(s: String): Pair<String, String>? =
            COORDS.matchEntire(s)?.let { it.groupValues[1] to it.groupValues[2] }
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE = "com.google.android.youtube"
        const val GEMINI = "com.google.android.apps.bard"
        const val YANDEX_NAVI = "ru.yandex.yandexnavi"
        const val YANDEX_MAPS = "ru.yandex.yandexmaps"
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        val NAV_APPS = listOf(YANDEX_NAVI, YANDEX_MAPS, GOOGLE_MAPS)    }
}
