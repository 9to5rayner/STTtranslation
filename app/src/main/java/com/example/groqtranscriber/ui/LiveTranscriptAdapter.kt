package com.example.groqtranscriber.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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

class LiveTranscriptAdapter(
    private val context: Context,
    private val targetLang: String,
    private val geminiClient: GeminiClient,
    private val scope: CoroutineScope
) : RecyclerView.Adapter<LiveTranscriptAdapter.EntryViewHolder>() {

    inner class EntryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
        val tvOriginal: TextView = view.findViewById(R.id.tvOriginal)
        val tvTranslation: TextView = view.findViewById(R.id.tvTranslation)
        val tvRetranslating: TextView = view.findViewById(R.id.tvRetranslating)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_transcript_entry, parent, false)
        return EntryViewHolder(view)
    }

    override fun getItemCount(): Int = SessionData.entries.size

    override fun onBindViewHolder(holder: EntryViewHolder, position: Int) {
        val entry = SessionData.entries[position]
        bindEntry(holder, entry, position)
    }

    private fun bindEntry(holder: EntryViewHolder, entry: TranscriptEntry, position: Int) {
        holder.tvTimestamp.text = formatTimestampRange(entry.timestampStart, entry.timestampEnd)
        holder.tvOriginal.text = "ID: ${entry.originalText}"
        holder.tvTranslation.text = "$targetLang: ${entry.translatedText}"
        holder.tvRetranslating.visibility = View.GONE
        holder.tvTranslation.visibility = View.VISIBLE

        holder.itemView.setOnClickListener {
            // Use adapterPosition (not the captured position) to survive insertions
            val currentPos = holder.adapterPosition
            if (currentPos == RecyclerView.NO_ID.toInt()) return@setOnClickListener
            showEditDialog(holder, SessionData.entries[currentPos], currentPos)
        }
    }

    private fun showEditDialog(
        holder: EntryViewHolder,
        entry: TranscriptEntry,
        index: Int
    ) {
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
                retranslate(holder, index, corrected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun retranslate(
        holder: EntryViewHolder,
        index: Int,
        correctedOriginal: String
    ) {
        val current = SessionData.entries[index]

        // Optimistic UI update
        holder.tvOriginal.text = "ID: $correctedOriginal"
        holder.tvTranslation.visibility = View.GONE
        holder.tvRetranslating.visibility = View.VISIBLE

        scope.launch {
            try {
                val newTranslation = withContext(Dispatchers.IO) {
                    geminiClient.translateText(correctedOriginal, targetLang)
                }
                SessionData.entries[index] = TranscriptEntry(
                    timestampStart = current.timestampStart,
                    timestampEnd = current.timestampEnd,
                    originalText = correctedOriginal,
                    translatedText = newTranslation
                )
                // Already on Main; update card in-place
                holder.tvTranslation.text = "$targetLang: $newTranslation"
                holder.tvTranslation.visibility = View.VISIBLE
                holder.tvRetranslating.visibility = View.GONE

            } catch (e: Exception) {
                e.printStackTrace()
                holder.tvTranslation.text = "$targetLang: [Translation failed]"
                holder.tvTranslation.visibility = View.VISIBLE
                holder.tvRetranslating.visibility = View.GONE
                Toast.makeText(context, "Re-translation failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Call from RecordingActivity after a new entry is added to SessionData.entries */
    fun appendEntry() {
        notifyItemInserted(SessionData.entries.size - 1)
    }

    /** Call to refresh a single card after an external edit */
    fun refreshEntry(index: Int) {
        notifyItemChanged(index)
    }

    private fun formatTimestampRange(startMs: Long, endMs: Long): String {
        fun fmt(ms: Long) = "%d:%02d".format(ms / 60000, (ms % 60000) / 1000)
        return "${fmt(startMs)} → ${fmt(endMs)}"
    }
}
