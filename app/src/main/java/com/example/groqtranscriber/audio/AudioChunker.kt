package com.example.groqtranscriber.audio

import android.content.Context
import java.io.File

class AudioChunker(private val context: Context) {

    /**
     * Generates a distinct temporary cache file path for an upcoming audio segment chunk.
     * Storing files inside the app's internal cache ensures they automatically 
     * clean up when processed and don't clog up your user's storage.
     *
     * @param chunkIndex The current iteration order number of the slice.
     * @return File object pointing to the cached file pathway.
     */
    fun createChunkFile(chunkIndex: Int): File {
        val cacheDirectory = context.cacheDir
        return File(cacheDirectory, "groq_audio_chunk_$chunkIndex.mp4")
    }

    /**
     * Safety clean up tool to wipe old cached audio data fragments 
     * from the system once they are successfully sent to Groq.
     */
    fun safelyDeleteChunk(file: File) {
        if (file.exists()) {
            try {
                file.delete()
            } catch (e: SecurityException) {
                e.printStackTrace()
            }
        }
    }
}