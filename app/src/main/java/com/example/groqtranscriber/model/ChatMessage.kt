package com.example.groqtranscriber.model

import com.google.firebase.database.Exclude

/**
 * Represents a fully-resolved message in the walkie-talkie conversation —
 * either sent by this device or received from the remote partner.
 *
 * Lifecycle of an OUTGOING message:
 *   1. Created locally with isPending = true after the 3-phase pipeline
 *      (transcribe → translate → TTS) completes in RecordingActivity.
 *   2. The TTS audio file is uploaded to Firebase Storage → ttsAudioUrl set.
 *   3. The message is written to the Firebase Realtime Database.
 *   4. isPending = false, isSent = true.
 *
 * Lifecycle of an INCOMING message:
 *   1. Firebase ChildEventListener fires → ChatMessage deserialized from DB.
 *   2. isIncoming = true, ttsAudioUrl holds a remote URL.
 *   3. The audio is downloaded to local cache → localAudioPath set.
 *   4. Play button enabled; optionally auto-played.
 *
 * Firebase serialization notes:
 *   - All fields must have default values so Gson / Firebase can deserialize
 *     with a no-arg constructor.
 *   - Transient UI fields (isPending, isDownloadingAudio) are NOT written to
 *     Firebase — they are local-only state managed at runtime.
 *   - ttsAudioUrl IS written to Firebase (it's the remote Storage URL).
 *   - localAudioPath is NOT written to Firebase (device-local cache path).
 */
data class ChatMessage(
    // ── Identity ──────────────────────────────────────────────────────────────
    /** Stable unique ID — UUID generated at creation time. Used as the Firebase key. */
    val id: String = "",

    /** Device ID of the sender (UUID stored in SharedPreferences as "device_id"). */
    val senderId: String = "",

    /** Optional display name chosen by the user (stored as "user_nickname"). */
    val senderNickname: String = "",

    // ── Timing ────────────────────────────────────────────────────────────────
    val timestampMs: Long = 0L,

    // ── Content ───────────────────────────────────────────────────────────────
    /** Raw transcription in Indonesian. */
    val originalText: String = "",

    /** Translated text in targetLang. */
    val translatedText: String = "",

    /** The target language used for translation ("English" or "Japanese"). */
    val targetLang: String = "English",

    // ── Audio ─────────────────────────────────────────────────────────────────
    /**
     * Firebase Storage download URL for the TTS audio file.
     * Null until the sender's upload completes, or if TTS generation failed.
     * Written to the database so the receiver can download the file.
     */
    val ttsAudioUrl: String? = null,

    // ── Local-only state (NOT persisted to Firebase) ──────────────────────────

    /**
     * True for messages sent by this device, false for received messages.
     * Determines bubble alignment and color in the UI.
     * Set at runtime by comparing senderId to the local device_id.
     */
    @get:Exclude val isIncoming: Boolean = false,

    /**
     * True while the message is still being written to Firebase.
     * Shows a "Sending…" indicator on the outgoing bubble.
     */
    @get:Exclude val isPending: Boolean = false,

    /**
     * True while downloading the remote TTS audio to local cache.
     * Shows a loading indicator on the incoming bubble's play button.
     */
    @get:Exclude val isDownloadingAudio: Boolean = false,

    /**
     * Local file path of the downloaded/generated TTS audio.
     * Set after either:
     *   - Sender: GeminiClient writes to filesDir (same as before)
     *   - Receiver: audio downloaded from ttsAudioUrl to cacheDir
     */
    @get:Exclude val localAudioPath: String? = null,

    /**
     * Non-null when TTS generation or audio download failed.
     * Drives the ⚠️ error row + ↻ retry button on the bubble.
     */
    @get:Exclude val ttsError: String? = null
)
