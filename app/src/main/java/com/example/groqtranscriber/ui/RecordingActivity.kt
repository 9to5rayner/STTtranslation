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
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.groqtranscriber.R
import com.example.groqtranscriber.api.KieAiClient
import com.example.groqtranscriber.api.TtsException
import com.example.groqtranscriber.audio.AudioChunker
import com.example.groqtranscriber.audio.AudioRecorder
import com.example.groqtranscriber.model.ChatMessage
import com.example.groqtranscriber.model.FirebaseRepository
import com.example.groqtranscriber.model.Language
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.SessionStore
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class RecordingActivity : AppCompatActivity() {

    // ── Dependencies ──────────────────────────────────────────────────────────
    private lateinit var audioRecorder:  AudioRecorder
    private lateinit var audioChunker:   AudioChunker
    private lateinit var kieAiClient:    KieAiClient
    private lateinit var myLanguage:     Language
    private lateinit var theirLanguage:  Language
    private var mediaPlayer:             MediaPlayer? = null

    // ── Room / identity ───────────────────────────────────────────────────────
    private lateinit var roomCode:   String
    private lateinit var deviceId:   String
    private lateinit var nickname:   String
    private var firebaseRepo:        FirebaseRepository? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var isRecording    = false
    private var recordingFile: File? = null
    private var elapsedSeconds = 0
    private val handler        = Handler(Looper.getMainLooper())
    private var blinkAnimator: ObjectAnimator? = null

    // ── Coroutines ────────────────────────────────────────────────────────────
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvStatus:  TextView
    private lateinit var tvTimer:   TextView
    private lateinit var btnRecord: Button
    private lateinit var btnExport: Button
    private lateinit var btnClear:  Button
    private lateinit var rvFeed:    RecyclerView
    private lateinit var adapter:   BubbleAdapter

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

        val prefs  = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        if (apiKey.isEmpty()) {
            Toast.makeText(this, "No API key — please go back.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        myLanguage    = Language.fromName(intent.getStringExtra("MY_LANGUAGE"))
        theirLanguage = myLanguage.other

        roomCode = intent.getStringExtra("ROOM_CODE") ?: ""
        deviceId = prefs.getString("device_id", "") ?: ""
        nickname = prefs.getString("user_nickname", "") ?: ""

        kieAiClient   = KieAiClient(apiKey)
        audioRecorder = AudioRecorder(this)
        audioChunker  = AudioChunker(this)

        if (roomCode.isNotEmpty() && deviceId.isNotEmpty()) {
            firebaseRepo = FirebaseRepository(roomCode, deviceId)
            attachFirebaseListener()
        }

        SessionData.entries.clear()
        SessionData.entries.addAll(SessionStore.load(this))

        adapter = BubbleAdapter(
            context        = this,
            kieAiClient    = kieAiClient,
            myLanguage     = myLanguage,
            theirLanguage  = theirLanguage,
            scope          = scope,
            onPlayRequest  = { filePath -> playAudio(filePath) },
            onEntryUpdated = { index, entry ->
                if (index < SessionData.entries.size) {
                    SessionData.entries[index] = entry
                    SessionStore.save(this, SessionData.entries)
                }
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

        btnRecord.setOnClickListener {
            if (isRecording) stopRecording() else requestPermissionAndRecord()
        }
        btnExport.setOnClickListener {
            startActivity(Intent(this, ExportActivity::class.java).apply {
                putExtra("TARGET_LANG", theirLanguage.displayName)
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

    // ── Firebase listener ─────────────────────────────────────────────────────

    private fun attachFirebaseListener() {
        firebaseRepo?.listenForMessages { chatMessage ->
            receiveIncomingMessage(chatMessage)
        }
    }

    private fun receiveIncomingMessage(msg: ChatMessage) {
        val entry = TranscriptEntry(
            id              = System.currentTimeMillis(),
            timestampStart  = msg.timestampMs,
            timestampEnd    = msg.timestampMs,
            originalText    = msg.originalText,
            translatedText  = msg.translatedText,
            senderNickname  = msg.senderNickname,
            isIncoming      = true,
            isGeneratingTts = msg.translatedText.isNotEmpty()
        )
        SessionData.entries.add(entry)
        val index = SessionData.entries.size - 1
        adapter.notifyItemInserted(index)
        rvFeed.smoothScrollToPosition(index)
        SessionStore.save(this, SessionData.entries)

        if (msg.translatedText.isNotEmpty()) {
            scope.launch { runTtsPhase(index) }
        }
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
                remaining <= 0 -> { stopRecording(); return }
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
            duration     = 500
            repeatCount  = ObjectAnimator.INFINITE
            repeatMode   = ObjectAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun formatTime(seconds: Int): String {
        val remaining = MAX_SECONDS - seconds
        return "%d:%02d".format(remaining / 60, remaining % 60)
    }

    // ── Pipeline ──────────────────────────────────────────────────────────────
    //
    // Phase 1 → Transcription
    // Phase 1.5 → Confirm / Edit dialog  ← NEW
    // Phase 2 → Translation
    // Phase 3 → TTS
    // Phase 4 → Firebase send

    private fun processRecording(file: File, startMs: Long, endMs: Long) {
        scope.launch {

            // ── Insert placeholder ────────────────────────────────────────────
            val placeholder = TranscriptEntry(
                timestampStart = startMs,
                timestampEnd   = endMs,
                senderNickname = nickname,
                isTranscribing = true
            )
            SessionData.entries.add(placeholder)
            val index = SessionData.entries.size - 1
            adapter.notifyItemInserted(index)
            rvFeed.smoothScrollToPosition(index)

            // ═══════════════════════════════════════════════════════════════════
            // PHASE 1 — TRANSCRIPTION
            // ═══════════════════════════════════════════════════════════════════
            val rawTranscription: String? = runWithRetry(
                tag        = "Transcription",
                maxRetries = MAX_RETRIES
            ) {
                withContext(Dispatchers.IO) { kieAiClient.transcribeAudio(file, myLanguage) }
            }

            withContext(Dispatchers.IO) { audioChunker.safelyDeleteChunk(file) }

            if (rawTranscription.isNullOrBlank()) {
                if (index < SessionData.entries.size) {
                    SessionData.entries.removeAt(index)
                    adapter.notifyItemRemoved(index)
                }
                setIdleUi()
                return@launch
            }

            // Show transcription, mark as awaiting confirmation
            if (index >= SessionData.entries.size) return@launch
            SessionData.entries[index] = SessionData.entries[index].copy(
                originalText    = rawTranscription,
                isTranscribing  = false,
                isAwaitingConfirmation = true
            )
            adapter.notifyItemChanged(index)
            setIdleUi()

            // ═══════════════════════════════════════════════════════════════════
            // PHASE 1.5 — CONFIRM / EDIT DIALOG
            // ═══════════════════════════════════════════════════════════════════
            val confirmedText: String? = suspendCancellableCoroutine { cont ->
                val editText = android.widget.EditText(this@RecordingActivity).apply {
                    setText(rawTranscription)
                    setSelection(rawTranscription.length)
                    setPadding(48, 24, 48, 24)
                    minLines = 2
                    textSize = 15f
                }

                AlertDialog.Builder(this@RecordingActivity)
                    .setTitle("Confirm Transcription")
                    .setMessage("Review or edit before translating (${myLanguage.flag} ${myLanguage.displayName}):")
                    .setView(editText)
                    .setCancelable(false)
                    .setPositiveButton("Translate ✓") { _, _ ->
                        val text = editText.text.toString().trim()
                        cont.resume(if (text.isEmpty()) null else text)
                    }
                    .setNegativeButton("Discard ✗") { _, _ ->
                        cont.resume(null)
                    }
                    .show()
            }

            if (index >= SessionData.entries.size) return@launch

            // User discarded — remove the bubble
            if (confirmedText == null) {
                SessionData.entries.removeAt(index)
                adapter.notifyItemRemoved(index)
                SessionStore.save(this@RecordingActivity, SessionData.entries)
                return@launch
            }

            // Update with confirmed (possibly edited) text, mark translating
            val wasEdited = confirmedText != rawTranscription
            SessionData.entries[index] = SessionData.entries[index].copy(
                originalText           = confirmedText,
                isAwaitingConfirmation = false,
                isEdited               = wasEdited,
                isTranslating          = true
            )
            adapter.notifyItemChanged(index)
            SessionStore.save(this@RecordingActivity, SessionData.entries)

            // ═══════════════════════════════════════════════════════════════════
            // PHASE 2 — TRANSLATION
            // ═══════════════════════════════════════════════════════════════════
            val translated: String? = runWithRetry(
                tag        = "Translation",
                maxRetries = MAX_RETRIES
            ) {
                withContext(Dispatchers.IO) {
                    kieAiClient.translateText(confirmedText, myLanguage, theirLanguage)
                }
            }

            if (index >= SessionData.entries.size) return@launch

            if (translated.isNullOrBlank()) {
                SessionData.entries[index] = SessionData.entries[index].copy(
                    isTranslating    = false,
                    translationError = "Translation failed after $MAX_RETRIES attempts. Tap ↻ to retry."
                )
                adapter.notifyItemChanged(index)
                SessionStore.save(this@RecordingActivity, SessionData.entries)
                return@launch
            }

            SessionData.entries[index] = SessionData.entries[index].copy(
                translatedText   = translated,
                isTranslating    = false,
                translationError = null,
                isGeneratingTts  = true
            )
            adapter.notifyItemChanged(index)

            // ═══════════════════════════════════════════════════════════════════
            // PHASE 3 — TTS
            // ═══════════════════════════════════════════════════════════════════
            runTtsPhase(index)

            // ═══════════════════════════════════════════════════════════════════
            // PHASE 4 — FIREBASE SEND
            // ═══════════════════════════════════════════════════════════════════
            if (index >= SessionData.entries.size) return@launch
            val finalEntry = SessionData.entries[index]

            if (finalEntry.originalText.isBlank() || finalEntry.translatedText.isBlank()) {
                return@launch
            }

            sendToFirebase(index, finalEntry, startMs)
        }
    }

    // ── Shared TTS phase ──────────────────────────────────────────────────────

    private suspend fun runTtsPhase(index: Int) {
        if (index >= SessionData.entries.size) return
        val entry = SessionData.entries[index]

        if (entry.translatedText.isBlank() || entry.translationError != null) {
            if (index < SessionData.entries.size) {
                SessionData.entries[index] = entry.copy(isGeneratingTts = false)
                adapter.notifyItemChanged(index)
            }
            return
        }

        val result: Result<String> = try {
            val ttsBytes  = withContext(Dispatchers.IO) { kieAiClient.generateTts(entry.translatedText) }
            val ext       = kieAiClient.ttsFileExtension()
            val audioFile = File(filesDir, "tts_${entry.id}.$ext")
            withContext(Dispatchers.IO) { kieAiClient.writeTtsBytesToFile(ttsBytes, audioFile) }
            Result.success(audioFile.absolutePath)
        } catch (e: TtsException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (index >= SessionData.entries.size) return

        SessionData.entries[index] = result.fold(
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
                    ttsError        = (err.message ?: "Unknown TTS error").take(140)
                )
            }
        )
        adapter.notifyItemChanged(index)
        SessionStore.save(this, SessionData.entries)
    }

    // ── Firebase send ─────────────────────────────────────────────────────────

    private fun sendToFirebase(index: Int, entry: TranscriptEntry, timestampMs: Long) {
        val repo = firebaseRepo ?: return

        if (index < SessionData.entries.size) {
            SessionData.entries[index] = entry.copy(isSendingToFirebase = true)
            adapter.notifyItemChanged(index)
        }

        val message = ChatMessage(
            id             = UUID.randomUUID().toString(),
            senderId       = deviceId,
            senderNickname = nickname,
            timestampMs    = timestampMs,
            originalText   = entry.originalText,
            translatedText = entry.translatedText,
            sourceLang     = myLanguage.name,
            targetLang     = theirLanguage.name
        )

        repo.sendMessage(
            message   = message,
            onSuccess = {
                if (index < SessionData.entries.size) {
                    SessionData.entries[index] = SessionData.entries[index].copy(
                        isSendingToFirebase = false,
                        isSentToFirebase    = true
                    )
                    adapter.notifyItemChanged(index)
                    SessionStore.save(this, SessionData.entries)
                }
            },
            onFailure = { errorMsg ->
                if (index < SessionData.entries.size) {
                    SessionData.entries[index] = SessionData.entries[index].copy(
                        isSendingToFirebase = false,
                        isSentToFirebase    = false,
                        sendError           = errorMsg.take(120)
                    )
                    adapter.notifyItemChanged(index)
                }
                Toast.makeText(
                    this,
                    "Failed to send message: $errorMsg",
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    // ── Retry helper ──────────────────────────────────────────────────────────

    private suspend fun <T> runWithRetry(
        tag: String,
        maxRetries: Int,
        block: suspend () -> T
    ): T? {
        var lastError: Exception? = null
        var delayMs = RETRY_DELAY_MS

        repeat(maxRetries + 1) { attempt ->
            try {
                val result = block()
                if (result is String && result.isBlank()) {
                    throw Exception("$tag returned blank result")
                }
                return result
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    delay(delayMs)
                    delayMs *= 2
                }
            }
        }
        lastError?.printStackTrace()
        return null
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
        tvStatus.text   = if (roomCode.isNotEmpty())
            "Room: $roomCode · ${myLanguage.flag} → ${theirLanguage.flag}"
        else
            "Ready · ${myLanguage.flag} → ${theirLanguage.flag}"
        tvTimer.text    = formatTime(0)
        btnRecord.text  = "⏺  Record"
        btnRecord.alpha = 1f
        btnRecord.setBackgroundColor(getColor(R.color.gold_primary))
        btnRecord.isEnabled = true
    }

    private fun setRecordingUi() {
        tvStatus.text  = "🔴 Recording…"
        btnRecord.text = "⏹  Stop"
        btnRecord.setBackgroundColor(getColor(R.color.recording_red))
    }

    private fun setProcessingUi() {
        tvStatus.text       = "⏳ Transcribing…"
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
        firebaseRepo?.stopListening()
    }

    companion object {
        private const val RC_AUDIO          = 200
        private const val MAX_SECONDS       = 60
        private const val WARNING_SECONDS   = 10
        private const val MAX_RETRIES       = 2
        private const val RETRY_DELAY_MS    = 2_000L
    }
}
