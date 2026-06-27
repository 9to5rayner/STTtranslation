package com.example.groqtranscriber.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.groqtranscriber.R
import com.example.groqtranscriber.api.GeminiClient
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Renders transcript chunks as chat-style bubbles.
 *
 * Two-phase display:
 *   Phase 1 — Indonesian bubble appears immediately (transcription done).
 *   Phase 2 — Gold translation bubble slides in when translateText() returns.
 *
 * The "Translating…" row is visible between phases so the user knows work
 * is still happening.
 */
class LiveTranscriptAdapter(
    private val context: Context,
    private val targetLang: String,
    private val geminiClient: GeminiClient,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<LiveTranscriptAdapter.BubbleViewHolder>() {

    inner class BubbleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView     = view.findViewById(R.id.tvTimestamp)
        val tvOriginal: TextView      = view.findViewById(R.id.tvOriginal)
        val llTranslationRow: LinearLayout = view.findViewById(R.id.llTranslationRow)
        val tvTranslation: TextView   = view.findViewById(R.id.tvTranslation)
        val llTranslatingRow: LinearLayout = view.findViewById(R.id.llTranslatingRow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BubbleViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_chat_bubble, parent, false)
        return BubbleViewHolder(view)
    }

    override fun getItemCount(): Int = SessionData.entries.size

    override fun onBindViewHolder(holder: BubbleViewHolder, position: Int) {
        bindBubble(holder, SessionData.entries[position])
    }

    private fun bindBubble(holder: BubbleViewHolder, entry: TranscriptEntry) {
        holder.tvTimestamp.text = formatRange(entry.timestampStart, entry.timestampEnd)
        holder.tvOriginal.text  = entry.originalText

        when {
            entry.translatedText.isNotEmpty() -> {
                // Phase 2 complete — show both bubbles
                holder.tvTranslation.text          = entry.translatedText
                holder.llTranslationRow.visibility = View.VISIBLE
                holder.llTranslatingRow.visibility = View.GONE
            }
            entry.isTranslating -> {
                // Phase 1 done, phase 2 in progress — show spinner row
                holder.llTranslationRow.visibility = View.GONE
                holder.llTranslatingRow.visibility = View.VISIBLE
            }
            else -> {
                // Translation not started yet (e.g. entry added before translation kicked off)
                holder.llTranslationRow.visibility = View.GONE
                holder.llTranslatingRow.visibility = View.GONE
            }
        }

        holder.itemView.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
            showEditDialog(holder, SessionData.entries[pos], pos)
        }
    }

    // ── Called from RecordingActivity after transcription arrives ───────────

    /** Add entry with Indonesian text only; starts translation internally. */
    fun appendAndTranslate(entry: TranscriptEntry) {
        SessionData.entries.add(entry)
        val index = SessionData.entries.size - 1
        notifyItemInserted(index)
        kickOffTranslation(index)
    }

    // ── Edit dialog ─────────────────────────────────────────────────────────

    private fun showEditDialog(holder: BubbleViewHolder, entry: TranscriptEntry, index: Int) {
        val editText = EditText(context).apply {
            setText(entry.originalText)
            setSelection(entry.originalText.length)
            setPadding(32, 16, 32, 16)
            minLines = 2
        }
        AlertDialog.Builder(context)
            .setTitle("Edit Transcription")
            .setMessage("Correct errors, then tap Re-Translate.")
            .setView(editText)
            .setPositiveButton("Re-Translate") { _, _ ->
                val corrected = editText.text.toString().trim()
                if (corrected.isEmpty()) {
                    Toast.makeText(context, "Transcription cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                applyEdit(holder, index, corrected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyEdit(holder: BubbleViewHolder, index: Int, correctedOriginal: String) {
        val current = SessionData.entries[index]
        // Update model immediately
        SessionData.entries[index] = current.copy(
            originalText   = correctedOriginal,
            translatedText = "",
            isTranslating  = true
        )
        // Optimistic UI
        holder.tvOriginal.text             = correctedOriginal
        holder.llTranslationRow.visibility = View.GONE
        holder.llTranslatingRow.visibility = View.VISIBLE

        kickOffTranslation(index)
    }

    // ── Translation worker ───────────────────────────────────────────────────

    private fun kickOffTranslation(index: Int) {
        scope.launch {
            try {
                val text = SessionData.entries[index].originalText
                val translated = withContext(Dispatchers.IO) {
                    geminiClient.translateText(text, targetLang)
                }
                val current = SessionData.entries[index]
                SessionData.entries[index] = current.copy(
                    translatedText = translated,
                    isTranslating  = false
                )
                notifyItemChanged(index)
            } catch (e: Exception) {
                e.printStackTrace()
                val current = SessionData.entries[index]
                SessionData.entries[index] = current.copy(
                    translatedText = "[Translation failed]",
                    isTranslating  = false
                )
                notifyItemChanged(index)
            }
        }
    }

    private fun formatRange(startMs: Long, endMs: Long): String {
        fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms % 60000) / 1000)
        return "🕐 ${fmt(startMs)} → ${fmt(endMs)}"
    }
}
