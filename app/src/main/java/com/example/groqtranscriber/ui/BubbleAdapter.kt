package com.example.groqtranscriber.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.example.groqtranscriber.R
import com.example.groqtranscriber.api.GeminiClient
import com.example.groqtranscriber.api.TtsException
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders each TranscriptEntry as a two-bubble chat item:
 *
 *   🕐 0:12  ·  tap to edit          [EDITED]
 *   ┌─────────────────────────────────────────┐  ← navy bubble (Indonesian)
 *   │ 🇮🇩  Selamat pagi saudara-saudari…      │
 *   └─────────────────────────────────────────┘
 *   ┌─────────────────────────────────────────┐  ← gold bubble (translation)
 *   │ 🌐  Good morning brothers and sisters…  │  ▶
 *   └─────────────────────────────────────────┘
 *   ⚠️ Audio failed — tap ↻ to retry            ← only if TTS failed
 *
 * State indicators:
 *   • isTranscribing  → "Transcribing…" shimmer, no bubbles yet
 *   • isTranslating   → navy bubble visible, "Translating…" below
 *   • isGeneratingTts → gold bubble visible, "Generating audio…" below play button
 *   • audioFilePath set → ▶ Play button active
 *   • ttsError set      → ⚠️ error row + ↻ retry button, play button disabled
 *
 * TTS generation itself is provider-agnostic from this class's point of view —
 * geminiClient.generateTts() + geminiClient.ttsFileExtension() +
 * geminiClient.writeTtsBytesToFile() handle the difference between native
 * Gemini TTS (raw PCM → .wav) and kie.ai/ElevenLabs Turbo v2.5 (complete
 * mp3 file → .mp3) internally.
 */
