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

    /** Ждёт, пока откроется приложение [pkg], и нажимает в нём кнопку микрофона. */
    fun clickVoiceButton(pkg: String, timeoutMs: Long = 7000, onResult: (Boolean) -> Unit = {}) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val attempt = object : Runnable {
            override fun run() {
                val root = rootInActiveWindow
                if (root != null && root.packageName?.toString() == pkg) {
                    val node = findVoiceNode(root)
                    if (node != null && clickNodeOrParent(node)) {
                        Log.i(TAG, "Voice button clicked in $pkg")
                        onResult(true)
                        return
                    }
                }
                if (System.currentTimeMillis() < deadline) {
                    handler.postDelayed(this, 400)
                } else {
                    Log.w(TAG, "Voice button not found in $pkg")
                    onResult(false)
                }
            }
        }
        handler.postDelayed(attempt, 800)
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
                if (root != null && root.packageName?.toString() == pkg) {
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
                if (root != null && root.packageName?.toString() == pkg) {
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

        private val START_LABELS = listOf("Поехали", "Едем", "В путь", "Начать", "Go", "Start")
        private val ROUTE_LABELS = listOf("Маршрут", "Маршрут сюда", "Route", "Directions")

        private val ID_HINTS =listOf("voice", "mic", "speech", "dictat", "alice", "assistant_button")
        private val TEXT_HINTS = listOf(
            "голос", "микрофон", "алиса", "скажите", "voice", "microphone", "mic", "speak", "speech", "dictat",
            "gemini live",
        )
        private val NEGATIVE = listOf("voicemail", "голосовое сообщение", "голосовые сообщения", "voice message")
    }
}
