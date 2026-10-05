package com.vectorheart.asuna.avatar

import com.vectorheart.asuna.llm.LlmClient
import kotlin.random.Random

/**
 * Интеллектуальный анализатор эмоций и движений для Асуны.
 *
 * Работает в двух ключевых сценариях:
 * 1. В "Lite LLM" режиме: когда локальная нейросеть пишет исключительно текст без тегов <live2d>,
 *    данный модуль считывает тональность, контекст и эмоциональные маркеры реплики,
 *    подбирая живую мимику (expression) и естественные микродвижения (голова, тело, дыхание, моушны).
 * 2. Как надежный Fallback для любых моделей (облачных и локальных), если модель забыла
 *    или не смогла выдать блок <live2d> (например, обрезалась по длине или сгенерировала битый JSON).
 */
object EmotionMotionEvaluator {

    /**
     * Анализирует текст ответа Асуны и строит живой [LlmClient.Action].
     */
    fun evaluate(text: String, userMessage: String = ""): LlmClient.Action {
        val clean = text.trim()
        val lower = clean.lowercase()
        val rnd = Random(clean.hashCode() + System.currentTimeMillis().toInt())

        // 1. Сонливость / Ночь / Усталость
        if (containsAny(lower, "спокойной ночи", "добрых снов", "сладких снов", "зеваю", "спать", "пора спать", "до завтра", "устала", "засыпаю")) {
            return LlmClient.Action(
                expression = "F_SLEEP",
                motion_group = "idle",
                motion_index = 2,
                head_x = (rnd.nextFloat() - 0.5f) * 0.1f,
                head_y = -0.15f,
                body_angle = -4f,
                eye_open = 0.35f,
                breath = 0.3f
            )
        }

        // 2. Смех / Веселье / Юмор
        if (containsAny(lower, "хаха", "ха-ха", "хи-хи", "хихи", "хе-хе", "хехе", "смешно", "смеюсь", "лол", "рассмешил", "ахах")) {
            return LlmClient.Action(
                expression = if (rnd.nextBoolean()) "F_GIGGLE" else "F_FUN_MAX",
                motion_group = "",
                motion_index = 1,
                head_x = 0.12f * (if (rnd.nextBoolean()) 1f else -1f),
                head_y = 0.18f,
                body_angle = 6f * (if (rnd.nextBoolean()) 1f else -1f),
                eye_open = 0.9f,
                breath = 0.8f
            )
        }

        // 3. Романтика / Нежность / Смущение
        if (containsAny(lower, "люблю", "милый", "краснею", "смущаешь", "смущена", "обожаю", "рядом с тобой", "скучала", "дорогой", "обнять", "поцелуй", "родной", "в сердечке")) {
            val expr = when (rnd.nextInt(3)) {
                0 -> "F_SHY"
                1 -> "F_FUN_HANIKAMI"
                else -> "F_ADORE"
            }
            return LlmClient.Action(
                expression = expr,
                motion_group = "idle",
                motion_index = 1,
                head_x = -0.15f,
                head_y = 0.08f,
                body_angle = 5f,
                eye_open = 0.95f,
                breath = 0.6f
            )
        }

        // 4. Бурный восторг / Радость
        if (containsAny(lower, "ура", "супер", "прекрасно", "замечательно", "восторг", "класс!", "здорово!", "обожаю!", "ура!") ||
            (lower.contains("!") && containsAny(lower, "здорово", "рада", "отлично", "получилось", "класс"))) {
            return LlmClient.Action(
                expression = "F_FUN_MAX",
                motion_group = "",
                motion_index = 0,
                head_x = 0.05f,
                head_y = 0.2f,
                body_angle = 4f,
                eye_open = 1.0f,
                breath = 0.7f
            )
        }

        // 5. Удивление / Шок
        if (containsAny(lower, "ого", "вау", "неужели", "правда?", "серьёзно?", "ничего себе", "не может быть", "вот это да", "ни фига", "да ладно") ||
            clean.endsWith("!?") || clean.endsWith("?!")) {
            return LlmClient.Action(
                expression = if (lower.contains("шок") || lower.contains("не может быть")) "F_SHOCKED" else "F_SURPRISE",
                motion_group = "",
                motion_index = 0,
                head_x = 0.0f,
                head_y = -0.15f,
                body_angle = -3f,
                eye_open = 1.0f,
                breath = 0.6f
            )
        }

        // 6. Поддразнивание / Игривость / Хитрость
        if (containsAny(lower, "хитрец", "дразнишь", "дразню", "а вот и нет", "ну-ну", "посмотрим", "хех", "секрет", "угадай", "думаешь?", "наивный")) {
            return LlmClient.Action(
                expression = if (rnd.nextBoolean()) "F_TEASING" else "F_PLAYFUL",
                motion_group = "idle",
                motion_index = 0,
                head_x = 0.18f,
                head_y = 0.05f,
                body_angle = -5f,
                eye_open = 0.95f,
                breath = 0.55f
            )
        }

        // 7. Обидка / Цундере / Каприз
        if (containsAny(lower, "хмпф", "дурак", "эй!", "нечестно", "обиделась", "вредина", "отстань", "бяка", "не смей", "ну ты и")) {
            return LlmClient.Action(
                expression = "F_POUTY",
                motion_group = "idle",
                motion_index = 2,
                head_x = -0.25f,
                head_y = 0.1f,
                body_angle = -8f,
                eye_open = 0.9f,
                breath = 0.65f
            )
        }

        // 8. Злость / Возмущение
        if (containsAny(lower, "злюсь", "прекрати", "хватит", "бесит", "ужасно", "рассердилась")) {
            return LlmClient.Action(
                expression = "F_ANGRY",
                motion_group = "",
                motion_index = 2,
                head_x = 0.0f,
                head_y = -0.2f,
                body_angle = 0f,
                eye_open = 1.0f,
                breath = 0.7f
            )
        }

        // 9. Грусть / Сочувствие / Утешение
        if (containsAny(lower, "жаль", "грустно", "сочувствую", "не переживай", "бедный", "печально", "расстроилась", "скучно", "тяжело", "больно", "держись")) {
            return LlmClient.Action(
                expression = if (lower.contains("не переживай") || lower.contains("бедный")) "F_SAD_CUTE" else "F_SAD",
                motion_group = "idle",
                motion_index = 1,
                head_x = -0.1f,
                head_y = -0.12f,
                body_angle = 3f,
                eye_open = 0.85f,
                breath = 0.45f
            )
        }

        // 10. Любопытство / Вопрос
        if (clean.endsWith("?") || containsAny(lower, "хм", "интересно", "почему", "думаю", "расскажи", "как думаешь", "а ты?")) {
            return LlmClient.Action(
                expression = "F_CURIOUS",
                motion_group = "idle",
                motion_index = 0,
                head_x = 0.15f,
                head_y = 0.08f,
                body_angle = 4f,
                eye_open = 1.0f,
                breath = 0.5f
            )
        }

        // 11. Тёплая улыбка / Дружелюбие / Приветствие
        if (containsAny(lower, "привет", "здравствуй", "доброе утро", "добрый день", "добрый вечер", "конечно", "с удовольствием", "рада", "приятно", "спасибо", "всегда пожалуйста", "договорились")) {
            return LlmClient.Action(
                expression = if (rnd.nextBoolean()) "F_WARM_SMILE" else "F_FUN_SMILE",
                motion_group = "idle",
                motion_index = 0,
                head_x = 0.08f,
                head_y = 0.05f,
                body_angle = 2f,
                eye_open = 1.0f,
                breath = 0.55f
            )
        }

        // 12. По умолчанию: спокойная дружелюбная Асуна с лёгким естественным микродвижением
        val randomHeadX = ((rnd.nextFloat() - 0.5f) * 0.12f)
        val randomAngle = ((rnd.nextFloat() - 0.5f) * 4f)
        return LlmClient.Action(
            expression = "F_NOMAL",
            motion_group = "idle",
            motion_index = rnd.nextInt(3),
            head_x = randomHeadX,
            head_y = 0.0f,
            body_angle = randomAngle,
            eye_open = 1.0f,
            breath = 0.5f
        )
    }

    private fun containsAny(text: String, vararg keywords: String): Boolean {
        for (kw in keywords) {
            if (text.contains(kw)) return true
        }
        return false
    }
}
