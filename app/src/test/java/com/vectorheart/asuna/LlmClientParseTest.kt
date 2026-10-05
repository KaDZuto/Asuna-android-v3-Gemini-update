package com.vectorheart.asuna

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.vectorheart.asuna.llm.LlmClient

class LlmClientParseTest {

    private val client = LlmClient(OkHttpClient())

    @Test
    fun `обычный ответ с live2d — display очищен, action распарсен`() {
        val raw = """Привет, Кирто! <live2d>{"expression": "F_FUN", "motion_group": "idle", "motion_index": 1}</live2d>"""
        val result = client.parseResponse(raw)
        assertEquals("Привет, Кирто!", result.display)
        assertEquals("F_FUN", result.action.expression)
        assertEquals("idle", result.action.motion_group)
        assertEquals(1, result.action.motion_index)
        assertNull(result.toolCall)
    }

    @Test
    fun `незакрытый live2d — тег и JSON скрыты из display, action подбирается по смыслу`() {
        val raw = """Я рада тебя видеть. <live2d>{"expression": "F_FUN""""
        val result = client.parseResponse(raw)
        assertEquals("Я рада тебя видеть.", result.display)
        assertTrue(result.action.expression in listOf("F_WARM_SMILE", "F_FUN_SMILE", "F_FUN"))
        assertNull(result.toolCall)
    }

    @Test
    fun `think блок вырезается из display`() {
        val raw = "Ответ пользователю. <think>скрытые рассуждения модели</think> Ещё фраза."
        val result = client.parseResponse(raw)
        assertEquals("Ответ пользователю.  Ещё фраза.".replace("  ", " ").trim(), result.display.replace("  ", " "))
        assertTrue(!result.display.contains("think"))
        assertTrue(!result.display.contains("скрытые"))
    }

    @Test
    fun `незакрытый think — хвост обрезается`() {
        val raw = "Видимый текст. <think>недописанные мысли модели"
        val result = client.parseResponse(raw)
        assertEquals("Видимый текст.", result.display)
    }

    @Test
    fun `tool блок парсится в toolCall и скрывается из display`() {
        val raw = """Сейчас посмотрю календарь. <tool>{"name": "calendar_list", "args": {"days": 7}}</tool>"""
        val result = client.parseResponse(raw)
        assertEquals("Сейчас посмотрю календарь.", result.display)
        assertNotNull(result.toolCall)
        assertEquals("calendar_list", result.toolCall!!.name)
        assertEquals(7, result.toolCall!!.args["days"]?.toString()?.toDoubleOrNull()?.toInt())
    }

    @Test
    fun `незакрытый tool блок скрывается, toolCall null`() {
        val raw = "Проверяю... <tool>{\"name\":"
        val result = client.parseResponse(raw)
        assertEquals("Проверяю...", result.display)
        assertNull(result.toolCall)
    }

    @Test
    fun `reasoning превью — think и reasoning не попадают в display`() {
        val raw = """<think>Думаю над ответом про погоду в Токио...</think>Сегодня в Токио солнечно. <live2d>{"expression":"F_FUN_SMILE"}</live2d>"""
        val result = client.parseResponse(raw)
        assertEquals("Сегодня в Токио солнечно.", result.display)
        assertTrue(!result.display.contains("Думаю"))
        assertEquals("F_FUN_SMILE", result.action.expression)
    }

    @Test
    fun `битый JSON внутри live2d — не падает, action по умолчанию`() {
        val raw = "Текст <live2d>{не json вообще}</live2d> конец"
        val result = client.parseResponse(raw)
        assertEquals("Текст  конец".replace("  ", " ").trim(), result.display.replace("  ", " "))
        assertEquals("F_NOMAL", result.action.expression)
    }

    @Test
    fun `tool с битым JSON — toolCall null, без краша`() {
        val raw = "Текст <tool>{not valid}</tool>"
        val result = client.parseResponse(raw)
        assertNull(result.toolCall)
        assertEquals("Текст", result.display)
    }
}
