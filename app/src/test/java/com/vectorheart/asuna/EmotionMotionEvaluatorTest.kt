package com.vectorheart.asuna

import com.vectorheart.asuna.avatar.EmotionMotionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmotionMotionEvaluatorTest {

    @Test
    fun `смех и веселье вызывают F_GIGGLE или F_FUN_MAX`() {
        val action = EmotionMotionEvaluator.evaluate("Ха-ха, ну ты даёшь, это очень смешно!")
        assertTrue(action.expression in listOf("F_GIGGLE", "F_FUN_MAX"))
    }

    @Test
    fun `романтика и нежность вызывают F_SHY, F_FUN_HANIKAMI или F_ADORE`() {
        val action = EmotionMotionEvaluator.evaluate("Ты такой милый, я так люблю проводить время с тобой.")
        assertTrue(action.expression in listOf("F_SHY", "F_FUN_HANIKAMI", "F_ADORE"))
        assertTrue(action.body_angle != 0f || action.head_x != 0f)
    }

    @Test
    fun `удивление вызывает F_SURPRISE или F_SHOCKED`() {
        val action = EmotionMotionEvaluator.evaluate("Ого! Неужели ты правда это сделал?!")
        assertTrue(action.expression in listOf("F_SURPRISE", "F_SHOCKED"))
    }

    @Test
    fun `обидка цундере вызывает F_POUTY`() {
        val action = EmotionMotionEvaluator.evaluate("Хмпф, дурак, так нечестно!")
        assertEquals("F_POUTY", action.expression)
    }

    @Test
    fun `сонливость вызывает F_SLEEP`() {
        val action = EmotionMotionEvaluator.evaluate("Я так устала за сегодня... Спокойной ночи и сладких снов.")
        assertEquals("F_SLEEP", action.expression)
    }

    @Test
    fun `поддразнивание вызывает F_TEASING или F_PLAYFUL`() {
        val action = EmotionMotionEvaluator.evaluate("Хитрец, думаешь я не заметила, как ты дразнишься?")
        assertTrue(action.expression in listOf("F_TEASING", "F_PLAYFUL"))
    }

    @Test
    fun `нейтральный текст возвращает живые микродвижения`() {
        val action = EmotionMotionEvaluator.evaluate("По прогнозу сегодня переменная облачность.")
        assertEquals("F_NOMAL", action.expression)
        assertEquals("idle", action.motion_group)
    }
}
