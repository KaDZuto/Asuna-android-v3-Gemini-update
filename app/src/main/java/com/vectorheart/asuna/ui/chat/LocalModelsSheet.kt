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
import androidx.compose.material3.LinearProgressIndicator
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
fun LocalModelsSheetContent(
    localModelManager: LocalModelManager,
    onPickModelFile: () -> Unit,
    onActivateLocalServer: () -> Unit,
    onActivateOnDevice: () -> Unit = {},
    onActivateModel: (com.vectorheart.asuna.localai.LocalModelManager.ImportedModel) -> Unit = {},
    onCopyText: (String) -> Unit
) {
    var importedModels by remember { mutableStateOf(localModelManager.getImportedModels()) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
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
                                Text("${m.sizeBytes / (1024 * 1024)} МБ • 4 потока • ${m.format}", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                Spacer(Modifier.height(4.dp))
                                OutlinedButton(onClick = { onActivateModel(m) }) {
                                    Text(if (m.format == "MediaPipeTask" || m.format == "LiteRT") "🧩 В чате (On-Device)" else "📱 В чате (Termux)", fontSize = 11.sp)
                                }
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
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onActivateOnDevice,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("🧩 Встроить On-Device (LiteRT) и подключить к Асуне")
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("⚙ Контекст и Lite LLM (Оптимизация RAM)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))

        val freeRamMb = localModelManager.getAvailableRamBytes().let { if (it > 0) it / 1024 / 1024 else -1L }
        val totalRamMb = localModelManager.getTotalRamBytes().let { if (it > 0) it / 1024 / 1024 else -1L }
        var contextMode by remember { mutableStateOf(localModelManager.onDeviceContextMode) }
        var manualContext by remember { mutableStateOf(localModelManager.onDeviceManualContext) }
        var isLiteLlm by remember { mutableStateOf(localModelManager.isLiteLlmEnabled) }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(12.dp)) {
                if (freeRamMb > 0) {
                    Text("Свободно RAM: $freeRamMb МБ / $totalRamMb МБ", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(6.dp))
                Text("Размер контекста On-Device модели:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    Box(modifier = Modifier.weight(1f)) {
                        ChipToggle(
                            selected = contextMode == "auto",
                            label = "Авто (по RAM)",
                            onClick = {
                                contextMode = "auto"
                                localModelManager.onDeviceContextMode = "auto"
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        ChipToggle(
                            selected = contextMode == "manual",
                            label = "Вручную ($manualContext)",
                            onClick = {
                                contextMode = "manual"
                                localModelManager.onDeviceContextMode = "manual"
                            }
                        )
                    }
                }
                if (contextMode == "manual") {
                    Spacer(Modifier.height(4.dp))
                    Text("Выберите лимит токенов:", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    val presets = listOf(1024, 1280, 2048, 3072, 4096)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                        presets.forEach { tokens ->
                            Box(modifier = Modifier.weight(1f)) {
                                ChipToggle(
                                    selected = manualContext == tokens,
                                    label = "$tokens",
                                    onClick = {
                                        manualContext = tokens
                                        localModelManager.onDeviceManualContext = tokens
                                    }
                                )
                            }
                        }
                    }
                }
                val effectiveTokens = localModelManager.getEffectiveContextLimit()
                Text("Итоговый контекст: $effectiveTokens токенов", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("⚡ Режим Lite LLM (Текст + авто-эмоции)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("Сжимает системный промпт с 1200 до ~100 токенов, а эмоции Асуны оцениваются автоматически. Идеально для 0.5B и 1.5B моделей.", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.material3.Switch(
                        checked = isLiteLlm,
                        onCheckedChange = {
                            isLiteLlm = it
                            localModelManager.isLiteLlmEnabled = it
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("⬇ Скачать модель прямо из приложения", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.height(2.dp))
        val freeBytes = localModelManager.freeDiskBytes()
        if (freeBytes > 0) {
            Text(
                "Свободно на телефоне: ${freeBytes / 1024 / 1024} МБ",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline
            )
        }

        // Токен HuggingFace — нужен только для gated-моделей (Gemma 3)
        var hfToken by remember { mutableStateOf(localModelManager.hfToken) }
        var verifyingToken by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = hfToken,
            onValueChange = {
                hfToken = it
                localModelManager.hfToken = it
            },
            label = { Text("Токен HuggingFace (необязательно, только для Gemma 3)", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            textStyle = MaterialTheme.typography.bodySmall
        )
        Text(
            "Gemma 4, Qwen и Phi открыты и скачиваются БЕЗ токена. Токен нужен только для gated-модели Gemma 3.",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            OutlinedButton(onClick = {
                localModelManager.hfToken = hfToken
                Toast.makeText(context, "Токен сохранён", Toast.LENGTH_SHORT).show()
            }) { Text("Сохранить", fontSize = 12.sp) }
            OutlinedButton(
                enabled = !verifyingToken && hfToken.isNotBlank(),
                onClick = {
                    verifyingToken = true
                    coroutineScope.launch {
                        val res = localModelManager.verifyHfToken(hfToken)
                        verifyingToken = false
                        res.onSuccess { msg -> Toast.makeText(context, "✅ $msg", Toast.LENGTH_LONG).show() }
                        res.onFailure { err -> Toast.makeText(context, "❌ ${err.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            ) { Text(if (verifyingToken) "Проверка..." else "Проверить", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://huggingface.co/settings/tokens"))
                context.startActivity(intent)
            }) { Text("Как получить", fontSize = 12.sp) }
        }

        Spacer(Modifier.height(8.dp))
        var downloading by remember { mutableStateOf<String?>(null) }
        var downloadProgress by remember { mutableStateOf<Int?>(null) }
        localModelManager.onDeviceRecommendations.forEach { rec ->
            val already = localModelManager.downloadedModelBytes(rec)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(rec.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("${rec.sizeFormatted} • ${rec.ramUsage} • ${rec.speedMi11T}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text(rec.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    if (already != null) {
                        Text("✅ Уже скачано (${already / 1024 / 1024} МБ)", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    if (rec.requiresHfToken && hfToken.isBlank()) {
                        Text("⚠ Нужен токен HuggingFace", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
                    }
                    val isDownloading = downloading == rec.filename
                    if (isDownloading) {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { (downloadProgress ?: 0) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Скачивание… ${downloadProgress ?: 0}%", fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = downloading == null && rec.downloadUrl.isNotBlank(),
                            onClick = {
                                downloading = rec.filename
                                downloadProgress = 0
                                coroutineScope.launch {
                                    try {
                                        val m = localModelManager.downloadModel(rec) { p -> downloadProgress = p }
                                        importedModels = localModelManager.getImportedModels()
                                        Toast.makeText(context, "Скачано: ${m.displayName}. Подключаю к Асуне…", Toast.LENGTH_LONG).show()
                                        onActivateModel(m)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                                    } finally {
                                        downloading = null
                                        downloadProgress = null
                                    }
                                }
                            }
                        ) {
                            Text(
                                when {
                                    isDownloading -> "Скачиваем…"
                                    already != null -> "↻ Проверить"
                                    rec.downloadUrl.isNotBlank() -> "⬇ Скачать"
                                    else -> "🔗 Открыть HF"
                                },
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(rec.huggingFaceUrl))
                            context.startActivity(intent)
                        }) { Text("Страница", fontSize = 12.sp) }
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
