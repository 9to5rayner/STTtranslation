package com.example.groqtranscriber.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException

class AudioRecorder(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var currentFile: File? = null

    /**
     * Starts recording audio from the microphone and saves it to the specified file.
     */
    fun startRecording(outputFile: File) {
        currentFile = outputFile
        
        // MediaRecorder initialization varies slightly based on Android version
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)       // CD Quality sampling rate
            setAudioEncodingBitRate(96000)     // High-quality bit rate clear for speech STT
            setOutputFile(outputFile.absolutePath)
            
            try {
                prepare()
                start()
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: IllegalStateException) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Stops the active recording session and frees up system media resources.
     */
    fun stopRecording() {
        try {
            mediaRecorder?.stop()
        } catch (e: RuntimeException) {
            // Catches a situation where stop() is called immediately after start() 
            // (e.g., if the user stops the app instantly)
            currentFile?.delete() 
        } finally {
            mediaRecorder?.release()
            mediaRecorder = null
        }
    }
}