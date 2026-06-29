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
import com.example.groqtranscriber.api.KieAiClient
import com.example.groqtranscriber.export.ExportHelper
import com.example.groqtranscriber.model.Language
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ExportActivity : AppCompatActivity() {

    private lateinit var myLanguage:    Language
    private lateinit var theirLanguage: Language
    private lateinit var kieAiClient:   KieAiClient
    private lateinit var llEntriesContainer: LinearLayout

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        val prefs  = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("api_key", "") ?: ""

        myLanguage    = Language.fromName(prefs.getString("my_language", Language.INDONESIAN.name))
        theirLanguage = myLanguage.other

        kieAiClient        = KieAiClient(apiKey)
        llEntriesContainer = findViewById(R.id.llEntriesContainer)

        val btnTxt  = findViewById<Button>(R.id.btnShareTxt)
        val btnSrt  = findViewById<Button>(R.id.btnShareSrt)
        val btnCopy = findViewById<Button>(R.id.btnCopy)

        renderEntries()

        btnTxt.setOnClickListener {
            if (SessionData.entries.isEmpty()) { toast("No entries to export."); return@setOnClickListener }
            val content = ExportHelper.buildTxt(SessionData.entries, theirLanguage.displayName)
            shareFile(ExportHelper.createTempFile(this, content, "session.txt"), "text/plain")
        }

        btnSrt.setOnClickListener {
            if (SessionData.entries.isEmpty()) { toast("No entries to export."); return@setOnClickListener }
            val content = ExportHelper.buildSrt(SessionData.entries)
            shareFile(ExportHelper.createTempFile(this, content, "subtitles.srt"), "application/x-subrip")
        }

        btnCopy.setOnClickListener {
            if (SessionData.entries.isEmpty()) { toast("Nothing to copy."); return@setOnClickListener }
            val content   = ExportHelper.buildTxt(SessionData.entries, theirLanguage.displayName)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", content))
            toast("Copied to clipboard!")
        }
    }

    private fun renderEntries() {
        llEntriesContainer.removeAllViews()
        if (SessionData.entries.isEmpty()) {
            llEntriesContainer.addView(TextView(this).apply {
                text = "No transcription captured."
                setPadding(16, 24, 16, 24)
            })
            return
        }
        SessionData.entries.forEachIndexed { index, entry ->
            val cardView = LayoutInflater.from(this)
                .inflate(R.layout.item_transcript_entry, llEntriesContainer, false)

            val tvTimestamp    = cardView.findViewById<TextView>(R.id.tvTimestamp)
            val tvOriginal     = cardView.findViewById<TextView>(R.id.tvOriginal)
            val tvTranslation  = cardView.findViewById<TextView>(R.id.tvTranslation)
            val tvRetranslating = cardView.findViewById<TextView>(R.id.tvRetranslating)

            val sourceFlag = if (entry.isIncoming) theirLanguage.flag else myLanguage.flag
            val targetFlag = if (entry.isIncoming) myLanguage.flag else theirLanguage.flag

            tvTimestamp.text   = "${entry.senderNickname.ifBlank { "—" }} · ${formatTimestampRange(entry.timestampStart, entry.timestampEnd)}"
            tvOriginal.text    = "$sourceFlag ${entry.originalText}"
            tvTranslation.text = "$targetFlag ${entry.translatedText}"

            cardView.setOnClickListener {
                showEditDialog(index, entry, tvOriginal, tvTranslation, tvRetranslating)
            }
            llEntriesContainer.addView(cardView)
        }
    }

    private fun showEditDialog(
        index: Int,
        entry: TranscriptEntry,
        tvOriginal: TextView,
        tvTranslation: TextView,
        tvRetranslating: TextView
    ) {
        val editText = EditText(this).apply {
            setText(entry.originalText)
            setSelection(entry.originalText.length)
            setPadding(32, 16, 32, 16)
            minLines = 2
        }
        AlertDialog.Builder(this)
            .setTitle("Edit Transcription")
            .setMessage("Correct any errors below, then tap Re-Translate.")
            .setView(editText)
            .setPositiveButton("Re-Translate") { _, _ ->
                val corrected = editText.text.toString().trim()
                if (corrected.isEmpty()) { toast("Transcription cannot be empty."); return@setPositiveButton }
                retranslateEntry(index, corrected, tvOriginal, tvTranslation, tvRetranslating)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun retranslateEntry(
        index: Int,
        correctedOriginal: String,
        tvOriginal: TextView,
        tvTranslation: TextView,
        tvRetranslating: TextView
    ) {
        val currentEntry = SessionData.entries[index]
        val sourceFlag = if (currentEntry.isIncoming) theirLanguage.flag else myLanguage.flag
        val targetFlag = if (currentEntry.isIncoming) myLanguage.flag else theirLanguage.flag

        tvOriginal.text           = "$sourceFlag $correctedOriginal"
        tvTranslation.visibility  = View.GONE
        tvRetranslating.visibility = View.VISIBLE

        scope.launch {
            try {
                val srcLang = if (currentEntry.isIncoming) theirLanguage else myLanguage
                val tgtLang = if (currentEntry.isIncoming) myLanguage else theirLanguage
                val newTranslation = withContext(Dispatchers.IO) {
                    kieAiClient.translateText(correctedOriginal, srcLang, tgtLang)
                }
                SessionData.entries[index] = currentEntry.copy(
                    originalText   = correctedOriginal,
                    translatedText = newTranslation
                )
                tvTranslation.text         = "$targetFlag $newTranslation"
                tvTranslation.visibility   = View.VISIBLE
                tvRetranslating.visibility  = View.GONE
            } catch (e: Exception) {
                e.printStackTrace()
                tvTranslation.text         = "$targetFlag [Translation failed: ${e.message}]"
                tvTranslation.visibility   = View.VISIBLE
                tvRetranslating.visibility  = View.GONE
                toast("Re-translation failed.")
            }
        }
    }

    private fun shareFile(file: File, mimeType: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Session via"
        ))
    }

    private fun formatTimestampRange(startMs: Long, endMs: Long): String {
        fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms % 60000) / 1000)
        return "${fmt(startMs)} → ${fmt(endMs)}"
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
