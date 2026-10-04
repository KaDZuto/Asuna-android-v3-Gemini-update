package com.vectorheart.asuna.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * STT-движок на базе Android [SpeechRecognizer].
 *
 * Поддерживает:
 *  - Русский язык (`ru-RU`)
 *  - Offline-режим (`EXTRA_PREFER_OFFLINE = true`)
 *
 * API:
 *  - [startListening] — начать распознавание
 *  - [stopListening] — остановить
 *  - [results] — Flow<[Result]> (partial / final)
 *
 * Если [SpeechRecognizer] недоступен (нет Google services), выдаёт ошибку
 * через [results] с type=Error.
 */
@Singleton
class SttEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class Result {
        data class Partial(val text: String) : Result()
        data class Final(val text: String) : Result()
        data class Error(val message: String) : Result()
    }

    private val _results = MutableSharedFlow<Result>(extraBufferCapacity = 8)
    val results: SharedFlow<Result> = _results.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null
    private var listening = false

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    @Synchronized
    fun startListening() {
        if (listening) return
        if (!isAvailable()) {
            _results.tryEmit(Result.Error("SpeechRecognizer недоступен на этом устройстве (нет Google services)"))
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(context)
        rec.setRecognitionListener(listener)
        rec.startListening(intent)
        recognizer = rec
        listening = true
    }

    @Synchronized
    fun stopListening() {
        if (!listening) return
        try {
            recognizer?.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening: ${e.message}")
        }
        listening = false
    }

    @Synchronized
    fun destroy() {
        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        recognizer = null
        listening = false
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "onReadyForSpeech")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            Log.d(TAG, "onEndOfSpeech")
        }
        override fun onError(error: Int) {
            listening = false
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH -> "Не удалось распознать речь"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Таймаут (вы слишком долго молчали)"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения RECORD_AUDIO"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят"
                SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента SpeechRecognizer"
                else -> "SpeechRecognizer error: $error"
            }
            Log.w(TAG, "onError: $msg")
            _results.tryEmit(Result.Error(msg))
        }
        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?: ""
            Log.d(TAG, "onResults: $text")
            _results.tryEmit(Result.Final(text))
        }
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?: ""
            if (text.isNotEmpty()) {
                _results.tryEmit(Result.Partial(text))
            }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        private const val TAG = "SttEngine"
    }
}
