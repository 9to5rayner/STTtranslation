package com.example.groqtranscriber.model

data class TranscriptEntry(
    val id: Long = System.currentTimeMillis(),  // unique stable ID for persistence
    val timestampStart: Long,                   // wall-clock ms when recording started
    val timestampEnd: Long,                     // wall-clock ms when recording stopped
    val originalText: String       = "",
    val translatedText: String     = "",
    val isEdited: Boolean          = false,     // shows EDITED tag on bubble
    val audioFilePath: String?     = null,      // path to cached TTS .mp3 file

    /**
     * Display name shown on the bubble — this device's own nickname for
     * outgoing entries, or the partner's nickname for incoming ones.
     */
    val senderNickname: String     = "",

    /**
     * True for messages received from the remote partner via Firebase.
     * BubbleAdapter uses this to flip bubble alignment (left/right) and colors.
     * NOT persisted — resets to false on load (incoming messages are
     * still displayed, just without the incoming-specific decoration).
     */
    val isIncoming: Boolean        = false,

    // ── Transient pipeline states — NOT persisted ─────────────────────────────
    val isTranscribing: Boolean    = false,
    val isTranslating: Boolean     = false,
    val isGeneratingTts: Boolean   = false,

    /**
     * Set when translation fails after all retries (timeout, network, API
     * error). Cleared on manual retry. NOT persisted.
     * When non-null: TTS and Firebase send phases are both blocked.
     * The bubble shows a ↻ retry translation button.
     */
    val translationError: String?  = null,

    /**
     * Set when TTS generation fails. Cleared on retry. NOT persisted.
     * The bubble shows a ↻ retry TTS button independently of translationError.
     */
    val ttsError: String?          = null,

    /**
     * True while the Firebase write is in flight.
     * Shows a small "Sending…" indicator on the bubble.
     */
    val isSendingToFirebase: Boolean = false,

    /**
     * True once Firebase confirmed the write succeeded.
     * Shows a ✓ sent indicator on the bubble.
     */
    val isSentToFirebase: Boolean    = false,

    /**
     * Non-null when the Firebase send failed.
     * Shows a ⚠️ send-failed indicator on the bubble.
     * The user can tap it to retry the send manually.
     */
    val sendError: String?           = null
)
