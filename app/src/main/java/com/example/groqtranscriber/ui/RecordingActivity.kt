package com.example.groqtranscriber.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.groqtranscriber.R
import com.example.groqtranscriber.audio.AudioRecorder
import com.example.groqtranscriber.audio.AudioChunker
import com.example.groqtranscriber.api.GroqClient
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class RecordingActivity : AppCompatActivity() {
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioChunker: AudioChunker
    private lateinit var groqClient: GroqClient
    private lateinit var targetLang: String
    
    private var isRecordingSession = true
    private var isSongSkippedMode = false
    private var chunkIndex = 0
    private var sessionStartTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var currentChunkFile: File? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        val btnSkip = findViewById<Button>(R.id.btnSkip)
        val btnEnd = findViewById<Button>(R.id.btnEnd)

        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        
        groqClient = GroqClient(apiKey)
        audioRecorder = AudioRecorder(this)
        audioChunker = AudioChunker(this)
        targetLang = intent.getStringExtra("TARGET_LANG") ?: "English"

        // Clear previous session runs
        SessionData.clear()
        sessionStartTime = System.currentTimeMillis()

        // Explicit micro runtime check
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        } else {
            startChunkCycle()
        }

        btnSkip.setOnClickListener {
            isSongSkippedMode = !isSongSkippedMode
            if (isSongSkippedMode) {
                btnSkip.text = "Resume Session"
                tvStatus.text = "Paused (Skip Song Active)"
                audioRecorder.stopRecording()
                handler.removeCallbacksAndMessages(null)
            } else {
                btnSkip.text = "Skip Song"
                tvStatus.text = "Recording active..."
                startChunkCycle()
            }
        }

        btnEnd.setOnClickListener {
            isRecordingSession = false
            handler.removeCallbacksAndMessages(null)
            audioRecorder.stopRecording()
            
            val intent = Intent(this, ExportActivity::class.java).apply {
                putExtra("TARGET_LANG", targetLang)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun startChunkCycle() {
        if (!isRecordingSession || isSongSkippedMode) return

        val startTimeOffset = System.currentTimeMillis() - sessionStartTime
        val chunkFile = audioChunker.createChunkFile(chunkIndex++)
        currentChunkFile = chunkFile
        
        audioRecorder.startRecording(chunkFile)

        handler.postDelayed({
            audioRecorder.stopRecording()
            val endTimeOffset = System.currentTimeMillis() - sessionStartTime
            
            processChunkAsync(chunkFile, startTimeOffset, endTimeOffset)
            
            if (isRecordingSession && !isSongSkippedMode) {
                startChunkCycle()
            }
        }, 12000) // 12-second intervals
    }

    private fun processChunkAsync(file: File, start: Long, end: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                val originalText = groqClient.transcribeAudio(file)
                if (originalText.isNotBlank()) {
                    val translatedText = groqClient.translateText(originalText, targetLang)
                    val entry = TranscriptEntry(start, end, originalText, translatedText)
                    SessionData.entries.add(entry)
                    
                    launch(Dispatchers.Main) {
                        tvLog.append("\nID: $originalText\n$targetLang: $translatedText\n—")
                    }
                }
            } catch (e: Exception) {
                e.provider()
            } finally {
                audioChunker.safelyDeleteChunk(file)
            }
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        if (rc == 200 && results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) {
            startChunkCycle()
        } else {
            Toast.makeText(this, "Microphone access is required.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}