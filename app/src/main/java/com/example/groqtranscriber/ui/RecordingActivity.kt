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
import com.example.groqtranscriber.api.GeminiClient
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var currentChunkStartTime = 0L

    // SupervisorJob ensures one failed chunk doesn't cancel sibling coroutines
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnPause: Button
    private lateinit var btnSkip: Button

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnPause = findViewById(R.id.btnPause)
        btnSkip = findViewById(R.id.btnSkip)
        val btnEnd = findViewById<Button>(R.id.btnEnd)

        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""

        if (apiKey.isEmpty()) {
            Toast.makeText(this, "No API key found. Please go back and enter one.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        geminiClient = GeminiClient(apiKey)
        audioRecorder = AudioRecorder(this)
        audioChunker = AudioChunker(this)
        targetLang = intent.getStringExtra("TARGET_LANG") ?: "English"

        SessionData.clear()
        sessionStartTime = System.currentTimeMillis()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Disable buttons until permission is resolved
            setControlsEnabled(false)
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
        } else {
            startChunkCycle()
        }

        btnPause.setOnClickListener {
            // Ignore taps during skip mode or if not recording
            if (!isRecordingSession || isSongSkippedMode) return@setOnClickListener
            isPausedMode = !isPausedMode
            if (isPausedMode) {
                btnPause.text = "Resume Session"
                tvStatus.text = "⏸️ Session Paused"
                audioRecorder.stopRecording()
                handler.removeCallbacksAndMessages(null)
            } else {
                btnPause.text = "Pause Session"
                tvStatus.text = "🔴 Recording active..."
                startChunkCycle()
            }
        }

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
            startActivity(
                Intent(this, ExportActivity::class.java).apply {
                    putExtra("TARGET_LANG", targetLang)
                }
            )
            finish()
        }
    }

    private fun setControlsEnabled(enabled: Boolean) {
        btnPause.isEnabled = enabled
        btnSkip.isEnabled = enabled
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
            processChunkAsync(chunkFile, startTimeOffset, endTimeOffset)
            // Schedule next cycle only if still active
            if (isRecordingSession && !isSongSkippedMode && !isPausedMode) {
                startChunkCycle()
            }
        }, CHUNK_DURATION_MS)
    }

    private fun processChunkAsync(file: File, start: Long, end: Long) {
        // Launch on IO, then switch to Main for UI — no nested launch needed
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    geminiClient.processAudio(file, targetLang)
                }
                val originalText = result.first
                val translatedText = result.second
                if (originalText.isNotBlank()) {
                    SessionData.entries.add(
                        TranscriptEntry(start, end, originalText, translatedText)
                    )
                    // Already on Main dispatcher here
                    tvLog.append("\nID: $originalText\n$targetLang: $translatedText\n—")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                // Surface errors visibly so they're not silent
                tvLog.append("\n[Error processing chunk: ${e.message}]\n")
            } finally {
                withContext(Dispatchers.IO) {
                    audioChunker.safelyDeleteChunk(file)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                setControlsEnabled(true)
                startChunkCycle()
            } else {
                Toast.makeText(this, "Microphone access is required.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up to avoid leaks if the activity is destroyed mid-session
        handler.removeCallbacksAndMessages(null)
        if (isRecordingSession) {
            audioRecorder.stopRecording()
        }
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 200
        private const val CHUNK_DURATION_MS = 12_000L
    }
}