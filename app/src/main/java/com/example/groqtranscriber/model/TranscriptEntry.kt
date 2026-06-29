package com.example.groqtranscriber.model

data class TranscriptEntry(
    val id: Long = System.currentTimeMillis(),
    val timestampStart: Long,
    val timestampEnd: Long,
    val originalText: String       = "",
    val translatedText: String     = "",
    val isEdited: Boolean          = false,
    val audioFilePath: String?     = null,
    val senderNickname: String     = "",
    val isIncoming: Boolean        = false,

    // ── Transient pipeline states — NOT persisted ─────────────────────────────
    val isTranscribing: Boolean    = false,

    /**
     * True after transcription succeeds, while waiting for the user to
     * confirm or edit the text before translation begins.
     * The bubble shows the transcribed text with a "Waiting for confirmation…"
     * label and the confirm/edit button is shown inline.
     */
    val isAwaitingConfirmation: Boolean = false,

    val isTranslating: Boolean     = false,
    val isGeneratingTts: Boolean   = false,
    val translationError: String?  = null,
    val ttsError: String?          = null,
    val isSendingToFirebase: Boolean = false,
    val isSentToFirebase: Boolean    = false,
    val sendError: String?           = null
)
