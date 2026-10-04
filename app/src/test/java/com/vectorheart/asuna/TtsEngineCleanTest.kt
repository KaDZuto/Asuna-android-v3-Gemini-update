package com.vectorheart.asuna

import android.content.ContextWrapper
import com.vectorheart.asuna.tts.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsEngineCleanTest {

    private val engine: TtsEngine by lazy {
        // TtsEngine требует Context, но cleanForSpeech не использует его.
        // ContextWrapper с null-базой и переопределённым getSystemService
        // позволяет создать движок без Android-среды.
        val fakeContext = object : ContextWrapper(null) {
            override fun getSystemService(name: String): Any? = null
        }
        TtsEngine(fakeContext)
    }

    @Test
    fun `закрытый live2d удаляется`() {
        val raw = "Привет! <live2d>{\"expression\":\"F_FUN\"}</live2d>"
        assertEquals("Привет!", engine.cleanForSpeech(raw))
    }

    @Test
    fun `незакрытый live2d обрезается`() {
        val raw = "Привет! <live2d>{\"expression\":"
        assertEquals("Привет!", engine.cleanForSpeech(raw))
    }

    @Test
    fun `tool теги закрытый и незакрытый удаляются`() {
        assertEquals("Секунду.", engine.cleanForSpeech("""Секунду. <tool>{"name":"calendar_list"}</tool>"""))
        assertEquals("Секунду.", engine.cleanForSpeech("""Секунду. <tool>{"name":""""))
    }

    @Test
    fun `think теги удаляются`() {
        assertEquals("Ответ.", engine.cleanForSpeech("Ответ. <think>размышления</think>"))
        assertEquals("Ответ.", engine.cleanForSpeech("Ответ. <THINK>размышления"))
    }

    @Test
    fun `действия в звёздочках убираются`() {
        assertEquals("Здравствуй!", engine.cleanForSpeech("*улыбнулась* Здравствуй!"))
    }

    @Test
    fun `markdown форматирование очищается`() {
        val raw = "# Заголовок\n**жирный** и `код` с ~волнистой~ чертой"
        val clean = engine.cleanForSpeech(raw)
        assertFalse(clean.contains("#"))
        assertFalse(clean.contains("`"))
        assertFalse(clean.contains("~"))
    }

    @Test
    fun `код-блоки убираются целиком`() {
        val raw = "Смотри: ```kotlin\nval x = 1``` вот пример"
        val clean = engine.cleanForSpeech(raw)
        assertFalse(clean.contains("val x"))
        assertTrue(clean.contains("пример"))
    }

    @Test
    fun `ссылки заменяются словом ссылка`() {
        assertEquals("Смотри ссылка тут", engine.cleanForSpeech("Смотри https://example.com/page тут"))
    }

    @Test
    fun `эмодзи вырезаются`() {
        val clean = engine.cleanForSpeech("Привет 🤖 как дела 👤")
        assertFalse(clean.contains("🤖"))
        assertFalse(clean.contains("👤"))
    }
}
