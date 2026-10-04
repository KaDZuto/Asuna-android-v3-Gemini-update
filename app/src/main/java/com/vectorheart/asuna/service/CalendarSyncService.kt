package com.vectorheart.asuna.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground Service для синхронизации Google Calendar.
 *
 * Запускается WorkManager-джобой каждые 30 минут.
 * Тип — `dataSync` (не нагружает батарею как microphone).
 *
 * Stubs-реализация: методы добавятся в тасках calendar/.
 */
class CalendarSyncService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
