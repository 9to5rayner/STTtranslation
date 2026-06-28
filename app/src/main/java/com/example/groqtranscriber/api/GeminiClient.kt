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
 * STT + translation: works with both Google Gemini (direct) and kie.ai
 * native Gemini endpoints — the request/response envelope is identical;
 * only URL and auth differ.
 *
 * TTS: branches by provider.
 *  - GOOGLE_GEMINI  → native Gemini TTS (generateContent w/ AUDIO modality).
 *  - KIE_AI_GEMINI  → delegates to [easyVoiceClient], since kie.ai has no
 *                     Gemini TTS-capable model. Requires a separate API key
 *                     (see [ApiProvider.needsSeparateTtsKey]).
 */
class GeminiClient(
    private val apiKey: String,
    private val provider: ApiProvider = ApiProvider.GOOGLE_GEMINI,
    private val easyVoiceClient: EasyVoiceClient? = null
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
     * Converts [text] to speech.
     *
     * Returns bytes ready to write DIRECTLY to a .wav file:
     *  - GOOGLE_GEMINI path returns raw 16-bit/24kHz PCM, which the caller
     *    must wrap with [com.example.groqtranscriber.audio.WavWriter] first.
     *  - KIE_AI_GEMINI (EasyVoice) path returns a COMPLETE wav file already —
     *    do NOT pass it through WavWriter, just write it as-is.
     *
     * Callers should check `provider.useNativeGeminiTts` to know which case
     * they're in (see [TtsResult] used by BubbleAdapter/RecordingActivity).
     *
     * Voice (Gemini path): "Kore" (neutral, clear). Override [voiceName] to
     * try others: Puck, Charon, Fenrir, Aoede, etc.
     * Voice (EasyVoice path): "af_heart" by default; override [voiceName]
     * with any EasyVoice voice ID.
     *
     * Throws [TtsException] with a specific, human-readable reason on failure.
     */
    suspend fun generateTts(text: String, voiceName: String? = null): ByteArray {
        if (!provider.useNativeGeminiTts) {
            val evClient = easyVoiceClient
                ?: throw TtsException(
                    "No EasyVoice API key configured for ${provider.displayName}. " +
                    "Add a TTS API key on the launch screen."
                )
            return evClient.generateSpeech(text, voice = voiceName ?: "af_heart")
        }
        return generateGeminiTts(text, voiceName ?: "Kore")
    }

    /** Native Gemini TTS — unchanged from before, just extracted into its own function. */
    private suspend fun generateGeminiTts(text: String, voiceName: String): ByteArray =
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
                        cont.resumeWithException(TtsException("Network error: ${e.message}", e))

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            val rawBody = try { res.body?.string() } catch (e: Exception) { null }

                            if (!res.isSuccessful) {
                                cont.resumeWithException(
                                    TtsException("TTS error ${res.code}: ${rawBody?.take(300)}")
                                )
                                return
                            }
                            if (rawBody.isNullOrBlank()) {
                                cont.resumeWithException(TtsException("TTS returned an empty response."))
                                return
                            }

                            try {
                                val json = JsonParser.parseString(rawBody).asJsonObject

                                val candidates = json.getAsJsonArray("candidates")
                                if (candidates == null || candidates.size() == 0) {
                                    cont.resumeWithException(
                                        TtsException("TTS response had no candidates (possibly blocked content).")
                                    )
                                    return
                                }

                                val partsArray = candidates.get(0).asJsonObject
                                    .getAsJsonObject("content")
                                    ?.getAsJsonArray("parts")

                                if (partsArray == null || partsArray.size() == 0) {
                                    cont.resumeWithException(
                                        TtsException("TTS response had no content parts.")
                                    )
                                    return
                                }

                                val firstPart = partsArray.get(0).asJsonObject
                                val inlineData = firstPart.getAsJsonObject("inlineData")

                                if (inlineData == null) {
                                    val textFallback = firstPart.get("text")?.asString
                                    cont.resumeWithException(
                                        TtsException(
                                            "Model did not return audio — it returned " +
                                            (if (textFallback != null) "text instead (\"${textFallback.take(80)}\")."
                                             else "no inlineData field.") +
                                            " Check that the configured TTS model/URL actually supports audio output."
                                        )
                                    )
                                    return
                                }

                                val b64pcm = inlineData.get("data")?.asString
                                if (b64pcm.isNullOrBlank()) {
                                    cont.resumeWithException(TtsException("TTS inlineData had no audio bytes."))
                                    return
                                }

                                cont.resume(Base64.decode(b64pcm, Base64.NO_WRAP))
                            } catch (e: Exception) {
                                cont.resumeWithException(TtsException("Failed to parse TTS response: ${e.message}", e))
                            }
                        }
                    }
                })
            } catch (e: Exception) { cont.resumeWithException(TtsException("TTS request setup failed: ${e.message}", e)) }
        }

    /**
     * Writes the bytes returned by [generateTts] to [outputFile] correctly,
     * regardless of which provider/path produced them:
     *  - Native Gemini TTS bytes are raw PCM → wrapped with [WavWriter].
     *  - EasyVoice bytes are already a complete WAV file → written as-is.
     *
     * Centralizing this here means callers never need to know or branch on
     * `provider.useNativeGeminiTts` themselves.
     */
    fun writeTtsBytesToWav(ttsBytes: ByteArray, outputFile: File) {
        if (provider.useNativeGeminiTts) {
            com.example.groqtranscriber.audio.WavWriter.write(ttsBytes, outputFile)
        } else {
            outputFile.writeBytes(ttsBytes)
        }
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

/**
 * Thrown by [GeminiClient.generateTts] / [EasyVoiceClient.generateSpeech]
 * with a specific, user-presentable reason for the failure (network error,
 * HTTP error, blocked content, model returned text instead of audio,
 * missing API key, malformed response, etc.).
 */
class TtsException(message: String, cause: Throwable? = null) : Exception(message, cause)
