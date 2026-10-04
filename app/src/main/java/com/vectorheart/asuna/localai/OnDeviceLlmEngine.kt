package com.vectorheart.asuna.localai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Встроенный on-device инференс (Google AI Edge / LiteRT, MediaPipe GenAI).
 * Прямый порт подхода из google-ai-edge/gallery: модель .task (Gemma и др.)
 * исполняется прямо на CPU/GPU телефона, без Termux/внешнего сервера.
 */
@Singleton
class OnDeviceLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var inference: LlmInference? = null
    private var loadedPath: String? = null

    val isLoaded: Boolean get() = inference != null
    val loadedModelPath: String? get() = loadedPath

    /** Поднимает модель, если ещё не поднята. */
    @Synchronized
    fun ensureLoaded(modelPath: String, maxTokens: Int = 1024) {
        if (inference != null && loadedPath == modelPath) return
        close()
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(40)
                .build()
            inference = LlmInference.createFromOptions(context, options)
            loadedPath = modelPath
            Log.d(TAG, "OnDevice model loaded: $modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load on-device model: ${e.message}", e)
            inference = null
            loadedPath = null
            throw RuntimeException("Не удалось загрузить on-device модель: ${e.message}", e)
        }
    }

    /** Синхронная генерация ответа. */
    @Synchronized
    fun generate(prompt: String): String {
        val engine = inference ?: throw IllegalStateException("On-device модель не загружена")
        val sb = StringBuilder()
        try {
            val result = engine.generateResponse(prompt)
            sb.append(result)
        } catch (e: Exception) {
            Log.e(TAG, "OnDevice generation failed: ${e.message}", e)
            throw RuntimeException("Ошибка on-device инференса: ${e.message}", e)
        }
        return sb.toString().trim()
    }

    @Synchronized
    fun close() {
        try {
            inference?.close()
        } catch (_: Exception) {}
        inference = null
        loadedPath = null
    }

    companion object {
        private const val TAG = "OnDeviceLlmEngine"
    }
}
