package com.vectorheart.asuna.memory

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Синхронизация долговременной памяти Асуны с Firebase Firestore.
 *
 * Чтобы включить: положи свой `google-services.json` в `app/`.
 * Без него код просто сообщит, что облако не настроено.
 *
 * Коллекция: asuna_memory / документы по timestamp-строке.
 */
object MemoryCloudSync {
    private const val TAG = "MemoryCloudSync"
    private const val COLLECTION = "asuna_memory"

    fun isAvailable(context: Context): Boolean {
        return try {
            FirebaseApp.getInstance()
            true
        } catch (_: Exception) {
            try {
                FirebaseApp.initializeApp(context)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** Выгружает все локальные записи памяти в Firestore. Возвращает число записей. */
    suspend fun pushMemories(context: Context, entries: List<MemoryManager.MemoryEntry>): Int {
        if (!isAvailable(context)) throw RuntimeException("Firebase не настроен: добавь app/google-services.json")
        val db = FirebaseFirestore.getInstance()
        var count = 0
        for (e in entries) {
            db.collection(COLLECTION)
                .document(e.timestamp.toString())
                .set(
                    mapOf(
                        "timestamp" to e.timestamp,
                        "dateRu" to e.dateRu,
                        "summary" to e.summary
                    )
                ).await()
            count++
        }
        Log.d(TAG, "Pushed $count memories to Firestore")
        return count
    }

    /** Скачивает записи памяти из Firestore. */
    suspend fun pullMemories(context: Context): List<MemoryManager.MemoryEntry> {
        if (!isAvailable(context)) throw RuntimeException("Firebase не настроен: добавь app/google-services.json")
        val db = FirebaseFirestore.getInstance()
        val snap = db.collection(COLLECTION).get().await()
        return snap.documents.mapNotNull { d ->
            val ts = d.getLong("timestamp") ?: return@mapNotNull null
            val dateRu = d.getString("dateRu") ?: return@mapNotNull null
            val summary = d.getString("summary") ?: return@mapNotNull null
            MemoryManager.MemoryEntry(timestamp = ts, dateRu = dateRu, summary = summary)
        }
    }
}
