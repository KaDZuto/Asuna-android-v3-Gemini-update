package com.vectorheart.asuna.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.vectorheart.asuna.llm.LlmRepository
import com.vectorheart.asuna.stt.SttManager
import com.vectorheart.asuna.tts.TtsManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground Service для фоновой работы Асуны.
 *
 * Тип сервиса — `microphone` (Android 14+ требует явного foregroundServiceType
 * для RECORD_AUDIO). Внутри: SttManager (распознавание голоса в фоне),
 * LlmRepository (история диалога), TtsManager (озвучка smalltalk).
 *
 * Stubs-реализация методов появится в следующих тасках. Здесь только каркас,
 * чтобы манифест был валиден.
 */
@AndroidEntryPoint
class AsunaCompanionService : Service() {

    @Inject lateinit var sttManager: SttManager
    @Inject lateinit var llmRepository: LlmRepository
    @Inject lateinit var ttsManager: TtsManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: поднять foreground notification, инициализировать STT/LLM/TTS,
        // запустить periodic smalltalk через WorkManager.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
