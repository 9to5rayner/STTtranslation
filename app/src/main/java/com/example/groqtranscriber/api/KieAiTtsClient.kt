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
import kotlinx.coroutines.delay
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Client for kie.ai's async Jobs API, used specifically to run the
 * ElevenLabs "Text To Speech Turbo 2.5" model
 * (model = "elevenlabs/text-to-speech-turbo-2-5").
 *
 * Used as the TTS backend for [ApiProvider.KIE_AI_GEMINI] — that provider's
 * main STT/translation calls still go through the synchronous native Gemini
 * `generateContent` envelope (see [GeminiClient]); only TTS is async on
 * kie.ai, via this separate jobs-based flow:
 *
 *   1. POST /api/v1/jobs/createTask   → { taskId }
 *   2. GET  /api/v1/jobs/recordInfo?taskId=...   (poll until state != "waiting")
 *   3. On state == "success", resultJson.resultUrls[0] is a URL to the
 *      generated audio — downloaded and returned as bytes.
 *
 * Uses the SAME API key as kie.ai's main Gemini calls (Bearer auth), since
 * both are kie.ai endpoints — no separate TTS key needed, unlike the
 * previous EasyVoice integration.
 */
class KieAiTtsClient(
    private val apiKey: String,
    private val baseUrl: String = "https://api.kie.ai/api/v1"
) {
    private val client        = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val MODEL = "elevenlabs/text-to-speech-turbo-2-5"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLL_ATTEMPTS = 30 // 30 * 2s = 60s timeout
    }

    /**
     * Converts [text] to speech via ElevenLabs Turbo v2.5 on kie.ai.
     * Returns the raw bytes of the generated audio file (mp3), ready to
     * write to disk as-is — do NOT pass through WavWriter.
     *
     * @param voice ElevenLabs voice name or voice ID (default "Rachel").
     * @throws TtsException with a specific, human-readable reason on failure
     * (submission error, task failure, timeout waiting for completion,
     * or download error).
     */
    suspend fun generateSpeech(text: String, voice: String = "Rachel"): ByteArray {
        val taskId = createTask(text, voice)
        val resultUrl = pollUntilComplete(taskId)
        return downloadAudio(resultUrl)
    }

    // ── Step 1: create task ──────────────────────────────────────────────────

    private suspend fun createTask(text: String, voice: String): String =
        suspendCancellableCoroutine { cont ->
            try {
                val input = JSONObject().apply {
                    put("text", text)
                    put("voice", voice)
                }
                val body = JSONObject().apply {
                    put("model", MODEL)
                    put("input", input)
                }.toString().toRequestBody(jsonMediaType)

                val req = Request.Builder()
                    .url("$baseUrl/jobs/createTask")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(body)
                    .build()

                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        cont.resumeWithException(TtsException("kie.ai TTS network error: ${e.message}", e))

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            val raw = try { res.body?.string() } catch (e: Exception) { null }
                            if (!res.isSuccessful || raw.isNullOrBlank()) {
                                cont.resumeWithException(
                                    TtsException("kie.ai TTS task creation failed (HTTP ${res.code}): ${raw?.take(200)}")
                                )
                                return
                            }
                            try {
                                val json = JSONObject(raw)
                                if (json.optInt("code", -1) != 200) {
                                    cont.resumeWithException(
                                        TtsException("kie.ai TTS task creation failed: ${json.optString("msg", "unknown error")}")
                                    )
                                    return
                                }
                                val taskId = json.getJSONObject("data").getString("taskId")
                                cont.resume(taskId)
                            } catch (e: Exception) {
                                cont.resumeWithException(TtsException("Failed to parse kie.ai task creation response: ${e.message}", e))
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                cont.resumeWithException(TtsException("kie.ai TTS request setup failed: ${e.message}", e))
            }
        }

    // ── Step 2: poll until complete ──────────────────────────────────────────

    /** Polls recordInfo every [POLL_INTERVAL_MS] up to [MAX_POLL_ATTEMPTS] times. Returns the result audio URL. */
    private suspend fun pollUntilComplete(taskId: String): String {
        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            val (state, resultUrl, failMsg) = queryTask(taskId)
            when (state) {
                "success" -> {
                    return resultUrl ?: throw TtsException("kie.ai TTS task succeeded but returned no audio URL.")
                }
                "fail" -> {
                    throw TtsException("kie.ai TTS task failed: ${failMsg ?: "unknown reason"}")
                }
                else -> {
                    // "waiting" or any other in-progress state — keep polling
                    if (attempt < MAX_POLL_ATTEMPTS - 1) delay(POLL_INTERVAL_MS)
                }
            }
        }
        throw TtsException("kie.ai TTS task timed out after ${MAX_POLL_ATTEMPTS * POLL_INTERVAL_MS / 1000}s.")
    }

    /** Single poll request. Returns Triple(state, resultUrl, failMsg). */
    private suspend fun queryTask(taskId: String): Triple<String, String?, String?> =
        suspendCancellableCoroutine { cont ->
            try {
                val req = Request.Builder()
                    .url("$baseUrl/jobs/recordInfo?taskId=$taskId")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()

                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        cont.resumeWithException(TtsException("kie.ai TTS poll network error: ${e.message}", e))

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            val raw = try { res.body?.string() } catch (e: Exception) { null }
                            if (!res.isSuccessful || raw.isNullOrBlank()) {
                                cont.resumeWithException(
                                    TtsException("kie.ai TTS poll failed (HTTP ${res.code}): ${raw?.take(200)}")
                                )
                                return
                            }
                            try {
                                val json = JSONObject(raw)
                                val data = json.getJSONObject("data")
                                val state = data.optString("state", "waiting")

                                if (state == "success") {
                                    val resultJsonStr = data.optString("resultJson", "")
                                    val resultUrl = if (resultJsonStr.isNotBlank()) {
                                        val resultJson = JSONObject(resultJsonStr)
                                        resultJson.optJSONArray("resultUrls")?.optString(0)
                                    } else null
                                    cont.resume(Triple("success", resultUrl, null))
                                } else if (state == "fail") {
                                    cont.resume(Triple("fail", null, data.optString("failMsg", null)))
                                } else {
                                    cont.resume(Triple(state, null, null))
                                }
                            } catch (e: Exception) {
                                cont.resumeWithException(TtsException("Failed to parse kie.ai poll response: ${e.message}", e))
                            }
                        }
                    }
                })
            } catch (e: Exception) {
                cont.resumeWithException(TtsException("kie.ai TTS poll setup failed: ${e.message}", e))
            }
        }

    // ── Step 3: download result audio ────────────────────────────────────────

    private suspend fun downloadAudio(url: String): ByteArray =
        suspendCancellableCoroutine { cont ->
            try {
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) =
                        cont.resumeWithException(TtsException("Failed to download generated audio: ${e.message}", e))

                    override fun onResponse(call: Call, response: Response) {
                        response.use { res ->
                            if (!res.isSuccessful) {
                                cont.resumeWithException(TtsException("Audio download failed (HTTP ${res.code})."))
                                return
                            }
                            val bytes = try { res.body?.bytes() } catch (e: Exception) { null }
                            if (bytes == null || bytes.isEmpty()) {
                                cont.resumeWithException(TtsException("Downloaded audio file was empty."))
                                return
                            }
                            cont.resume(bytes)
                        }
                    }
                })
            } catch (e: Exception) {
                cont.resumeWithException(TtsException("Audio download setup failed: ${e.message}", e))
            }
        }
}
