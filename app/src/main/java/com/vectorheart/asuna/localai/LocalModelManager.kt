package com.vectorheart.asuna.localai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Менеджер локальных ИИ-моделей (GGUF / On-Device)
 * Специально оптимизирован под Xiaomi Mi 11T (MediaTek Dimensity 1200, 8GB RAM, Android 14).
 */
@Singleton
class LocalModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    @Serializable
    data class ImportedModel(
        val id: String,
        val displayName: String,
        val fileName: String,
        val filePath: String,
        val sizeBytes: Long,
        val format: String = "GGUF",
        val recommendedThreads: Int = 4,
        val recommendedContext: Int = 2048,
        val addedAt: Long = System.currentTimeMillis()
    )

    data class ModelRecommendation(
        val title: String,
        val filename: String,
        val sizeFormatted: String,
        val ramUsage: String,
        val speedMi11T: String,
        val huggingFaceUrl: String,
        val description: String
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    private val modelsConfigFile: File
        get() = File(context.filesDir, "asuna_local_models.json")

    /**
     * Проверенные и оптимизированные модели для Xiaomi Mi 11T (Dimensity 1200, 8GB RAM)
     */
    val recommendations: List<ModelRecommendation> = listOf(
        ModelRecommendation(
            title = "Qwen 2.5 1.5B Instruct (Q4_K_M)",
            filename = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeFormatted = "1.05 ГБ",
            ramUsage = "~1.4 ГБ RAM",
            speedMi11T = "26-32 ток/сек (очень быстро)",
            huggingFaceUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            description = "Лучший выбор для живого голосового компаньона: великолепно понимает русский язык, мгновенно отвечает."
        ),
        ModelRecommendation(
            title = "Llama 3.2 1B Instruct (Q4_K_M)",
            filename = "llama-3.2-1b-instruct-q4_k_m.gguf",
            sizeFormatted = "780 МБ",
            ramUsage = "~1.1 ГБ RAM",
            speedMi11T = "35-42 ток/сек (максимальная скорость)",
            huggingFaceUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF",
            description = "Ультра-легкая модель от Meta. Минимальный нагрев батареи Mi 11T."
        ),
        ModelRecommendation(
            title = "Llama 3.2 3B Instruct (Q4_K_M)",
            filename = "llama-3.2-3b-instruct-q4_k_m.gguf",
            sizeFormatted = "2.02 ГБ",
            ramUsage = "~2.7 ГБ RAM",
            speedMi11T = "16-20 ток/сек (плавно)",
            huggingFaceUrl = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF",
            description = "Высокий интеллект и тонкое чувство контекста при умеренном потреблении памяти."
        ),
        ModelRecommendation(
            title = "Gemma 2 2B IT (Q4_K_M)",
            filename = "gemma-2-2b-it-q4_k_m.gguf",
            sizeFormatted = "1.65 ГБ",
            ramUsage = "~2.2 ГБ RAM",
            speedMi11T = "19-24 ток/сек (быстро)",
            huggingFaceUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF",
            description = "Модель от Google DeepMind — прекрасна для ролевого общения и диалогов Асуны."
        )
    )

    fun getImportedModels(): List<ImportedModel> {
        return try {
            if (!modelsConfigFile.exists()) return emptyList()
            val text = modelsConfigFile.readText()
            if (text.isBlank()) return emptyList()
            json.decodeFromString<List<ImportedModel>>(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load imported models: ${e.message}")
            emptyList()
        }
    }

    private fun saveImportedModels(list: List<ImportedModel>) {
        try {
            modelsConfigFile.writeText(json.encodeToString(list))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save models config: ${e.message}")
        }
    }

    /**
     * Импорт .gguf файла через Android SAF (Storage Access Framework)
     */
    suspend fun importModelFromUri(uri: Uri): ImportedModel = withContext(Dispatchers.IO) {
        var displayName = "model.gguf"
        var size: Long = 0

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) displayName = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }

        // Сохраняем модель в приватную папку models или регистрируем постоянный путь
        val modelsDir = File(context.filesDir, "local_models").apply { mkdirs() }
        val targetFile = File(modelsDir, displayName)

        // Копируем поток из SAF в локальный файл
        context.contentResolver.openInputStream(uri)?.use { input: InputStream ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val model = ImportedModel(
            id = "local_${System.currentTimeMillis()}",
            displayName = displayName.removeSuffix(".gguf"),
            fileName = displayName,
            filePath = targetFile.absolutePath,
            sizeBytes = targetFile.length(),
            recommendedThreads = 4, // 4 ядра Cortex-A78 на Dimensity 1200
            recommendedContext = 2048
        )

        val list = getImportedModels().toMutableList()
        list.removeAll { it.fileName == displayName }
        list.add(model)
        saveImportedModels(list)

        Log.d(TAG, "Imported local model: ${model.displayName} (${model.sizeBytes} bytes)")
        model
    }

    fun removeModel(id: String) {
        val list = getImportedModels().toMutableList()
        val found = list.find { it.id == id }
        if (found != null) {
            try {
                val f = File(found.filePath)
                if (f.exists()) f.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Error deleting file ${found.filePath}: ${e.message}")
            }
            list.remove(found)
            saveImportedModels(list)
        }
    }

    /**
     * Генерирует готовые команды для запуска сервера в Termux на Xiaomi Mi 11T
     */
    fun getTermuxServerCommand(model: ImportedModel? = null): String {
        val modelPath = model?.filePath ?: "/sdcard/Download/qwen2.5-1.5b-instruct-q4_k_m.gguf"
        // На Dimensity 1200: -t 4 использует ровно 4 производительных ядра Cortex-A78 (3.0 + 2.6 ГГц)
        return "llama-server -m \"$modelPath\" -c 2048 -t 4 --port 8080 --host 127.0.0.1"
    }

    fun getTermuxQuickSetupGuide(): String {
        return """
# Установка и запуск офлайн-сервера в Termux на Xiaomi Mi 11T:
1. Установи Termux и Termux:API (F-Droid).
2. Запусти команду установки llama.cpp:
   pkg update -y && pkg install clang cmake git -y
   git clone https://github.com/ggerganov/llama.cpp && cd llama.cpp
   cmake -B build -DLLAMA_OPENMP=ON && cmake --build build --config Release -j4
3. Разреши доступ к файлам:
   termux-setup-storage
4. Запусти сервер с твоей моделью:
   ./build/bin/llama-server -m ~/storage/downloads/qwen2.5-1.5b-instruct-q4_k_m.gguf -c 2048 -t 4 --port 8080 --host 127.0.0.1
        """.trimIndent()
    }

    /**
     * Проверка доступности локального HTTP сервера (Termux / Ollama)
     */
    suspend fun checkLocalServerHealth(baseUrl: String = "http://127.0.0.1:8080"): Boolean = withContext(Dispatchers.IO) {
        try {
            val root = baseUrl.trimEnd('/')
            val testUrl = if (root.endsWith("/v1")) "$root/models" else "$root/v1/models"
            val req = Request.Builder()
                .url(testUrl)
                .get()
                .build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        private const val TAG = "LocalModelManager"
    }
}
