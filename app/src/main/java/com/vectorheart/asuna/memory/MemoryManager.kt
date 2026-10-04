package com.vectorheart.asuna.memory

import android.content.Context
import android.util.Log
import com.vectorheart.asuna.llm.LlmClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
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
            memoryFile.writeText(json.encodeToString(ListSerializer(MemoryEntry.serializer()), list))
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

    /** Сохраняет готовый текст саммари (например, сгенерированный on-device моделью). */
    fun saveSummaryRaw(raw: String): String {
        val clean = raw
            .replace(Regex("""<live2d>[\s\S]*?</live2d>"""), "")
            .replace(Regex("""<tool>[\s\S]*?</tool>"""), "")
            .replace(Regex("""<think>[\s\S]*?</think>"""), "")
            .trim()
        if (clean.isBlank()) return "Пустое саммари, не сохранила."

        val dateRu = SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
        val entry = MemoryEntry(dateRu = dateRu, summary = clean)
        saveMemories((loadMemories() + entry).takeLast(50))
        Log.d(TAG, "Saved summary (raw): ${clean.take(120)}")
        return clean
    }

    /**
     * Локальное саммари без обращения к облаку: последние реплики превращаются
     * в память простым текстом. Используется, если on-device модель недоступна.
     */
    fun summarizeLocally(history: List<Pair<String, String>>, note: String? = null): String {
        if (history.isEmpty()) return "История пуста, нечего сохранять."
        val userLines = history.filter { it.first == "user" }.takeLast(6)
        val asunaLines = history.filter { it.first != "user" }.takeLast(4)
        val text = buildString {
            append("Сессия на телефоне.")
            if (userLines.isNotEmpty()) {
                append(" Обсуждали: ")
                append(userLines.joinToString("; ") { it.second.replace(Regex("\\s+"), " ").take(120) })
                append(".")
            }
            if (asunaLines.isNotEmpty()) {
                append(" Асуна отвечала тепло и по-русски, поддерживала диалог.")
            }
            if (!note.isNullOrBlank()) append(" (${note.take(80)})")
        }
        return saveSummaryRaw(text)
    }

    fun mergeMemories(entries: List<MemoryEntry>) {
        val merged = (loadMemories() + entries)
            .distinctBy { "${it.dateRu}|${it.summary}" }
            .sortedBy { it.timestamp }
            .takeLast(50)
        saveMemories(merged)
    }

    fun clearMemories() {
        if (memoryFile.exists()) {
            memoryFile.delete()
        }
    }

    /**
     * Импорт памяти из JSON (в формате ПК-версии).
     * Поддерживает:
     *  - массив объектов {timestamp, dateRu, summary} (родной формат)
     *  - массив объектов с полями summary/text/content и опциональными date/dateRu/timestamp
     *  - объект с полем "memories"/"entries"/"items" — массивом таких объектов
     */
    suspend fun importFromUri(context: android.content.Context, uri: android.net.Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw RuntimeException("Не удалось прочитать файл")
        val root = try {
            kotlinx.serialization.json.Json.parseToJsonElement(text)
        } catch (e: Exception) {
            throw RuntimeException("Файл не является валидным JSON")
        }

        val array = when (root) {
            is kotlinx.serialization.json.JsonArray -> root
            is kotlinx.serialization.json.JsonObject -> {
                val arr = root["memories"] ?: root["entries"] ?: root["items"] ?: root["data"]
                arr as? kotlinx.serialization.json.JsonArray
                    ?: throw RuntimeException("Не найден массив памяти в JSON")
            }
            else -> throw RuntimeException("Неподдерживаемый формат JSON")
        }

        val parsed = array.mapNotNull { el ->
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val summary = obj["summary"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                ?: obj["text"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                ?: obj["content"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                ?: return@mapNotNull null
            val dateRu = obj["dateRu"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                ?: obj["date"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                ?: SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("ru")).format(Date())
            val ts = obj["timestamp"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toLongOrNull() }
                ?: System.currentTimeMillis()
            MemoryEntry(timestamp = ts, dateRu = dateRu, summary = summary)
        }

        if (parsed.isEmpty()) throw RuntimeException("В JSON не найдено ни одной записи памяти")

        val merged = (loadMemories() + parsed)
            .distinctBy { "${it.dateRu}|${it.summary}" }
            .sortedBy { it.timestamp }
            .takeLast(50)
        saveMemories(merged)
        Log.d(TAG, "Imported ${parsed.size} memories, total ${merged.size}")
        parsed.size
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
