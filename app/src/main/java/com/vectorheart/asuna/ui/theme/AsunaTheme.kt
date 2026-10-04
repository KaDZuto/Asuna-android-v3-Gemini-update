package com.vectorheart.asuna.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.vectorheart.asuna.R

/**
 * SAO UI — готический sans, для заголовков и кнопок.
 * Загружается из res/font (скопирован из C:\Users\Alexius\Documents\for asuna android\sao-ui).
 */
val SaoUiFamily = FontFamily(
    Font(R.font.sao_ui_regular, FontWeight.Normal),
    Font(R.font.sao_ui_bold, FontWeight.Bold)
)

/**
 * SAOUITT — моноширинный, для системных сообщений и лога чата.
 */
val SaoUiMonoFamily = FontFamily(
    Font(R.font.saouitt_regular, FontWeight.Normal),
    Font(R.font.saouitt_bold, FontWeight.Bold)
)

/**
 * Цветовая палитра Asuna VectorHeart.
 *
 * Логика выбора акцента и фона — в SettingsScreen. Здесь фиксируем
 * дефолтную тёмную тёплую палитру: персиково-оранжевый акцент на фоне
 * глубокого тёмно-фиолетового (как в "Wake me up Asuna").
 */
private val AsunaDarkColors = darkColorScheme(
    primary = Color(0xFFFF9F66),         // тёплый оранжевый
    onPrimary = Color(0xFF1A0F1F),
    primaryContainer = Color(0xFF6B3A1F),
    onPrimaryContainer = Color(0xFFFFE2D1),

    secondary = Color(0xFFFFC78A),       // светло-персиковый
    onSecondary = Color(0xFF1A0F1F),
    secondaryContainer = Color(0xFF553A22),
    onSecondaryContainer = Color(0xFFFFE9D6),

    tertiary = Color(0xFFB8A0FF),        // лавандовый (для Асуны)
    onTertiary = Color(0xFF1A0F1F),
    tertiaryContainer = Color(0xFF4A3D7A),
    onTertiaryContainer = Color(0xFFE5DDFF),

    background = Color(0xFF1A0F1F),      // глубокий тёмно-фиолетовый
    onBackground = Color(0xFFF5EBE0),   // тёплый кремовый текст
    surface = Color(0xFF261829),
    onSurface = Color(0xFFF5EBE0),
    surfaceVariant = Color(0xFF3D2937),
    onSurfaceVariant = Color(0xFFE6D7C7),

    error = Color(0xFFFF6B6B),
    onError = Color(0xFF1A0F1F)
)

/**
 * Типографика на основе SAO-шрифтов.
 *
 * SaoUiFamily — готический, для UI (заголовки, кнопки).
 * SaoUiMonoFamily — моноширинный, для лога чата и системных сообщений.
 */
private val AsunaTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 48.sp, lineHeight = 56.sp, letterSpacing = 0.5.sp
    ),
    displayMedium = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 36.sp, lineHeight = 44.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 36.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 22.sp, lineHeight = 28.sp
    ),
    titleLarge = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, lineHeight = 22.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = SaoUiMonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SaoUiMonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.8.sp
    ),
    labelMedium = TextStyle(
        fontFamily = SaoUiFamily, fontWeight = FontWeight.Bold,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp
    )
)

/**
 * Корневая Compose-тема Asuna VectorHeart.
 *
 * Edge-to-edge тёмная тема с SAO-шрифтами и тёплой оранжево-персиковой палитрой.
 * Логика выбора accent-цвета (по настройке "TextColor") реализуется отдельно
 * в AsunaApp через LocalContentColor.
 */
@Composable
fun AsunaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AsunaDarkColors,
        typography = AsunaTypography,
        content = content
    )
}
