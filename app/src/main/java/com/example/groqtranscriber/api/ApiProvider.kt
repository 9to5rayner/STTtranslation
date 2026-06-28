package com.example.groqtranscriber.api

/**
 * Supported API providers.
 *
 * Each entry carries:
 *  - displayName   : shown in the UI spinner
 *  - baseUrl       : for STT + translation  (gemini-2.5-flash or equivalent)
 *  - ttsUrl        : for TTS generation     (gemini-2.5-flash-preview-tts)
 *  - useBearerAuth : true = Authorization: Bearer <key>  (kie.ai)
 *                    false = ?key=<key> query param       (Google)
 *
 * The native Gemini request/response envelope is identical for both providers;
 * only the URL and auth header style differ.
 */
enum class ApiProvider(
    val displayName: String,
    val baseUrl: String,
    val ttsUrl: String,
    val useBearerAuth: Boolean
) {
    GOOGLE_GEMINI(
        displayName   = "Google Gemini (Direct)",
        baseUrl       = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        ttsUrl        = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-preview-tts:generateContent",
        useBearerAuth = false
    ),
    KIE_AI_GEMINI(
        displayName   = "kie.ai – Gemini 3.5 Flash",
        baseUrl       = "https://api.kie.ai/gemini/v1/models/gemini-3-5-flash:generateContent",
        ttsUrl        = "https://api.kie.ai/gemini/v1/models/gemini-3-5-flash:generateContent",
        useBearerAuth = true
    );

    companion object {
        fun fromDisplayName(name: String): ApiProvider =
            entries.firstOrNull { it.displayName == name } ?: GOOGLE_GEMINI
    }
}
