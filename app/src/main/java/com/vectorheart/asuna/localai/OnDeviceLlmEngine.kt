package com.vectorheart.asuna.localai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Встроенный on-device инференс (Google AI Edge / LiteRT, MediaPipe GenAI).
 * Прямой порт подхода из google-ai-edge/gallery: модель .task (Qwen/Gemma/Phi)
 * исполняется прямо на CPU/GPU телефона, без Termux и внешнего сервера.
 *
 * Диалог ведётся через [LlmInferenceSession]: движок сам хранитKV-кэш истории,
 * поэтому мы не пересылаем всю переписку каждый раз — это и быстрее, и честнее
 * по контексту. Системный промпт (личность + память) уходит один раз за диалог.
 */
@Singleton
class OnDeviceLlmEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var inference: LlmInference? = null
    private var session: LlmInferenceSession? = null
    private var loadedPath: String? = null
    private var contextInjected = false
    private var maxContextTokens = 2048

    val isLoaded: Boolean get() = session != null
    val loadedModelPath: String? get() = loadedPath

    /** Поднимает модель и создаёт сессию диалога, если ещё не поднята. */
    @Synchronized
    fun ensureLoaded(
        modelPath: String,
        maxTokens: Int = 1024,
        maxContextTokens: Int = 2048,
        temperature: Float = 0.85f
    ) {
        if (session != null && loadedPath == modelPath) return
        close()
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(maxTokens)
                .setMaxTopK(40)
                .build()
            val inf = LlmInference.createFromOptions(context, options)
            inference = inf
            loadedPath = modelPath
            this.maxContextTokens = maxContextTokens
            openSession(temperature)
            Log.d(TAG, "OnDevice model loaded: $modelPath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load on-device model: ${e.message}", e)
            inference = null
            session = null
            loadedPath = null
            throw RuntimeException("Не удалось загрузить on-device модель: ${e.message}", e)
        }
    }

    @Synchronized
    private fun openSession(temperature: Float = 0.85f) {
        val inf = inference ?: return
        try {
            session?.close()
            session = LlmInferenceSession.createFromOptions(
                inf,
                LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(20)
                    .setTopP(0.95f)
                    .setTemperature(temperature)
                    .build()
            )
            contextInjected = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create session: ${e.message}", e)
            session = null
            throw RuntimeException("Не удалось создать сессию on-device: ${e.message}", e)
        }
    }

    /** Начинает новый диалог: следующий запрос пойдёт без старой истории. */
    @Synchronized
    fun resetConversation() {
        if (inference != null) {
            try {
                openSession()
            } catch (e: Exception) {
                Log.w(TAG, "resetConversation failed: ${e.message}")
            }
        }
    }

    /** Сколько токенов займёт текст в контексте этой модели (0 — неизвестно). */
    fun countTokens(text: String): Int = try {
        inference?.sizeInTokens(text) ?: 0
    } catch (_: Exception) {
        0
    }

    val contextLimit: Int get() = maxContextTokens

    /**
     * Один ход диалога с потоковой выдачей. [onPartial] вызывается на главном потоке.
     * Системный промпт подставляется автоматически (один раз за диалог).
     */
    suspend fun chat(
        userText: String,
        systemPrompt: String,
        onPartial: ((String) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val s = session ?: throw IllegalStateException("On-device модель не загружена — сначала выбери .task модель")

        if (!contextInjected && systemPrompt.isNotBlank()) {
            s.addQueryChunk(systemPrompt)
            contextInjected = true
        }
        s.addQueryChunk(userText)

        val chunks = Channel<String>(Channel.UNLIMITED)
        val pump = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            for (partial in chunks) onPartial?.invoke(partial)
        }

        val collected = StringBuilder()
        try {
            val future = s.generateResponseAsync(ProgressListener<String> { partial, _ ->
                if (!partial.isNullOrEmpty()) {
                    synchronized(collected) { collected.append(partial) }
                    chunks.trySend(partial)
                }
            })
            val full = awaitListenable(future)
            synchronized(collected) { collected.toString() }.ifBlank { full }
        } catch (e: Exception) {
            Log.e(TAG, "OnDevice generation failed: ${e.message}", e)
            val partialText = synchronized(collected) { collected.toString() }
            if (partialText.isNotBlank()) partialText
            else throw RuntimeException("Ошибка on-device инференса: ${e.message}", e)
        } finally {
            chunks.close()
            pump.cancel()
        }
    }

    /** Мост Guava ListenableFuture -> корутина (kotlinx-coroutines-play-services умеет только Task). */
    private suspend fun <T> awaitListenable(future: com.google.common.util.concurrent.ListenableFuture<T>): T =
        suspendCancellableCoroutine { cont ->
            com.google.common.util.concurrent.Futures.addCallback(
                future,
                object : com.google.common.util.concurrent.FutureCallback<T> {
                    override fun onSuccess(result: T) {
                        if (cont.isActive) cont.resume(result)
                    }

                    override fun onFailure(t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    }
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
            cont.invokeOnCancellation { try { future.cancel(true) } catch (_: Exception) {} }
        }

    /** Прерывает текущую генерацию (например, пользователь заговорил). */
    fun cancel() {
        try {
            session?.cancelGenerateResponseAsync()
        } catch (_: Exception) {
        }
    }

    /** Простой однострочный вызов без сессии (для суммаризации, тестов). */
    @Synchronized
    fun generateOnce(prompt: String): String {
        val engine = inference ?: throw IllegalStateException("On-device модель не загружена")
        return try {
            engine.generateResponse(prompt).trim()
        } catch (e: Exception) {
            Log.e(TAG, "OnDevice generation failed: ${e.message}", e)
            throw RuntimeException("Ошибка on-device инференса: ${e.message}", e)
        }
    }

    @Synchronized
    fun close() {
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            inference?.close()
        } catch (_: Exception) {
        }
        session = null
        inference = null
        loadedPath = null
        contextInjected = false
    }

    companion object {
        private const val TAG = "OnDeviceLlmEngine"
    }
}
