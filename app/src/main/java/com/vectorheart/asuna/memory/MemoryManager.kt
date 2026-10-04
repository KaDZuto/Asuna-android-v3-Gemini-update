package com.vectorheart.asuna.memory

import android.content.Context
import android.util.Log
import com.vectorheart.asuna.llm.LlmClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер долгосрочной памяти Асуны ("Режим сна").
 *
 * Сохраняет краткие выжимки прошлых разговоров между сессиями
 * и подмешивает их в системный промпт, чтобы Асуна помнила пользователя.
 */
@Singleton
class MemoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Serializable
    data class MemoryEntry(
        val timestamp: Long = System.currentTimeMillis(),
        val dateRu: String,
        val summary: String
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    private val memoryFile: File
        get() = File(context.filesDir, "vectorheart_memory.json")

    fun loadMemories(): List<MemoryEntry> {
        return try {
            if (!memoryFile.exists()) return emptyList()
            val text = memoryFile.readText()
            if (text.isBlank()) return emptyList()
            json.decodeFromString<List<MemoryEntry>>(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load memories: ${e.message}")
            emptyList()
        }
    }

    private fun saveMemories(list: List<MemoryEntry>) {
        try {
            memoryFile.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save memories: ${e.message}")
        }
    }

    fun getMemoryPromptContext(): String {
        val memories = loadMemories().takeLast(5)
        if (memories.isEmpty()) return ""

        return buildString {
            append("== ДОЛГОСРОЧНАЯ ПАМЯТЬ (саммари прошлых бесед, используй как контекст) ==\n")
            memories.forEach { m ->
                append("• [${m.dateRu}]: ${m.summary}\n")
            }
        }.trimEnd()
    }

    suspend fun summarizeAndSave(
        history: List<Pair<String, String>>,
        llmClient: LlmClient,
        apiKey: String?,
        apiType: String,
        model: String?,
        baseUrl: String?
    ): String = withContext(Dispatchers.IO) {
        if (history.isEmpty()) return@withContext "История пуста, нечего сохранять."

        val transcript = history.joinToString("\n") { (role, content) ->
            val speaker = if (role == "user") "Пользователь" else "Асуна"
            val clean = content.replace(Regex("""<live2d>[\s\S]*?</live2d>"""), "").trim()
            "$speaker: $clean"
        }

        val prompt = "История диалога за сессию:\n\n$transcript\n\nСделай очень краткое саммари (2-4 предложения) для долговременной памяти."
        val summaryRaw = try {
            llmClient.callLLM(
                userMessage = prompt,
                history = emptyList(),
                apiKey = apiKey,
                apiType = apiType,
                model = model,
                baseUrl = baseUrl,
                systemPrompt = SUMMARY_SYSTEM_PROMPT
            )
        } catch (e: Exception) {
            Log.e(TAG, "Summarization failed: ${e.message}", e)
            throw e
        }

        val cleanSummary = summaryRaw
            .replace(Regex("""<live2d>[\s\S]*?</live2d>"""), "")
            .replace(Regex("""<tool>[\s\S]*?</tool>"""), "")
            .trim()

        if (cleanSummary.isNotBlank()) {
            val dateRu = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
            val entry = MemoryEntry(dateRu = dateRu, summary = cleanSummary)
            val current = loadMemories().toMutableList()
            current.add(entry)
            // Сохраняем последние 30 сессий
            saveMemories(current.takeLast(30))
            Log.d(TAG, "Saved new memory summary: $cleanSummary")
        }

        cleanSummary
    }

    fun clearMemories() {
        if (memoryFile.exists()) {
            memoryFile.delete()
        }
    }

    companion object {
        private const val TAG = "MemoryManager"

        const val SUMMARY_SYSTEM_PROMPT: String =
            "Ты помогаешь Асуне вести долгосрочную память. " +
            "Тебе дают историю чата за сессию. Сделай очень краткое саммари (2-4 предложения, простым текстом, " +
            "без markdown, без списков, без JSON): что обсуждали, какие важные факты о пользователе узнала Асуна, " +
            "какое было настроение/тон общения. Пиши от третьего лица, по-русски. Ничего кроме саммари не пиши."
    }
}
