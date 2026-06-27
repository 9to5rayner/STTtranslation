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
import kotlinx.coroutines.suspendCancellableCoroutine

class GeminiClient(private val apiKey: String) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val apiUrl =
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

    /**
     * Sends an audio file to Gemini for STT + translation in one shot.
     * Returns a Pair(originalIndonesian, translatedText).
     */
    suspend fun processAudio(file: File, targetLanguage: String): Pair<String, String> =
        suspendCancellableCoroutine { continuation ->
            try {
                val audioBytes = file.readBytes()
                val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

                val promptText = """
                    You are a precise subtitle assistant. Analyze this Indonesian audio clip.
                    Output a valid JSON object with exactly two keys: "original" and "translation".
                    In "original", transcribe the Indonesian speech exactly as spoken.
                    In "translation", translate that transcription into $targetLanguage.
                    Return ONLY the raw JSON string. Do not use markdown code blocks or backticks.
                """.trimIndent()

                val parts = JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", promptText) })
                    add(JsonObject().apply {
                        add("inlineData", JsonObject().apply {
                            addProperty("mimeType", "audio/mp4")
                            addProperty("data", base64Audio)
                        })
                    })
                }

                val body = JsonObject().apply {
                    add("contents", JsonArray().apply {
                        add(JsonObject().apply { add("parts", parts) })
                    })
                }

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        continuation.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                continuation.resumeWithException(
                                    IOException("Gemini API Error: ${res.code}")
                                )
                                return
                            }
                            try {
                                val outputText = extractTextFromResponse(res.body?.string() ?: "")
                                val parsed = JsonParser.parseString(outputText).asJsonObject
                                val original = parsed.get("original").asString
                                val translation = parsed.get("translation").asString
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

    /**
     * Translates a plain text string (already-transcribed Indonesian) into the target language.
     * Used by the edit + re-translate feature in ExportActivity — no audio involved.
     */
    suspend fun translateText(indonesianText: String, targetLanguage: String): String =
        suspendCancellableCoroutine { continuation ->
            try {
                val promptText = """
                    Translate the following Indonesian text into $targetLanguage.
                    Return ONLY the translated text. No explanations, no markdown, no quotes.
                    
                    Text to translate:
                    $indonesianText
                """.trimIndent()

                val parts = JsonArray().apply {
                    add(JsonObject().apply { addProperty("text", promptText) })
                }

                val body = JsonObject().apply {
                    add("contents", JsonArray().apply {
                        add(JsonObject().apply { add("parts", parts) })
                    })
                }

                val request = Request.Builder()
                    .url(apiUrl)
                    .post(body.toString().toRequestBody(jsonMediaType))
                    .build()

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        continuation.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                continuation.resumeWithException(
                                    IOException("Gemini API Error: ${res.code}")
                                )
                                return
                            }
                            try {
                                val translation = extractTextFromResponse(res.body?.string() ?: "")
                                continuation.resume(translation.trim())
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

    /**
     * Shared response parser — pulls the text content out of Gemini's response envelope.
     */
    private fun extractTextFromResponse(responseBody: String): String {
        val json = JsonParser.parseString(responseBody).asJsonObject
        return json.getAsJsonArray("candidates")
            .get(0).asJsonObject
            .getAsJsonObject("content")
            .getAsJsonArray("parts")
            .get(0).asJsonObject
            .get("text").asString
            .trim()
    }
}
