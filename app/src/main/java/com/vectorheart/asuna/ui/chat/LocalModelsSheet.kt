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
fun LocalModelsSheetContent(
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
