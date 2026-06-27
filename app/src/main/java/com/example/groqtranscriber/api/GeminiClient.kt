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
 * Both Google Gemini (direct) and kie.ai's native Gemini endpoint share the
 * same request/response envelope (contents → parts → inlineData for audio,
 * candidates → content → parts → text for output). The only differences are:
 *
 *  - The URL (carried by [ApiProvider.baseUrl])
 *  - Auth style: Google uses ?key=… query param; kie.ai uses Bearer token header
 *
 * This class handles both transparently via the [provider] parameter.
 */
class GeminiClient(
    private val apiKey: String,
    private val provider: ApiProvider = ApiProvider.GOOGLE_GEMINI
) {

    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Builds the final request URL.
     * - Google: appends ?key= to the base URL
     * - kie.ai: uses the base URL as-is (auth goes in header)
     */
    private val apiUrl: String
        get() = if (provider.useBearerAuth) {
            provider.baseUrl
        } else {
            "${provider.baseUrl}?key=$apiKey"
        }

    /**
     * Builds an OkHttp Request with the correct auth style for the active provider.
     */
    private fun buildRequest(body: RequestBody): Request {
        val builder = Request.Builder()
            .url(apiUrl)
            .post(body)
        if (provider.useBearerAuth) {
            builder.addHeader("Authorization", "Bearer $apiKey")
        }
        return builder.build()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Sends an audio file to the model for STT + translation in one shot.
     * Audio is encoded as base64 inline data — works with both Google and kie.ai
     * native Gemini endpoints.
     *
     * @return Pair(originalIndonesian, translatedText)
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

                val requestBody = buildGeminiBody(parts)
                val request = buildRequest(requestBody.toString().toRequestBody(jsonMediaType))

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        continuation.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                continuation.resumeWithException(
                                    IOException("${provider.displayName} API Error ${res.code}: ${res.body?.string()}")
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

                val requestBody = buildGeminiBody(parts)
                val request = buildRequest(requestBody.toString().toRequestBody(jsonMediaType))

                client.newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        continuation.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                continuation.resumeWithException(
                                    IOException("${provider.displayName} API Error ${res.code}: ${res.body?.string()}")
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

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wraps a parts array in the standard Gemini request envelope.
     * Identical for Google and kie.ai native Gemini format.
     */
    private fun buildGeminiBody(parts: JsonArray): JsonObject = JsonObject().apply {
        add("contents", JsonArray().apply {
            add(JsonObject().apply { add("parts", parts) })
        })
    }

    /**
     * Extracts the text content from a Gemini-format response.
     * Works for both Google's direct API and kie.ai's native Gemini endpoint.
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
