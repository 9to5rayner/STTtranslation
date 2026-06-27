package com.example.groqtranscriber.api

/**
 * Supported API providers.
 * Each entry carries a human-readable label (shown in the UI spinner),
 * the base URL to call, and the model name string to embed in that URL.
 *
 * For the NATIVE GEMINI format both Google and kie.ai use the same
 * request/response envelope (contents/parts/inlineData), so GeminiClient
 * only needs to swap the URL + auth header style.
 */
enum class ApiProvider(
    val displayName: String,
    val baseUrl: String,
    /** True  → Authorization: Bearer <key>  (kie.ai)
     *  False → ?key=<key> query param        (Google) */
    val useBearerAuth: Boolean
) {
    GOOGLE_GEMINI(
        displayName  = "Google Gemini (Direct)",
        baseUrl      = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent",
        useBearerAuth = false
    ),
    KIE_AI_GEMINI_35_FLASH(
        displayName  = "kie.ai – Gemini 3.5 Flash",
        // Native Gemini format on kie.ai — supports inline base64 audio
        baseUrl      = "https://api.kie.ai/gemini/v1/models/gemini-3-5-flash:generateContent",
        useBearerAuth = true
    );

    companion object {
        fun fromDisplayName(name: String): ApiProvider =
            entries.firstOrNull { it.displayName == name } ?: GOOGLE_GEMINI
    }
}
