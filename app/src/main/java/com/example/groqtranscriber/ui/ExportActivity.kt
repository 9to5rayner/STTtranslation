package com.example.groqtranscriber.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.groqtranscriber.R
import com.example.groqtranscriber.api.GeminiClient
import com.example.groqtranscriber.export.ExportHelper
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ExportActivity : AppCompatActivity() {

    private lateinit var targetLang: String
    private lateinit var geminiClient: GeminiClient
    private lateinit var llEntriesContainer: LinearLayout

    // SupervisorJob so one failed re-translate doesn't kill others
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        targetLang = intent.getStringExtra("TARGET_LANG") ?: "English"

        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""
        geminiClient = GeminiClient(apiKey)

        llEntriesContainer = findViewById(R.id.llEntriesContainer)

        val btnTxt = findViewById<Button>(R.id.btnShareTxt)
        val btnSrt = findViewById<Button>(R.id.btnShareSrt)
        val btnCopy = findViewById<Button>(R.id.btnCopy)

        renderEntries()

        btnTxt.setOnClickListener {
            if (SessionData.entries.isEmpty()) {
                Toast.makeText(this, "No entries to export.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val content = ExportHelper.buildTxt(SessionData.entries, targetLang)
            shareFile(ExportHelper.createTempFile(this, content, "session.txt"), "text/plain")
        }

        btnSrt.setOnClickListener {
            if (SessionData.entries.isEmpty()) {
                Toast.makeText(this, "No entries to export.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val content = ExportHelper.buildSrt(SessionData.entries)
            shareFile(ExportHelper.createTempFile(this, content, "subtitles.srt"), "application/x-subrip")
        }

        btnCopy.setOnClickListener {
            if (SessionData.entries.isEmpty()) {
                Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val content = ExportHelper.buildTxt(SessionData.entries, targetLang)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", content))
            Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Inflate a card view for every entry and attach a tap-to-edit listener.
     * Called on start and after any entry is updated.
     */
    private fun renderEntries() {
        llEntriesContainer.removeAllViews()

        if (SessionData.entries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No transcription captured."
                setPadding(16, 24, 16, 24)
            }
            llEntriesContainer.addView(empty)
            return
        }

        SessionData.entries.forEachIndexed { index, entry ->
            val cardView = LayoutInflater.from(this)
                .inflate(R.layout.item_transcript_entry, llEntriesContainer, false)

            val tvTimestamp = cardView.findViewById<TextView>(R.id.tvTimestamp)
            val tvOriginal = cardView.findViewById<TextView>(R.id.tvOriginal)
            val tvTranslation = cardView.findViewById<TextView>(R.id.tvTranslation)
            val tvRetranslating = cardView.findViewById<TextView>(R.id.tvRetranslating)

            tvTimestamp.text = formatTimestampRange(entry.timestampStart, entry.timestampEnd)
            tvOriginal.text = "🇮🇩 ${entry.originalText}"
            tvTranslation.text = "🌐 ${entry.translatedText}"

            // Tap to open edit dialog for this entry
            cardView.setOnClickListener {
                showEditDialog(index, entry, tvOriginal, tvTranslation, tvRetranslating)
            }

            llEntriesContainer.addView(cardView)
        }
    }

    /**
     * Shows a dialog pre-filled with the original Indonesian transcription.
     * On confirm, updates the entry and fires a re-translation API call.
     */
    private fun showEditDialog(
        index: Int,
        entry: TranscriptEntry,
        tvOriginal: TextView,
        tvTranslation: TextView,
        tvRetranslating: TextView
    ) {
        val editText = EditText(this).apply {
            setText(entry.originalText)
            setSelection(entry.originalText.length)  // cursor at end
            setPadding(32, 16, 32, 16)
            minLines = 2
        }

        AlertDialog.Builder(this)
            .setTitle("Edit Transcription")
            .setMessage("Correct any transcription errors below, then tap Re-Translate.")
            .setView(editText)
            .setPositiveButton("Re-Translate") { _, _ ->
                val correctedText = editText.text.toString().trim()
                if (correctedText.isEmpty()) {
                    Toast.makeText(this, "Transcription cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                retranslateEntry(index, correctedText, tvOriginal, tvTranslation, tvRetranslating)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Updates the original text locally, shows a spinner label, then calls Gemini
     * to re-translate. On success, updates SessionData and the card UI in-place.
     */
    private fun retranslateEntry(
        index: Int,
        correctedOriginal: String,
        tvOriginal: TextView,
        tvTranslation: TextView,
        tvRetranslating: TextView
    ) {
        val currentEntry = SessionData.entries[index]

        // Optimistic UI: show corrected original immediately
        tvOriginal.text = "🇮🇩 $correctedOriginal"
        tvTranslation.visibility = View.GONE
        tvRetranslating.visibility = View.VISIBLE

        scope.launch {
            try {
                // We only need the translation this time, not STT — send as plain text prompt
                val newTranslation = withContext(Dispatchers.IO) {
                    geminiClient.translateText(correctedOriginal, targetLang)
                }

                // Commit the corrected entry back to SessionData
                SessionData.entries[index] = TranscriptEntry(
                    timestampStart = currentEntry.timestampStart,
                    timestampEnd = currentEntry.timestampEnd,
                    originalText = correctedOriginal,
                    translatedText = newTranslation
                )

                // Update the card in-place (already on Main)
                tvTranslation.text = "🌐 $newTranslation"
                tvTranslation.visibility = View.VISIBLE
                tvRetranslating.visibility = View.GONE

            } catch (e: Exception) {
                e.printStackTrace()
                tvTranslation.text = "🌐 [Translation failed: ${e.message}]"
                tvTranslation.visibility = View.VISIBLE
                tvRetranslating.visibility = View.GONE
                Toast.makeText(this@ExportActivity, "Re-translation failed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share Session via"))
    }

    private fun formatTimestampRange(startMs: Long, endMs: Long): String {
        fun fmt(ms: Long): String {
            val m = ms / 60000
            val s = (ms % 60000) / 1000
            return "%d:%02d".format(m, s)
        }
        return "${fmt(startMs)} → ${fmt(endMs)}"
    }
}
