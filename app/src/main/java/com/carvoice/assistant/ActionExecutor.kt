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
        DebugLog.add(context, "▶ Действие: ${a.type} target=${a.target} query=${a.query} value=${a.value}")
        return when (a.type) {
            "volume" -> { volume(a.value); false }
            "media" -> { media(a.value); false }
            "play_music" -> playMusic(a.target, a.query)
            "wifi" -> wifi()
            "navigate" -> navigate(a.target, a.query, via = a.value == "via")
            "open_app" -> openApp(a.target, a.value == "voice")
            "app_task" -> appTask(a.target, a.query)
            "split_screen" -> splitScreen(a.target, a.query, a.value)
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
        if (query.trim().lowercase() == "@liked") return playLiked()
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

    /** «Любимые треки» в Spotify: открыть плейлист и нажать Play (автопилот). */
    private fun playLiked(): Boolean {
        if (!start(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:collection:tracks")).setPackage(SPOTIFY))) {
            throw UserMessage("Spotify не установлен")
        }
        if (VoiceAccessibilityService.isEnabled) {
            val appContext = context.applicationContext
            AppScope.scope.launch {
                val ok = UiPilot(appContext).run(
                    SPOTIFY,
                    "Включить воспроизведение плейлиста «Любимые треки» (нажать Play). Если уже играет — ничего не делать.",
                    UiPilot.playFastPath,
                    maxSteps = 3,
                    timeoutMs = 20000,
                )
                DebugLog.add(appContext, if (ok) "🎵 Любимые треки: играет" else "🎵 Любимые треки: не нажал Play")
            }
        }
        return true
    }

    /**
     * Wi-Fi: Android 13 не даёт приложениям включать Wi-Fi напрямую — открываем окно Wi-Fi
     * и, если он выключен, нажимаем переключатель через спецвозможности.
     */
    private fun wifi(): Boolean {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        // Полный экран Wi-Fi в настройках (мини-окно Wi-Fi на этом боксе пустое).
        val opened = start(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)) ||
            start(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
        if (!opened) throw UserMessage("Не удалось открыть настройки Wi-Fi")
        if (wm.isWifiEnabled || !VoiceAccessibilityService.isEnabled) return true
        val appContext = context.applicationContext
        AppScope.scope.launch {
            val fast: (List<UiButton>, Set<String>) -> FastClick? = { buttons, clicked ->
                if (wm.isWifiEnabled) null
                else buttons.firstOrNull { b ->
                    val l = b.label.lowercase()
                    (l.contains("wi-fi") || l.contains("wifi") || l.contains("wi‑fi")) && l !in clicked
                }?.let { FastClick(it, final = true) }
            }
            val ok = UiPilot(appContext).run(
                "com.android.settings",
                "Включить Wi-Fi (переключатель Wi-Fi). Если Wi-Fi уже включён — done.",
                fast, maxSteps = 2, timeoutMs = 15000,
            )
            DebugLog.add(appContext, "📶 Wi-Fi: ${if (wm.isWifiEnabled) "включён" else "не удалось включить"} (автопилот $ok)")
        }
        return true
    }

    private fun navigate(target: String, rawQuery: String, via: Boolean): Boolean {
        val prefs = Prefs(context)
        val query = when (rawQuery.trim().lowercase()) {
            "@home" -> prefs.home.ifBlank { throw UserMessage("Адрес дома не указан в настройках CarVoice") }
            "@work" -> prefs.work.ifBlank { throw UserMessage("Адрес работы не указан в настройках CarVoice") }
            "@fav" -> prefs.fav.ifBlank {
                // Координаты попали в поле названия — используем их.
                prefs.favName.takeIf { parseCoords(it) != null }
                    ?: throw UserMessage("Избранное место не указано в настройках CarVoice")
            }
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
            val place = when (rawQuery.trim().lowercase()) {
                "@home" -> "дом"
                "@work" -> "работа"
                "@fav" -> prefs.favName.ifBlank { "избранное" }
                else -> query
            }
            val goal = if (via) {
                "Добавить промежуточную остановку «$place» в текущий маршрут (заехать по пути) и продолжить ведение."
            } else {
                "Построить новый маршрут до «$place» (вместо текущего, если он есть) и запустить ведение по маршруту кнопкой «Поехали». Если найдено несколько мест — выбрать первое подходящее."
            }
            val appContext = context.applicationContext
            AppScope.scope.launch {
                val ok = UiPilot(appContext).run(pkg, goal, UiPilot.navigationFastPath(via))
                if (!ok) UiPilot.toast(appContext, "CarVoice: не удалось построить маршрут до «$place». Попробуйте назвать адрес иначе.")
            }
        }
        return true
    }

    private fun openApp(pkg: String, voice: Boolean): Boolean =
        if (voice) appTask(pkg, "включить голосовой режим или голосовой чат; если его нет — голосовой ввод или голосовой поиск (микрофон)")
        else launchPackage(pkg)

    /**
     * Открыть приложение и выполнить в нём задачу [goal] (например «включить Gemini Live»).
     * Дальше «автопилот» смотрит на кнопки на экране, а Claude решает, что нажать, шаг за шагом.
     */
    private fun appTask(pkg: String, goal: String): Boolean {
        val g = goal.lowercase()
        val wantsVoice = listOf("голос", "live", "лайв", "микрофон", "voice").any { g.contains(it) }
        // Gemini: голосовой режим открывается ссылкой (как в flow Automate), затем автопилот жмёт кнопку.
        val launched = (pkg == GEMINI && wantsVoice &&
            start(Intent(Intent.ACTION_VIEW, Uri.parse("googleassistant://gemini-live")).setPackage(GEMINI))) ||
            launchPackage(pkg)
        if (!launched) return false
        if (!VoiceAccessibilityService.isEnabled) {
            throw UserMessage("Включите спецвозможности для CarVoice, чтобы я мог нажимать кнопки в приложениях")
        }
        val appContext = context.applicationContext
        DebugLog.add(appContext, "🎯 Задача в $pkg: $goal")
        AppScope.scope.launch {
            val fast: (List<UiButton>, Set<String>) -> FastClick? =
                if (wantsVoice) UiPilot.voiceFastPath else { _, _ -> null }
            val done = UiPilot(appContext).run(pkg, "В этом приложении: $goal.", fast, maxSteps = 6, timeoutMs = 40000)
            DebugLog.add(appContext, if (done) "✅ Задача выполнена" else "⚠ Задача не выполнена")
            if (!done) UiPilot.toast(appContext, "CarVoice: не получилось — $goal")
            if (!done) VoiceAccessibilityService.instance?.reportButtons(pkg)
        }
        return true
    }

    /**
     * Разделить экран: [first] слева/сверху, [second] рядом. value=off — убрать разделение.
     * Работает, если прошивка бокса поддерживает режим разделения экрана Android.
     */
    private fun splitScreen(first: String, second: String, value: String): Boolean {
        val svc = VoiceAccessibilityService.instance
            ?: throw UserMessage("Включите спецвозможности для CarVoice, чтобы делить экран")
        if (value == "off") {
            exitSplit(svc, first)
            return false
        }
        if (first.isBlank() || second.isBlank()) throw UserMessage("Назовите два приложения для разделения экрана")
        if (!launchPackage(first)) throw UserMessage("Не удалось открыть первое приложение")
        val appContext = context.applicationContext
        AppScope.scope.launch {
            delay(2500)
            val ok = VoiceAccessibilityService.instance?.toggleSplitScreen() == true
            DebugLog.add(appContext, "🪟 Разделение экрана: $first + $second, системный ответ=$ok")
            if (!ok) {
                // Способ, который работает на боксе: через меню недавних приложений («Приложение слева»).
                if (splitViaRecents(appContext, first, second)) return@launch
                // Окна произвольной формы не используем: Android запоминает такие окна,
                // и приложения потом открываются маленькими. Только разделение через недавние.
                UiPilot.toast(appContext, "CarVoice: не получилось разделить экран")
                return@launch
            }
            delay(1500)
            val intent = appContext.packageManager.getLaunchIntentForPackage(second)
            if (intent == null) {
                UiPilot.toast(appContext, "CarVoice: не нашёл второе приложение")
                return@launch
            }
            try {
                appContext.startActivity(
                    intent.addFlags(
                        Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                    )
                )
            } catch (e: Exception) {
                DebugLog.add(appContext, "🪟 Не удалось открыть второе приложение: ${e.message}")
            }
        }
        return true
    }

    /**
     * Разделение экрана как руками: недавние приложения → иконка первого приложения →
     * «Приложение слева» → выбрать второе приложение.
     */
    private suspend fun splitViaRecents(ctx: Context, first: String, second: String): Boolean {
        val svc = VoiceAccessibilityService.instance ?: return false
        val pm = ctx.packageManager
        // Подписи приложения: название из ярлыка на рабочем столе и общее название.
        fun labels(pkg: String): List<String> = buildList {
            try {
                pm.getLaunchIntentForPackage(pkg)?.let { pm.resolveActivity(it, 0)?.loadLabel(pm)?.toString() }
                    ?.let { add(it) }
            } catch (_: Exception) {
            }
            try {
                add(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
            } catch (_: Exception) {
            }
        }.distinct().ifEmpty { listOf(pkg) }
        val namesA = labels(first)
        val namesB = labels(second)
        val labelA = namesA.first()
        val labelB = namesB.first()
        fun step(msg: String) = DebugLog.add(ctx, "🪟 Недавние: $msg")

        // Оба приложения должны быть в недавних, первое — последним открытым.
        pm.getLaunchIntentForPackage(second)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        delay(2500)
        pm.getLaunchIntentForPackage(first)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        delay(2500)
        if (!svc.recents()) { step("не открылись"); return false }
        // Ждём, пока список недавних действительно появится (до 6 секунд).
        run {
            val until = System.currentTimeMillis() + 6000
            delay(1200)
            while (!svc.recentsVisible() && System.currentTimeMillis() < until) delay(500)
            step(if (svc.recentsVisible()) "список открыт" else "не дождался карточек")
        }

        // Логотип первого приложения (с прокруткой карточек, как вручную).
        val icon = findWithScroll(svc, namesA, largest = false)
        if (icon == null || !svc.click(icon)) {
            step("не нашёл иконку «$labelA». Экран:\n${svc.dumpScreen()}")
            return false
        }
        step("нажал иконку «${icon.contentDescription ?: icon.text ?: icon.viewIdResourceName}»")
        delay(1500)

        val splitItem = svc.findOnScreen(SPLIT_MENU_LABELS, contains = true, largest = false)
        if (splitItem == null || !svc.click(splitItem)) {
            step("нет пункта «Приложение слева». Экран:\n${svc.dumpScreen()}")
            return false
        }
        step("нажал «${splitItem.text ?: splitItem.contentDescription}»")
        delay(2000)

        // Второе приложение: нажимаем его логотип (как вручную), при необходимости прокручиваем.
        val other = findWithScroll(svc, namesB, largest = false)
        if (other == null || !svc.click(other)) {
            step("не нашёл «$labelB» для правой половины. Экран:\n${svc.dumpScreen()}")
            return false
        }
        step("выбрал «$labelB» — готово")
        Prefs(ctx).lastSplitLeft = first
        Prefs(ctx).lastSplitRight = second
        return true
    }

    /**
     * Убрать разделение экрана, оставив на весь экран [keep] (или левое приложение).
     * Сначала тянем разделитель к краю; если не вышло — домой и открываем приложение заново.
     */
    private fun exitSplit(svc: VoiceAccessibilityService, keep: String) {
        val prefs = Prefs(context)
        val keepPkg = keep.ifBlank { prefs.lastSplitLeft }
        val keepLeft = keepPkg.isBlank() || keepPkg != prefs.lastSplitRight
        val appContext = context.applicationContext
        AppScope.scope.launch {
            delay(800)
            val dragged = svc.dragDivider(keepLeft)
            DebugLog.add(appContext, "🪟 На весь экран: тяну разделитель (${if (keepLeft) "вправо" else "влево"}) — $dragged, окон: ${svc.appWindowBounds().size}")
            delay(2000)
            if (svc.appWindowBounds().size >= 2 && keepPkg.isNotBlank()) {
                // Запасной способ: на рабочий стол и открыть нужное приложение.
                svc.home()
                delay(1200)
                appContext.packageManager.getLaunchIntentForPackage(keepPkg)?.let {
                    appContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                DebugLog.add(appContext, "🪟 На весь экран: открыл $keepPkg заново")
            }
        }
    }

    /** Найти элемент по названию, прокручивая список вперёд, а потом назад. */
    private suspend fun findWithScroll(
        svc: VoiceAccessibilityService,
        names: List<String>,
        largest: Boolean,
    ): android.view.accessibility.AccessibilityNodeInfo? {
        fun find() = svc.findRecentsIcon(names)
            ?: svc.findOnScreen(names, contains = false, largest = largest)
            ?: svc.findOnScreen(names, contains = true, largest = largest)
        find()?.let { return it }
        repeat(5) {
            if (!svc.scrollScreen(forward = true)) return@repeat
            delay(900)
            find()?.let { return it }
        }
        repeat(8) {
            if (!svc.scrollScreen(forward = false)) return@repeat
            delay(900)
            find()?.let { return it }
        }
        return null
    }

    /** Открыть два приложения окнами: левая и правая половина экрана. */
    private suspend fun launchFreeformPair(ctx: Context, first: String, second: String): Boolean {
        val pm = ctx.packageManager
        val hasFreeform = pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FREEFORM_WINDOW_MANAGEMENT) ||
            android.provider.Settings.Global.getInt(ctx.contentResolver, "enable_freeform_support", 0) == 1
        // Некоторые прошивки не сообщают о поддержке окон — пробуем открыть в любом случае.
        DebugLog.add(ctx, "🪟 Поддержка окон по данным системы: $hasFreeform")
        val metrics = ctx.resources.displayMetrics
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val halves = listOf(
            first to android.graphics.Rect(0, 0, w / 2, h),
            second to android.graphics.Rect(w / 2, 0, w, h),
        )
        var okAll = true
        for ((pkg, bounds) in halves) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            val options = android.app.ActivityOptions.makeBasic().setLaunchBounds(bounds)
            // Явно просим режим «окно произвольной формы» (WINDOWING_MODE_FREEFORM = 5).
            // Это скрытый метод Android — если прошивка его не даёт, просто пишем в журнал.
            try {
                android.app.ActivityOptions::class.java
                    .getMethod("setLaunchWindowingMode", Int::class.javaPrimitiveType)
                    .invoke(options, 5)
            } catch (e: Throwable) {
                DebugLog.add(ctx, "🪟 Режим окна недоступен: ${e.javaClass.simpleName}")
            }
            try {
                ctx.startActivity(intent, options.toBundle())
            } catch (e: Exception) {
                DebugLog.add(ctx, "🪟 Окно $pkg не открылось: ${e.message}")
                okAll = false
            }
            delay(800)
        }
        return okAll
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
            "recents" -> if (svc?.recents() != true) throw UserMessage("Не удалось открыть недавние приложения")
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

        private val NUMBER = Regex("""-?\d{1,3}[.,]\d{3,}""")

        /** Координаты в любом виде: «40.1770, 44.4397», «40,1770, 44,4397», «40.1770 44.4397»; иначе адрес. */
        fun parseCoords(s: String): Pair<String, String>? {
            val nums = NUMBER.findAll(s).map { it.value.replace(',', '.') }.toList()
            if (nums.size != 2) return null
            val lat = nums[0].toDoubleOrNull() ?: return null
            val lon = nums[1].toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return nums[0] to nums[1]
        }
        const val SPOTIFY = "com.spotify.music"
        const val YOUTUBE = "com.google.android.youtube"
        const val GEMINI = "com.google.android.apps.bard"
        const val YANDEX_NAVI = "ru.yandex.yandexnavi"
        const val YANDEX_MAPS = "ru.yandex.yandexmaps"
        const val GOOGLE_MAPS = "com.google.android.apps.maps"
        val NAV_APPS = listOf(YANDEX_NAVI, YANDEX_MAPS, GOOGLE_MAPS)
        val SPLIT_MENU_LABELS = listOf(
            "приложение слева", "приложение сверху", "разделить экран", "разделённый экран",
            "split screen", "split left", "split top",
        )    }
}
