package com.carvoice.assistant

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/** Данные для «CarVoice Панели»: последняя команда и ответ. Только для приложений с той же подписью. */
class StatusProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor {
        val prefs = Prefs(context!!)
        return MatrixCursor(arrayOf("last_heard", "last_say", "home", "fav_name")).apply {
            addRow(arrayOf(prefs.lastHeard, prefs.lastSay, prefs.home, prefs.favName))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
