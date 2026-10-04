package com.vectorheart.asuna

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vectorheart.asuna.ui.AsunaApp
import com.vectorheart.asuna.ui.theme.AsunaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Главный экран Asuna VectorHeart.
 *
 * Edge-to-edge с прозрачными системными барами — аватар и фон занимают весь экран,
 * включая области под status bar / nav bar.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            AsunaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AsunaApp()
                }
            }
        }
    }
}
