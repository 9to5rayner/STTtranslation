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
import java.util.UUID

/**
 * Room entry screen — sits between LaunchActivity and RecordingActivity.
 *
 * Two paths:
 *  - CREATE: generates a random 5-character alphanumeric room code, displays
 *    it, and waits for the user to tap "Start" once their partner has joined.
 *  - JOIN: user types in the code their partner shared, then taps "Join".
 *
 * In both cases, the room code and this device's stable UUID are written to
 * SharedPreferences before RecordingActivity is launched.
 *
 * Device identity:
 *   A UUID is generated once on first run and stored as "device_id".
 *   It never changes. The user can optionally set a nickname ("user_nickname")
 *   which is shown on their own bubbles by the remote partner.
 *
 * What this activity does NOT do:
 *   - No Firebase calls yet (Phase 3).
 *   - No validation that the room code actually exists in the database.
 *     That check will happen in RecordingActivity when the listener attaches.
 */
class RoomActivity : AppCompatActivity() {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var etNickname:    EditText
    private lateinit var tvRoomCode:    TextView
    private lateinit var btnCreate:     Button
    private lateinit var btnStartRoom:  Button
    private lateinit var etJoinCode:    EditText
    private lateinit var btnJoin:       Button

    // ── Extras passed from LaunchActivity ─────────────────────────────────────
    private lateinit var targetLang:    String
    private lateinit var apiProvider:   String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room)

        targetLang  = intent.getStringExtra("TARGET_LANG")   ?: "English"
        apiProvider = intent.getStringExtra("API_PROVIDER")  ?: "GOOGLE_GEMINI"

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
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun launchRecording(roomCode: String) {
        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)

        // Persist nickname (may be blank — that's fine)
        val nickname = etNickname.text.toString().trim()
        prefs.edit().putString("user_nickname", nickname).apply()

        // Persist room code for the session
        prefs.edit().putString("current_room", roomCode).apply()

        startActivity(
            Intent(this, RecordingActivity::class.java).apply {
                putExtra("TARGET_LANG",   targetLang)
                putExtra("API_PROVIDER",  apiProvider)
                putExtra("ROOM_CODE",     roomCode)
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
