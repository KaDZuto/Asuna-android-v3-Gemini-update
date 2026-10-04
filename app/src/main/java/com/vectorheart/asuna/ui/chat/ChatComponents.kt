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
fun BottomBar(
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
fun NavButton(
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
fun PttButton(onDown: () -> Unit, onUp: () -> Unit) {
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
