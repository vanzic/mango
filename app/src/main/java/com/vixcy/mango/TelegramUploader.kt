package com.vixcy.mango

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object TelegramUploader {

    private const val TAG = "TelegramUploader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun uploadVideo(file: File): Boolean {
        val token  = BuildConfig.TELEGRAM_BOT_TOKEN
        val chatId = BuildConfig.TELEGRAM_CHAT_ID

        if (token.isBlank() || chatId.isBlank()) {
            Log.e(TAG, "Missing token or chatId — check local.properties")
            return false
        }

        val url = "https://api.telegram.org/bot$token/sendVideo"
        val caption = "🟠 Mango | ${file.nameWithoutExtension}"

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart(
                "video",
                file.name,
                file.asRequestBody("video/mp4".toMediaType())
            )
            .addFormDataPart("caption", caption)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d(TAG, "Upload success: ${file.name}")
                    true
                } else {
                    Log.e(TAG, "Upload failed: ${response.code} — ${response.body?.string()}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload exception: ${e.message}")
            false
        }
    }
}