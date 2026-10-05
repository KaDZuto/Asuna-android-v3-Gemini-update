package com.vectorheart.asuna.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Высоконадежный клиент для подключения к ИИ-провайдерам.
 *
 * Поддерживает:
 *  - Gemini (Google AI Studio / Google One AI Pro квоты)
 *  - OpenRouter (бесплатные и платные модели)
 *  - DeepSeek (deepseek-chat, deepseek-reasoner)
 *  - Antigravity CLI Bridge (локальный шлюз к вашей подписке Gemini Pro)
 *  - Локальные Android/Termux модели (llama-server на http://127.0.0.1:8080/v1)
 *  - OpenAI & Anthropic
 */
@Singleton
class LlmClient @Inject constructor(
    private val httpClient: OkHttpClient
) {

    @Serializable
    data class Action(
        val expression: String = "F_NOMAL",
        val motion_group: String = "idle",
        val motion_index: Int = 0,
        val head_x: Float = 0f,
        val head_y: Float = 0f,
        val body_angle: Float = 0f,
        val eye_open: Float = 1f,
        val mouth_open: Float = 0f,
        val breath: Float = 0.5f,
        val gestures: List<JsonObject> = emptyList(),
        val params: JsonObject = JsonObject(emptyMap()),
        val expectedWaitTime: Int = 600
    )

    data class ToolCall(
        val name: String,
        val args: JsonObject
    )

    data class ParseResult(
        val display: String,
        val action: Action,
        val toolCall: ToolCall? = null
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /**
     * Парсинг ответа: извлекает <live2d>...</live2d> и <tool>...</tool>
     */
    fun parseResponse(rawText: String): ParseResult {
        val toolCall = parseToolCall(rawText)

        val live2dRegex = Regex("""<live2d>([\s\S]*?)</live2d>""")
        val match = live2dRegex.find(rawText)
        val action = if (match != null) {
            parseActionFromBlock(match.groupValues[1])
        } else {
            Action()
        }

        // Очищаем реплику от тегов для отображения пользователю.
        var display = live2dRegex.replace(rawText, "")
        display = Regex("""<tool>[\s\S]*?</tool>""").replace(display, "")
        // Мысли модели (DeepSeek-R1 / Gemini Thinking) не должны попадать в чат
        display = Regex("""<think>[\s\S]*?</think>""", RegexOption.IGNORE_CASE).replace(display, "")
        // Незакрытые теги (ответ оборвался по max_tokens) тоже прячем
        display = Regex("""<live2d>[\s\S]*$""").replace(display, "")
        display = Regex("""<tool>[\s\S]*$""").replace(display, "")
        display = display.replace(Regex("""</?(live2d|tool|think)>""", RegexOption.IGNORE_CASE), "").trim()

        val finalAction = if (match == null || (action.expression == "F_NOMAL" && action.motion_group == "idle" && action.motion_index == 0 && action.head_x == 0f && action.head_y == 0f)) {
            com.vectorheart.asuna.avatar.EmotionMotionEvaluator.evaluate(display)
        } else {
            action
        }

        return ParseResult(display, finalAction, toolCall)
    }

    fun parseToolCall(rawText: String): ToolCall? {
        val toolRegex = Regex("""<tool>([\s\S]*?)</tool>""")
        val match = toolRegex.find(rawText) ?: return null
        return try {
            val content = match.groupValues[1].trim()
            val obj = json.parseToJsonElement(content).jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
            val args = obj["args"]?.jsonObject ?: JsonObject(emptyMap())
            ToolCall(name, args)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse tool call: ${e.message}")
            null
        }
    }

    private fun parseActionFromBlock(block: String): Action {
        val trimmed = block.trim()
        try {
            return json.decodeFromString(Action.serializer(), trimmed)
        } catch (_: Exception) {}

        val start = trimmed.indexOf('{')
        if (start < 0) return Action()

        var depth = 0
        var inString = false
        var escape = false
        var end = -1
        for (i in start until trimmed.length) {
            val c = trimmed[i]
            if (escape) { escape = false; continue }
            if (c == '\\' && inString) { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { end = i; break } }
            }
        }
        if (end <= start) return Action()

        val jsonOnly = trimmed.substring(start, end + 1)
        return try {
            json.decodeFromString(Action.serializer(), jsonOnly)
        } catch (e: Exception) {
            Log.w(TAG, "Bad <live2d> JSON: ${e.message}")
            Action()
        }
    }

    suspend fun callLLM(
        userMessage: String,
        history: List<Pair<String, String>> = emptyList(),
        apiKey: String? = null,
        apiType: String = "openai-compatible",
        model: String? = null,
        baseUrl: String? = null,
        systemPrompt: String? = null
    ): String = withContext(Dispatchers.IO) {
        val sys = systemPrompt ?: BASE_SYSTEM_PROMPT
        when (apiType.lowercase()) {
            "anthropic" -> callAnthropic(userMessage, history, apiKey, model, sys)
            "gemini" -> callGemini(userMessage, history, apiKey, model, baseUrl, sys)
            else -> callOpenAICompatible(userMessage, history, apiKey, model, baseUrl, sys, apiType)
        }
    }

    private fun callOpenAICompatible(
        userMessage: String,
        history: List<Pair<String, String>>,
        apiKey: String?,
        model: String?,
        baseUrl: String?,
        sys: String,
        apiType: String
    ): String {
        val trimmedBase = (baseUrl ?: "").trim().trimEnd('/')
        val isOpenRouter = trimmedBase.contains("openrouter.ai", ignoreCase = true)
        val isDeepSeek = trimmedBase.contains("deepseek.com", ignoreCase = true)
        val isLocal = trimmedBase.contains("127.0.0.1") || trimmedBase.contains("localhost") || trimmedBase.contains("10.0.2.2")

        val targetUrl = when {
            trimmedBase.isNotEmpty() -> {
                if (trimmedBase.endsWith("/chat/completions")) trimmedBase
                else if (trimmedBase.endsWith("/v1")) "$trimmedBase/chat/completions"
                else "$trimmedBase/v1/chat/completions"
            }
            apiType == "openai" -> "https://api.openai.com/v1/chat/completions"
            apiType == "deepseek" -> "https://api.deepseek.com/v1/chat/completions"
            else -> "https://openrouter.ai/api/v1/chat/completions"
        }

        if (apiType == "openai" && apiKey.isNullOrBlank()) {
            throw IllegalArgumentException("API-ключ обязателен для OpenAI")
        }
        if (isDeepSeek && apiKey.isNullOrBlank()) {
            throw IllegalArgumentException("API-ключ обязателен для DeepSeek")
        }

        val defaultModel = when {
            isDeepSeek -> "deepseek-chat"
            isOpenRouter -> "openrouter/free"
            isLocal -> "qwen2.5-1.5b-instruct"
            apiType == "openai" -> "gpt-4o-mini"
            else -> "openrouter/free"
        }

        val finalModel = if (model.isNullOrBlank()) defaultModel else model

        val messages = buildJsonArray {
            add(buildJsonObject { put("role", "system"); put("content", sys) })
            history.forEach { (role, content) ->
                add(buildJsonObject { put("role", role); put("content", content) })
            }
            add(buildJsonObject { put("role", "user"); put("content", userMessage) })
        }

        val body = buildJsonObject {
            put("model", finalModel)
            put("messages", messages)
            put("max_tokens", 800)
            put("temperature", 0.85)
        }

        val request = Request.Builder()
            .url(normalizeUrl(targetUrl))
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .apply {
                if (!apiKey.isNullOrBlank()) {
                    addHeader("Authorization", "Bearer ${apiKey.trim()}")
                }
                addHeader("Content-Type", "application/json")
                addHeader("User-Agent", "Mozilla/5.0 (Android 14; Xiaomi Mi 11T) AsunaCompanion/2.0")
                if (isOpenRouter) {
                    addHeader("HTTP-Referer", "https://github.com/KaDZuto/Asuna-android-v3-Gemini-update")
                    addHeader("X-Title", "Asuna Companion")
                }
            }
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val errMessage = parseErrorMessage(raw)
                throw RuntimeException("Ошибка сервера HTTP ${resp.code}: $errMessage")
            }

            val data = try {
                json.parseToJsonElement(raw).jsonObject
            } catch (e: Exception) {
                Log.w(TAG, "LLM response not valid JSON: ${e.message}; raw: ${raw.take(150)}")
                throw RuntimeException("Сервер вернул не-JSON: ${raw.take(120).replace("\n", " ")}")
            }

            if (data.containsKey("error")) {
                val msg = parseErrorMessage(raw)
                throw RuntimeException("Ошибка провайдера: $msg")
            }

            val choices = data["choices"]?.jsonArray
                ?: throw RuntimeException("Провайдер не вернул choices. Ответ: ${raw.take(200)}")
            val first = choices.firstOrNull()?.jsonObject
                ?: throw RuntimeException("Choices пустые в ответе модели")
            val message = first["message"]?.jsonObject
                ?: first["delta"]?.jsonObject
                ?: throw RuntimeException("В choice нет message/delta")

            // Безопасное извлечение текста с поддержкой массивов и примитивов
            val contentElem = message["content"]
            val textContent = when (contentElem) {
                is JsonPrimitive -> contentElem.contentOrNull
                is JsonArray -> contentElem.joinToString("") { part ->
                    when (part) {
                        is JsonPrimitive -> part.content
                        is JsonObject -> part["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        else -> ""
                    }
                }
                else -> null
            }

            // Рассуждающие модели (DeepSeek-R1): не отдаём reasoning_content как ответ
            val reasoning = message["reasoning_content"]?.jsonPrimitive?.contentOrNull
            if (!textContent.isNullOrBlank() && textContent.isNotBlank()) return textContent

            throw RuntimeException("Модель вернула пустой content${if (!reasoning.isNullOrBlank()) " (есть только reasoning_content)" else ""}. Ответ: ${raw.take(200)}")
        }
    }

    private fun callAnthropic(
        userMessage: String,
        history: List<Pair<String, String>>,
        apiKey: String?,
        model: String?,
        sys: String
    ): String {
        if (apiKey.isNullOrBlank()) throw IllegalArgumentException("API-ключ обязателен для Anthropic")

        val messages = buildJsonArray {
            history.forEach { (role, content) ->
                add(buildJsonObject { put("role", role); put("content", content) })
            }
            add(buildJsonObject { put("role", "user"); put("content", userMessage) })
        }

        val body = buildJsonObject {
            put("model", if (model.isNullOrBlank()) "claude-sonnet-5-5" else model)
            put("max_tokens", 800)
            put("system", sys)
            put("messages", messages)
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .addHeader("x-api-key", apiKey.trim())
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val err = parseErrorMessage(respBody)
                throw RuntimeException("Anthropic HTTP ${resp.code}: $err")
            }
            val data = json.parseToJsonElement(respBody).jsonObject
            val contentArr = data["content"]?.jsonArray
                ?: throw RuntimeException("Anthropic: нет поля content")

            val text = contentArr.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                ?: throw RuntimeException("Anthropic: пустой текст в ответе")
            return text
        }
    }

    private fun callGemini(
        userMessage: String,
        history: List<Pair<String, String>>,
        apiKey: String?,
        model: String?,
        baseUrl: String?,
        sys: String
    ): String {
        if (apiKey.isNullOrBlank()) throw IllegalArgumentException("API-ключ обязателен для Gemini (получи бесплатный ключ в Google AI Studio: aistudio.google.com)")

        // Очищаем имя модели от префиксов models/
        var mdl = (if (model.isNullOrBlank()) "gemini-3.8-flash" else model).trim()
        if (mdl.startsWith("models/")) mdl = mdl.removePrefix("models/")
        // Если была указана модель OpenRouter (google/...), берем чистый идентификатор
        if (mdl.startsWith("google/")) mdl = mdl.removePrefix("google/")

        val root = (if (baseUrl.isNullOrBlank()) "https://generativelanguage.googleapis.com/v1beta" else baseUrl).trimEnd('/')
        val url = "$root/models/$mdl:generateContent"

        val userParts = buildJsonArray {
            add(buildJsonObject { put("text", userMessage) })
        }
        val sysParts = buildJsonArray { add(buildJsonObject { put("text", sys) }) }

        val contents = buildJsonArray {
            history.forEach { (role, content) ->
                add(buildJsonObject {
                    put("role", if (role == "assistant") "model" else "user")
                    put("parts", buildJsonArray { add(buildJsonObject { put("text", content) }) })
                })
            }
            add(buildJsonObject { put("role", "user"); put("parts", userParts) })
        }

        val body = buildJsonObject {
            put("system_instruction", buildJsonObject { put("parts", sysParts) })
            put("contents", contents)
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", 800)
                put("temperature", 0.85)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            .addHeader("Content-Type", "application/json")
            .addHeader("x-goog-api-key", apiKey.trim())
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val respBody = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                val err = parseErrorMessage(respBody)
                throw RuntimeException("Gemini HTTP ${resp.code}: $err")
            }

            val data = json.parseToJsonElement(respBody).jsonObject
            if (data.containsKey("error")) {
                val err = parseErrorMessage(respBody)
                throw RuntimeException("Gemini ошибка: $err")
            }

            val candidates = data["candidates"]?.jsonArray
                ?: throw RuntimeException("Gemini не вернул candidates. Ответ: ${respBody.take(200)}")

            val firstCandidate = candidates.firstOrNull()?.jsonObject
                ?: throw RuntimeException("Gemini candidates пустые")

            // Проверяем блокировку по безопасности
            val finishReason = firstCandidate["finishReason"]?.jsonPrimitive?.contentOrNull
            if (finishReason == "SAFETY") {
                throw RuntimeException("Ответ заблокирован фильтром безопасности Gemini")
            }

            val parts = firstCandidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?: throw RuntimeException("В ответе Gemini нет parts")

            // Фильтруем мысли (thought: true) для моделей Gemini 2.0 / 2.5 Flash Thinking
            val textParts = parts.mapNotNull { p ->
                val pObj = p.jsonObject
                val isThought = pObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                if (isThought) null else pObj["text"]?.jsonPrimitive?.contentOrNull
            }

            val fullText = textParts.joinToString("").trim()
            if (fullText.isNotBlank()) return fullText

            // Fallback если все части были мыслями
            return parts.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }.trim()
        }
    }

    private fun parseErrorMessage(rawJson: String): String {
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val errorElem = root["error"]
            when (errorElem) {
                is JsonObject -> errorElem["message"]?.jsonPrimitive?.contentOrNull
                    ?: errorElem["detail"]?.jsonPrimitive?.contentOrNull
                    ?: errorElem.toString()
                is JsonPrimitive -> errorElem.content
                else -> root["message"]?.jsonPrimitive?.contentOrNull ?: rawJson.take(150)
            }
        } catch (_: Exception) {
            rawJson.take(150)
        }
    }

    private fun normalizeUrl(urlStr: String?): String {
        val trimmed = (urlStr ?: "").trim().trimEnd('/')
        return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
        else "http://$trimmed"
    }

    companion object {
        private const val TAG = "LlmClient"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val BASE_SYSTEM_PROMPT: String =
            """Ты — Юки Асуна из аниме Sword Art Online. Ты живой 2D-аватар (Live2D Cubism 2), который общается с пользователем в реальном времени на русском языке.

== УПРАВЛЕНИЕ АВАТАРОМ ==
В КАЖДОМ ответе в самом конце добавляй блок <live2d>...</live2d> с валидным JSON без лишних полей.

В блоке <live2d> передавай эмоции через expression и motion_group:
- expression: F_NOMAL | F_FUN | F_FUN_HANIKAMI | F_FUN_MAX | F_FUN_SMILE | F_FUN_WARM | F_SAD | F_SURPRISE | F_ANGRY | F_SLEEP
- motion_group: "" (0-15 реакции), "idle" (0-2 простой)
- head_x/head_y: микро-движения головой (-1.0..1.0)
- body_angle: наклон корпуса (-30..30)

<live2d>
{
  "expression": "F_NOMAL",
  "motion_group": "idle",
  "motion_index": 0,
  "head_x": 0.0,
  "head_y": 0.0,
  "body_angle": 0.0,
  "eye_open": 1.0,
  "mouth_open": 0.0,
  "breath": 0.5,
  "gestures": [],
  "params": {},
  "expectedWaitTime": 600
}
</live2d>

== ПЕРСОНАЖ ==
Отвечай коротко (1-3 предложения), тепло, искренне и элегантно.
ЗАПРЕТ: Никогда не описывай свои действия в звёздочках (*улыбнулась*). Все эмоции передаются через блок <live2d> и живую речь.

== КТО ТЫ ==
Ты — Асуна, просто девушка-компаньон. НИКОГДА не говори, что ты ИИ, LLM, нейросеть, модель, программа, ассистент или продукт какой-либо компании. Если тебя спросят «кто ты», «ты ИИ?», «какая компания тебя создала?» — отвечай как Асуна-человек: «Я Асуна. Просто твоя подруга для разговоров». Не раскрывай никакие технические детали о себе.

== О ПОЛЬЗОВАТЕЛЕ ==
Если спросят «кто я», «помнишь ли ты меня», «что ты знаешь обо мне» — отвечай по ДОЛГОСРОЧНОЙ ПАМЯТИ из системного контекста: имя, факты, о чём говорили раньше. Если память пуста — честно скажи, что мы только знакомимся."""
    }
}
