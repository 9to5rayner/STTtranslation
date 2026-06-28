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
 * All LLM API calls — STT, translation, and TTS — in one place.
 *
 * Works with both Google Gemini (direct) and kie.ai native Gemini endpoints.
 * The request/response envelope is identical; only URL and auth differ.
 */
class GeminiClient(
    private val apiKey: String,
    private val provider: ApiProvider = ApiProvider.GOOGLE_GEMINI
) {
    private val client        = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // ── URL builders ──────────────────────────────────────────────────────────

    private fun mainUrl() = if (provider.useBearerAuth) provider.baseUrl
                            else "${provider.baseUrl}?key=$apiKey"

    private fun ttsUrl()  = if (provider.useBearerAuth) provider.ttsUrl
                            else "${provider.ttsUrl}?key=$apiKey"

    private fun buildRequest(url: String, body: RequestBody): Request =
        Request.Builder().url(url).post(body).apply {
            if (provider.useBearerAuth) addHeader("Authorization", "Bearer $apiKey")
        }.build()

    // ── 1. Speech-to-text ─────────────────────────────────────────────────────

    /**
     * Transcribes Indonesian speech from [file] (AAC/MP4).
     * Returns raw transcription text, or empty string if no speech detected.
     */
    suspend fun transcribeAudio(file: File): String =
        suspendCancellableCoroutine { cont ->
            try {
                val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val prompt  = """
                    Transcribe the Indonesian speech in this audio clip exactly as spoken.
                    Return ONLY the raw transcription — no labels, no JSON, no markdown,
                    no explanations. If there is no intelligible speech, return an empty string.
                """.trimIndent()

                val parts = JsonArray().apply {
                    add(textPart(prompt))
                    add(audioPart(base64))
                }
                val req = buildRequest(mainUrl(), geminiBody(parts))
                client.newCall(req).enqueue(simpleCallback(cont))
            } catch (e: Exception) { cont.resumeWithException(e) }
        }

    // ── 2. Translation ────────────────────────────────────────────────────────

    /**
     * Translates [indonesianText] into [targetLanguage].
     * Returns translated text only — no extra formatting.
     */
    suspend fun translateText(indonesianText: String, targetLanguage: String): String =
        suspendCancellableCoroutine { cont ->
            try {
                val prompt = """
                    Translate the following Indonesian text into $targetLanguage.
                    Return ONLY the translated text. No explanations, no markdown, no quotes.

                    Text:
                    $indonesianText
                """.trimIndent()

                val parts = JsonArray().apply { add(textPart(prompt)) }
                val req   = buildRequest(mainUrl(), geminiBody(parts))
                client.newCall(req).enqueue(simpleCallback(cont))
            } catch (e: Exception) { cont.resumeWithException(e) }
        }

    // ── 3. Text-to-speech ─────────────────────────────────────────────────────

    /**
     * Converts [text] to speech using the Gemini TTS model.
     * Returns raw PCM bytes (16-bit signed, little-endian, mono, 24 kHz).
     * Caller is responsible for wrapping in a WAV header before playback.
     *
     * Voice: "Kore" (neutral, clear — good for translated meeting content).
     * Override [voiceName] to try others: Puck, Charon, Fenrir, Aoede, etc.
     */
    suspend fun generateTts(text: String, voiceName: String = "Kore"): ByteArray =
        suspendCancellableCoroutine { cont ->
            try {
                val body = JsonObject().apply {
                    add("contents", JsonArray().apply {
                        add(JsonObject().apply {
                            add("parts", JsonArray().apply { add(textPart(text)) })
                        })
                    })
                    add("generationConfig", JsonObject().apply {
                        add("responseModalities", JsonArray().apply { add("AUDIO") })
                        add("speechConfig", JsonObject().apply {
                            add("voiceConfig", JsonObject().apply {
                                add("prebuiltVoiceConfig", JsonObject().apply {
                                    addProperty("voiceName", voiceName)
                                })
                            })
                        })
                    })
                }

                val req = buildRequest(ttsUrl(), body.toString().toRequestBody(jsonMediaType))
                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        cont.resumeWithException(e)

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                cont.resumeWithException(
                                    IOException("TTS error ${res.code}: ${res.body?.string()}")
                                )
                                return
                            }
                            try {
                                val json    = JsonParser.parseString(res.body?.string() ?: "").asJsonObject
                                val b64pcm  = json.getAsJsonArray("candidates")
                                    .get(0).asJsonObject
                                    .getAsJsonObject("content")
                                    .getAsJsonArray("parts")
                                    .get(0).asJsonObject
                                    .getAsJsonObject("inlineData")
                                    .get("data").asString
                                cont.resume(Base64.decode(b64pcm, Base64.NO_WRAP))
                            } catch (e: Exception) { cont.resumeWithException(e) }
                        }
                    }
                })
            } catch (e: Exception) { cont.resumeWithException(e) }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun textPart(text: String) = JsonObject().apply { addProperty("text", text) }

    private fun audioPart(base64: String) = JsonObject().apply {
        add("inlineData", JsonObject().apply {
            addProperty("mimeType", "audio/mp4")
            addProperty("data", base64)
        })
    }

    private fun geminiBody(parts: JsonArray): RequestBody = JsonObject().apply {
        add("contents", JsonArray().apply {
            add(JsonObject().apply { add("parts", parts) })
        })
    }.toString().toRequestBody(jsonMediaType)

    /** Callback that resumes a coroutine with extracted text or an exception. */
    private fun simpleCallback(cont: kotlin.coroutines.Continuation<String>) =
        object : Callback {
            override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        cont.resumeWithException(
                            IOException("${provider.displayName} error ${res.code}: ${res.body?.string()}")
                        )
                        return
                    }
                    try {
                        val json = JsonParser.parseString(res.body?.string() ?: "").asJsonObject
                        val text = json.getAsJsonArray("candidates")
                            .get(0).asJsonObject
                            .getAsJsonObject("content")
                            .getAsJsonArray("parts")
                            .get(0).asJsonObject
                            .get("text").asString.trim()
                        (cont as kotlin.coroutines.Continuation<String>).resume(text)
                    } catch (e: Exception) { cont.resumeWithException(e) }
                }
            }
        }
}
