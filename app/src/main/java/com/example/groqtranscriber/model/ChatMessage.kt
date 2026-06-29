package com.example.groqtranscriber.model

import com.google.firebase.database.Exclude

/**
 * Represents a fully-resolved message in the walkie-talkie conversation —
 * either sent by this device or received from the remote partner.
 *
 * No Firebase Storage is used. TTS audio is generated locally on each device
 * from the translatedText — the sender generates it for themselves, and the
 * receiver generates it independently using their own API key and provider.
 * This means only text fields travel over the network.
 *
 * Firebase-persisted fields (written to Realtime Database):
 *   id, senderId, senderNickname, timestampMs, originalText,
 *   translatedText, targetLang
 *
 * Local-only fields (annotated @get:Exclude, never written to Firebase):
 *   isIncoming, isPending, isSentToFirebase, localAudioPath,
 *   ttsError, isGeneratingTts
 *
 * Serialization requirement:
 *   All fields must have default values so Firebase can deserialize using
 *   its no-arg constructor reflection path.
 */
data class ChatMessage(

    // ── Persisted to Firebase ─────────────────────────────────────────────────

    /** Stable unique ID — UUID. Used as the explicit Firebase child key. */
    val id: String = "",

    /** device_id of the sender (UUID in SharedPreferences). Used to filter
     *  incoming vs. outgoing on each device. */
    val senderId: String = "",

    /** Optional display name set by the user in RoomActivity. */
    val senderNickname: String = "",

    val timestampMs: Long = 0L,

    /** Raw Indonesian transcription. */
    val originalText: String = "",

    /** Translated text. The receiver uses this to generate TTS locally. */
    val translatedText: String = "",

    /** "English" or "Japanese" — lets the receiver know what was translated. */
    val targetLang: String = "English",

    // ── Local-only UI state (NOT written to Firebase) ─────────────────────────

    /** True when received from the remote partner; false for own messages. */
    @get:Exclude val isIncoming: Boolean = false,

    /** True while waiting for the Firebase write to complete. */
    @get:Exclude val isPending: Boolean = false,

    /** True once the message has been confirmed written to Firebase. */
    @get:Exclude val isSentToFirebase: Boolean = false,

    /** Absolute path to the locally generated TTS audio file. */
    @get:Exclude val localAudioPath: String? = null,

    /** True while TTS audio is being generated (sender or receiver). */
    @get:Exclude val isGeneratingTts: Boolean = false,

    /** Non-null when TTS generation failed. Shows ⚠️ + ↻ retry on the bubble. */
    @get:Exclude val ttsError: String? = null
)
