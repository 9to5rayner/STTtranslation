package com.example.groqtranscriber.api

import android.util.Base64
import com.example.groqtranscriber.model.Language
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
 * All AI calls for the app — STT, translation, and TTS — backed entirely
 * by kie.ai. There is no longer a direct-Gemini path; kie.ai is the only
 * backend, reached with Bearer auth.
 *
 * STT + translation: kie.ai's Gemini-compatible `generateContent` endpoint.
 * TTS: delegates to [KieAiTtsClient], which runs ElevenLabs "Text To Speech
 * Turbo 2.5" via kie.ai's async Jobs API (create task → poll → download).
 * TTS output is always a complete mp3 file — written to disk as-is.
 */
class KieAiClient(
    private val apiKey: String,
    private val kieAiTtsClient: KieAiTtsClient = KieAiTtsClient(apiKey)
) {
    private val client        = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val STT_TRANSLATE_URL =
            "https://api.kie.ai/gemini/v1/models/gemini-3-5-flash:generateContent"
    }

    private fun buildRequest(body: RequestBody): Request =
        Request.Builder()
            .url(STT_TRANSLATE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

    // ── 1. Speech-to-text ─────────────────────────────────────────────────────

    /**
     * Transcribes speech in [sourceLang] from [file] (AAC/MP4).
     * Returns raw transcription text, or empty string if no speech detected.
     */
    suspend fun transcribeAudio(file: File, sourceLang: Language): String =
        suspendCancellableCoroutine { cont ->
            try {
                val base64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                val prompt = """
                    Transcribe the ${sourceLang.displayName} speech in this audio clip exactly as spoken.
                    Return ONLY the raw transcription — no labels, no JSON, no markdown,
                    no explanations. If there is no intelligible speech, return an empty string.
                """.trimIndent()

                val parts = JsonArray().apply {
                    add(textPart(prompt))
                    add(audioPart(base64))
                }
                val req = buildRequest(geminiBody(parts))
                client.newCall(req).enqueue(simpleCallback(cont))
            } catch (e: Exception) { cont.resumeWithException(e) }
        }

    // ── 2. Translation ────────────────────────────────────────────────────────

    /**
     * Translates [text] from [sourceLang] into [targetLang].
     * Returns translated text only — no extra formatting.
     */
    suspend fun translateText(text: String, sourceLang: Language, targetLang: Language): String =
        suspendCancellableCoroutine { cont ->
            try {
                val prompt = """
                    Translate the following ${sourceLang.displayName} text into ${targetLang.displayName}.
                    Return ONLY the translated text. No explanations, no markdown, no quotes.

                    Text:
                    $text
                """.trimIndent()

                val parts = JsonArray().apply { add(textPart(prompt)) }
                val req   = buildRequest(geminiBody(parts))
                client.newCall(req).enqueue(simpleCallback(cont))
            } catch (e: Exception) { cont.resumeWithException(e) }
        }

    // ── 3. Text-to-speech ─────────────────────────────────────────────────────

    /** TTS always returns a complete mp3 file via kie.ai/ElevenLabs. */
    fun ttsFileExtension(): String = "mp3"

    /**
     * Converts [text] to speech via ElevenLabs Turbo v2.5 on kie.ai.
     * Returns bytes ready to write DIRECTLY to a file — use
     * [writeTtsBytesToFile] for clarity/consistency at call sites.
     *
     * Throws [TtsException] with a specific, human-readable reason on failure.
     */
    suspend fun generateTts(text: String, voiceName: String = "Rachel"): ByteArray =
        kieAiTtsClient.generateSpeech(text, voice = voiceName)

    /** Writes TTS bytes to [outputFile] as-is (always a complete mp3 already). */
    fun writeTtsBytesToFile(ttsBytes: ByteArray, outputFile: File) {
        outputFile.writeBytes(ttsBytes)
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
                            IOException("kie.ai error ${res.code}: ${res.body?.string()}")
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
                        cont.resume(text)
                    } catch (e: Exception) { cont.resumeWithException(e) }
                }
            }
        }
}

/**
 * Thrown by [KieAiClient.generateTts] / [KieAiTtsClient.generateSpeech]
 * with a specific, user-presentable reason for the failure (network error,
 * HTTP error, blocked content, task failure/timeout, malformed response, etc.).
 */
class TtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
