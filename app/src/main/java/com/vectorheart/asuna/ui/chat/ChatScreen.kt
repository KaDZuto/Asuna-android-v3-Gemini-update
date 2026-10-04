@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vectorheart.asuna.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.vectorheart.asuna.avatar.TiltSensor
import com.vectorheart.asuna.llm.LlmClient
import com.vectorheart.asuna.llm.LlmRepository
import com.vectorheart.asuna.localai.LocalModelManager
import com.vectorheart.asuna.pickup.PickupMode
import com.vectorheart.asuna.stt.SttEngine
import com.vectorheart.asuna.tts.TtsEngine
import com.vectorheart.asuna.tts.TtsManager
import com.vectorheart.asuna.ui.avatar.AsunaJsBridge
import com.vectorheart.asuna.ui.avatar.AvatarWebView
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class PttState { Idle, Listening, Thinking, Speaking }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatScreenEntryPoint {
    fun llmClient(): LlmClient
    fun llmRepository(): LlmRepository
    fun localModelManager(): LocalModelManager
    fun ttsManager(): TtsManager
    fun ttsEngine(): TtsEngine
    fun sttEngine(): SttEngine
    fun tiltSensor(): TiltSensor
}

// Структура сообщения в чате
private data class ChatMessage(
    val id: Long,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val status: String = "" // "" / "sending" / "error" / "tool"
)

// Ключи SharedPreferences
private const val KEY_API_KEY      = "api_key"
private const val KEY_API_TYPE     = "api_type"
private const val KEY_MODEL        = "model"
private const val KEY_BASE_URL     = "base_url"
private const val KEY_AVATAR_MODEL = "avatar_model"
private const val KEY_TTS_MODE     = "tts_mode"
private const val KEY_SILERO_URL   = "silero_url"
private const val KEY_TTS_RATE     = "tts_rate"
private const val KEY_TTS_PITCH    = "tts_pitch"

private val DEFAULT_API_KEY = com.vectorheart.asuna.BuildConfig.OPENROUTER_API_KEY
private const val DEFAULT_MODEL = "openrouter/free"
private const val MEMORY_WINDOW = 20

private val AVAILABLE_MODELS = listOf(
    "asuna_01","asuna_02","asuna_03","asuna_04","asuna_05","asuna_06","asuna_07","asuna_08","asuna_09",
    "asuna_12","asuna_13","asuna_14","asuna_15","asuna_16","asuna_17","asuna_18","asuna_19",
    "asuna_20","asuna_21","asuna_22","asuna_23","asuna_24","asuna_25","asuna_26","asuna_27","asuna_28","asuna_29",
    "asuna_30","asuna_31","asuna_33","asuna_34","asuna_35","asuna_36","asuna_37","asuna_38","asuna_39",
    "asuna_40","asuna_41","asuna_43","asuna_44","asuna_45","asuna_46","asuna_47","asuna_48","asuna_49",
    "asuna_50","asuna_51","asuna_52","asuna_53","asuna_54","asuna_55","asuna_56"
)

