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
import java.util.UUID

/**
 * Room entry screen — sits between LaunchActivity and RecordingActivity.
 *
 * Two paths:
 *  - CREATE: generates a random 5-character alphanumeric room code, displays
 *    it, and waits for the user to tap "Start" once their partner has joined.
 *  - JOIN: user types in the code their partner shared, then taps "Join".
 *
 * A nickname is now REQUIRED — it's shown on every chat bubble (own messages
 * on the left under your name, partner's on the right under theirs), so an
 * empty name would make the chat impossible to follow.
 *
 * In both cases, the room code and this device's stable UUID are written to
 * SharedPreferences before RecordingActivity is launched.
 *
 * Device identity:
 *   A UUID is generated once on first run and stored as "device_id".
 *   It never changes. The nickname ("user_nickname") is required from here on.
 */
class RoomActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var etNickname:    EditText
    private lateinit var tvRoomCode:    TextView
    private lateinit var btnCreate:     Button
    private lateinit var btnStartRoom:  Button
    private lateinit var etJoinCode:    EditText
    private lateinit var btnJoin:       Button

    // ── Extra passed from LaunchActivity ────────────────────────────────────
    private lateinit var myLanguage:    String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room)

        myLanguage = intent.getStringExtra("MY_LANGUAGE") ?: Language.INDONESIAN.name

        etNickname   = findViewById(R.id.etNickname)
        tvRoomCode   = findViewById(R.id.tvRoomCode)
        btnCreate    = findViewById(R.id.btnCreate)
        btnStartRoom = findViewById(R.id.btnStartRoom)
        etJoinCode   = findViewById(R.id.etJoinCode)
        btnJoin      = findViewById(R.id.btnJoin)

        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)

        // ── Ensure this device has a stable UUID ───────────────────────────────
        if (!prefs.contains("device_id")) {
            prefs.edit().putString("device_id", UUID.randomUUID().toString()).apply()
        }

        // ── Restore nickname if previously set ─────────────────────────────────
        etNickname.setText(prefs.getString("user_nickname", ""))

        // ── CREATE path ────────────────────────────────────────────────────────

        btnCreate.setOnClickListener {
            if (!requireNickname()) return@setOnClickListener
            val code = generateRoomCode()
            tvRoomCode.text = code
            btnStartRoom.isEnabled = true
            // Copy to clipboard as a convenience
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Room Code", code)
            )
            Toast.makeText(this, "Code copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnStartRoom.setOnClickListener {
            if (!requireNickname()) return@setOnClickListener
            val code = tvRoomCode.text.toString().trim()
            if (code.length != ROOM_CODE_LENGTH) {
                Toast.makeText(this, "Tap 'Create Room' first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            launchRecording(code)
        }

        // ── JOIN path ──────────────────────────────────────────────────────────

        btnJoin.setOnClickListener {
            if (!requireNickname()) return@setOnClickListener
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
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns true if a non-blank nickname is present; otherwise shows a toast and returns false. */
    private fun requireNickname(): Boolean {
        val name = etNickname.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter your name first.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun launchRecording(roomCode: String) {
        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)

        // Persist nickname (now guaranteed non-blank by requireNickname())
        val nickname = etNickname.text.toString().trim()
        prefs.edit().putString("user_nickname", nickname).apply()

        // Persist room code for the session
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
