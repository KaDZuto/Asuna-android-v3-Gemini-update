package com.vectorheart.asuna.llm

import android.content.Context
import android.util.Log
import com.vectorheart.asuna.localai.OnDeviceLlmEngine
import com.vectorheart.asuna.memory.MemoryManager
import com.vectorheart.asuna.pickup.PickupMode
import com.vectorheart.asuna.tools.ToolRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Главный репозиторий для координации LLM, памяти, инструментов и режимов.
 *
 * Полностью объединяет функционал из ПК-версии (hermes_paperclip_agent):
 *  - Инструменты (календарь, веб-поиск, статус устройства) с автоматическим циклом вызова
 *  - Долгосрочная память ("Режим сна")
 *  - Тренажёр знакомств (пикап-режим со случайными сценариями)
 *  - Системное время на русском языке
 */
@Singleton
class LlmRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    val llmClient: LlmClient,
    val toolRegistry: ToolRegistry,
    val memoryManager: MemoryManager,
    val onDeviceLlmEngine: OnDeviceLlmEngine
) {
    companion object {
        private const val TAG = "LlmRepository"
        const val MODE_COMPANION = "companion"
        const val MODE_PICKUP = "pickup"
    }

    var currentMode: String = MODE_COMPANION
        private set

    var activePickupScenario: PickupMode.Scenario? = null
        private set

    var proactivity: String = "occasional" // "asked" | "occasional" | "never"

    fun startPickupMode(): PickupMode.Scenario {
        currentMode = MODE_PICKUP
        val scenario = PickupMode.generateScenario()
        activePickupScenario = scenario
        Log.d(TAG, "Started pickup mode: ${scenario.location}, ${scenario.situation}")
        return scenario
    }

    fun exitPickupMode() {
        currentMode = MODE_COMPANION
        activePickupScenario = null
        Log.d(TAG, "Exited pickup mode, back to companion")
    }

    fun isPickupMode(): Boolean = currentMode == MODE_PICKUP

    /**
     * Формирует системный промпт с учетом текущей даты, памяти и инструментов
     */
    fun buildSystemPrompt(): String {
        if (currentMode == MODE_PICKUP && activePickupScenario != null) {
            return PickupMode.buildPickupSystemPrompt(activePickupScenario!!)
        }

        val proactivityInstruction = when (proactivity) {
            "asked" -> "Используй инструменты только когда пользователь сам явно об этом попросил."
            "never" -> "Никогда не используй инструменты по своей инициативе."
            else -> "Изредка, когда это уместно в контексте разговора (планы, поиск информации), можешь предложить воспользоваться инструментом."
        }

        val nowRu = formatNowRu()
        val toolsDesc = toolRegistry.describeTools()
        val memoryContext = memoryManager.getMemoryPromptContext()

        // Персональные настройки личности из SharedPreferences
        val prefs = context.getSharedPreferences("asuna_settings", android.content.Context.MODE_PRIVATE)
        val userName = prefs.getString("persona_user_name", "")?.trim().orEmpty()
        val personaExtra = prefs.getString("persona_extra", "")?.trim().orEmpty()

        return buildString {
            append(LlmClient.BASE_SYSTEM_PROMPT)
            if (userName.isNotBlank()) {
                append("\n\n== ИМЯ ПОЛЬЗОВАТЕЛЯ ==\n\nПользователя зовут $userName. Обращайся к нему по имени, когда уместно.")
            }
            if (personaExtra.isNotBlank()) {
                append("\n\n== ДОПОЛНИТЕЛЬНЫЕ ПРАВИЛА ЛИЧНОСТИ ==\n\n$personaExtra")
            }
            append("\n\n== ТЕКУЩАЯ ДАТА И ВРЕМЯ ==\n")
            append("Сейчас: $nowRu. Всегда используй это системное время для ответов.\n\n")

            append("== ИНСТРУМЕНТЫ ==\n")
            append("Если для ответа необходимо действие (календарь, веб-поиск, статус устройства) — ответь СТРОГО одним тегом:\n")
            append("<tool>{\"name\":\"имя_инструмента\",\"args\":{...}}</tool>\n")
            append("Тебе придёт результат работы инструмента, после чего ты дашь финальный голосовой ответ с блоком <live2d>.\n\n")
            append("Доступные инструменты:\n")
            append(toolsDesc)
            append("\n\n== ИНИЦИАТИВА ==\n")
            append(proactivityInstruction)

            if (memoryContext.isNotBlank()) {
                append("\n\n")
                append(memoryContext)
            }
        }
    }

    private fun formatNowRu(): String {
        val days = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")
        val months = arrayOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

        val cal = java.util.Calendar.getInstance()
        val dayName = days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        val dayNum = cal.get(java.util.Calendar.DAY_OF_MONTH)
        val monthName = months[cal.get(java.util.Calendar.MONTH)]
        val year = cal.get(java.util.Calendar.YEAR)
        val timeStr = SimpleDateFormat("HH:mm", Locale("ru")).format(Date())

        return "$dayName, $dayNum $monthName $year года, $timeStr"
    }

    /**
     * Отправляет сообщение модели с автоматическим исполнением инструментов (Tool Calling loop)
     */
    suspend fun sendMessage(
        userText: String,
        history: List<Pair<String, String>>,
        apiKey: String?,
        apiType: String,
        model: String?,
        baseUrl: String?,
        onToolExecuting: ((String) -> Unit)? = null
    ): LlmClient.ParseResult = withContext(Dispatchers.IO) {
        val sysPrompt = buildSystemPrompt()

        Log.d(TAG, "Calling LLM: '$userText' [mode=$currentMode, provider=$apiType]")
        val firstRaw = if (apiType.equals("local-ondevice", ignoreCase = true)) {
            val path = model?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Не выбрана on-device .task модель (Настройки → Локальные модели → On-Device)")
            onDeviceLlmEngine.ensureLoaded(path)
            val promptText = buildString {
                append(sysPrompt)
                append("\n\n")
                history.takeLast(10).forEach { (role, content) ->
                    append(if (role == "user") "Пользователь: " else "Асуна: ")
                    append(content)
                    append("\n")
                }
                append("Пользователь: $userText\nАсуна:")
            }
            onDeviceLlmEngine.generate(promptText)
        } else {
            llmClient.callLLM(
                userMessage = userText,
                history = history,
                apiKey = apiKey,
                apiType = apiType,
                model = model,
                baseUrl = baseUrl,
                systemPrompt = sysPrompt
            )
        }

        val firstParsed = llmClient.parseResponse(firstRaw)

        // Если модель запросила вызов инструмента (<tool>...</tool>)
        if (firstParsed.toolCall != null) {
            val tool = firstParsed.toolCall
            Log.d(TAG, "Tool call detected: ${tool.name} with ${tool.args}")
            onToolExecuting?.invoke("Выполняю ${tool.name}...")

            val toolResult = toolRegistry.executeTool(tool.name, tool.args)
            Log.d(TAG, "Tool result: $toolResult")

            // Создаем расширенную историю с вызовом инструмента и результатом
            val updatedHistory = history.toMutableList().apply {
                add("user" to userText)
                add("assistant" to "<tool>{\"name\":\"${tool.name}\"}</tool>")
            }

            val followUpPrompt = "Результат работы инструмента ${tool.name}:\n$toolResult\n\nТеперь дай естественный ответ пользователю на русском языке с блоком <live2d> в конце."
            val finalRaw = if (apiType.equals("local-ondevice", ignoreCase = true)) {
                onDeviceLlmEngine.generate("$sysPrompt\n\n$followUpPrompt")
            } else {
                llmClient.callLLM(
                    userMessage = followUpPrompt,
                    history = updatedHistory,
                    apiKey = apiKey,
                    apiType = apiType,
                    model = model,
                    baseUrl = baseUrl,
                    systemPrompt = sysPrompt
                )
            }

            val finalParsed = llmClient.parseResponse(finalRaw)
            return@withContext finalParsed
        }

        return@withContext firstParsed
    }

    /**
     * Запуск режима сна: суммаризация и сохранение в долговременную память
     */
    suspend fun sleepAndSummarize(
        history: List<Pair<String, String>>,
        apiKey: String?,
        apiType: String,
        model: String?,
        baseUrl: String?
    ): String {
        return memoryManager.summarizeAndSave(
            history = history,
            llmClient = llmClient,
            apiKey = apiKey,
            apiType = apiType,
            model = model,
            baseUrl = baseUrl
        )
    }
}
