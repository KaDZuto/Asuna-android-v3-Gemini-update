package com.vectorheart.asuna.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin

/**
 * Менеджер синтеза речи (TTS).
 *
 * Поддерживает два режима:
 *  1. [TTS_MODE_SYSTEM] — системный TextToSpeech Android с улучшенным русским языком.
 *  2. [TTS_MODE_SILERO_HTTP] — подключение к HTTP-серверу Silero TTS (голос baya,
 *     совместимо с сервером из ПК-версии hermes_paperclip_agent).
 *
 * При недоступности Silero-сервера автоматически переключается на системный TTS.
 */
@Singleton
class TtsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    val ttsEngine: TtsEngine,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "TtsManager"
        const val TTS_MODE_SYSTEM = "system"
        const val TTS_MODE_SILERO_HTTP = "silero_http"
    }

    private var activeMode: String = TTS_MODE_SYSTEM
    private var sileroServerUrl: String = "http://192.168.1.100:8000"

    private val _events = MutableSharedFlow<TtsEngine.Event>(extraBufferCapacity = 16)
    val events: SharedFlow<TtsEngine.Event> = _events.asSharedFlow()

    private val _mouthLevel = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    val mouthLevel: SharedFlow<Float> = _mouthLevel.asSharedFlow()

    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var lipSyncJob: Job? = null

    init {
        // Пробрасываем события из системного движка
        scope.launch {
            ttsEngine.events.collect { _events.emit(it) }
        }
        scope.launch {
            ttsEngine.mouthLevel.collect { _mouthLevel.emit(it) }
        }
    }

    fun init() {
        ttsEngine.init()
    }

    fun configure(mode: String, serverUrl: String?) {
        activeMode = mode
        if (!serverUrl.isNullOrBlank()) {
            sileroServerUrl = serverUrl.trimEnd('/')
        }
    }

    suspend fun speak(text: String): Boolean = withContext(Dispatchers.IO) {
        val clean = ttsEngine.cleanForSpeech(text)
        if (clean.isBlank()) return@withContext false

        if (activeMode == TTS_MODE_SILERO_HTTP) {
            val ok = speakViaSileroHttp(clean)
            if (ok) return@withContext true
            Log.w(TAG, "Silero HTTP server недоступен, откат на системный TTS")
        }

        withContext(Dispatchers.Main) {
            ttsEngine.speak(clean)
        }
    }

    private suspend fun speakViaSileroHttp(cleanText: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val encoded = URLEncoder.encode(cleanText, "UTF-8")
            val url = "$sileroServerUrl/tts?text=$encoded&voice=baya"

            val req = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Silero server HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val tempAudioFile = File(context.cacheDir, "asuna_tts_temp.wav")
                FileOutputStream(tempAudioFile).use { out ->
                    body.byteStream().copyTo(out)
                }

                playAudioFile(tempAudioFile)
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Silero HTTP speak failed: ${e.message}")
            false
        }
    }

    private suspend fun playAudioFile(file: File) = withContext(Dispatchers.Main) {
        stop()
        try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
            mediaPlayer = player

            val uttId = "silero_${System.currentTimeMillis()}"
            _events.tryEmit(TtsEngine.Event.Start(uttId))
            startLipSync()

            player.setOnCompletionListener {
                stopLipSync()
                _events.tryEmit(TtsEngine.Event.Done(uttId))
                player.release()
                mediaPlayer = null
            }
            player.setOnErrorListener { _, what, extra ->
                stopLipSync()
                _events.tryEmit(TtsEngine.Event.Error(uttId, what))
                player.release()
                mediaPlayer = null
                true
            }
            player.start()
        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio file: ${e.message}", e)
            stopLipSync()
        }
    }

    private fun startLipSync() {
        lipSyncJob?.cancel()
        lipSyncJob = scope.launch {
            var step = 0.0
            while (isActive) {
                val level = (0.45f + 0.35f * sin(step).toFloat()).coerceIn(0.1f, 0.85f)
                _mouthLevel.tryEmit(level)
                step += 0.5
                delay(60)
            }
        }
    }

    private fun stopLipSync() {
        lipSyncJob?.cancel()
        lipSyncJob = null
        _mouthLevel.tryEmit(0.0f)
    }

    fun stop() {
        stopLipSync()
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        ttsEngine.stop()
    }

    fun destroy() {
        stop()
        ttsEngine.destroy()
    }
}