@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit = {},
    onNavigateToCalendar: () -> Unit = {},
    onNavigateToMedia: () -> Unit = {},
    onNavigateToLiveVision: () -> Unit = {},
    onOpenChatLog: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, ChatScreenEntryPoint::class.java)
    }
    val llmRepository = remember { entryPoint.llmRepository() }
    val localModelManager = remember { entryPoint.localModelManager() }
    val ttsManager = remember { entryPoint.ttsManager() }
    val ttsEngine = remember { entryPoint.ttsEngine() }
    val sttEngine = remember { entryPoint.sttEngine() }
    val tiltSensor = remember { entryPoint.tiltSensor() }

    val prefs = remember {
        context.getSharedPreferences("asuna_settings", Context.MODE_PRIVATE)
    }

    var apiKey by remember { mutableStateOf(prefs.getString(KEY_API_KEY, DEFAULT_API_KEY)) }
    var apiType by remember { mutableStateOf(prefs.getString(KEY_API_TYPE, "openai-compatible") ?: "openai-compatible") }
    var model by remember { mutableStateOf<String?>(prefs.getString(KEY_MODEL, DEFAULT_MODEL)) }
    var baseUrl by remember { mutableStateOf(prefs.getString(KEY_BASE_URL, "https://openrouter.ai/api/v1") ?: "https://openrouter.ai/api/v1") }
    var currentAvatarModel by remember { mutableStateOf(prefs.getString(KEY_AVATAR_MODEL, "asuna_01") ?: "asuna_01") }

    var ttsMode by remember { mutableStateOf(prefs.getString(KEY_TTS_MODE, TtsManager.TTS_MODE_SYSTEM) ?: TtsManager.TTS_MODE_SYSTEM) }
    var sileroUrl by remember { mutableStateOf(prefs.getString(KEY_SILERO_URL, "http://192.168.1.100:8000") ?: "http://192.168.1.100:8000") }
    var ttsRate by remember { mutableStateOf(prefs.getFloat(KEY_TTS_RATE, 1.05f)) }
    var ttsPitch by remember { mutableStateOf(prefs.getFloat(KEY_TTS_PITCH, 1.10f)) }

    var chatLog by remember { mutableStateOf(listOf<ChatMessage>()) }
    var nextMsgId by remember { mutableStateOf(0L) }

    var isPickupMode by remember { mutableStateOf(false) }
    var pickupScenario by remember { mutableStateOf<PickupMode.Scenario?>(null) }

    LaunchedEffect(apiKey)    { prefs.edit().putString(KEY_API_KEY, apiKey).apply() }
    LaunchedEffect(apiType)   { prefs.edit().putString(KEY_API_TYPE, apiType).apply() }
    LaunchedEffect(model)     { prefs.edit().putString(KEY_MODEL, model).apply() }
    LaunchedEffect(baseUrl)   { prefs.edit().putString(KEY_BASE_URL, baseUrl).apply() }
    LaunchedEffect(currentAvatarModel) { prefs.edit().putString(KEY_AVATAR_MODEL, currentAvatarModel).apply() }
    LaunchedEffect(ttsMode)   { prefs.edit().putString(KEY_TTS_MODE, ttsMode).apply() }
    LaunchedEffect(sileroUrl) { prefs.edit().putString(KEY_SILERO_URL, sileroUrl).apply() }
    LaunchedEffect(ttsRate)   { prefs.edit().putFloat(KEY_TTS_RATE, ttsRate).apply(); ttsEngine.setRate(ttsRate) }
    LaunchedEffect(ttsPitch)  { prefs.edit().putFloat(KEY_TTS_PITCH, ttsPitch).apply(); ttsEngine.setPitch(ttsPitch) }

    // Конфигурируем TTS Manager
    LaunchedEffect(ttsMode, sileroUrl) {
        ttsManager.configure(ttsMode, sileroUrl)
    }

    var pttState by remember { mutableStateOf(PttState.Idle) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var recognizedText by remember { mutableStateOf("") }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showChatSheet by remember { mutableStateOf(false) }
    var showLocalModelsSheet by remember { mutableStateOf(false) }
    var textInputValue by remember { mutableStateOf("") }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    // Лаунчер для импорта локальных .gguf моделей
    val modelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                try {
                    statusMessage = "Импорт модели..."
                    val imported = localModelManager.importModelFromUri(uri)
                    Toast.makeText(context, "Импортирована: ${imported.displayName}", Toast.LENGTH_SHORT).show()
                    statusMessage = "Модель добавлена: ${imported.displayName}"
                } catch (e: Exception) {
                    Toast.makeText(context, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                    statusMessage = "Ошибка импорта"
                }
            }
        }
    }

    DisposableEffect(Unit) {
        ttsManager.init()
        onDispose { ttsManager.destroy() }
    }

    // Липсинк для Live2D аватара
    LaunchedEffect(Unit) {
        ttsManager.mouthLevel.collectLatest { level ->
            webView?.evaluateJavascript(
                "window.vh && window.vh.emitMouth && window.vh.emitMouth($level);",
                null
            )
        }
    }

    LaunchedEffect(Unit) {
        ttsManager.events.collectLatest { ev ->
            when (ev) {
                is TtsEngine.Event.Start -> pttState = PttState.Speaking
                is TtsEngine.Event.Done -> pttState = PttState.Idle
                is TtsEngine.Event.Error -> pttState = PttState.Idle
            }
        }
    }

    // Акселерометр
    DisposableEffect(Unit) {
        if (tiltSensor.isAvailable()) {
            tiltSensor.start()
        }
        onDispose { tiltSensor.stop() }
    }
    LaunchedEffect(Unit) {
        tiltSensor.tilt.collectLatest { data ->
            val js = "window.vh && window.vh.emitTilt && window.vh.emitTilt({" +
                    "x:" + data.x + ",y:" + data.y + ",z:" + data.z +
                    ",isBouncing:" + data.isBouncing + "});"
            webView?.evaluateJavascript(js, null)
        }
    }

    fun applyActionToAvatar(action: LlmClient.Action) {
        val actionJson = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf(
                "expression" to JsonPrimitive(action.expression),
                "motion_group" to JsonPrimitive(action.motion_group),
                "motion_index" to JsonPrimitive(action.motion_index),
                "head_x" to JsonPrimitive(action.head_x),
                "head_y" to JsonPrimitive(action.head_y),
                "body_angle" to JsonPrimitive(action.body_angle),
                "eye_open" to JsonPrimitive(action.eye_open),
                "mouth_open" to JsonPrimitive(action.mouth_open),
                "breath" to JsonPrimitive(action.breath)
            ))
        )
        webView?.evaluateJavascript(
            "window.vh && window.vh.emitReact && window.vh.emitReact($actionJson);",
            null
        )
    }

    fun switchAvatarModel(newModel: String) {
        currentAvatarModel = newModel
        webView?.evaluateJavascript(
            "window.vh && window.vh.emitModel && window.vh.emitModel('$newModel');",
            null
        )
        statusMessage = "Модель: $newModel"
    }

    fun sendToLlm(text: String) {
        if (apiKey.isNullOrBlank() && (apiType == "openai" || apiType == "gemini")) {
            val errMsg = "Укажите API-ключ в настройках (шестеренка)"
            statusMessage = errMsg
            pttState = PttState.Idle
            chatLog = chatLog + ChatMessage(nextMsgId++, "⚠️ $errMsg", isUser = false, status = "error")
            return
        }

        val history = chatLog
            .filter { it.status.isEmpty() }
            .takeLast(MEMORY_WINDOW * 2)
            .map { (if (it.isUser) "user" else "assistant") to it.text }

        val userMsg = ChatMessage(nextMsgId++, text, isUser = true, status = "sent")
        val asunaMsg = ChatMessage(nextMsgId++, "⏳ Думаю...", isUser = false, status = "sending")
        chatLog = chatLog + userMsg + asunaMsg
        statusMessage = "→ $text"
        pttState = PttState.Thinking

        coroutineScope.launch {
            try {
                val parsed = llmRepository.sendMessage(
                    userText = text,
                    history = history,
                    apiKey = apiKey?.takeIf { it.isNotBlank() },
                    apiType = apiType,
                    model = model,
                    baseUrl = baseUrl,
                    onToolExecuting = { toolInfo ->
                        statusMessage = "⚙️ $toolInfo"
                    }
                )

                applyActionToAvatar(parsed.action)
                chatLog = chatLog.dropLast(1) + asunaMsg.copy(text = parsed.display, status = "")
                statusMessage = "💬 ${parsed.display.take(50)}"

                ttsManager.speak(parsed.display)
            } catch (e: Exception) {
                Log.e(TAG, "LLM failed: ${e.message}", e)
                val errText = "❌ " + (e.message ?: "Ошибка соединения")
                chatLog = chatLog.dropLast(1) + asunaMsg.copy(text = errText, status = "error")
                statusMessage = errText
                pttState = PttState.Idle
            }
        }
    }

    fun triggerSleepMode() {
        val history = chatLog
            .filter { it.status.isEmpty() }
            .map { (if (it.isUser) "user" else "assistant") to it.text }

        if (history.isEmpty()) {
            Toast.makeText(context, "История чата пуста", Toast.LENGTH_SHORT).show()
            return
        }

        coroutineScope.launch {
            try {
                statusMessage = "😴 Сохраняю память сессии..."
                val summary = llmRepository.sleepAndSummarize(history, apiKey, apiType, model, baseUrl)
                chatLog = chatLog + ChatMessage(
                    id = nextMsgId++,
                    text = "😴 Сессия завершена. Асуна запомнила этот разговор:\n$summary",
                    isUser = false,
                    status = "tool"
                )
                statusMessage = "Память обновлена"
                Toast.makeText(context, "Память успешно сохранена в vectorheart_memory.json", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Ошибка сохранения памяти: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun togglePickupMode() {
        if (!isPickupMode) {
            val scenario = llmRepository.startPickupMode()
            isPickupMode = true
            pickupScenario = scenario
            chatLog = listOf(
                ChatMessage(
                    id = nextMsgId++,
                    text = "❤️ Тренажёр знакомств активирован!\nМесто: ${scenario.location}\nСитуация: ${scenario.situation}\nНастроение: ${scenario.mood}\nХарактер: ${scenario.personality.label}",
                    isUser = false,
                    status = "tool"
                )
            )
            Toast.makeText(context, "Пикап-тренажёр включен", Toast.LENGTH_SHORT).show()
        } else {
            llmRepository.exitPickupMode()
            isPickupMode = false
            pickupScenario = null
            chatLog = listOf(
                ChatMessage(
                    id = nextMsgId++,
                    text = "✨ Возврат в обычный режим тёплого компаньона Асуны.",
                    isUser = false,
                    status = "tool"
                )
            )
            Toast.makeText(context, "Режим компаньона", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        sttEngine.results.collectLatest { res ->
            when (res) {
                is SttEngine.Result.Partial -> {
                    recognizedText = res.text
                    statusMessage = "«${res.text}»"
                }
                is SttEngine.Result.Final -> {
                    recognizedText = res.text
                    statusMessage = "Распознано: «${res.text}»"
                    sendToLlm(res.text)
                }
                is SttEngine.Result.Error -> {
                    statusMessage = "STT: ${res.message}"
                    pttState = PttState.Idle
                }
            }
        }
    }

    val bridge = remember {
        AsunaJsBridge(
            onMouthLevel = { },
            onAvatarReact = { },
            onSendVoice = { pttState = PttState.Idle },
            onSendTouch = { zone -> Log.d(TAG, "Touch on avatar: $zone") },
            onOpenControl = { onOpenChatLog() },
            onHideWindow = { },
            onQuit = { }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        AvatarWebView(
            bridge = bridge,
            modifier = Modifier.fillMaxSize(),
            onWebViewCreated = { wv -> webView = wv }
        )

        // Верхний индикатор (речь, статус, активный сценарий)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .zIndex(10f)
        ) {
            if (isPickupMode && pickupScenario != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    Text(
                        text = "❤️ Тренажёр: ${pickupScenario?.location} • ${pickupScenario?.personality?.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        maxLines = 1
                    )
                }
            }

            if (recognizedText.isNotEmpty() || pttState != PttState.Idle || statusMessage != null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val (indicator, stateColor) = when (pttState) {
                            PttState.Idle -> ("OK" to MaterialTheme.colorScheme.outline)
                            PttState.Listening -> ("REC" to MaterialTheme.colorScheme.tertiary)
                            PttState.Thinking -> ("THINK" to MaterialTheme.colorScheme.primary)
                            PttState.Speaking -> ("SND" to MaterialTheme.colorScheme.secondary)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = stateColor,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Text(
                                indicator,
                                color = MaterialTheme.colorScheme.surface,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = recognizedText.ifEmpty {
                                statusMessage ?: when (pttState) {
                                    PttState.Idle -> "Удерживай микрофон для разговора"
                                    PttState.Listening -> "Слушаю..."
                                    PttState.Thinking -> "Думаю..."
                                    PttState.Speaking -> "Говорю..."
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        // Кнопка истории чата (с бейджем)
        if (chatLog.isNotEmpty()) {
            FloatingActionButton(
                onClick = { showChatSheet = true },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp, top = 80.dp)
                    .zIndex(10f),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Badge(containerColor = MaterialTheme.colorScheme.tertiary) {
                    Text("${chatLog.size}", fontSize = 10.sp)
                }
            }
        }

        // Нижняя панель управления
        BottomBar(
            currentModel = currentAvatarModel,
            isPickupMode = isPickupMode,
            onPttDown = {
                if (!hasMicPermission) {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    recognizedText = ""
                    pttState = PttState.Listening
                    sttEngine.startListening()
                }
            },
            onPttUp = {
                if (pttState == PttState.Listening) {
                    pttState = PttState.Thinking
                    sttEngine.stopListening()
                }
            },
            onSettingsClick = { showSettingsSheet = true },
            onModelClick = { showModelSheet = true },
            onChatClick = { showChatSheet = true },
            onLocalModelsClick = { showLocalModelsSheet = true },
            onTogglePickupMode = { togglePickupMode() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(20f)
        )
    }

    // ============= Settings Sheet =============
    if (showSettingsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            sheetState = sheetState
        ) {
            SettingsSheetContent(
                apiType = apiType,
                onApiTypeChange = { newType ->
                    apiType = newType
                    // Умная авто-подстановка провайдеров
                    when (newType) {
                        "gemini" -> {
                            baseUrl = "https://generativelanguage.googleapis.com/v1beta"
                            model = "gemini-3.8-flash"
                        }
                        "deepseek" -> {
                            baseUrl = "https://api.deepseek.com/v1"
                            model = "deepseek-chat"
                        }
                        "antigravity-bridge" -> {
                            apiType = "openai-compatible"
                            baseUrl = "http://192.168.1.100:8080/v1"
                            model = "gemini-3.8-flash-high"
                        }
                        "local-termux" -> {
                            apiType = "openai-compatible"
                            baseUrl = "http://127.0.0.1:8080/v1"
                            model = "qwen2.5-1.5b-instruct"
                        }
                        "anthropic" -> {
                            baseUrl = "https://api.anthropic.com/v1"
                            model = "claude-sonnet-5-5"
                        }
                        "openai" -> {
                            baseUrl = "https://api.openai.com/v1"
                            model = "gpt-4o-mini"
                        }
                        else -> {
                            baseUrl = "https://openrouter.ai/api/v1"
                            model = "openrouter/free"
                        }
                    }
                },
                model = model ?: "",
                onModelChange = { model = it.ifBlank { null } },
                baseUrl = baseUrl,
                onBaseUrlChange = { baseUrl = it },
                apiKey = apiKey ?: "",
                onApiKeyChange = { apiKey = it },
                ttsMode = ttsMode,
                onTtsModeChange = { ttsMode = it },
                sileroUrl = sileroUrl,
                onSileroUrlChange = { sileroUrl = it },
                ttsRate = ttsRate,
                onTtsRateChange = { ttsRate = it },
                ttsPitch = ttsPitch,
                onTtsPitchChange = { ttsPitch = it },
                onTestSpeech = {
                    coroutineScope.launch {
                        ttsManager.speak("Привет! Я Асуна, твой живой компаньон.")
                    }
                },
                onOpenTtsSettings = {
                    try {
                        context.startActivity(ttsEngine.getVoiceInstallIntent())
                    } catch (e: Exception) {
                        Toast.makeText(context, "Не удалось открыть настройки TTS", Toast.LENGTH_SHORT).show()
                    }
                },
                onClose = { showSettingsSheet = false }
            )
        }
    }

    // ============= Local Models Sheet (Xiaomi Mi 11T / GGUF) =============
    if (showLocalModelsSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showLocalModelsSheet = false },
            sheetState = sheetState
        ) {
            LocalModelsSheetContent(
                localModelManager = localModelManager,
                onPickModelFile = {
                    modelPickerLauncher.launch(arrayOf("*/*", "application/octet-stream"))
                },
                onActivateLocalServer = {
                    apiType = "openai-compatible"
                    baseUrl = "http://127.0.0.1:8080/v1"
                    model = "qwen2.5-1.5b-instruct"
                    showLocalModelsSheet = false
                    Toast.makeText(context, "Переключено на офлайн Termux сервер (127.0.0.1:8080)", Toast.LENGTH_LONG).show()
                },
                onCopyText = { txt ->
                    clipboardManager.setText(AnnotatedString(txt))
                    Toast.makeText(context, "Скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    // ============= Chat Sheet =============
    if (showChatSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showChatSheet = false },
            sheetState = sheetState
        ) {
            ChatSheetContent(
                messages = chatLog,
                textValue = textInputValue,
                onTextValueChange = { textInputValue = it },
                onSend = {
                    if (textInputValue.isNotBlank()) {
                        val toSend = textInputValue
                        textInputValue = ""
                        showChatSheet = false
                        sendToLlm(toSend)
                    }
                },
                onSleepMode = { triggerSleepMode() },
                onClear = { chatLog = emptyList() },
                onClose = { showChatSheet = false }
            )
        }
    }

    // ============= Model Sheet (52 Live2D модели) =============
    if (showModelSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showModelSheet = false },
            sheetState = sheetState
        ) {
            ModelSheetContent(
                currentModel = currentAvatarModel,
                onModelSelect = { m ->
                    switchAvatarModel(m)
                    showModelSheet = false
                }
            )
        }
    }
}

private const val TAG = "ChatScreen"

@Composable
private fun BottomBar(
    currentModel: String,
    isPickupMode: Boolean,
    onPttDown: () -> Unit,
    onPttUp: () -> Unit,
    onSettingsClick: () -> Unit,
    onModelClick: () -> Unit,
    onChatClick: () -> Unit,
    onLocalModelsClick: () -> Unit,
    onTogglePickupMode: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavButton(icon = Icons.Default.Chat, onClick = onChatClick, contentDesc = "История чата", size = 38)
            NavButton(icon = Icons.Default.SwapHoriz, onClick = onModelClick, contentDesc = "Сменить аватар", size = 38)
            NavButton(
                icon = Icons.Default.Favorite,
                onClick = onTogglePickupMode,
                contentDesc = "Пикап-тренажёр",
                size = 38,
                tint = if (isPickupMode) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onBackground
            )
            PttButton(onDown = onPttDown, onUp = onPttUp)
            NavButton(icon = Icons.Default.Memory, onClick = onLocalModelsClick, contentDesc = "Локальные ИИ модели", size = 38)
            NavButton(icon = Icons.Default.Settings, onClick = onSettingsClick, contentDesc = "Настройки", size = 38)
        }
    }
}

@Composable
private fun NavButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    contentDesc: String,
    size: Int = 40,
    tint: Color = MaterialTheme.colorScheme.onBackground
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), shape = CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        val change = down.changes.firstOrNull()
                        if (change != null && change.pressed && !down.changes.any { it.isConsumed }) {
                            down.changes.forEach { it.consume() }
                            onClick()
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.firstOrNull()?.pressed == false) {
                                    ev.changes.forEach { it.consume() }
                                    break
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = tint,
            modifier = Modifier.size((size * 0.55f).dp)
        )
    }
}

@Composable
private fun PttButton(onDown: () -> Unit, onUp: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(color = MaterialTheme.colorScheme.primary, shape = CircleShape)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitPointerEvent()
                        if (down.changes.firstOrNull()?.pressed == true) {
                            onDown()
                            while (true) {
                                val ev = awaitPointerEvent()
                                if (ev.changes.firstOrNull()?.pressed == false) {
                                    onUp()
                                    break
                                }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🎤",
            fontSize = 28.sp,
            color = MaterialTheme.colorScheme.onPrimary
        )
    }
}

@Composable
private fun SettingsSheetContent(
    apiType: String,
    onApiTypeChange: (String) -> Unit,
    model: String,
    onModelChange: (String) -> Unit,
    baseUrl: String,
    onBaseUrlChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    ttsMode: String,
    onTtsModeChange: (String) -> Unit,
    sileroUrl: String,
    onSileroUrlChange: (String) -> Unit,
    ttsRate: Float,
    onTtsRateChange: (Float) -> Unit,
    ttsPitch: Float,
    onTtsPitchChange: (Float) -> Unit,
    onTestSpeech: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Настройки ИИ и Синтеза Речи",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(14.dp))

        Text("ИИ Провайдер", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))

        // Быстрые пресеты провайдеров
        val providers = listOf(
            "gemini" to "✨ Gemini Pro / AI Studio",
            "openai-compatible" to "🌐 OpenRouter",
            "deepseek" to "🐋 DeepSeek V3/R1",
            "antigravity-bridge" to "⚡ Antigravity CLI (ПК)",
            "local-termux" to "📱 Офлайн (Mi 11T / Termux)",
            "openai" to "OpenAI (GPT-4o)",
            "anthropic" to "Anthropic (Claude)"
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            providers.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (id, label) ->
                        Box(modifier = Modifier.weight(1f)) {
                            ChipToggle(
                                selected = (id == "antigravity-bridge" && baseUrl.contains("8080") && model.contains("gemini-3")) ||
                                        (id == "local-termux" && baseUrl.contains("127.0.0.1:8080")) ||
                                        apiType == id,
                                label = label,
                                onClick = { onApiTypeChange(id) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Base URL", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))
        Text("Модель", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = model,
            onValueChange = onModelChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))
        Text("API-ключ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )

        Spacer(Modifier.height(16.dp))
        Text("Синтез Речи (TTS)", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                ChipToggle(
                    selected = ttsMode == TtsManager.TTS_MODE_SYSTEM,
                    label = "Android System TTS",
                    onClick = { onTtsModeChange(TtsManager.TTS_MODE_SYSTEM) }
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                ChipToggle(
                    selected = ttsMode == TtsManager.TTS_MODE_SILERO_HTTP,
                    label = "Silero HTTP (ПК/Baya)",
                    onClick = { onTtsModeChange(TtsManager.TTS_MODE_SILERO_HTTP) }
                )
            }
        }

        if (ttsMode == TtsManager.TTS_MODE_SILERO_HTTP) {
            Spacer(Modifier.height(8.dp))
            Text("URL сервера Silero TTS (ПК-версия)", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = sileroUrl,
                onValueChange = onSileroUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://192.168.1.100:8000") },
                singleLine = true
            )
        }

        Spacer(Modifier.height(8.dp))
        Text("Скорость речи: ${String.format("%.2f", ttsRate)}x", style = MaterialTheme.typography.bodySmall)
        Slider(value = ttsRate, onValueChange = onTtsRateChange, valueRange = 0.7f..1.5f)

        Text("Тон голоса (Pitch): ${String.format("%.2f", ttsPitch)}x", style = MaterialTheme.typography.bodySmall)
        Slider(value = ttsPitch, onValueChange = onTtsPitchChange, valueRange = 0.8f..1.4f)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onTestSpeech,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Тест голоса")
            }
            OutlinedButton(
                onClick = onOpenTtsSettings,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Голоса в системе")
            }
        }

        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Готово")
        }
    }
}

@Composable
private fun LocalModelsSheetContent(
    localModelManager: LocalModelManager,
    onPickModelFile: () -> Unit,
    onActivateLocalServer: () -> Unit,
    onCopyText: (String) -> Unit
) {
    var importedModels by remember { mutableStateOf(localModelManager.getImportedModels()) }
    val coroutineScope = rememberCoroutineScope()
    var isServerOnline by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(Unit) {
        isServerOnline = localModelManager.checkLocalServerHealth("http://127.0.0.1:8080")
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Локальные ИИ Модели (Офлайн)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Оптимизировано для Xiaomi Mi 11T (Dimensity 1200, 8GB RAM)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(14.dp))

        // Кнопка импорта
        Button(
            onClick = onPickModelFile,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Импортировать модель (.gguf)")
        }

        Spacer(Modifier.height(12.dp))
        Text("Импортированные модели (${importedModels.size})", fontWeight = FontWeight.Bold)

        if (importedModels.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
            ) {
                Text(
                    "Файлы моделей ещё не импортированы. Скачайте .gguf файл на телефон и нажмите кнопку выше.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                importedModels.forEach { m ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(m.displayName, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text("${m.sizeBytes / (1024 * 1024)} МБ • 4 потока CPU", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                            }
                            IconButton(onClick = {
                                localModelManager.removeModel(m.id)
                                importedModels = localModelManager.getImportedModels()
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        // Статус сервера
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Статус офлайн сервера: ", fontWeight = FontWeight.Bold)
                    Text(
                        text = when (isServerOnline) {
                            true -> "🟢 Работает (127.0.0.1:8080)"
                            false -> "🔴 Не запущен"
                            null -> "Проверка..."
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onActivateLocalServer,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Подключить к Асуне")
                    }
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isServerOnline = localModelManager.checkLocalServerHealth()
                            }
                        }
                    ) {
                        Text("Проверить")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("Команда для запуска в Termux на Xiaomi Mi 11T:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        val termuxCmd = localModelManager.getTermuxServerCommand(importedModels.firstOrNull())
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clickable { onCopyText(termuxCmd) }
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(termuxCmd, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text("📋 Нажмите, чтобы скопировать команду", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("Рекомендованные модели для Mi 11T (8GB RAM):", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        localModelManager.recommendations.forEach { r ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(r.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${r.sizeFormatted} • ОЗУ: ${r.ramUsage} • Скорость: ${r.speedMi11T}", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                    Text(r.description, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatSheetContent(
    messages: List<ChatMessage>,
    textValue: String,
    onTextValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onSleepMode: () -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Чат с Асуной (${messages.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Row {
                IconButton(onClick = onSleepMode) {
                    Icon(Icons.Default.Bedtime, contentDescription = "Режим сна (сохранить память)", tint = MaterialTheme.colorScheme.primary)
                }
                TextButton(onClick = onClear, enabled = messages.isNotEmpty()) {
                    Text("Очистить")
                }
                TextButton(onClick = onClose) {
                    Text("Закрыть")
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages) { msg ->
                ChatMessageBubble(msg)
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = textValue,
                onValueChange = onTextValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Напиши Асуне...") },
                maxLines = 3
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = textValue.isNotBlank()
            ) {
                Text("Отправить")
            }
        }
    }
}

@Composable
private fun ChatMessageBubble(msg: ChatMessage) {
    val bgColor = when {
        msg.status == "error" -> MaterialTheme.colorScheme.errorContainer
        msg.status == "tool" -> MaterialTheme.colorScheme.tertiaryContainer
        msg.isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = when {
        msg.status == "error" -> MaterialTheme.colorScheme.onErrorContainer
        msg.status == "tool" -> MaterialTheme.colorScheme.onTertiaryContainer
        msg.isUser -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val align = if (msg.isUser) Alignment.End else Alignment.Start

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (msg.isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .background(bgColor, shape = RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalAlignment = align
        ) {
            Text(
                text = if (msg.isUser) "👤 Ты" else "🤖 Асуна",
                style = MaterialTheme.typography.labelSmall,
                color = textColor.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = msg.text,
                style = MaterialTheme.typography.bodySmall,
                color = textColor
            )
        }
    }
}

@Composable
private fun ModelSheetContent(
    currentModel: String,
    onModelSelect: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
    ) {
        Text(
            "Выбор 2D-модели Асуны",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Текущая: $currentModel | Всего: 52 модели",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(12.dp))

        val rows = AVAILABLE_MODELS.chunked(4)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { id ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .background(
                                    color = if (id == currentModel) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onModelSelect(id) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                id.removePrefix("asuna_"),
                                color = if (id == currentModel) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (id == currentModel) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ChipToggle(selected: Boolean, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}
