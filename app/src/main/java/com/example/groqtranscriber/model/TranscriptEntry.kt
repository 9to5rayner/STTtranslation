package com.example.groqtranscriber.model

data class TranscriptEntry(
    val timestampStart: Long, // Milliseconds from session start
    val timestampEnd: Long,
    val originalText: String,
    val translatedText: String
)