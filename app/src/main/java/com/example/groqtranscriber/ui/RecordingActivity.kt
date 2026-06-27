package com.example.groqtranscriber.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.groqtranscriber.R
import com.example.groqtranscriber.api.ApiProvider
import com.example.groqtranscriber.api.GeminiClient
import com.example.groqtranscriber.audio.AudioChunker
import com.example.groqtranscriber.audio.AudioRecorder
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
    private lateinit var provider: ApiProvider

    // Recording state machine
    private var hasStarted      = false   // user tapped Start at least once
    private var isActive        = false   // currently recording chunks
    private var isPaused        = false
    private var isSongSkipped   = false

    private var chunkIndex       = 0
    private var sessionStartTime = 0L

    private val scope   = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    // Views
    private lateinit var tvStatus:        TextView
    private lateinit var btnStart:        Button
    private lateinit var btnPause:        Button
    private lateinit var btnSkip:         Button
    private lateinit var btnEnd:          Button
    private lateinit var rvTranscript:    RecyclerView
    private lateinit var transcriptAdapter: LiveTranscriptAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)

        tvStatus     = findViewById(R.id.tvStatus)
        btnStart     = findViewById(R.id.btnStart)
        btnPause     = findViewById(R.id.btnPause)
        btnSkip      = findViewById(R.id.btnSkip)
        btnEnd       = findViewById(R.id.btnEnd)
        rvTranscript = findViewById(R.id.rvTranscriptLog)

        val prefs  = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "No API key found — please go back.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val providerName = intent.getStringExtra("API_PROVIDER") ?: ApiProvider.GOOGLE_GEMINI.name
        provider = try { ApiProvider.valueOf(providerName) }
                   catch (e: IllegalArgumentException) { ApiProvider.GOOGLE_GEMINI }

        geminiClient = GeminiClient(apiKey, provider)
        audioRecorder = AudioRecorder(this)
        audioChunker  = AudioChunker(this)
        targetLang    = intent.getStringExtra("TARGET_LANG") ?: "English"

        SessionData.clear()

        // RecyclerView
        transcriptAdapter = LiveTranscriptAdapter(this, targetLang, geminiClient, scope)
        rvTranscript.apply {
            adapter       = transcriptAdapter
            layoutManager = LinearLayoutManager(this@RecordingActivity)
                .also { it.stackFromEnd = true }
        }

        // Initial idle state
        showIdleState()

        // ── Button wiring ───────────────────────────────────────────────────

        btnStart.setOnClickListener {
            if (!hasStarted) {
                requestRecordPermissionOrStart()
            }
        }

        btnPause.setOnClickListener {
            if (!isActive && !isPaused) return@setOnClickListener
            if (isPaused) {
                resumeSession()
            } else {
                pauseSession()
            }
        }

        btnSkip.setOnClickListener {
            if (!hasStarted) return@setOnClickListener
            if (isSongSkipped) {
                isSongSkipped = false
                btnSkip.text  = "Skip Song"
                resumeSession()
            } else {
                isSongSkipped = true
                btnSkip.text  = "Resume Music"
                pauseSession()
                tvStatus.text = "⏭️  Song skipped — not recording"
            }
        }

        btnEnd.setOnClickListener {
            stopEverything()
            startActivity(
                Intent(this, ExportActivity::class.java).apply {
                    putExtra("TARGET_LANG",  targetLang)
                    putExtra("API_PROVIDER", provider.name)
                }
            )
            finish()
        }
    }

    // ── State helpers ────────────────────────────────────────────────────────

    private fun showIdleState() {
        tvStatus.text        = "Ready — tap Start to begin"
        btnStart.visibility  = View.VISIBLE
        btnPause.visibility  = View.GONE
        btnPause.isEnabled   = false
        btnSkip.isEnabled    = false
        btnEnd.isEnabled     = false
    }

    private fun showRecordingState() {
        tvStatus.text        = "🔴 Recording · ${provider.displayName}"
        btnStart.visibility  = View.GONE
        btnPause.visibility  = View.VISIBLE
        btnPause.text        = "Pause"
        btnPause.isEnabled   = true
        btnSkip.isEnabled    = true
        btnEnd.isEnabled     = true
    }

    private fun pauseSession() {
        isPaused = true
        isActive = false
        audioRecorder.stopRecording()
        handler.removeCallbacksAndMessages(null)
        tvStatus.text   = "⏸️  Paused"
        btnPause.text   = "Resume"
    }

    private fun resumeSession() {
        isPaused      = false
        isSongSkipped = false
        btnSkip.text  = "Skip Song"
        startChunkCycle()
    }

    // ── Permission + start ───────────────────────────────────────────────────

    private fun requestRecordPermissionOrStart() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_AUDIO_PERMISSION
            )
        } else {
            beginSession()
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
                beginSession()
            } else {
                Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun beginSession() {
        hasStarted       = true
        sessionStartTime = System.currentTimeMillis()
        showRecordingState()
        startChunkCycle()
    }

    // ── Chunk cycle ──────────────────────────────────────────────────────────

    private fun startChunkCycle() {
        if (isPaused || isSongSkipped) return
        isActive = true
        showRecordingState()

        val chunkStart = System.currentTimeMillis() - sessionStartTime
        val chunkFile  = audioChunker.createChunkFile(chunkIndex++)

        audioRecorder.startRecording(chunkFile)

        handler.postDelayed({
            audioRecorder.stopRecording()
            val chunkEnd = System.currentTimeMillis() - sessionStartTime
            processChunk(chunkFile, chunkStart, chunkEnd)
            // Keep rolling unless paused/skipped/ended
            if (isActive && !isPaused && !isSongSkipped) startChunkCycle()
        }, CHUNK_DURATION_MS)
    }

    // ── Two-phase processing: transcribe first, show bubble, then translate ──

    private fun processChunk(file: File, start: Long, end: Long) {
        scope.launch {
            try {
                // ── Phase 1: transcription ──────────────────────────────────
                val originalText = withContext(Dispatchers.IO) {
                    geminiClient.transcribeAudio(file)
                }

                if (originalText.isBlank()) {
                    // Nothing spoken — skip silently
                    withContext(Dispatchers.IO) { audioChunker.safelyDeleteChunk(file) }
                    return@launch
                }

                // Show Indonesian bubble immediately, translation pending
                val entry = TranscriptEntry(
                    timestampStart = start,
                    timestampEnd   = end,
                    originalText   = originalText,
                    translatedText = "",
                    isTranslating  = true
                )
                // appendAndTranslate adds the entry, notifies, AND kicks off translation
                transcriptAdapter.appendAndTranslate(entry)
                rvTranscript.smoothScrollToPosition(SessionData.entries.size - 1)

            } catch (e: Exception) {
                e.printStackTrace()
                tvStatus.text = "⚠️ Error: ${e.message?.take(60)}"
            } finally {
                withContext(Dispatchers.IO) { audioChunker.safelyDeleteChunk(file) }
            }
        }
    }

    // ── Cleanup ──────────────────────────────────────────────────────────────

    private fun stopEverything() {
        isActive = false
        handler.removeCallbacksAndMessages(null)
        audioRecorder.stopRecording()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEverything()
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 200
        private const val CHUNK_DURATION_MS        = 8_000L   // 8 s — faster first result
    }
}
