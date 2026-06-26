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
import androidx.core.ActivityCompat
import com.example.groqtranscriber.R
import com.example.groqtranscriber.audio.AudioRecorder
import com.example.groqtranscriber.audio.AudioChunker
import com.example.groqtranscriber.api.GeminiClient
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class RecordingActivity : AppCompatActivity() {
    private lateinit var audioRecorder: AudioRecorder
    private lateinit var audioChunker: AudioChunker
    private lateinit var geminiClient: GeminiClient
    private lateinit var targetLang: String
    
    private var isRecordingSession = true
    private var isSongSkippedMode = false
    private var isPausedMode = false
    
    private var chunkIndex = 0
    private var sessionStartTime = 0L
    private var pauseAccumulatedTime = 0L
    private var currentChunkStartTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main)
    
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        val btnPause = findViewById<Button>(R.id.btnPause)
        val btnSkip = findViewById<Button>(R.id.btnSkip)
        val btnEnd = findViewById<Button>(R.id.btnEnd)

        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        
        // Swapped to Gemini Engine
        geminiClient = GeminiClient(apiKey)
        audioRecorder = AudioRecorder(this)
        audioChunker = AudioChunker(this)
        targetLang = intent.getStringExtra("TARGET_LANG") ?: "English"

        SessionData.clear()
        sessionStartTime = System.currentTimeMillis()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 200)
        } else {
            startChunkCycle()
        }

        // Pause Button Logic
        btnPause.setOnClickListener {
            if (!isRecordingSession || isSongSkippedMode) return@setOnClickListener
            
            isPausedMode = !isPausedMode
            if (isPausedMode) {
                btnPause.text = "Resume Session"
                tvStatus.text = "⏸️ Session Paused"
                audioRecorder.stopRecording()
                handler.removeCallbacksAndMessages(null)
                // Track how much time passed in the partial chunk before hitting pause
                pauseAccumulatedTime += System.currentTimeMillis() - currentChunkStartTime
            } else {
                btnPause.text = "Pause Session"
                tvStatus.text = "🔴 Recording active..."
                startChunkCycle()
            }
        }

        // Skip Song Logic
        btnSkip.setOnClickListener {
            if (isPausedMode) return@setOnClickListener
            isSongSkippedMode = !isSongSkippedMode
            if (isSongSkippedMode) {
                btnSkip.text = "Resume Music"
                tvStatus.text = "⏭️ Paused (Skip Song Active)"
                audioRecorder.stopRecording()
                handler.removeCallbacksAndMessages(null)
            } else {
                btnSkip.text = "Skip Song"
                tvStatus.text = "🔴 Recording active..."
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
        if (!isRecordingSession || isSongSkippedMode || isPausedMode) return

        currentChunkStartTime = System.currentTimeMillis()
        val startTimeOffset = currentChunkStartTime - sessionStartTime
        val chunkFile = audioChunker.createChunkFile(chunkIndex++)
        
        audioRecorder.startRecording(chunkFile)

        handler.postDelayed({
            audioRecorder.stopRecording()
            val endTimeOffset = System.currentTimeMillis() - sessionStartTime
            
            processChunkWithGeminiAsync(chunkFile, startTimeOffset, endTimeOffset)
            
            if (isRecordingSession && !isSongSkippedMode && !isPausedMode) {
                startChunkCycle()
            }
        }, 12000)
    }

    private fun processChunkWithGeminiAsync(file: File, start: Long, end: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                // One single structural request yields both values instantly!
                val result = geminiClient.processAudio(file, targetLang)
                val originalText = result.first
                val translatedText = result.second

                if (originalText.isNotBlank()) {
                    val entry = TranscriptEntry(start, end, originalText, translatedText)
                    SessionData.entries.add(entry)
                    
                    launch(Dispatchers.Main) {
                        tvLog.append("\nID: $originalText\n$targetLang: $translatedText\n—")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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