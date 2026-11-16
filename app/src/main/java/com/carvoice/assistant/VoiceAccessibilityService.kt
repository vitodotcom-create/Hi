package com.carvoice.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Служба специальных возможностей:
 *  - находит и нажимает кнопку голосового ввода (микрофон) в открытом приложении;
 *  - выполняет «Назад» и «Домой»;
 *  - разрешает приложению запускать другие приложения из фона (так устроен Android).
 */
class VoiceAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)

    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun allRoots(): List<AccessibilityNodeInfo> = buildList {
        rootInActiveWindow?.let { add(it) }
        try {
            windows.forEach { w -> w.root?.let { add(it) } }
        } catch (_: Exception) {
        }
    }

    /** Иконка приложения ближе всего к центру экрана (карточка последнего приложения в недавних). */
    fun centerIcon(): AccessibilityNodeInfo? {
        val w = resources.displayMetrics.widthPixels
        val rect = android.graphics.Rect()
        var best: AccessibilityNodeInfo? = null
        var bestDist = Int.MAX_VALUE
        for (root in allRoots()) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 3000) {
                val n = queue.removeFirst()
                visited++
                val id = n.viewIdResourceName.orEmpty()
                if (n.isVisibleToUser && (id.endsWith(":id/icon") || id.endsWith("task_icon"))) {
                    n.getBoundsInScreen(rect)
                    val dist = kotlin.math.abs(rect.centerX() - w / 2)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = n
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    /** Всё, что видно на экране (все окна), — для журнала. */
    fun dumpScreen(): String {
        val lines = mutableListOf<String>()
        val rect = android.graphics.Rect()
        for (root in allRoots()) {
            lines += "== окно ${root.packageName}"
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 1500 && lines.size < 80) {
                val n = queue.removeFirst()
                visited++
                val d = n.contentDescription?.toString().orEmpty()
                val t = n.text?.toString().orEmpty().take(40)
                val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
                if (n.isVisibleToUser && (d.isNotBlank() || t.isNotBlank() || id.isNotBlank())) {
                    n.getBoundsInScreen(rect)
                    lines += "${if (n.isClickable) "[btn]" else "[ ]"} desc=$d | text=$t | id=$id | ${rect.toShortString()}"
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return lines.joinToString("\n")
    }

    /**
     * Найти элемент на экране (в любом окне, включая системные: список недавних, меню)
     * по подписи. [contains] — подпись содержит одно из слов; иначе точное совпадение.
     * [largest] — из нескольких выбрать самый большой (карточка), иначе самый маленький (иконка).
     */
    fun findOnScreen(labels: List<String>, contains: Boolean, largest: Boolean): AccessibilityNodeInfo? {
        val wanted = labels.map { it.trim().lowercase() }
        val roots = buildList {
            rootInActiveWindow?.let { add(it) }
            try {
                windows.forEach { w -> w.root?.let { add(it) } }
            } catch (_: Exception) {
            }
        }
        var best: AccessibilityNodeInfo? = null
        var bestArea = if (largest) -1L else Long.MAX_VALUE
        val rect = android.graphics.Rect()
        for (root in roots) {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < 3000) {
                val n = queue.removeFirst()
                visited++
                if (n.isVisibleToUser) {
                    val t = n.text?.toString()?.trim()?.lowercase().orEmpty()
                    val d = n.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    val match = wanted.any { w ->
                        if (contains) (t.isNotEmpty() && t.contains(w)) || (d.isNotEmpty() && d.contains(w))
                        else t == w || d == w
                    }
                    if (match) {
                        n.getBoundsInScreen(rect)
                        val area = rect.width().toLong() * rect.height()
                        if ((largest && area > bestArea) || (!largest && area < bestArea)) {
                            best = n
                            bestArea = area
                        }
                    }
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
            }
        }
        return best
    }

    /** Включить/выключить разделение экрана для текущего приложения. */
    fun toggleSplitScreen() = performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)

    /** Ждёт, пока откроется приложение [pkg], и нажимает в нём кнопку микрофона. */
    fun clickVoiceButton(pkg: String, timeoutMs: Long = 12000, onResult: (Boolean) -> Unit = {}) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val attempt = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow
                if (root != null && isApp(root, pkg)) {
                    val node = findVoiceNode(root)
                    if (node != null && clickNodeOrParent(node)) {
                        Log.i(TAG, "Voice button clicked in $pkg")
                        DebugLog.add(this@VoiceAccessibilityService, "🔘 Кнопка голоса в $pkg: «${node.contentDescription ?: node.text ?: node.viewIdResourceName}»; все кнопки: ${snapshot(pkg)?.map { it.label }}")
                        onResult(true)
                        return
                    }
                }
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 400)
                } else {
                    Log.w(TAG, "Voice button not found in $pkg")
                    DebugLog.add(this@VoiceAccessibilityService, "⚠ Кнопка голоса не найдена в $pkg (на экране: ${rootInActiveWindow?.packageName})")
                    onResult(false)
                }
            }
        }
        handler.postDelayed(attempt, 1200)
    }

    /**
     * Ждёт появления в приложении [pkg] кнопки с одной из подписей [labels]
     * (например «Поехали» после построения маршрута) и нажимает её.
     */
    fun clickButtonWithText(
        pkg: String,
        labels: List<String>,
        timeoutMs: Long = 15000,
        onResult: (Boolean) -> Unit = {},
    ) {
        val wanted = labels.map { it.lowercase() }
        val deadline = System.currentTimeMillis() + timeoutMs
        val attempt = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow
                if (root != null && isApp(root, pkg)) {
                    val node = findByText(root, wanted)
                    if (node != null && clickNodeOrParent(node)) {
                        Log.i(TAG, "Clicked '${node.text ?: node.contentDescription}' in $pkg")
                        onResult(true)
                        return
                    }
                }
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 500)
                } else {
                    Log.w(TAG, "Button $labels not found in $pkg")
                    onResult(false)
                }
            }
        }
        handler.postDelayed(attempt, 1500)
    }

    /**
     * Запуск ведения по маршруту в Яндекс Картах/Навигаторе:
     * если открылась карточка места — жмём «Маршрут», затем жмём «Поехали».
     */
    fun startRoute(pkg: String, timeoutMs: Long = 20000) {
        val go = START_LABELS.map { it.lowercase() }
        val route = ROUTE_LABELS.map { it.lowercase() }
        val deadline = System.currentTimeMillis() + timeoutMs
        var routePressed = false
        val attempt = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow
                if (root != null && isApp(root, pkg)) {
                    val goNode = findByText(root, go)
                    if (goNode != null && clickNodeOrParent(goNode)) {
                        Log.i(TAG, "Route started in $pkg")
                        return
                    }
                    if (!routePressed) {
                        val routeNode = findByText(root, route)
                        if (routeNode != null && clickNodeOrParent(routeNode)) {
                            Log.i(TAG, "Pressed 'Маршрут' in $pkg")
                            routePressed = true
                        }
                    }
                }
                if (System.currentTimeMillis() < deadline) handler.postDelayed(this, 500)
                else Log.w(TAG, "Could not start route in $pkg")
            }
        }
        handler.postDelayed(attempt, 1500)
    }

    /** Список нажимаемых кнопок с подписями в приложении [pkg] (null — приложение не на экране). */
    fun snapshot(pkg: String): List<UiButton>? {
        val root = rootInActiveWindow ?: return null
        if (!isApp(root, pkg)) return null
        val result = mutableListOf<UiButton>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000 && result.size < 70) {
            val n = queue.removeFirst()
            visited++
            if (n.isClickable && n.isEnabled && n.isVisibleToUser) {
                val label = labelOf(n)
                if (label.isNotBlank() && seen.add(label.lowercase())) {
                    result += UiButton(result.size, label, n)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        return result
    }

    fun click(node: AccessibilityNodeInfo): Boolean = clickNodeOrParent(node)

    /** Какое приложение сейчас на экране. */
    fun foregroundPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /**
     * На экране ли приложение [pkg]? Веб-приложения (WebAPK, например Claude из Chrome)
     * показываются системой как сам Chrome, поэтому их сравниваем с браузером.
     */
    private fun isApp(root: AccessibilityNodeInfo, pkg: String): Boolean {
        val onScreen = root.packageName?.toString() ?: return false
        if (onScreen == pkg) return true
        if (ALIASES[pkg]?.contains(onScreen) == true) return true
        return pkg.startsWith("org.chromium.webapk") && onScreen in BROWSERS
    }

    /** Подпись кнопки: её текст/описание или текст первого вложенного элемента. */
    private fun labelOf(node: AccessibilityNodeInfo, depth: Int = 0): String {
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(60) }
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it.take(60) }
        if (depth < 3) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val l = labelOf(child, depth + 1)
                if (l.isNotEmpty()) return l
            }
        }
        return ""
    }

    /**
     * Кнопку не нашли: копируем список кнопок на экране в буфер обмена,
     * чтобы пользователь мог отправить его разработчику и кнопку добавили точно.
     */
    fun reportButtons(pkg: String) {
        val root = rootInActiveWindow ?: return
        if (!isApp(root, pkg)) {
            DebugLog.add(this, "CarVoice: $pkg уже не на экране (сейчас ${root.packageName}), список кнопок не снят")
            return
        }
        val lines = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 2000 && lines.size < 60) {
            val n = queue.removeFirst()
            visited++
            val desc = n.contentDescription?.toString().orEmpty()
            val text = n.text?.toString().orEmpty().take(40)
            val id = n.viewIdResourceName?.substringAfter(":id/").orEmpty()
            if (n.isVisibleToUser && (desc.isNotBlank() || id.isNotBlank() || (n.isClickable && text.isNotBlank()))) {
                lines += "${if (n.isClickable) "[btn]" else "[ ]"} desc=$desc | text=$text | id=$id"
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
        val report = "CarVoice: кнопки в $pkg\n" + lines.joinToString("\n")
        DebugLog.add(this, report)
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("CarVoice", report))
            android.widget.Toast.makeText(
                this,
                "CarVoice: не нашёл кнопку голоса. Список кнопок скопирован — вставьте и отправьте в чат с Claude.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard failed", e)
        }
    }

    private fun findByText(root: AccessibilityNodeInfo, wanted: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                val text = node.text?.toString()?.trim()?.lowercase()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase()
                if (wanted.any { it == text || it == desc }) return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return null
    }

    private fun findVoiceNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var best: AccessibilityNodeInfo? = null
        var bestScore = 0
        var visited = 0
        while (queue.isNotEmpty() && visited < 3000) {
            val node = queue.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                val score = score(node)
                if (score > bestScore) {
                    bestScore = score
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return best
    }

    private fun score(node: AccessibilityNodeInfo): Int {
        val id = node.viewIdResourceName?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()
        val all = "$id $desc $text"
        if (NEGATIVE.any { all.contains(it) }) return 0
        var s = 0
        if (VOICE_CHAT_HINTS.any { desc.contains(it) || text.contains(it) || id.contains(it) }) s += 5
        if (ID_HINTS.any { id.contains(it) }) s += 3
        if (TEXT_HINTS.any { desc.contains(it) }) s += 3
        if (TEXT_HINTS.any { text.contains(it) } && text.length < 40) s += 1
        return s
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 5) {
            if (n.isClickable && n.isEnabled) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
            depth++
        }
        return false
    }

    companion object {
        private const val TAG = "CarVoiceA11y"

        @Volatile
        var instance: VoiceAccessibilityService? = null
            private set

        val isEnabled: Boolean get() = instance != null

        /** Приложения, которые на экране показываются под другим пакетом. */
        private val ALIASES = mapOf(
            "com.google.android.apps.bard" to setOf("com.google.android.googlequicksearchbox"),
        )

        private val BROWSERS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "com.google.android.apps.chrome", "org.chromium.chrome",
        )

        private val START_LABELS = listOf("Поехали", "Едем", "В путь", "Начать", "Go", "Start")
        private val ROUTE_LABELS = listOf("Маршрут", "Маршрут сюда", "Route", "Directions")

        // Голосовой режим/чат важнее обычной диктовки (например, в приложении Claude).
        private val VOICE_CHAT_HINTS = listOf(
            "voice mode", "голосовой режим", "голосовой чат", "голосового режима", "start voice",
            "voice_mode", "voicemode", "voice chat", "talk to", "gemini live",
        )

        private val ID_HINTS = listOf("voice", "mic", "speech", "dictat", "alice", "assistant_button")
        private val TEXT_HINTS = listOf(
            "голос", "микрофон", "алиса", "скажите", "voice", "microphone", "mic", "speak", "speech", "dictat",
            "gemini live",
        )
        private val NEGATIVE = listOf("voicemail", "голосовое сообщение", "голосовые сообщения", "voice message")
    }
}
