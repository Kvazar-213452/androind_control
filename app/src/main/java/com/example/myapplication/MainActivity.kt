package com.example.myapplication

import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background

class MainActivity : ComponentActivity() {
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var telegramBotManager: TelegramBotManager
    private var shouldExit = false

    private val builtInSounds = mapOf(
        1 to R.raw.sound,
        2 to R.raw.sound1,
        3 to R.raw.sound2,
        4 to R.raw.sound3
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaPlayer = MediaPlayer()
        telegramBotManager = TelegramBotManager(this, mediaPlayer, ::finishAndExit, builtInSounds)
        telegramBotManager.startBot()

        setContent {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black), // Чорний фон
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "viva 213452",
                    style = TextStyle(
                        color = Color.Red, // Червоний текст
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }

    private fun finishAndExit() {
        shouldExit = true
        finishAffinity()
        System.exit(0)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!shouldExit) {
            mediaPlayer.release()
            telegramBotManager.stopBot()
        }
    }
}