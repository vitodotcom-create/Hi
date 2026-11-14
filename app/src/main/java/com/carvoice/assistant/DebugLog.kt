package com.carvoice.assistant

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Журнал последних команд (хранится только на устройстве), чтобы разбираться в ошибках. */
object DebugLog {
    private const val MAX_LINES = 300
    private var file: File? = null
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "carvoice_log.txt")
        file = f
        if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
    }

    @Synchronized
    fun add(context: Context, msg: String) {
        init(context)
        Log.i("CarVoiceLog", msg)
        val line = "${time.format(Date())} $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        try {
            file?.writeText(lines.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun text(context: Context): String {
        init(context)
        return lines.joinToString("\n")
    }

    @Synchronized
    fun clear(context: Context) {
        init(context)
        lines.clear()
        file?.delete()
    }
}
