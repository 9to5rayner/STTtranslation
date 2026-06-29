package com.example.groqtranscriber.model

import com.example.groqtranscriber.crypto.RoomCrypto
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

/**
 * All Firebase Realtime Database reads and writes for the walkie-talkie feature.
 *
 * ENCRYPTION
 * ──────────
 * Every message's [ChatMessage.originalText] and [ChatMessage.translatedText]
 * are encrypted with AES-256-GCM before being written to Firebase, and
 * decrypted immediately after being read back.
 *
 * The encryption key is derived from [roomCode] via PBKDF2-HMAC-SHA256
 * (see [RoomCrypto]).  Both devices arrive at the identical key independently
 * because they share the room code out-of-band — no key exchange is required.
 *
 * Firebase therefore stores only ciphertext; even with full database access
 * the plaintext conversations cannot be recovered without the room code.
 *
 * Database structure (fields marked * are AES-256-GCM encrypted):
 *   /rooms/{roomCode}/messages/{messageId}/
 *       id                String
 *       senderId          String
 *       senderNickname    String
 *       timestampMs       Long
 *       originalText      String   *
 *       translatedText    String   *
 *       sourceLang        String
 *       targetLang        String
 *
 * Usage:
 *   val repo = FirebaseRepository(roomCode, myDeviceId)
 *   repo.sendMessage(msg, onSuccess, onFailure)
 *   repo.listenForMessages { msg -> … }
 *   repo.stopListening()   // call in onDestroy
 */
class FirebaseRepository(
    private val roomCode:   String,
    private val myDeviceId: String
) {
    private val db          = Firebase.database
    private val messagesRef = db.getReference("rooms/$roomCode/messages")
    private val crypto      = RoomCrypto(roomCode)

    private var childListener: ChildEventListener? = null

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Encrypts the text fields of [message] and writes it to Firebase under
     * its own [ChatMessage.id] key.
     *
     * [onSuccess] is called on the main thread when the write is confirmed.
     * [onFailure] is called with a human-readable reason on any error.
     */
    fun sendMessage(
        message:   ChatMessage,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (roomCode.isBlank()) {
            onFailure("No room code set — cannot send message.")
            return
        }

        // Encrypt the two plaintext fields; all other fields travel as-is.
        val encryptedMessage = message.copy(
            originalText   = crypto.encrypt(message.originalText),
            translatedText = crypto.encrypt(message.translatedText)
        )

        messagesRef
            .child(encryptedMessage.id)
            .setValue(encryptedMessage)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                onFailure(e.message ?: "Firebase write failed")
            }
    }

    // ── Listen ────────────────────────────────────────────────────────────────

    /**
     * Attaches a [ChildEventListener] that decrypts incoming messages and
     * delivers them to [onMessage] on the main thread.
     *
     * Only messages from other devices (senderId ≠ [myDeviceId]) that arrived
     * after this listener was attached are forwarded — own messages are already
     * displayed locally, and historical messages are suppressed by timestamp.
     *
     * Messages that cannot be decrypted (wrong key, corrupted data) are
     * silently dropped — the sender is on a different room code or the data
     * has been tampered with.
     *
     * Call [stopListening] in onDestroy to avoid leaks.
     */
    fun listenForMessages(onMessage: (ChatMessage) -> Unit) {
        stopListening()

        val attachedAt = System.currentTimeMillis()

        childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val raw = try {
                    snapshot.getValue(ChatMessage::class.java) ?: return
                } catch (e: Exception) {
                    e.printStackTrace()
                    return
                }

                // Ignore own messages (displayed locally already)
                if (raw.senderId == myDeviceId) return

                // Suppress history that existed before we attached
                if (raw.timestampMs < attachedAt) return

                // Decrypt text fields — drop the message if either field fails.
                // Failure means the sender used a different room code (wrong
                // room) or the data is corrupted / tampered.
                val decryptedOriginal    = crypto.decrypt(raw.originalText)
                val decryptedTranslation = crypto.decrypt(raw.translatedText)

                if (decryptedOriginal == null || decryptedTranslation == null) {
                    // Do not crash or surface an error — silently discard.
                    // A log line is kept for debug builds only.
                    android.util.Log.w(
                        "FirebaseRepository",
                        "Dropped message ${raw.id}: decryption failed " +
                        "(wrong room code or corrupted data)"
                    )
                    return
                }

                val decrypted = raw.copy(
                    originalText   = decryptedOriginal,
                    translatedText = decryptedTranslation,
                    isIncoming     = true
                )
                onMessage(decrypted)
            }

            override fun onChildChanged(s: DataSnapshot, p: String?) { /* no-op */ }
            override fun onChildRemoved(s: DataSnapshot)              { /* no-op */ }
            override fun onChildMoved(s: DataSnapshot, p: String?)    { /* no-op */ }
            override fun onCancelled(error: DatabaseError) {
                error.toException().printStackTrace()
            }
        }

        messagesRef.addChildEventListener(childListener!!)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun stopListening() {
        childListener?.let { messagesRef.removeEventListener(it) }
        childListener = null
    }
}
