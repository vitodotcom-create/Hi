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

        private val ID_HINTS = listOf("voice", "mic", "speech", "dictat", "alice", "assistant_button")
        private val TEXT_HINTS = listOf(
            "голос", "микрофон", "алиса", "скажите", "voice", "microphone", "mic", "speak", "speech", "dictat",
            "gemini live",
        )
        private val NEGATIVE = listOf("voicemail", "голосовое сообщение", "голосовые сообщения", "voice message")
    }
}
