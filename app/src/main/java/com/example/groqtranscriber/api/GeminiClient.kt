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

/**
 * Handles all LLM API calls.
 *
 * Speed strategy: transcription and translation are now SEPARATE calls.
 * The caller shows the Indonesian text immediately after transcribeAudio()
 * returns, then fires translateText() and updates the UI when that finishes.
 * This halves perceived latency.
 *
 * Both Google Gemini (direct) and kie.ai's native Gemini endpoint share the
 * same request/response envelope, so only the URL + auth header differ.
 */
class GeminiClient(
    private val apiKey: String,
    private val provider: ApiProvider = ApiProvider.GOOGLE_GEMINI
) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val apiUrl: String
        get() = if (provider.useBearerAuth) {
            provider.baseUrl
        } else {
            "${provider.baseUrl}?key=$apiKey"
        }

    private fun buildRequest(body: RequestBody): Request {
        val builder = Request.Builder().url(apiUrl).post(body)
        if (provider.useBearerAuth) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Step 1 — STT only. Returns raw Indonesian transcription.
    //          Show this in the UI immediately while translation runs.
    // ─────────────────────────────────────────────────────────────────────────
    suspend fun transcribeAudio(file: File): String =
        suspendCancellableCoroutine { continuation ->
            try {
                val base64Audio = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

                val promptText = """
                    Transcribe the Indonesian speech in this audio clip exactly as spoken.
                    Return ONLY the raw transcription text — no labels, no JSON, no markdown,
                    no explanations. If there is no speech, return an empty string.
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

                val request = buildRequest(
                    buildGeminiBody(parts).toString().toRequestBody(jsonMediaType)
                )

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        continuation.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                continuation.resumeWithException(
                                    IOException("${provider.displayName} STT error ${res.code}: ${res.body?.string()}")
                                )
                                return
                            }
                            try {
                                continuation.resume(
                                    extractTextFromResponse(res.body?.string() ?: "").trim()
                                )
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

    // ─────────────────────────────────────────────────────────────────────────
    // Step 2 — Translation only. Takes plain text, returns translated text.
    //          Used after transcribeAudio() and in the edit/re-translate flow.
    // ─────────────────────────────────────────────────────────────────────────
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

                val request = buildRequest(
                    buildGeminiBody(parts).toString().toRequestBody(jsonMediaType)
                )

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        continuation.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                continuation.resumeWithException(
                                    IOException("${provider.displayName} translate error ${res.code}: ${res.body?.string()}")
                                )
                                return
                            }
                            try {
                                continuation.resume(
                                    extractTextFromResponse(res.body?.string() ?: "").trim()
                                )
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

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildGeminiBody(parts: JsonArray): JsonObject = JsonObject().apply {
        add("contents", JsonArray().apply {
            add(JsonObject().apply { add("parts", parts) })
        })
    }

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
