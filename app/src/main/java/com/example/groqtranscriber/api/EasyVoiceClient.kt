package com.example.groqtranscriber.api

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Client for EasyVoice's OpenAI-compatible TTS API.
 *
 * Used as the TTS backend for [ApiProvider.KIE_AI_GEMINI], since kie.ai has
 * no Gemini TTS-capable model. Requires its own API key, separate from the
 * key used for STT/translation.
 *
 * Unlike Gemini's TTS endpoint (JSON response with base64-encoded raw PCM
 * that needs a WAV header attached), EasyVoice's /api/v1/audio/speech
 * returns the **complete audio file** directly in the response body —
 * already a valid, playable WAV (or MP3) with its own header. So the bytes
 * here should be written straight to disk; do NOT pass them through
 * WavWriter, which would double-wrap/corrupt the header.
 */
class EasyVoiceClient(
    private val apiKey: String,
    private val baseUrl: String = "https://your-domain.com/api/v1"
) {
    private val client        = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Converts [text] to speech.
     * Returns the raw bytes of a complete WAV file, ready to write to disk as-is.
     *
     * @param voice EasyVoice voice ID (default "af_heart").
     * @throws TtsException with a specific, human-readable reason on failure.
     */
    suspend fun generateSpeech(text: String, voice: String = "af_heart"): ByteArray =
        suspendCancellableCoroutine { cont ->
            try {
                val body = JSONObject().apply {
                    put("model", "kokoro-82m")
                    put("input", text)
                    put("voice", voice)
                    put("response_format", "wav") // ask for WAV so we can write bytes directly
                }.toString().toRequestBody(jsonMediaType)

                val req = Request.Builder()
                    .url("$baseUrl/audio/speech")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        cont.resumeWithException(TtsException("EasyVoice network error: ${e.message}", e))

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                // Error responses from EasyVoice are JSON, unlike the
                                // success path which is raw audio bytes — try to surface
                                // the message, but don't crash if it isn't JSON.
                                val errBody = try { res.body?.string() } catch (e: Exception) { null }
                                val reason = try {
                                    errBody?.let { JSONObject(it).optString("error", it) } ?: "HTTP ${res.code}"
                                } catch (e: Exception) {
                                    errBody?.take(200) ?: "HTTP ${res.code}"
                                }
                                cont.resumeWithException(TtsException("EasyVoice error ${res.code}: $reason"))
                                return
                            }

                            val bytes = try { res.body?.bytes() } catch (e: Exception) { null }
                            if (bytes == null || bytes.isEmpty()) {
                                cont.resumeWithException(TtsException("EasyVoice returned an empty audio response."))
                                return
                            }
                            cont.resume(bytes)
                        }
                    }
                })
            } catch (e: Exception) {
                cont.resumeWithException(TtsException("EasyVoice request setup failed: ${e.message}", e))
            }
        }
}
