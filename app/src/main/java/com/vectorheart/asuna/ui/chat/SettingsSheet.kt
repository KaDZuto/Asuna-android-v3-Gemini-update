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

@Composable
fun SettingsSheetContent(
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
    onClose: () -> Unit,
    onOpenMemorySheet: () -> Unit = {},
    onBackgroundChanged: () -> Unit = {},
    onOpenLocalModels: () -> Unit = {},
    isPickupMode: Boolean = false,
    pickupScenario: com.vectorheart.asuna.pickup.PickupMode.Scenario? = null,
    onTogglePickupMode: () -> Unit = {},
    localModelManager: com.vectorheart.asuna.localai.LocalModelManager? = null
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("asuna_settings", android.content.Context.MODE_PRIVATE) }
    var personaUserName by remember { mutableStateOf(prefs.getString("persona_user_name", "") ?: "") }
    var personaExtra by remember { mutableStateOf(prefs.getString("persona_extra", "") ?: "") }

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
            "local-ondevice" to "🧩 On-Device (LiteRT .task)",
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
        Text("Личность и память", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = personaUserName,
            onValueChange = { personaUserName = it; prefs.edit().putString("persona_user_name", it).apply() },
            label = { Text("Ваше имя (Асуна будет так к вам обращаться)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = personaExtra,
            onValueChange = { personaExtra = it; prefs.edit().putString("persona_extra", it).apply() },
            label = { Text("Доп. правила личности (необязательно)") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    onClose()
                    onOpenLocalModels()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("⬇ Скачать модель для Асуны")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = onOpenMemorySheet,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("🧠 Память Асуны (просмотр / импорт JSON)")
        }
        Spacer(Modifier.height(8.dp))

        val bgLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                try {
                    val input = context.contentResolver.openInputStream(uri)
                    val out = java.io.File(context.filesDir, "chat_background.img")
                    input?.use { it.copyTo(out.outputStream()) }
                    onBackgroundChanged()
                    android.widget.Toast.makeText(context, "Фон установлен", android.widget.Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Ошибка: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { bgLauncher.launch(arrayOf("image/*")) }, modifier = Modifier.weight(1f)) {
                Text("🖼 Сменить фон чата")
            }
            OutlinedButton(onClick = {
                val f = java.io.File(context.filesDir, "chat_background.img")
                if (f.exists()) f.delete()
                onBackgroundChanged()
            }) {
                Text("Сбросить")
            }
        }

        // Секция: Пикап-тренажёр (перенесённая с главного экрана)
        Spacer(Modifier.height(18.dp))
        Text("Тренажёр общения и знакомств", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(
            "Тренировка флирта и спонтанных диалогов с Асуной в случайных жизненных ситуациях.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(6.dp))
        if (isPickupMode && pickupScenario != null) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text("Активен сценарий: ${pickupScenario.location}", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(pickupScenario.situation, fontSize = 11.sp)
                }
            }
            Button(
                onClick = onTogglePickupMode,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Выйти из режима тренажёра")
            }
        } else {
            OutlinedButton(
                onClick = onTogglePickupMode,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
                Text("Запустить сценарий знакомства")
            }
        }

        // Секция: Lite LLM и контекст
        if (localModelManager != null) {
            Spacer(Modifier.height(18.dp))
            Text("Оптимизация On-Device и Lite LLM", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            val freeRamMb = localModelManager.getAvailableRamBytes().let { if (it > 0) it / 1024 / 1024 else -1L }
            var isLite by remember { mutableStateOf(localModelManager.isLiteLlmEnabled) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("⚡ Режим Lite LLM", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text("Сжимает системный промпт в 10 раз (~100 токенов вместо 1200). Эмоции и движения подбираются автоматически.", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        androidx.compose.material3.Switch(
                            checked = isLite,
                            onCheckedChange = {
                                isLite = it
                                localModelManager.isLiteLlmEnabled = it
                            }
                        )
                    }
                    if (freeRamMb > 0) {
                        Spacer(Modifier.height(4.dp))
                        val eff = localModelManager.getEffectiveContextLimit()
                        Text("Свободно RAM: $freeRamMb МБ • Контекст: $eff токенов", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
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
fun ChipToggle(selected: Boolean, label: String, onClick: () -> Unit) {
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
