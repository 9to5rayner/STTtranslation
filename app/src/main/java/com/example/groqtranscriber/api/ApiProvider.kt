package com.example.groqtranscriber.api

/**
 * Supported API providers.
 *
 * Each entry carries:
 *  - displayName         : shown in the UI spinner
 *  - baseUrl             : for STT + translation  (gemini-2.5-flash or equivalent)
 *  - ttsUrl              : for native Gemini TTS, ONLY used when useNativeGeminiTts = true
 *  - useBearerAuth       : true = Authorization: Bearer <key>  (kie.ai)
 *                          false = ?key=<key> query param       (Google)
 *  - useNativeGeminiTts  : true = call ttsUrl with the Gemini generateContent envelope
 *                          false = TTS is handled by kie.ai's async ElevenLabs job
 *                          (see KieAiTtsClient) using the SAME api key
 *
 * The native Gemini request/response envelope is identical for STT + translation
 * across both providers; only the URL and auth header style differ.
 *
 * NOTE on kie.ai TTS:
 * kie.ai has no Gemini TTS-capable model, but it does expose ElevenLabs
 * "Text To Speech Turbo 2.5" via its async Jobs API
 * (model = "elevenlabs/text-to-speech-turbo-2-5"; create task → poll →
 * download result). That flow is a different request/response shape than
 * Gemini's synchronous generateContent, so it's handled by a dedicated
 * [KieAiTtsClient] rather than forced into GeminiClient's Gemini-specific
 * JSON parsing. It reuses the same kie.ai API key — no second key field
 * needed (unlike the earlier EasyVoice integration, now removed).
 */
enum class ApiProvider(
    val displayName: String,
    val baseUrl: String,
    val ttsUrl: String,
    val useBearerAuth: Boolean,
    val useNativeGeminiTts: Boolean
) {
    GOOGLE_GEMINI(
        displayName       = "Google Gemini (Direct)",
        baseUrl            = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        ttsUrl             = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent",
        useBearerAuth      = false,
        useNativeGeminiTts = true
    ),
    KIE_AI_GEMINI(
        displayName        = "kie.ai – Gemini 3.5 Flash",
        baseUrl             = "https://api.kie.ai/gemini/v1/models/gemini-3-5-flash:generateContent",
        // Unused for this provider — TTS goes through KieAiTtsClient instead.
        ttsUrl              = "",
        useBearerAuth       = true,
        useNativeGeminiTts  = false
    );

    companion object {
        fun fromDisplayName(name: String): ApiProvider =
            entries.firstOrNull { it.displayName == name } ?: GOOGLE_GEMINI
    }
}
