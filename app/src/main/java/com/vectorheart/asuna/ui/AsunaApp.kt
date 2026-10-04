package com.vectorheart.asuna.ui

import androidx.compose.runtime.Composable
import com.vectorheart.asuna.ui.chat.ChatScreen

/**
 * Корневой Composable приложения.
 *
 * Сейчас показывает [ChatScreen] (главный экран с PTT-аватаром).
 * По мере реализации добавим навигацию на Settings/Calendar/Media/LiveVision
 * через Compose Navigation.
 */
@Composable
fun AsunaApp() {
    ChatScreen()
}
