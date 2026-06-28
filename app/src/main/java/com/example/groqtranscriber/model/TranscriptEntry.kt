package com.example.groqtranscriber.model

data class TranscriptEntry(
    val id: Long = System.currentTimeMillis(),  // unique stable ID for persistence
    val timestampStart: Long,                   // wall-clock ms when recording started
    val timestampEnd: Long,                     // wall-clock ms when recording stopped
    val originalText: String       = "",
    val translatedText: String     = "",
    val isEdited: Boolean          = false,     // shows EDITED tag on bubble
    val audioFilePath: String?     = null,      // path to cached TTS .wav file

    // Transient UI states — NOT persisted, reset on load
    val isTranscribing: Boolean    = false,
    val isTranslating: Boolean     = false,
    val isGeneratingTts: Boolean   = false
)