class BubbleAdapter(
    private val context:        Context,
    private val geminiClient:   GeminiClient,
    private val targetLang:     String,
    private val scope:          CoroutineScope,
    private val onPlayRequest:  (filePath: String) -> Unit,
    private val onEntryUpdated: (index: Int, entry: TranscriptEntry) -> Unit
) : RecyclerView.Adapter<BubbleAdapter.BubbleVH>() {

    inner class BubbleVH(view: View) : RecyclerView.ViewHolder(view) {
        // Timestamp row
        val tvTimestamp:    TextView     = view.findViewById(R.id.tvTimestamp)
        val tvEditHint:     TextView     = view.findViewById(R.id.tvEditHint)
        val tvEdited:       TextView     = view.findViewById(R.id.tvEdited)

        // Indonesian bubble
        val llNavyRow:      LinearLayout = view.findViewById(R.id.llNavyRow)
        val tvOriginal:     TextView     = view.findViewById(R.id.tvOriginal)

        // Translation bubble
        val llGoldRow:      LinearLayout = view.findViewById(R.id.llGoldRow)
        val tvTranslation:  TextView     = view.findViewById(R.id.tvTranslation)
        val btnPlay:        ImageButton  = view.findViewById(R.id.btnPlay)
        val btnRetryTts:    ImageButton  = view.findViewById(R.id.btnRetryTts)

        // Status rows
        val llTranscribing: LinearLayout = view.findViewById(R.id.llTranscribing)
        val llTranslating:  LinearLayout = view.findViewById(R.id.llTranslating)
        val llTtsLoading:   LinearLayout = view.findViewById(R.id.llTtsLoading)
        val llTtsError:     LinearLayout = view.findViewById(R.id.llTtsError)
        val tvTtsError:     TextView     = view.findViewById(R.id.tvTtsError)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        BubbleVH(LayoutInflater.from(context).inflate(R.layout.item_chat_bubble, parent, false))

    override fun getItemCount() = SessionData.entries.size

    override fun onBindViewHolder(holder: BubbleVH, position: Int) =
        bind(holder, SessionData.entries[position], position)

    private fun bind(h: BubbleVH, e: TranscriptEntry, pos: Int) {

        // ── Timestamp + tags ──────────────────────────────────────────────────
        h.tvTimestamp.text = formatTs(e.timestampStart)
        h.tvEdited.visibility = if (e.isEdited) View.VISIBLE else View.GONE

        // ── Transcribing placeholder ──────────────────────────────────────────
        h.llTranscribing.visibility = if (e.isTranscribing) View.VISIBLE else View.GONE

        // ── Indonesian bubble ─────────────────────────────────────────────────
        h.llNavyRow.visibility = if (e.originalText.isNotEmpty()) View.VISIBLE else View.GONE
        h.tvOriginal.text      = e.originalText

        // ── "Translating…" row ────────────────────────────────────────────────
        h.llTranslating.visibility = if (e.isTranslating) View.VISIBLE else View.GONE

        // ── Translation bubble ────────────────────────────────────────────────
        val hasTranslation = e.translatedText.isNotEmpty()
        h.llGoldRow.visibility = if (hasTranslation) View.VISIBLE else View.GONE
        h.tvTranslation.text   = e.translatedText

        // ── TTS loading row ───────────────────────────────────────────────────
        h.llTtsLoading.visibility = if (e.isGeneratingTts) View.VISIBLE else View.GONE

        // ── TTS error row + retry button ──────────────────────────────────────
        val hasTtsError = e.ttsError != null && !e.isGeneratingTts
        h.llTtsError.visibility = if (hasTtsError) View.VISIBLE else View.GONE
        if (hasTtsError) {
            h.tvTtsError.text = "⚠️ Audio failed: ${e.ttsError}"
        }
        h.btnRetryTts.visibility = if (hasTtsError) View.VISIBLE else View.GONE
        h.btnRetryTts.setOnClickListener {
            val p = h.adapterPosition
            if (p == RecyclerView.NO_POSITION || p >= SessionData.entries.size) return@setOnClickListener
            retryTts(p)
        }

        // ── Play button ───────────────────────────────────────────────────────
        h.btnPlay.visibility  = if (hasTranslation && !e.isGeneratingTts) View.VISIBLE else View.GONE
        h.btnPlay.isEnabled   = e.audioFilePath != null
        h.btnPlay.alpha       = if (e.audioFilePath != null) 1f else 0.35f
        h.btnPlay.setOnClickListener {
            e.audioFilePath?.let { onPlayRequest(it) }
                ?: Toast.makeText(context, "Audio not ready yet.", Toast.LENGTH_SHORT).show()
        }

        // ── Tap bubble to edit ────────────────────────────────────────────────
        h.tvEditHint.visibility = if (e.originalText.isNotEmpty()) View.VISIBLE else View.GONE
        h.itemView.setOnClickListener {
            val p = h.adapterPosition
            if (p == RecyclerView.NO_POSITION || p >= SessionData.entries.size) return@setOnClickListener
            val entry = SessionData.entries[p]
            if (entry.isTranscribing || entry.isTranslating || entry.isGeneratingTts) return@setOnClickListener
            showEditDialog(h, entry, p)
        }
    }

    // ── TTS retry ─────────────────────────────────────────────────────────────

    /**
     * Re-runs only the TTS phase for the entry at [index], using its current
     * translatedText. Clears any previous error and shows the loading row
     * while it runs. Safe to call from the bubble's own retry button.
     */
    fun retryTts(index: Int) {
        if (index < 0 || index >= SessionData.entries.size) return
        val entry = SessionData.entries[index]
        if (entry.translatedText.isEmpty() || entry.isGeneratingTts) return

        val withLoading = entry.copy(isGeneratingTts = true, ttsError = null)
        SessionData.entries[index] = withLoading
        notifyItemChanged(index)
        onEntryUpdated(index, withLoading)

        scope.launch {
            runTtsPhase(index, entry.translatedText)
        }
    }

    /**
     * Runs TTS generation for [translatedText] and writes the result (success
     * or failure) back into SessionData.entries[index]. Shared by the initial
     * pipeline-completion path (applyEdit) and the manual retry path so both
     * report errors the same way instead of one of them swallowing to null.
     *
     * geminiClient.generateTts() + ttsFileExtension()/writeTtsBytesToFile()
     * handle the byte-format difference between native Gemini TTS and
     * kie.ai/ElevenLabs internally — this function doesn't need to know
     * which provider is active.
     */
    private suspend fun runTtsPhase(index: Int, translatedText: String) {
        if (index >= SessionData.entries.size) return

        val result: Result<String> = try {
            val ttsBytes  = withContext(Dispatchers.IO) { geminiClient.generateTts(translatedText) }
            val ext       = geminiClient.ttsFileExtension()
            val audioFile = File(context.filesDir, "tts_${SessionData.entries[index].id}.$ext")
            withContext(Dispatchers.IO) { geminiClient.writeTtsBytesToFile(ttsBytes, audioFile) }
            Result.success(audioFile.absolutePath)
        } catch (e: TtsException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (index >= SessionData.entries.size) return // entry may have been removed/cleared meanwhile

        val final = result.fold(
            onSuccess = { path ->
                SessionData.entries[index].copy(
                    audioFilePath   = path,
                    isGeneratingTts = false,
                    ttsError        = null
                )
            },
            onFailure = { err ->
                SessionData.entries[index].copy(
                    audioFilePath   = null,
                    isGeneratingTts = false,
                    ttsError        = (err.message ?: "Unknown error").take(140)
                )
            }
        )
        SessionData.entries[index] = final
        notifyItemChanged(index)
        onEntryUpdated(index, final)
    }

    // ── Edit dialog ───────────────────────────────────────────────────────────

    private fun showEditDialog(h: BubbleVH, entry: TranscriptEntry, index: Int) {
        val editText = EditText(context).apply {
            setText(entry.originalText)
            setSelection(entry.originalText.length)
            setPadding(48, 24, 48, 24)
            minLines = 2
        }
        AlertDialog.Builder(context)
            .setTitle("Edit Transcription")
            .setMessage("Correct any errors, then tap Re-Translate.")
            .setView(editText)
            .setPositiveButton("Re-Translate") { _, _ ->
                val corrected = editText.text.toString().trim()
                if (corrected.isEmpty()) {
                    Toast.makeText(context, "Cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                // Only kick off re-translate if the text actually changed
                if (corrected == entry.originalText) return@setPositiveButton
                applyEdit(h, index, corrected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyEdit(h: BubbleVH, index: Int, corrected: String) {
        // Update model → mark as edited + isTranslating
        val updated = SessionData.entries[index].copy(
            originalText   = corrected,
            translatedText = "",
            audioFilePath  = null,
            isEdited       = true,
            isTranslating  = true,
            isGeneratingTts = false,
            ttsError        = null
        )
        SessionData.entries[index] = updated
        notifyItemChanged(index)
        onEntryUpdated(index, updated)

        scope.launch {
            // Phase 2: re-translate
            val translated = try {
                withContext(Dispatchers.IO) { geminiClient.translateText(corrected, targetLang) }
            } catch (e: Exception) { "[Translation failed]" }

            if (index >= SessionData.entries.size) return@launch

            val afterTranslate = SessionData.entries[index].copy(
                translatedText  = translated,
                isTranslating   = false,
                isGeneratingTts = true
            )
            SessionData.entries[index] = afterTranslate
            notifyItemChanged(index)
            onEntryUpdated(index, afterTranslate)

            // Phase 3: re-generate TTS (shared helper — records ttsError on failure
            // instead of silently leaving audioFilePath null with no explanation)
            runTtsPhase(index, translated)
        }
    }

    // ── Formatting ─────────────────────────────────────────────────────────────

    private fun formatTs(wallMs: Long): String {
        val h = java.util.Calendar.getInstance().apply { timeInMillis = wallMs }
        return "🕐 %02d:%02d".format(h.get(java.util.Calendar.HOUR_OF_DAY), h.get(java.util.Calendar.MINUTE))
    }
}
