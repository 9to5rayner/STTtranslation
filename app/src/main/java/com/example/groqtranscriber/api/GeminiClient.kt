package com.example.groqtranscriber.api

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCancellableCoroutine

class GeminiClient(private val apiKey: String) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Direct Multimodal Audio processing step. Gemini accepts the audio data inline 
     * alongside text prompts, returning high-accuracy transcription and translation together.
     */
    suspend fun processAudio(file: File, targetLanguage: String): Pair<String, String> = suspendCancellableCoroutine { continuation ->
        try {
            val audioBytes = file.readBytes()
            val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            // Multi-task instruction for Gemini
            val promptText = """
                You are a precise subtitle assistant. Analyze this Indonesian audio clip.
                Output a valid JSON format with exactly two keys: "original" and "translation".
                In "original", transcribe the Indonesian speech exactly.
                In "translation", translate that transcription into $targetLanguage.
                Return ONLY the raw JSON string payload. Do not include markdown code block styling like ```json.
            """.trimIndent()

            // Construct structural inline payload matching Gemini API expectations
            val root = JsonObject()
            val contents = JsonArray()
            val contentItem = JsonObject()
            val parts = JsonArray()

            // Part 1: Instruction Text
            val textPart = JsonObject().apply { addProperty("text", promptText) }
            parts.add(textPart)

            // Part 2: Inline Audio Binary
            val audioPart = JsonObject()
            val inlineData = JsonObject().apply {
                addProperty("mimeType", "audio/mp4")
                addProperty("data", base64Audio)
            }
            audioPart.add("inlineData", inlineData)
            parts.add(audioPart)

            contentItem.add("parts", parts)
            contents.add(contentItem)
            root.add("contents", contents)

            val request = Request.Builder()
                .url("[https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey](https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey)")
                .post(root.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            continuation.resumeWithException(IOException("Gemini API Error Code: ${res.code}"))
                            return
                        }
                        try {
                            val responseBody = res.body?.string() ?: ""
                            val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
                            val outputText = jsonResponse.getAsJsonArray("candidates")
                                .get(0).asJsonObject
                                .getAsJsonObject("content")
                                .getAsJsonArray("parts")
                                .get(0).asJsonObject
                                .get("text").asString.trim()

                            // Parse Gemini's structured output response
                            val parsedOutput = JsonParser.parseString(outputText).asJsonObject
                            val original = parsedOutput.get("original").asString
                            val translation = parsedOutput.get("translation").asString

                            continuation.resume(Pair(original, translation))
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }
}