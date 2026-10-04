@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.vectorheart.asuna.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vectorheart.asuna.memory.MemoryManager
import kotlinx.coroutines.launch

@Composable
fun MemorySheetContent(
    memoryManager: MemoryManager,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var memories by remember { mutableStateOf(memoryManager.loadMemories()) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                coroutineScope.launch {
                    try {
                        val count = memoryManager.importFromUri(context, uri)
                        memories = memoryManager.loadMemories()
                        Toast.makeText(context, "Импортировано записей: $count", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Ошибка импорта: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text("🧠 Память Асуны", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Выжимки прошлых бесед. Импортируй JSON из ПК-версии, чтобы Асуна помнила тебя.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(10.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) }, modifier = Modifier.weight(1f)) {
                Text("Импорт JSON")
            }
            OutlinedButton(onClick = {
                memoryManager.clearMemories()
                memories = emptyList()
                Toast.makeText(context, "Память очищена", Toast.LENGTH_SHORT).show()
            }) {
                Text("Очистить")
            }
        }

        Spacer(Modifier.height(10.dp))
        if (memories.isEmpty()) {
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text("Память пока пуста.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(memories.reversed()) { m ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(m.dateRu, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(m.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Готово") }
    }
}
