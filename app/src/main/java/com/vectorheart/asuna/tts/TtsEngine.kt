package com.vectorheart.asuna.tts

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sin

/**
 * Надежный TTS-движок на базе Android [TextToSpeech] с поддержкой русского языка,
 * анимированного липсинка (mouthLevel) и очистки от разметки/тегов.
 */
@Singleton
class TtsEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    sealed class Event {
        data class Start(val utteranceId: String) : Event()
        data class Done(val utteranceId: String) : Event()
        data class Error(val utteranceId: String, val code: Int) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _mouthLevel = MutableSharedFlow<Float>(extraBufferCapacity = 64)
    val mouthLevel: SharedFlow<Float> = _mouthLevel.asSharedFlow()

    private var tts: TextToSpeech? = null
    @Volatile var isReady: Boolean = false
        private set
    var hasRussianVoice: Boolean = false
        private set

    private var currentRate: Float = 1.05f
    private var currentPitch: Float = 1.10f

    private val scope = CoroutineScope(Dispatchers.Default)
    private var lipSyncJob: Job? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    fun init(onReadyCallback: ((Boolean) -> Unit)? = null) {
        if (tts != null) {
            onReadyCallback?.invoke(isReady)
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                val ruLocale = Locale("ru", "RU")
                val langResult = tts?.setLanguage(ruLocale)

                hasRussianVoice = langResult != TextToSpeech.LANG_MISSING_DATA &&
                        langResult != TextToSpeech.LANG_NOT_SUPPORTED

                if (!hasRussianVoice) {
                    Log.w(TAG, "Русский голос не установлен в системе Android. Пробуем Locale.getDefault()")
                    val defRes = tts?.setLanguage(Locale.getDefault())
                    if (defRes == TextToSpeech.LANG_MISSING_DATA || defRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e(TAG, "Язык по умолчанию также недоступен для TTS")
                    }
                } else {
                    Log.d(TAG, "Русский язык TTS успешно активирован")
                }

                tts?.setPitch(currentPitch)
                tts?.setSpeechRate(currentRate)
                tts?.setOnUtteranceProgressListener(progressListener)
                Log.d(TAG, "Android TTS initialized successfully")
                onReadyCallback?.invoke(true)
            } else {
                isReady = false
                Log.e(TAG, "Android TTS init failed with status: $status")
                onReadyCallback?.invoke(false)
            }
        }
    }

    /**
     * Очищает текст от тегов Live2D, тегов инструментов, markdown и смайлов
     */
    fun cleanForSpeech(raw: String): String {
        return raw
            .replace(Regex("""<live2d>[\s\S]*?</live2d>"""), "")
            .replace(Regex("""<tool>[\s\S]*?</tool>"""), "")
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""\*[^*]+\*"""), "") // убираем действия вида *улыбнулась*
            .replace(Regex("""https?://\S+"""), "ссылка")
            .replace(Regex("""[#_`~]"""), "")
            .replace(Regex("""[🤖👤💬⏳❌⚠️]"""), "")
            .trim()
    }

    fun speak(text: String): Boolean {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return false
        val t = tts ?: return false
        if (!isReady) return false

        requestAudioFocus()

        val id = "utt_${System.currentTimeMillis()}"
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putFloat(TextToSpeech.Engine.KEY_PARAM_PAN, 0f)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        // Разделяем на предложения для плавной речи
        val sentences = clean.split(Regex("""(?<=[.!?])\s+""")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return false

        var success = true
        sentences.forEachIndexed { index, sentence ->
            val sentenceId = "${id}_$index"
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val res = t.speak(sentence, queueMode, params, sentenceId)
            if (res != TextToSpeech.SUCCESS) success = false
        }

        return success
    }

    fun stop() {
        stopLipSync()
        tts?.stop()
        abandonAudioFocus()
    }

    fun setRate(rate: Float) {
        currentRate = rate
        tts?.setSpeechRate(rate)
    }

    fun setPitch(pitch: Float) {
        currentPitch = pitch
        tts?.setPitch(pitch)
    }

    fun getVoiceInstallIntent(): Intent {
        return Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
    }

    private fun startLipSync() {
        lipSyncJob?.cancel()
        lipSyncJob = scope.launch {
            var step = 0.0
            while (isActive) {
                // Плавная синусоидальная модуляция движения рта при речи
                val level = (0.45f + 0.35f * sin(step).toFloat()).coerceIn(0.1f, 0.85f)
                _mouthLevel.tryEmit(level)
                step += 0.45
                delay(60)
            }
        }
    }

    private fun stopLipSync() {
        lipSyncJob?.cancel()
        lipSyncJob = null
        _mouthLevel.tryEmit(0.0f)
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .build()
                audioManager?.requestAudioFocus(focusRequest)
            }
        } catch (_: Exception) {}
    }

    private fun abandonAudioFocus() {
        try {
            audioManager?.abandonAudioFocus(null)
        } catch (_: Exception) {}
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.let {
                _events.tryEmit(Event.Start(it))
                startLipSync()
            }
        }

        override fun onDone(utteranceId: String?) {
            utteranceId?.let {
                stopLipSync()
                _events.tryEmit(Event.Done(it))
            }
        }

        @Deprecated("Deprecated in API 21")
        override fun onError(utteranceId: String?) {
            utteranceId?.let {
                stopLipSync()
                _events.tryEmit(Event.Error(it, -1))
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let {
                stopLipSync()
                _events.tryEmit(Event.Error(it, errorCode))
            }
        }
    }

    fun destroy() {
        stopLipSync()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "destroy: ${e.message}")
        }
        tts = null
        isReady = false
    }

    companion object {
        private const val TAG = "TtsEngine"
    }
}
