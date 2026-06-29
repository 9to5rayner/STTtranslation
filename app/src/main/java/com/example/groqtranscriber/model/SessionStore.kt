package com.example.groqtranscriber.model

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

object SessionStore {

    private const val FILE_NAME = "session_history.json"
    private val gson: Gson = GsonBuilder().create()

    fun save(context: Context, entries: List<TranscriptEntry>) {
        val file = storageFile(context)
        file.writeText(gson.toJson(entries))
    }

    fun load(context: Context): MutableList<TranscriptEntry> {
        val file = storageFile(context)
        if (!file.exists()) return mutableListOf()
        return try {
            val type = object : TypeToken<List<TranscriptEntry>>() {}.type
            val loaded: List<TranscriptEntry> = gson.fromJson(file.readText(), type)
            loaded.map {
                it.copy(
                    isIncoming             = false,
                    isTranscribing         = false,
                    isAwaitingConfirmation = false,   // never survives a restart
                    isTranslating          = false,
                    isGeneratingTts        = false,
                    translationError       = null,
                    ttsError               = null,
                    isSendingToFirebase    = false,
                    sendError              = null
                )
            }.toMutableList()
        } catch (e: Exception) {
            e.printStackTrace()
            mutableListOf()
        }
    }

    fun clear(context: Context) {
        storageFile(context).delete()
    }

    private fun storageFile(context: Context): File =
        File(context.filesDir, FILE_NAME)
}
