package com.vectorheart.asuna.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Модульная система инструментов для Асуны (порт tools/index.js из PC-версии).
 *
 * Поддерживает:
 *  - calendar_add: добавление событий в локальный календарь
 *  - calendar_list: список ближайших событий
 *  - calendar_remove: удаление события
 *  - web_search: поиск в интернете (DuckDuckGo API / HTML, не требует платных ключей)
 *  - device_status: опрос состояния телефона (Xiaomi Mi 11T, заряд батареи, время)
 */
@Singleton
class ToolRegistry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient
) {
    @Serializable
    data class CalendarEvent(
        val id: String = UUID.randomUUID().toString().take(8),
        val title: String,
        val date: String,
        val time: String? = null,
        val notes: String? = null,
        val createdAt: Long = System.currentTimeMillis()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
    }

    private val calendarFile: File
        get() = File(context.filesDir, "vectorheart_calendar.json")

    private fun loadCalendarEvents(): MutableList<CalendarEvent> {
        return try {
            if (!calendarFile.exists()) return mutableListOf()
            val text = calendarFile.readText()
            if (text.isBlank()) return mutableListOf()
            json.decodeFromString<List<CalendarEvent>>(text).toMutableList()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load calendar events: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveCalendarEvents(events: List<CalendarEvent>) {
        try {
            calendarFile.writeText(json.encodeToString(ListSerializer(CalendarEvent.serializer()), events))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save calendar events: ${e.message}")
        }
    }

    fun describeTools(): String {
        return """
- calendar_add: Добавить событие или заметку в календарь пользователя.
  Аргументы: {"title": string, "date": "YYYY-MM-DD", "time": "HH:MM" (необязательно), "notes": string (необязательно)}
- calendar_list: Показать ближайшие события календаря пользователя.
  Аргументы: {"days": number (необязательно, по умолчанию 14)}
- calendar_remove: Удалить событие календаря по id (id можно узнать через calendar_list).
  Аргументы: {"id": string}
- web_search: Поиск актуальной информации в интернете: места, погода, новости, ответы на вопросы.
  Аргументы: {"query": string}
- device_status: Узнать состояние телефона пользователя (модель, заряд батареи, текущее время).
  Аргументы: {}
- send_notification: Отправить пользователю уведомление на телефон от имени Асуны.
  Аргументы: {"title": string, "text": string}
        """.trimIndent()
    }

    suspend fun executeTool(name: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing tool '$name' with args: $args")
        try {
            when (name) {
                "calendar_add" -> executeCalendarAdd(args)
                "calendar_list" -> executeCalendarList(args)
                "calendar_remove" -> executeCalendarRemove(args)
                "web_search" -> executeWebSearch(args)
                "device_status" -> executeDeviceStatus()
                "send_notification" -> executeSendNotification(args)
                else -> "Неизвестный инструмент '$name'."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution error ($name): ${e.message}", e)
            "Ошибка при выполнении инструмента $name: ${e.message}"
        }
    }

    private fun executeCalendarAdd(args: JsonObject): String {
        val title = args["title"]?.jsonPrimitive?.contentOrNull ?: return "Ошибка: не указано название события (title)."
        val date = args["date"]?.jsonPrimitive?.contentOrNull ?: SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val time = args["time"]?.jsonPrimitive?.contentOrNull
        val notes = args["notes"]?.jsonPrimitive?.contentOrNull

        val events = loadCalendarEvents()
        val event = CalendarEvent(
            title = title,
            date = date,
            time = time,
            notes = notes
        )
        events.add(event)
        saveCalendarEvents(events)

        return "Добавлено в календарь: \"$title\" на $date${if (time != null) " $time" else ""} (id: ${event.id})"
    }

    private fun executeCalendarList(args: JsonObject): String {
        val days = args["days"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 14
        val events = loadCalendarEvents()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_YEAR, days)
        val end = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
        val upcoming = events
            .filter { it.date >= today && it.date <= end }
            .sortedWith(compareBy({ it.date }, { it.time ?: "" }))
        if (upcoming.isEmpty()) return "В календаре пока нет событий на ближайшие $days дн."

        return buildString {
            append("Ближайшие события в календаре:\n")
            upcoming.take(10).forEach { e ->
                append("- [${e.id}] ${e.date}${if (e.time != null) " ${e.time}" else ""} — ${e.title}")
                if (!e.notes.isNullOrBlank()) append(" (${e.notes})")
                append("\n")
            }
        }.trimEnd()
    }

    private fun executeCalendarRemove(args: JsonObject): String {
        val id = args["id"]?.jsonPrimitive?.contentOrNull ?: return "Ошибка: не указан id события."
        val events = loadCalendarEvents()
        // Сначала точное/регистронезависимое совпадение по id
        val byId = events.filter { it.id.equals(id, ignoreCase = true) }
        if (byId.isNotEmpty()) {
            events.removeAll(byId.toSet())
            saveCalendarEvents(events)
            return "Событие ${byId.first().id} успешно удалено из календаря."
        }
        // По названию — только если совпадение ровно одно, иначе просим уточнить
        val byTitle = events.filter { it.title.equals(id, ignoreCase = true) }
        if (byTitle.size == 1) {
            events.remove(byTitle.first())
            saveCalendarEvents(events)
            return "Событие \"${byTitle.first().title}\" успешно удалено из календаря."
        }
        if (byTitle.size > 1) {
            return "Найдено несколько событий с названием '$id'. Уточни по id из calendar_list: ${byTitle.joinToString { it.id }}"
        }
        return "Событие с id или названием '$id' не найдено."
    }

    private fun executeWebSearch(args: JsonObject): String {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrBlank()) return "Пустой поисковый запрос."

        // DuckDuckGo Instant Answer API (бесплатно, без ключей, мгновенный ответ)
        val url = "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(query, "UTF-8")}&format=json&no_html=1&skip_disambig=1"
        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14; Xiaomi Mi 11T) AsunaCompanion/1.0")
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use "Поиск временно недоступен (HTTP ${resp.code})"
                val body = resp.body?.string() ?: return@use "Пустой ответ поисковика"
                val root = json.parseToJsonElement(body) as? JsonObject
                val answer = root?.get("AbstractText")?.jsonPrimitive?.contentOrNull
                val source = root?.get("AbstractSource")?.jsonPrimitive?.contentOrNull

                if (!answer.isNullOrBlank()) {
                    "Результат поиска по \"$query\":\n$answer ${if (source != null) "($source)" else ""}"
                } else {
                    // Fallback to related topics: собираем читаемые заголовки
                    val topics = root?.get("RelatedTopics") as? JsonArray
                    val snippets = topics?.mapNotNull { t ->
                        val obj = t as? JsonObject ?: return@mapNotNull null
                        obj["Text"]?.jsonPrimitive?.contentOrNull
                            ?: (obj["Topics"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                                ?.mapNotNull { it["Text"]?.jsonPrimitive?.contentOrNull }?.firstOrNull()
                    }?.filter { it.isNotBlank() }?.take(5)
                    if (!snippets.isNullOrEmpty()) {
                        "По запросу \"$query\" найдены материалы:\n" + snippets.joinToString("\n") { "- $it" }
                    } else {
                        "По запросу \"$query\" прямых быстрых фактов не найдено, поищи с более точными формулировками."
                    }
                }
            }
        } catch (e: Exception) {
            "Ошибка поиска в интернете: ${e.message}"
        }
    }

    private fun executeDeviceStatus(): String {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        val timeStr = SimpleDateFormat("HH:mm", Locale("ru")).format(Date())
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}" // e.g. Xiaomi Mi 11T

        return "Статус устройства: модель $deviceModel, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), время $timeStr, батарея $batteryPct%."
    }

    private fun executeSendNotification(args: JsonObject): String {
        val title = args["title"]?.jsonPrimitive?.contentOrNull ?: "Асуна"
        val text = args["text"]?.jsonPrimitive?.contentOrNull
            ?: return "Ошибка: не указан текст уведомления (text)."

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            ?: return "Ошибка: NotificationManager недоступен"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                "asuna_proactive", "Асуна", android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Уведомления от Асуны" }
            nm.createNotificationChannel(channel)
        }

        val notif = androidx.core.app.NotificationCompat.Builder(context, "asuna_proactive")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notif)
        return "Уведомление отправлено: \"$title — $text\""
    }

    companion object {
        private const val TAG = "ToolRegistry"
    }
}
