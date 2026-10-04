package com.vectorheart.asuna.localai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
        val description: String,
        val downloadUrl: String = "",
        val requiresHfToken: Boolean = false
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

    /**
     * Рекомендации для встроенного on-device инференса (MediaPipe GenAI, .task).
     * Используются через OnDeviceLlmEngine — без Termux.
     */
    val onDeviceRecommendations: List<ModelRecommendation> = listOf(
        ModelRecommendation(
            title = "Qwen 2.5 1.5B Instruct — q8, контекст 4096",
            filename = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            sizeFormatted = "1.60 ГБ",
            ramUsage = "~2.4 ГБ RAM",
            speedMi11T = "12-20 ток/сек",
            huggingFaceUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
            description = "Лучший выбор для Асуны: отлично знает русский, контекста хватает на длинную личность + память. Без логина.",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
        ),
        ModelRecommendation(
            title = "Qwen 2.5 1.5B Instruct — q8, контекст 1280",
            filename = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeFormatted = "1.60 ГБ",
            ramUsage = "~2.2 ГБ RAM",
            speedMi11T = "15-22 ток/сек",
            huggingFaceUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct",
            description = "То же качество, но быстрее и экономнее по RAM. Короткая память беседы.",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        ),
        ModelRecommendation(
            title = "Qwen 2.5 0.5B Instruct — q8 (самая лёгкая)",
            filename = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeFormatted = "0.55 ГБ",
            ramUsage = "~1.1 ГБ RAM",
            speedMi11T = "25-35 ток/сек",
            huggingFaceUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct",
            description = "Максимально быстрая и тёплая — для слабых батареек. Русский понимает, но отвечает проще.",
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        ),
        ModelRecommendation(
            title = "Phi-4-mini-instruct — q8, контекст 4096",
            filename = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task",
            sizeFormatted = "3.91 ГБ",
            ramUsage = "~4.5 ГБ RAM",
            speedMi11T = "6-11 ток/сек",
            huggingFaceUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct",
            description = "Самая умная из доступных без логина, но заметно медленнее и нужно 4+ ГБ свободной RAM.",
            downloadUrl = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.task"
        ),
        ModelRecommendation(
            title = "Gemma 3 1B-it — int4 (нужен токен HuggingFace)",
            filename = "gemma3-1b-it-int4.task",
            sizeFormatted = "0.56 ГБ",
            ramUsage = "~1.2 ГБ RAM",
            speedMi11T = "20-30 ток/сек",
            huggingFaceUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT",
            description = "Лёгкая и быстрая. Gemma — gated: вставь токен HuggingFace (бесплатно) в поле ниже.",
            downloadUrl = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.task",
            requiresHfToken = true
        ),
        ModelRecommendation(
            title = "Gemma 3n E2B-it (открыть в AI Edge Gallery)",
            filename = "gemma-3n-e2b-it.task",
            sizeFormatted = "~2.9 ГБ",
            ramUsage = "~3.5 ГБ RAM",
            speedMi11T = "8-15 ток/сек",
            huggingFaceUrl = "https://huggingface.co/litert-community/gemma-3n-E2B-it-litert-lm",
            description = "Нативная модель Google для галереи. Прямой ссылки нет — скачай через google-ai-edge/gallery.",
            downloadUrl = ""
        )
    )

    /** Модели, пригодные для встроенного on-device инференса (.task/.litertlm). */
    fun getOnDeviceModels(): List<ImportedModel> =
        getImportedModels().filter {
            it.format == "MediaPipeTask" || it.format == "LiteRT" ||
                it.fileName.endsWith(".task", ignoreCase = true) ||
                it.fileName.endsWith(".litertlm", ignoreCase = true)
        }

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
            modelsConfigFile.writeText(json.encodeToString(ListSerializer(ImportedModel.serializer()), list))
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

        val detectedFormat = when {
            displayName.endsWith(".task", ignoreCase = true) -> "MediaPipeTask"
            displayName.endsWith(".litertlm", ignoreCase = true) -> "LiteRT"
            else -> "GGUF"
        }
        val model = ImportedModel(
            id = "local_${System.currentTimeMillis()}",
            displayName = displayName.removeSuffix(".gguf").removeSuffix(".task").removeSuffix(".litertlm"),
            fileName = displayName,
            filePath = targetFile.absolutePath,
            sizeBytes = targetFile.length(),
            format = detectedFormat,
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

    /** Токен HuggingFace для скачивания gated-моделей (Gemma). */
    var hfToken: String
        get() = context.getSharedPreferences("asuna_local", Context.MODE_PRIVATE).getString("hf_token", "") ?: ""
        set(value) {
            context.getSharedPreferences("asuna_local", Context.MODE_PRIVATE).edit().putString("hf_token", value.trim()).apply()
        }

    /** Свободное место на внутреннем диске, байт. */
    fun freeDiskBytes(): Long = try {
        val stat = android.os.StatFs(context.filesDir.absolutePath)
        stat.availableBytes
    } catch (_: Exception) { -1L }

    /** Размер файла модели, если она уже скачана, иначе null. */
    fun downloadedModelBytes(rec: ModelRecommendation): Long? {
        val f = File(File(context.filesDir, "local_models"), rec.filename)
        return if (f.exists() && f.length() > 0) f.length() else null
    }

    /**
     * Скачивает модель по прямой ссылке в filesDir/local_models и регистрирует её,
     * как при импорте через SAF (подход из google-ai-edge/gallery).
     * Поддерживает докачку (Range), прогресс и токен HF для gated-моделей.
     */
    suspend fun downloadModel(
        rec: ModelRecommendation,
        onProgress: ((Int) -> Unit)? = null
    ): ImportedModel = withContext(Dispatchers.IO) {
        val url = rec.downloadUrl
        if (url.isBlank()) throw RuntimeException("Нет прямой ссылки — открой страницу HuggingFace вручную.")

        val modelsDir = File(context.filesDir, "local_models").apply { mkdirs() }
        val targetFile = File(modelsDir, rec.filename)
        val partFile = File(modelsDir, "${rec.filename}.part")

        // Уже скачана?
        if (targetFile.exists() && targetFile.length() > 1024 * 1024) {
            Log.d(TAG, "Model already downloaded: ${rec.filename}")
        } else {
            val already = if (partFile.exists()) partFile.length() else 0L
            val builder = Request.Builder().url(url).get()
            val token = hfToken
            if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
            if (already > 0) builder.header("Range", "bytes=$already-")

            httpClient.newCall(builder.build()).execute().use { resp ->
                val code = resp.code
                if (code == 401 || code == 403) {
                    throw RuntimeException("HuggingFace не пускает: нужна авторизация. Вставь токен HF в поле выше (Model Scope / gated-модель).")
                }
                if (code == 416) {
                    // Файл уже докачан полностью
                    if (partFile.exists()) partFile.renameTo(targetFile)
                } else if (!resp.isSuccessful) {
                    throw RuntimeException("Ошибка загрузки HTTP $code")
                } else {
                    val body = resp.body ?: throw RuntimeException("Пустой ответ сервера")
                    val total = resp.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: body.contentLength()
                    val fullSize = if (total > 0) (if (already > 0) already + total else total) else -1L

                    // Проверка свободного места перед записью
                    if (fullSize > 0) {
                        val free = freeDiskBytes()
                        if (free in 0..(fullSize / 2)) {
                            throw RuntimeException("Мало места: нужно ~${fullSize / 1024 / 1024} МБ, свободно ${free / 1024 / 1024} МБ")
                        }
                    }

                    body.byteStream().use { input ->
                        val out = if (already > 0 && code == 206) {
                            java.io.FileOutputStream(partFile, true)
                        } else {
                            java.io.FileOutputStream(partFile, false)
                        }
                        out.use { output ->
                            val buffer = ByteArray(128 * 1024)
                            var downloaded = already
                            var lastPct = -1
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                if (fullSize > 0) {
                                    val pct = ((downloaded * 100) / fullSize).toInt().coerceIn(0, 100)
                                    // Прогресс обновляем на главном потоке и не чаще 1% — иначе Compose зальётся
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        withContext(Dispatchers.Main) { onProgress?.invoke(pct) }
                                    }
                                }
                            }
                        }
                    }
                    if (!partFile.renameTo(targetFile)) {
                        partFile.copyTo(targetFile, overwrite = true)
                        partFile.delete()
                    }
                    onProgress?.invoke(100)
                }
            }
        }

        if (!targetFile.exists() || targetFile.length() < 1024 * 1024) {
            throw RuntimeException("Файл модели не загрузился (повреждённая загрузка) — попробуй ещё раз")
        }

        val format = when {
            rec.filename.endsWith(".task", ignoreCase = true) -> "MediaPipeTask"
            rec.filename.endsWith(".litertlm", ignoreCase = true) -> "LiteRT"
            else -> "GGUF"
        }
        val model = ImportedModel(
            id = "local_${rec.filename}",
            displayName = rec.filename.substringBeforeLast('.'),
            fileName = rec.filename,
            filePath = targetFile.absolutePath,
            sizeBytes = targetFile.length(),
            format = format,
            recommendedThreads = 4,
            recommendedContext = if (rec.filename.contains("ekv4096")) 4096 else 1280
        )
        val list = getImportedModels().toMutableList()
        list.removeAll { it.fileName == rec.filename }
        list.add(model)
        saveImportedModels(list)
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
