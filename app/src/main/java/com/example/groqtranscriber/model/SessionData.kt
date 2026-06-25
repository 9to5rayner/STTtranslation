package com.example.groqtranscriber.model

object SessionData {
    val entries = mutableListOf<TranscriptEntry>()
    fun clear() = entries.clear()
}
