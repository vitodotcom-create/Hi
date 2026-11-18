package com.carvoice.desktop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * После включения бокса открывает рабочий стол поверх рабочего стола системы.
 * Android разрешает открыть окно из фона, только если включено «Поверх других приложений».
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Settings.canDrawOverlays(context)) return
        val pending = goAsync()
        val app = context.applicationContext
        // Даём системе дорисовать свой рабочий стол, затем открываемся поверх.
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                app.startActivity(
                    Intent(app, DesktopActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                )
            } catch (_: Exception) {
            }
            pending.finish()
        }, 7000)
    }
}
