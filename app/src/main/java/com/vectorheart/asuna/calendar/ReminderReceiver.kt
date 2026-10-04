package com.vectorheart.asuna.calendar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Срабатывает по AlarmManager для напоминаний о событиях.
 *
 * Stubs-реализация: показать уведомление + TTS "Асуна напомнит..." будет в тасках calendar/.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // TODO: показать foreground notification + воспроизвести TTS
    }
}
