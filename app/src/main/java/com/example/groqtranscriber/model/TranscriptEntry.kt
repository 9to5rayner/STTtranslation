package com.example.groqtranscriber.model

data class TranscriptEntry(
    val timestampStart: Long,   // ms from session start
    val timestampEnd: Long,
    val originalText: String,
    val translatedText: String  = "",   // empty until translation arrives
    val isTranslating: Boolean  = false // true while the translation API call is in flight
)
