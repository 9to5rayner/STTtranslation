package com.example.groqtranscriber.api

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

class GroqClient(private val apiKey: String) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribeAudio(file: File): String = suspendCancellableCoroutine { continuation ->
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("language", "id")
            .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        continuation.resumeWithException(IOException("Unexpected code ${it.code}"))
                        return
                    }
                    val json = JsonParser.parseString(it.body?.string()).asJsonObject
                    continuation.resume(json.get("text").asString)
                }
            }
        })
    }

    suspend fun translateText(text: String, targetLanguage: String): String = suspendCancellableCoroutine { continuation ->
        val model = if (targetLanguage.equals("Japanese", ignoreCase = true)) "qwen/qwen3-32b" else "llama-3.3-70b-versatile"
        val systemPrompt = "You are a precise translator. Translate the following Indonesian text directly into $targetLanguage. Provide only the direct translation without explanations or conversational filler."

        val jsonBody = JsonObject().apply {
            addProperty("model", model)
            val messages = com.google.gson.JsonArray().apply {
                add(JsonObject().apply { addProperty("role", "system"); addProperty("content", systemPrompt) })
                add(JsonObject().apply { addProperty("role", "user"); addProperty("content", text) })
            }
            add("messages", messages)
            addProperty("temperature", 0.3)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        continuation.resumeWithException(IOException("API Error: ${it.code}"))
                        return
                    }
                    val json = JsonParser.parseString(it.body?.string()).asJsonObject
                    val translation = json.getAsJsonArray("choices")
                        .get(0).asJsonObject
                        .getAsJsonObject("message")
                        .get("content").asString
                    continuation.resume(translation.trim())
                }
            }
        })
    }
}