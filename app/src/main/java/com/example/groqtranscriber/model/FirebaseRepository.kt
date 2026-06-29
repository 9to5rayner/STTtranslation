package com.example.groqtranscriber.model

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase

/**
 * All Firebase Realtime Database reads and writes for the walkie-talkie feature.
 *
 * Database structure:
 *   /rooms/{roomCode}/messages/{messageId}/
 *       id:               String   (same as the key)
 *       senderId:         String
 *       senderNickname:   String
 *       timestampMs:      Long
 *       originalText:     String
 *       translatedText:   String
 *       targetLang:       String
 *
 * No Firebase Storage is used. TTS audio is generated locally on each device.
 *
 * Usage:
 *   val repo = FirebaseRepository(roomCode, myDeviceId)
 *   repo.sendMessage(msg)                      // write one message
 *   repo.listenForMessages { msg -> ... }      // start receiving
 *   repo.stopListening()                       // call in onDestroy
 */
class FirebaseRepository(
    private val roomCode: String,
    private val myDeviceId: String
) {
    private val db = Firebase.database
    private val messagesRef = db.getReference("rooms/$roomCode/messages")

    private var childListener: ChildEventListener? = null

    // ── Send ──────────────────────────────────────────────────────────────────

    /**
     * Writes [message] to Firebase under its own [ChatMessage.id] key.
     * Calls [onSuccess] on the main thread when confirmed, [onFailure] on error.
     *
     * Only the Firebase-safe fields are written (local-only @get:Exclude fields
     * are automatically skipped by Firebase's serializer).
     */
    fun sendMessage(
        message: ChatMessage,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (roomCode.isBlank()) {
            onFailure("No room code set — cannot send message.")
            return
        }

        messagesRef
            .child(message.id)
            .setValue(message)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { e ->
                onFailure(e.message ?: "Firebase write failed")
            }
    }

    // ── Listen ────────────────────────────────────────────────────────────────

    /**
     * Attaches a [ChildEventListener] to the room's messages node.
     * [onMessage] is called on the main thread for every NEW child added
     * whose senderId differs from [myDeviceId] (i.e. incoming only).
     *
     * Already-existing messages at listener attach time also fire onChildAdded,
     * which would flood the UI with history on every app open. To avoid this,
     * we record the attach timestamp and ignore any message whose timestampMs
     * is earlier than that moment.
     *
     * Call [stopListening] in onDestroy to avoid leaks.
     */
    fun listenForMessages(onMessage: (ChatMessage) -> Unit) {
        stopListening() // detach any previous listener first

        val attachedAt = System.currentTimeMillis()

        childListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val msg = try {
                    snapshot.getValue(ChatMessage::class.java) ?: return
                } catch (e: Exception) {
                    e.printStackTrace()
                    return
                }

                // Ignore own messages (we already display them locally)
                if (msg.senderId == myDeviceId) return

                // Ignore historical messages that existed before we attached
                if (msg.timestampMs < attachedAt) return

                onMessage(msg.copy(isIncoming = true))
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) { /* no-op */ }
            override fun onChildRemoved(snapshot: DataSnapshot) { /* no-op */ }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) { /* no-op */ }
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
