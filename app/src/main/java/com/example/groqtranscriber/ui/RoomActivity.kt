package com.example.groqtranscriber.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groqtranscriber.R
import com.example.groqtranscriber.model.Language
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.firebase.auth.FirebaseAuth

/**
 * Room entry screen — sits between LaunchActivity and RecordingActivity.
 *
 * Two paths:
 *  - CREATE: generates a random 5-character alphanumeric room code, displays
 *    it, and waits for the user to tap "Start" once their partner has joined.
 *  - JOIN: user types in the code their partner shared, then taps "Join".
 *
 * IDENTITY (post Firebase-Auth migration):
 *   The nickname shown on chat bubbles is no longer typed here — it comes
 *   from FirebaseAuth.currentUser.displayName, set once at registration in
 *   AuthActivity. This screen only displays it for confirmation.
 *
 *   Likewise, the per-device random UUID ("device_id") has been retired.
 *   FirebaseAuth.currentUser.uid is now the single source of identity used
 *   everywhere a sender needs to be distinguished (ChatMessage.senderId,
 *   FirebaseRepository's myDeviceId parameter).
 *
 * A signed-in, verified user is guaranteed by the time this Activity is
 * reached — AuthActivity gates everything before LaunchActivity, which is
 * the only screen that can lead here.
 */
class RoomActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var tvNicknameDisplay: TextView
    private lateinit var tvRoomCode:        TextView
    private lateinit var btnCreate:         Button
    private lateinit var btnStartRoom:      Button
    private lateinit var etJoinCode:        EditText
    private lateinit var btnJoin:           Button
    private lateinit var btnSignOut:        TextView

    // ── Extra passed from LaunchActivity ────────────────────────────────────
    private lateinit var myLanguage: String

    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room)

        auth = FirebaseAuth.getInstance()

        // Safety net: if somehow reached without a signed-in user, bounce back
        // to the auth gate rather than crashing later on a null uid.
        val user = auth.currentUser
        if (user == null) {
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
            return
        }

        myLanguage = intent.getStringExtra("MY_LANGUAGE") ?: Language.INDONESIAN.name

        tvNicknameDisplay = findViewById(R.id.tvNicknameDisplay)
        tvRoomCode        = findViewById(R.id.tvRoomCode)
        btnCreate         = findViewById(R.id.btnCreate)
        btnStartRoom      = findViewById(R.id.btnStartRoom)
        etJoinCode        = findViewById(R.id.etJoinCode)
        btnJoin           = findViewById(R.id.btnJoin)
        btnSignOut        = findViewById(R.id.btnSignOut)

        // Display name comes straight from the auth profile now.
        val displayName = user.displayName?.takeIf { it.isNotBlank() } ?: "User"
        tvNicknameDisplay.text = displayName

        // ── CREATE path ────────────────────────────────────────────────────────

        btnCreate.setOnClickListener {
            val code = generateRoomCode()
            tvRoomCode.text = code
            btnStartRoom.isEnabled = true
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Room Code", code)
            )
            Toast.makeText(this, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnStartRoom.setOnClickListener {
            val code = tvRoomCode.text.toString().trim()
            if (code.length != ROOM_CODE_LENGTH) {
                Toast.makeText(this, "Tap 'Create Room' first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            launchRecording(code)
        }

        // ── JOIN path ──────────────────────────────────────────────────────────

        btnJoin.setOnClickListener {
            val raw = etJoinCode.text.toString().trim().uppercase()
            if (raw.length != ROOM_CODE_LENGTH) {
                Toast.makeText(
                    this,
                    "Room code must be $ROOM_CODE_LENGTH characters.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            launchRecording(raw)
        }

        // ── Sign out ───────────────────────────────────────────────────────────

        btnSignOut.setOnClickListener {
            auth.signOut()
            // Also clear the Google session — otherwise GoogleSignInClient
            // silently re-authenticates the same account on next launch
            // instead of showing the account picker (Google caches the
            // last-used account on-device, independent of FirebaseAuth state).
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .build()
            GoogleSignIn.getClient(this, gso).signOut()
            startActivity(Intent(this, AuthActivity::class.java))
            finish()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun launchRecording(roomCode: String) {
        // Persist only the room code locally — identity now lives in
        // FirebaseAuth, not SharedPreferences.
        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("current_room", roomCode).apply()

        startActivity(
            Intent(this, RecordingActivity::class.java).apply {
                putExtra("MY_LANGUAGE", myLanguage)
                putExtra("ROOM_CODE",   roomCode)
            }
        )
    }

    /**
     * Generates a 5-character alphanumeric room code using only unambiguous
     * characters (no 0/O, 1/I/L pairs that are hard to read aloud).
     */
    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..ROOM_CODE_LENGTH)
            .map { chars.random() }
            .joinToString("")
    }

    companion object {
        private const val ROOM_CODE_LENGTH = 5
    }
}
