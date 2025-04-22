package com.example.myapplication

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TelegramBotManager(
    private val context: Context,
    private val mediaPlayer: MediaPlayer,
    private val exitCallback: () -> Unit,
    private val builtInSounds: Map<Int, Int>
) {
    private var lastUpdateId = 0
    private val dbFile = "db.json"
    private val musicDir = "music_files"
    private var botJob: Job? = null
    private var awaitingShutdownConfirmation = false
    private var shutdownChatId: Long = -1

    fun startBot() {
        File(context.getExternalFilesDir(null), musicDir).mkdirs()

        botJob = CoroutineScope(Dispatchers.IO).launch {
            val botToken = "7226888743:AAE_cqY8YEdDl438Eadla_FGMSzB9w5gkpE"
            while (true) {
                try {
                    val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=${lastUpdateId + 1}"
                    val response = makeRequest(url)
                    Log.d("TelegramBot", "Response: $response")

                    val jsonResponse = JSONObject(response)
                    val result = jsonResponse.getJSONArray("result")
                    if (result.length() > 0) {
                        for (i in 0 until result.length()) {
                            val update = result.getJSONObject(i)
                            lastUpdateId = update.getInt("update_id")

                            if (update.has("message")) {
                                val message = update.getJSONObject("message")
                                val chatId = message.getJSONObject("chat").getLong("id")

                                if (message.has("text")) {
                                    val text = message.getString("text").trim()

                                    when {
                                        text.equals("#off", ignoreCase = true) -> {
                                            awaitingShutdownConfirmation = true
                                            shutdownChatId = chatId
                                            sendMessage(chatId, "⚠️ Ви впевнені, що хочете вимкнути додаток? Напишіть #yes для підтвердження", botToken)
                                        }
                                        text.equals("#yes", ignoreCase = true) && awaitingShutdownConfirmation && chatId == shutdownChatId -> {
                                            sendMessage(chatId, "🛑 Вимикаю додаток...", botToken)
                                            exitCallback()
                                        }
                                        text.startsWith("#ping", ignoreCase = true) -> {
                                            sendMessage(chatId, "jsw", botToken)
                                        }
                                        text.startsWith("#music", ignoreCase = true) -> {
                                            handleMusicCommand(text, chatId, botToken)
                                        }
                                        text.startsWith("#spin", ignoreCase = true) -> {
                                            handleSpinCommand(text, chatId, botToken)
                                        }
                                        text.startsWith("#data", ignoreCase = true) -> {
                                            val dbContent = readDbFile()
                                            sendMessage(chatId, "📁 Вміст db.json:\n$dbContent", botToken)
                                        }
                                    }
                                }

                                if ((message.has("audio") ||
                                            (message.has("document") && message.getJSONObject("document")
                                                .getString("mime_type").contains("audio"))) &&
                                    message.has("caption") && message.getString("caption")
                                        .contains("#up_music")) {

                                    processMusicUpload(message, chatId, botToken)
                                }
                            }
                        }
                    }
                    kotlinx.coroutines.delay(2000)
                } catch (e: Exception) {
                    Log.e("TelegramBot", "Помилка: ${e.message}", e)
                }
            }
        }
    }

    private fun handleSpinCommand(text: String, chatId: Long, botToken: String) {
        val parts = text.split("\\s+".toRegex())
        if (parts.size >= 2) {
            try {
                val soundNum = parts[1].toInt()
                if (builtInSounds.containsKey(soundNum)) {
                    playBuiltInSound(soundNum, chatId, botToken)
                } else {
                    sendMessage(chatId, "❌ Звук з номером #$soundNum не знайдено", botToken)
                }
            } catch (e: NumberFormatException) {
                sendMessage(chatId, "❌ Невірний формат номеру. Використовуйте: #spin [номер]", botToken)
            }
        } else {
            sendMessage(chatId, "❌ Вкажіть номер звуку. Наприклад: #spin 1", botToken)
        }
    }

    private fun playBuiltInSound(soundNum: Int, chatId: Long, botToken: String) {
        try {
            val soundResId = builtInSounds[soundNum] ?: return

            // Використовуємо Handler для виконання коду в головному потоці
            android.os.Handler(context.mainLooper).post {
                try {
                    mediaPlayer.release() // Спочатку звільняємо старого
                    val newMediaPlayer = MediaPlayer.create(context, soundResId)
                    newMediaPlayer?.start()
                    sendMessage(chatId, "🔊 Програю вбудований звук #$soundNum", botToken)
                } catch (e: Exception) {
                    sendMessage(chatId, "❌ Помилка програвання звуку: ${e.message}", botToken)
                }
            }
        } catch (e: Exception) {
            sendMessage(chatId, "❌ Помилка: ${e.message}", botToken)
        }
    }

    fun stopBot() {
        botJob?.cancel()
    }

    private fun processMessage(message: org.json.JSONObject, botToken: String) {
        val chatId = message.getJSONObject("chat").getLong("id")

        if (message.has("text")) {
            val text = message.getString("text").trim()

            when {
                text.startsWith("#ping", ignoreCase = true) -> {
                    sendMessage(chatId, "jsw", botToken)
                }
                text.startsWith("#music", ignoreCase = true) -> {
                    handleMusicCommand(text, chatId, botToken)
                }
                text.startsWith("#data", ignoreCase = true) -> {
                    val dbContent = readDbFile()
                    sendMessage(chatId, "📁 Вміст db.json:\n$dbContent", botToken)
                }
            }
        }

        if ((message.has("audio") ||
                    (message.has("document") && message.getJSONObject("document")
                        .getString("mime_type").contains("audio"))) &&
            message.has("caption") && message.getString("caption")
                .contains("#up_music")) {

            processMusicUpload(message, chatId, botToken)
        }
    }

    private fun handleMusicCommand(text: String, chatId: Long, botToken: String) {
        val parts = text.split("\\s+".toRegex())
        if (parts.size >= 2) {
            try {
                val musicNum = parts[1].toInt()
                playMusicFromDb(musicNum, chatId, botToken)
            } catch (e: NumberFormatException) {
                sendMessage(chatId, "❌ Невірний формат номеру. Використовуйте: #music [номер]", botToken)
            }
        } else {
            sendMessage(chatId, "❌ Вкажіть номер музики. Наприклад: #music 1", botToken)
        }
    }

    private fun playMusicFromDb(musicNum: Int, chatId: Long, botToken: String) {
        try {
            val dbContent = readDbFile()
            val dbJson = JSONObject(dbContent)
            val musicKey = "music_$musicNum"

            if (dbJson.has(musicKey)) {
                val filePath = dbJson.getString(musicKey)
                val musicFile = File(filePath)

                if (musicFile.exists()) {
                    if (mediaPlayer.isPlaying) {
                        mediaPlayer.stop()
                    }
                    mediaPlayer.reset()
                    mediaPlayer.setDataSource(filePath)
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                    sendMessage(chatId, "🔊 Програю музику #$musicNum: ${musicFile.name}", botToken)
                } else {
                    sendMessage(chatId, "❌ Файл музики #$musicNum не знайдено", botToken)
                }
            } else {
                sendMessage(chatId, "❌ Музика з номером #$musicNum не знайдена в базі", botToken)
            }
        } catch (e: Exception) {
            sendMessage(chatId, "❌ Помилка програвання: ${e.message}", botToken)
        }
    }

    private fun processMusicUpload(message: org.json.JSONObject, chatId: Long, botToken: String) {
        try {
            val fileInfo = if (message.has("audio")) {
                message.getJSONObject("audio")
            } else {
                message.getJSONObject("document")
            }

            val fileId = fileInfo.getString("file_id")
            val fileName = fileInfo.getString("file_name")
            val caption = message.getString("caption")

            val musicIndex = try {
                caption.split("#up_music")[1].trim().toInt()
            } catch (e: Exception) {
                sendMessage(chatId, "❌ Невірний формат. Використовуйте: #up_music [номер]", botToken)
                return
            }

            val downloadedFile = downloadFile(fileId, botToken, fileName)
            if (downloadedFile != null) {
                saveToDb(musicIndex, downloadedFile.absolutePath)
                sendMessage(chatId, "✅ Файл $fileName збережено як музика #$musicIndex", botToken)
            } else {
                sendMessage(chatId, "❌ Помилка завантаження файлу", botToken)
            }
        } catch (e: Exception) {
            sendMessage(chatId, "❌ Помилка обробки файлу: ${e.message}", botToken)
        }
    }

    private fun downloadFile(fileId: String, botToken: String, fileName: String): File? {
        return try {
            val filePathUrl = "https://api.telegram.org/bot$botToken/getFile?file_id=$fileId"
            val filePathResponse = makeRequest(filePathUrl)
            val filePathJson = JSONObject(filePathResponse)
            val filePath = filePathJson.getJSONObject("result").getString("file_path")

            val fileUrl = "https://api.telegram.org/file/bot$botToken/$filePath"
            val connection = URL(fileUrl).openConnection() as HttpURLConnection
            connection.connect()

            val musicDir = File(context.getExternalFilesDir(null), musicDir)
            val outputFile = File(musicDir, fileName)

            val inputStream = connection.inputStream
            val outputStream = FileOutputStream(outputFile)

            inputStream.copyTo(outputStream)
            outputStream.close()
            inputStream.close()

            outputFile
        } catch (e: Exception) {
            Log.e("Download", "Помилка завантаження: ${e.message}")
            null
        }
    }

    private fun saveToDb(index: Int, filePath: String) {
        try {
            val dbFile = File(context.getExternalFilesDir(null), dbFile)
            val dbData = if (dbFile.exists()) {
                JSONObject(dbFile.readText())
            } else {
                JSONObject()
            }

            dbData.put("music_$index", filePath)
            dbFile.writeText(dbData.toString())
        } catch (e: Exception) {
            Log.e("DB", "Помилка збереження в db.json: ${e.message}")
        }
    }

    private fun readDbFile(): String {
        return try {
            val dbFile = File(context.getExternalFilesDir(null), dbFile)
            if (dbFile.exists()) {
                dbFile.readText()
            } else {
                "{}"
            }
        } catch (e: Exception) {
            Log.e("DB", "Помилка читання db.json: ${e.message}")
            "{}"
        }
    }

    private fun makeRequest(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        return try {
            val inputStream = connection.inputStream
            val response = inputStream.bufferedReader().readText()
            inputStream.close()
            response
        } catch (e: Exception) {
            Log.e("HTTP", "Помилка запиту: ${e.message}")
            "{}"
        } finally {
            connection.disconnect()
        }
    }

    private fun sendMessage(chatId: Long, text: String, botToken: String) {
        try {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val params = "chat_id=$chatId&text=${URLEncoder.encode(text, "UTF-8")}"
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.outputStream.write(params.toByteArray())
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                Log.d("TelegramBot", "Повідомлення відправлено!")
            }
            connection.disconnect()
        } catch (e: Exception) {
            Log.e("TelegramBot", "Помилка при відправці повідомлення: ${e.message}", e)
        }
    }
}
