package com.carvoice.panel

import android.service.notification.NotificationListenerService

/**
 * Нужен только для того, чтобы панель видела, что играет (исполнитель, песня, обложка).
 * Включается один раз: Настройки → Доступ к уведомлениям → «CarVoice Панель».
 */
class MediaListenerService : NotificationListenerService()
