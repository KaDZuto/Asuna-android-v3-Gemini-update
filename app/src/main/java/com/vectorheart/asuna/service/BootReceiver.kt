package com.vectorheart.asuna.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Запускает AsunaCompanionService после загрузки устройства.
 *
 * Stubs-реализация: проверка настроек "запускать после загрузки" будет добавлена.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // TODO: если в настройках включён "autoStart", запустить AsunaCompanionService
    }
}
