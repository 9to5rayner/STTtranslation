package com.example.groqtranscriber.ui

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.groqtranscriber.R
import com.example.groqtranscriber.api.ApiProvider
import com.example.groqtranscriber.api.EasyVoiceClient
import com.example.groqtranscriber.api.GeminiClient
import com.example.groqtranscriber.api.TtsException
import com.example.groqtranscriber.audio.AudioChunker
import com.example.groqtranscriber.audio.AudioRecorder
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.SessionStore
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class RecordingActivity : AppCompatActivity() {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private lateinit var audioRecorder:  AudioRecorder
    private lateinit var audioChunker:   AudioChunker
    private lateinit var geminiClient:   GeminiClient
    private lateinit var targetLang:     String
    private lateinit var provider:       ApiProvider
    private var mediaPlayer:             MediaPlayer? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var isRecording       = false
    private var recordingFile:      File? = null
    private var elapsedSeconds    = 0
    private val handler           = Handler(Looper.getMainLooper())
    private var blinkAnimator:      ObjectAnimator? = null

    // ── Coroutines ────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvStatus:    TextView
    private lateinit var tvTimer:     TextView
    private lateinit var btnRecord:   Button
    private lateinit var btnExport:   Button
    private lateinit var btnClear:    Button
    private lateinit var rvFeed:      RecyclerView
    private lateinit var adapter:     BubbleAdapter

    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording)

        tvStatus  = findViewById(R.id.tvStatus)
        tvTimer   = findViewById(R.id.tvTimer)
        btnRecord = findViewById(R.id.btnRecord)
        btnExport = findViewById(R.id.btnExport)
        btnClear  = findViewById(R.id.btnClear)
        rvFeed    = findViewById(R.id.rvFeed)

        // ── Resolve API settings ──────────────────────────────────────────────
        val prefs  = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "No API key — please go back.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val providerName = intent.getStringExtra("API_PROVIDER") ?: ApiProvider.GOOGLE_GEMINI.name
        provider = try { ApiProvider.valueOf(providerName) }
                   catch (_: IllegalArgumentException) { ApiProvider.GOOGLE_GEMINI }

        // ── Resolve TTS backend (native Gemini vs. EasyVoice) ───────────────────
        // kie.ai has no Gemini TTS model, so that provider's audio generation is
        // delegated to EasyVoice using a separate key (see ApiProvider.needsSeparateTtsKey
        // and LaunchActivity's second key field).
        val easyVoiceClient: EasyVoiceClient? = if (provider.needsSeparateTtsKey) {
            val ttsKey = prefs.getString("tts_api_key", "") ?: ""
            if (ttsKey.isEmpty()) {
                Toast.makeText(
                    this,
                    "No TTS API key configured for ${provider.displayName} — please go back and add one.",
                    Toast.LENGTH_LONG
                ).show()
                finish(); return
            }
            EasyVoiceClient(ttsKey)
        } else null

        targetLang   = intent.getStringExtra("TARGET_LANG") ?: "English"
        geminiClient = GeminiClient(apiKey, provider, easyVoiceClient)
        audioRecorder = AudioRecorder(this)
        audioChunker  = AudioChunker(this)

        // ── Load persisted history ────────────────────────────────────────────
        SessionData.entries.clear()
        SessionData.entries.addAll(SessionStore.load(this))

        // ── RecyclerView ──────────────────────────────────────────────────────
        adapter = BubbleAdapter(
            context       = this,
            geminiClient  = geminiClient,
            targetLang    = targetLang,
            scope         = scope,
            onPlayRequest = { filePath -> playAudio(filePath) },
            onEntryUpdated = { index, entry ->
                SessionData.entries[index] = entry
                SessionStore.save(this, SessionData.entries)
            }
        )
        rvFeed.apply {
            adapter       = this@RecordingActivity.adapter
            layoutManager = LinearLayoutManager(this@RecordingActivity)
                .also { it.stackFromEnd = true }
        }
        if (SessionData.entries.isNotEmpty()) {
            adapter.notifyDataSetChanged()
            rvFeed.scrollToPosition(SessionData.entries.size - 1)
        }

        // ── Button wiring ─────────────────────────────────────────────────────
        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else requestPermissionAndRecord()
        }

        btnExport.setOnClickListener {
            startActivity(Intent(this, ExportActivity::class.java).apply {
                putExtra("TARGET_LANG",  targetLang)
                putExtra("API_PROVIDER", provider.name)
            })
        }

        btnClear.setOnClickListener {
            SessionData.clear()
            SessionStore.clear(this)
            adapter.notifyDataSetChanged()
            tvStatus.text = "History cleared"
        }

        setIdleUi()
    }

    // ── Recording lifecycle ───────────────────────────────────────────────────

    private fun requestPermissionAndRecord() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), RC_AUDIO
            )
        } else {
            startRecording()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_AUDIO &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) startRecording()
        else Toast.makeText(this, "Microphone permission required.", Toast.LENGTH_LONG).show()
    }

    private fun startRecording() {
        isRecording    = true
        elapsedSeconds = 0
        recordingFile  = audioChunker.createChunkFile(0)

        audioRecorder.startRecording(recordingFile!!)
        setRecordingUi()
        startTimer()
    }

    private fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        handler.removeCallbacksAndMessages(null)
        blinkAnimator?.cancel()
        audioRecorder.stopRecording()

        setProcessingUi()

        val file = recordingFile ?: return
        val now  = System.currentTimeMillis()
        processRecording(file, now - (elapsedSeconds * 1000L), now)
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private val timerRunnable = object : Runnable {
        override fun run() {
            elapsedSeconds++
            val remaining = MAX_SECONDS - elapsedSeconds
            tvTimer.text = formatTime(elapsedSeconds)

            when {
                remaining <= 0 -> {
                    // Auto-stop at 60 s
                    stopRecording()
                    return
                }
                remaining <= WARNING_SECONDS -> startBlinking()
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun startTimer() {
        tvTimer.text = formatTime(0)
        handler.postDelayed(timerRunnable, 1_000)
    }

    private fun startBlinking() {
        if (blinkAnimator?.isRunning == true) return
        blinkAnimator = ObjectAnimator.ofFloat(btnRecord, "alpha", 1f, 0.2f).apply {
            duration       = 500
            repeatCount    = ObjectAnimator.INFINITE
            repeatMode     = ObjectAnimator.REVERSE
            interpolator   = LinearInterpolator()
            start()
        }
    }

    private fun formatTime(seconds: Int): String {
        val remaining = MAX_SECONDS - seconds
        return "%d:%02d".format(remaining / 60, remaining % 60)
    }

    // ── Three-phase pipeline ──────────────────────────────────────────────────

    private fun processRecording(file: File, startMs: Long, endMs: Long) {
        scope.launch {
            // Add placeholder entry in TRANSCRIBING state
            val placeholder = TranscriptEntry(
                timestampStart  = startMs,
                timestampEnd    = endMs,
                isTranscribing  = true
            )
            SessionData.entries.add(placeholder)
            val index = SessionData.entries.size - 1
            adapter.notifyItemInserted(index)
            rvFeed.smoothScrollToPosition(index)

            // ── Phase 1: Transcription ────────────────────────────────────────
            try {
                val original = withContext(Dispatchers.IO) { geminiClient.transcribeAudio(file) }
                withContext(Dispatchers.IO) { audioChunker.safelyDeleteChunk(file) }

                if (original.isBlank()) {
                    // Silent clip — remove placeholder
                    SessionData.entries.removeAt(index)
                    adapter.notifyItemRemoved(index)
                    setIdleUi()
                    return@launch
                }

                // Show navy Indonesian bubble (translation pending)
                SessionData.entries[index] = SessionData.entries[index].copy(
                    originalText   = original,
                    isTranscribing = false,
                    isTranslating  = true
                )
                adapter.notifyItemChanged(index)
                setIdleUi()

                // ── Phase 2: Translation ──────────────────────────────────────
                val translated = try {
                    withContext(Dispatchers.IO) { geminiClient.translateText(original, targetLang) }
                } catch (e: Exception) {
                    e.printStackTrace()
                    "[Translation failed: ${e.message}]"
                }

                SessionData.entries[index] = SessionData.entries[index].copy(
                    translatedText = translated,
                    isTranslating  = false,
                    isGeneratingTts = true
                )
                adapter.notifyItemChanged(index)

                // ── Phase 3: TTS ──────────────────────────────────────────────
                // Records the specific failure reason on ttsError (instead of
                // silently leaving audioFilePath null) so the UI can show an
                // error tag + retry button (see BubbleAdapter.retryTts).
                // writeTtsBytesToWav() handles the byte-format difference
                // between native Gemini TTS (raw PCM, needs a WAV header) and
                // EasyVoice (already a complete WAV file) — callers don't need
                // to know which path produced the bytes.
                val ttsResult: Result<String> = try {
                    val ttsBytes = withContext(Dispatchers.IO) { geminiClient.generateTts(translated) }
                    val wavFile  = File(filesDir, "tts_${SessionData.entries[index].id}.wav")
                    withContext(Dispatchers.IO) { geminiClient.writeTtsBytesToWav(ttsBytes, wavFile) }
                    Result.success(wavFile.absolutePath)
                } catch (e: TtsException) {
                    e.printStackTrace()
                    Result.failure(e)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Result.failure(e)
                }

                if (index < SessionData.entries.size) {
                    SessionData.entries[index] = ttsResult.fold(
                        onSuccess = { path ->
                            SessionData.entries[index].copy(
                                audioFilePath   = path,
                                isGeneratingTts = false,
                                ttsError        = null
                            )
                        },
                        onFailure = { err ->
                            SessionData.entries[index].copy(
                                audioFilePath   = null,
                                isGeneratingTts = false,
                                ttsError        = (err.message ?: "Unknown error").take(140)
                            )
                        }
                    )
                    adapter.notifyItemChanged(index)
                    SessionStore.save(this@RecordingActivity, SessionData.entries)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                tvStatus.text = "⚠️ Error: ${e.message?.take(80)}"
                // Remove failed placeholder
                if (index < SessionData.entries.size) {
                    SessionData.entries.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
                withContext(Dispatchers.IO) { audioChunker.safelyDeleteChunk(file) }
                setIdleUi()
            }
        }
    }

    // ── Audio playback ────────────────────────────────────────────────────────

    fun playAudio(filePath: String) {
        mediaPlayer?.release()
        mediaPlayer = null
        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(filePath)
                prepare()
                start()
                setOnCompletionListener { release(); mediaPlayer = null }
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Playback error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ── UI states ─────────────────────────────────────────────────────────────

    private fun setIdleUi() {
        tvStatus.text    = "Ready · ${provider.displayName}"
        tvTimer.text     = formatTime(0)
        btnRecord.text   = "⏺  Record"
        btnRecord.alpha  = 1f
        btnRecord.setBackgroundColor(getColor(R.color.gold_primary))
        btnRecord.isEnabled = true
    }

    private fun setRecordingUi() {
        tvStatus.text  = "🔴 Recording…"
        btnRecord.text = "⏹  Stop"
        btnRecord.setBackgroundColor(getColor(R.color.recording_red))
    }

    private fun setProcessingUi() {
        tvStatus.text       = "⏳ Processing…"
        btnRecord.text      = "⏺  Record"
        btnRecord.isEnabled = false
        btnRecord.alpha     = 0.5f
        tvTimer.text        = formatTime(0)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        blinkAnimator?.cancel()
        if (isRecording) audioRecorder.stopRecording()
        mediaPlayer?.release()
    }

    companion object {
        private const val RC_AUDIO          = 200
        private const val MAX_SECONDS       = 60
        private const val WARNING_SECONDS   = 10
    }
}
