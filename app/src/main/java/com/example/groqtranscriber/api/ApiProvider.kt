package com.example.groqtranscriber.api

/**
 * Supported API providers.
 *
 * Each entry carries:
 *  - displayName         : shown in the UI spinner
 *  - baseUrl             : for STT + translation  (gemini-2.5-flash or equivalent)
 *  - ttsUrl              : for TTS generation, ONLY used when useNativeGeminiTts = true
 *  - useBearerAuth       : true = Authorization: Bearer <key>  (kie.ai)
 *                          false = ?key=<key> query param       (Google)
 *  - useNativeGeminiTts  : true = call ttsUrl with the Gemini generateContent envelope
 *                          false = TTS is handled by a separate provider (see EasyVoiceClient)
 *
 * The native Gemini request/response envelope is identical for STT + translation
 * across both providers; only the URL and auth header style differ.
 *
 * NOTE on kie.ai TTS:
 * kie.ai does not currently expose a Gemini TTS-capable model. Rather than
 * point ttsUrl at a text-only model (which silently fails — see prior fix),
 * TTS for this provider is routed to EasyVoice instead, using a SEPARATE
 * API key entered by the user. See LaunchActivity (second key field, shown
 * only for this provider) and EasyVoiceClient.
 */
enum class ApiProvider(
    val displayName: String,
    val baseUrl: String,
    val ttsUrl: String,
    val useBearerAuth: Boolean,
    val useNativeGeminiTts: Boolean,
    val needsSeparateTtsKey: Boolean
) {
    GOOGLE_GEMINI(
        displayName        = "Google Gemini (Direct)",
        baseUrl             = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        ttsUrl              = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent",
        useBearerAuth       = false,
        useNativeGeminiTts  = true,
        needsSeparateTtsKey = false
    ),
    KIE_AI_GEMINI(
        displayName         = "kie.ai – Gemini 3.5 Flash",
        baseUrl              = "https://api.kie.ai/gemini/v1/models/gemini-3-5-flash:generateContent",
        // No Gemini TTS model available on kie.ai — ttsUrl is unused for this
        // provider (useNativeGeminiTts = false). TTS instead goes through
        // EasyVoiceClient using a separate key. Left blank rather than
        // pointed at a wrong/guessed model.
        ttsUrl               = "",
        useBearerAuth        = true,
        useNativeGeminiTts   = false,
        needsSeparateTtsKey  = true
    );

    companion object {
        fun fromDisplayName(name: String): ApiProvider =
            entries.firstOrNull { it.displayName == name } ?: GOOGLE_GEMINI
    }
}
