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
import com.example.groqtranscriber.api.KieAiClient
import com.example.groqtranscriber.api.TtsException
import com.example.groqtranscriber.model.Language
import com.example.groqtranscriber.model.SessionData
import com.example.groqtranscriber.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BubbleAdapter(
    private val context:        Context,
    private val kieAiClient:    KieAiClient,
    private val myLanguage:     Language,
    private val theirLanguage:  Language,
    private val scope:          CoroutineScope,
    private val onPlayRequest:  (filePath: String) -> Unit,
    private val onEntryUpdated: (index: Int, entry: TranscriptEntry) -> Unit
) : RecyclerView.Adapter<BubbleAdapter.BubbleVH>() {

    inner class BubbleVH(view: View) : RecyclerView.ViewHolder(view) {
        val vSpacerStart:        View         = view.findViewById(R.id.vSpacerStart)
        val vSpacerEnd:          View         = view.findViewById(R.id.vSpacerEnd)

        val tvNickname:          TextView     = view.findViewById(R.id.tvNickname)
        val tvTimestamp:         TextView     = view.findViewById(R.id.tvTimestamp)
        val tvEditHint:          TextView     = view.findViewById(R.id.tvEditHint)
        val tvEdited:            TextView     = view.findViewById(R.id.tvEdited)
        val tvSendStatus:        TextView     = view.findViewById(R.id.tvSendStatus)

        val llOriginalRow:       LinearLayout = view.findViewById(R.id.llOriginalRow)
        val tvOriginal:          TextView     = view.findViewById(R.id.tvOriginal)

        val llTranslationRow:    LinearLayout = view.findViewById(R.id.llTranslationRow)
        val tvTranslation:       TextView     = view.findViewById(R.id.tvTranslation)
        val btnPlay:             ImageButton  = view.findViewById(R.id.btnPlay)
        val btnRetryTts:         ImageButton  = view.findViewById(R.id.btnRetryTts)

        val llTranscribing:      LinearLayout = view.findViewById(R.id.llTranscribing)
        val llTranslating:       LinearLayout = view.findViewById(R.id.llTranslating)
        val llTtsLoading:        LinearLayout = view.findViewById(R.id.llTtsLoading)
        val llTtsError:          LinearLayout = view.findViewById(R.id.llTtsError)
        val tvTtsError:          TextView     = view.findViewById(R.id.tvTtsError)

        val llTranslationError:  LinearLayout = view.findViewById(R.id.llTranslationError)
        val tvTranslationError:  TextView     = view.findViewById(R.id.tvTranslationError)
        val btnRetryTranslation: ImageButton  = view.findViewById(R.id.btnRetryTranslation)

        // Confirmation row
        val llAwaitingConfirmation: LinearLayout = view.findViewById(R.id.llAwaitingConfirmation)
        val btnConfirmTranscription: android.widget.Button = view.findViewById(R.id.btnConfirmTranscription)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        BubbleVH(LayoutInflater.from(context).inflate(R.layout.item_chat_bubble, parent, false))

    override fun getItemCount() = SessionData.entries.size

    override fun onBindViewHolder(holder: BubbleVH, position: Int) =
        bind(holder, SessionData.entries[position], position)

    private fun bind(h: BubbleVH, e: TranscriptEntry, pos: Int) {

        // ── Left/right alignment ──────────────────────────────────────────────
        h.vSpacerStart.visibility = if (e.isIncoming) View.VISIBLE else View.GONE
        h.vSpacerEnd.visibility   = if (e.isIncoming) View.GONE else View.VISIBLE

        // ── Nickname + timestamp ──────────────────────────────────────────────
        h.tvNickname.text = e.senderNickname.ifBlank { if (e.isIncoming) "Partner" else "Me" }
        h.tvNickname.setTextColor(
            context.getColor(if (e.isIncoming) R.color.teal_deep else R.color.navy_deep)
        )
        h.tvTimestamp.text = formatTs(e.timestampStart)

        h.tvEdited.visibility   = if (!e.isIncoming && e.isEdited) View.VISIBLE else View.GONE

        // Hide "tap to edit" while awaiting confirmation — the confirm button serves that purpose
        h.tvEditHint.visibility = if (!e.isIncoming && e.originalText.isNotEmpty() &&
                                       !e.isTranscribing && !e.isTranslating &&
                                       !e.isGeneratingTts && !e.isAwaitingConfirmation) View.VISIBLE else View.GONE

        // ── Send status (outgoing only) ───────────────────────────────────────
        h.tvSendStatus.visibility = if (!e.isIncoming && e.originalText.isNotEmpty()) {
            View.VISIBLE
        } else View.GONE

        h.tvSendStatus.text = when {
            e.isIncoming            -> ""
            e.sendError != null     -> "⚠️ Not sent"
            e.isSendingToFirebase   -> "⏳ Sending…"
            e.isSentToFirebase      -> "✓ Sent"
            else                    -> ""
        }

        // ── Transcribing placeholder ──────────────────────────────────────────
        h.llTranscribing.visibility = if (e.isTranscribing) View.VISIBLE else View.GONE

        // ── Original-language bubble ──────────────────────────────────────────
        val originalFlag = if (e.isIncoming) theirLanguage.flag else myLanguage.flag
        h.llOriginalRow.visibility = if (e.originalText.isNotEmpty()) View.VISIBLE else View.GONE
        h.tvOriginal.text = "$originalFlag ${e.originalText}"
        h.tvOriginal.setBackgroundResource(if (e.isIncoming) R.drawable.bubble_teal else R.drawable.bubble_navy)

        // ── Awaiting confirmation row ─────────────────────────────────────────
        h.llAwaitingConfirmation.visibility =
            if (!e.isIncoming && e.isAwaitingConfirmation) View.VISIBLE else View.GONE

        h.btnConfirmTranscription.setOnClickListener {
            val p = h.adapterPosition
            if (p == RecyclerView.NO_POSITION || p >= SessionData.entries.size) return@setOnClickListener
            // Tapping the inline button opens an edit dialog then confirms
            showInlineConfirmDialog(p)
        }

        // ── "Translating…" row ────────────────────────────────────────────────
        h.llTranslating.visibility = if (e.isTranslating) View.VISIBLE else View.GONE

        // ── Translation error row ─────────────────────────────────────────────
        val hasTranslationError = !e.isIncoming && e.translationError != null
        h.llTranslationError.visibility = if (hasTranslationError) View.VISIBLE else View.GONE
        if (hasTranslationError) {
            h.tvTranslationError.text = "⚠️ ${e.translationError}"
        }
        h.btnRetryTranslation.visibility = if (hasTranslationError) View.VISIBLE else View.GONE
        h.btnRetryTranslation.setOnClickListener {
            val p = h.adapterPosition
            if (p == RecyclerView.NO_POSITION || p >= SessionData.entries.size) return@setOnClickListener
            retryTranslation(p)
        }

        // ── Translation bubble ────────────────────────────────────────────────
        val hasTranslation = e.translatedText.isNotEmpty()
        val translationFlag = if (e.isIncoming) myLanguage.flag else theirLanguage.flag
        h.llTranslationRow.visibility = if (hasTranslation) View.VISIBLE else View.GONE
        h.tvTranslation.text = "$translationFlag ${e.translatedText}"
        h.tvTranslation.setBackgroundResource(if (e.isIncoming) R.drawable.bubble_lavender else R.drawable.bubble_gold)

        // ── TTS loading row ───────────────────────────────────────────────────
        h.llTtsLoading.visibility = if (e.isGeneratingTts) View.VISIBLE else View.GONE

        // ── TTS error row ─────────────────────────────────────────────────────
        val hasTtsError = e.ttsError != null && !e.isGeneratingTts
        h.llTtsError.visibility  = if (hasTtsError) View.VISIBLE else View.GONE
        if (hasTtsError) h.tvTtsError.text = "⚠️ Audio failed: ${e.ttsError}"
        h.btnRetryTts.visibility = if (hasTtsError) View.VISIBLE else View.GONE
        h.btnRetryTts.setOnClickListener {
            val p = h.adapterPosition
            if (p == RecyclerView.NO_POSITION || p >= SessionData.entries.size) return@setOnClickListener
            retryTts(p)
        }

        // ── Play button ───────────────────────────────────────────────────────
        h.btnPlay.visibility = if (hasTranslation && !e.isGeneratingTts) View.VISIBLE else View.GONE
        h.btnPlay.isEnabled  = e.audioFilePath != null
        h.btnPlay.alpha      = if (e.audioFilePath != null) 1f else 0.35f
        h.btnPlay.setOnClickListener {
            e.audioFilePath?.let { onPlayRequest(it) }
                ?: Toast.makeText(context, "Audio not ready yet.", Toast.LENGTH_SHORT).show()
        }

        // ── Tap to edit (outgoing only, after translation done) ───────────────
        h.itemView.setOnClickListener {
            if (e.isIncoming) return@setOnClickListener
            val p = h.adapterPosition
            if (p == RecyclerView.NO_POSITION || p >= SessionData.entries.size) return@setOnClickListener
            val entry = SessionData.entries[p]
            if (entry.isTranscribing || entry.isAwaitingConfirmation ||
                entry.isTranslating || entry.isGeneratingTts) return@setOnClickListener
            showEditDialog(entry, p)
        }
    }

    // ── Inline confirm dialog (from the bubble button) ────────────────────────

    /**
     * Shown when the user taps "Edit & Confirm" on the awaiting-confirmation
     * bubble. Lets them make corrections, then kicks off translation directly
     * from the adapter (mirrors what RecordingActivity does for new recordings).
     */
    private fun showInlineConfirmDialog(index: Int) {
        if (index < 0 || index >= SessionData.entries.size) return
        val entry = SessionData.entries[index]

        val editText = EditText(context).apply {
            setText(entry.originalText)
            setSelection(entry.originalText.length)
            setPadding(48, 24, 48, 24)
            minLines = 2
            textSize = 15f
        }

        AlertDialog.Builder(context)
            .setTitle("Confirm Transcription")
            .setMessage("Review or edit before translating (${myLanguage.flag} ${myLanguage.displayName}):")
            .setView(editText)
            .setCancelable(false)
            .setPositiveButton("Translate ✓") { _, _ ->
                val confirmed = editText.text.toString().trim()
                if (confirmed.isEmpty()) {
                    Toast.makeText(context, "Cannot be empty.", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val wasEdited = confirmed != entry.originalText
                val updating = SessionData.entries[index].copy(
                    originalText           = confirmed,
                    isAwaitingConfirmation = false,
                    isEdited               = wasEdited,
                    isTranslating          = true,
                    translationError       = null
                )
                SessionData.entries[index] = updating
                notifyItemChanged(index)
                onEntryUpdated(index, updating)

                scope.launch {
                    val translated: String? = try {
                        withContext(Dispatchers.IO) {
                            kieAiClient.translateText(confirmed, myLanguage, theirLanguage)
                        }.takeIf { it.isNotBlank() }
                    } catch (e: Exception) { null }

                    if (index >= SessionData.entries.size) return@launch

                    if (translated.isNullOrBlank()) {
                        val failed = SessionData.entries[index].copy(
                            isTranslating    = false,
                            translationError = "Translation failed. Tap ↻ to retry."
                        )
                        SessionData.entries[index] = failed
                        notifyItemChanged(index)
                        onEntryUpdated(index, failed)
                        return@launch
                    }

                    val afterTranslate = SessionData.entries[index].copy(
                        translatedText   = translated,
                        isTranslating    = false,
                        translationError = null,
                        isGeneratingTts  = true
                    )
                    SessionData.entries[index] = afterTranslate
                    notifyItemChanged(index)
                    onEntryUpdated(index, afterTranslate)

                    runTtsPhase(index)
                }
            }
            .setNegativeButton("Discard ✗") { _, _ ->
                // Remove the entry entirely
                if (index < SessionData.entries.size) {
                    SessionData.entries.removeAt(index)
                    notifyItemRemoved(index)
                    onEntryUpdated(index, TranscriptEntry(timestampStart = 0, timestampEnd = 0))
                }
            }
            .show()
    }

    // ── Translation retry ─────────────────────────────────────────────────────

    fun retryTranslation(index: Int) {
        if (index < 0 || index >= SessionData.entries.size) return
        val entry = SessionData.entries[index]
        if (entry.originalText.isBlank() || entry.isTranslating) return

        val withRetrying = entry.copy(
            isTranslating    = true,
            translationError = null,
            translatedText   = "",
            audioFilePath    = null,
            ttsError         = null,
            isGeneratingTts  = false
        )
        SessionData.entries[index] = withRetrying
        notifyItemChanged(index)
        onEntryUpdated(index, withRetrying)

        scope.launch {
            val translated: String? = try {
                withContext(Dispatchers.IO) {
                    kieAiClient.translateText(entry.originalText, myLanguage, theirLanguage)
                }.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }

            if (index >= SessionData.entries.size) return@launch

            if (translated.isNullOrBlank()) {
                val failed = SessionData.entries[index].copy(
                    isTranslating    = false,
                    translationError = "Translation failed. Tap ↻ to retry again."
                )
                SessionData.entries[index] = failed
                notifyItemChanged(index)
                onEntryUpdated(index, failed)
                return@launch
            }

            val afterTranslate = SessionData.entries[index].copy(
                translatedText   = translated,
                isTranslating    = false,
                translationError = null,
                isGeneratingTts  = true
            )
            SessionData.entries[index] = afterTranslate
            notifyItemChanged(index)
            onEntryUpdated(index, afterTranslate)

            runTtsPhase(index)
        }
    }

    // ── TTS retry ─────────────────────────────────────────────────────────────

    fun retryTts(index: Int) {
        if (index < 0 || index >= SessionData.entries.size) return
        val entry = SessionData.entries[index]

        if (entry.translatedText.isBlank() || entry.translationError != null || entry.isGeneratingTts) return

        val withLoading = entry.copy(isGeneratingTts = true, ttsError = null)
        SessionData.entries[index] = withLoading
        notifyItemChanged(index)
        onEntryUpdated(index, withLoading)

        scope.launch { runTtsPhase(index) }
    }

    // ── Shared TTS phase ──────────────────────────────────────────────────────

    private suspend fun runTtsPhase(index: Int) {
        if (index >= SessionData.entries.size) return
        val entry = SessionData.entries[index]

        if (entry.translatedText.isBlank() || entry.translationError != null) {
            if (index < SessionData.entries.size) {
                SessionData.entries[index] = entry.copy(isGeneratingTts = false)
                notifyItemChanged(index)
            }
            return
        }

        val result: Result<String> = try {
            val ttsBytes  = withContext(Dispatchers.IO) { kieAiClient.generateTts(entry.translatedText) }
            val ext       = kieAiClient.ttsFileExtension()
            val audioFile = File(context.filesDir, "tts_${entry.id}.$ext")
            withContext(Dispatchers.IO) { kieAiClient.writeTtsBytesToFile(ttsBytes, audioFile) }
            Result.success(audioFile.absolutePath)
        } catch (e: TtsException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }

        if (index >= SessionData.entries.size) return

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

    // ── Edit dialog (outgoing only, post-translation) ─────────────────────────

    private fun showEditDialog(entry: TranscriptEntry, index: Int) {
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
                if (corrected == entry.originalText) return@setPositiveButton
                applyEdit(index, corrected)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyEdit(index: Int, corrected: String) {
        val updated = SessionData.entries[index].copy(
            originalText     = corrected,
            translatedText   = "",
            audioFilePath    = null,
            isEdited         = true,
            isTranslating    = true,
            translationError = null,
            isGeneratingTts  = false,
            ttsError         = null
        )
        SessionData.entries[index] = updated
        notifyItemChanged(index)
        onEntryUpdated(index, updated)

        scope.launch {
            val translated: String? = try {
                withContext(Dispatchers.IO) {
                    kieAiClient.translateText(corrected, myLanguage, theirLanguage)
                }.takeIf { it.isNotBlank() }
            } catch (e: Exception) { null }

            if (index >= SessionData.entries.size) return@launch

            if (translated.isNullOrBlank()) {
                val failed = SessionData.entries[index].copy(
                    isTranslating    = false,
                    translationError = "Translation failed. Tap ↻ to retry."
                )
                SessionData.entries[index] = failed
                notifyItemChanged(index)
                onEntryUpdated(index, failed)
                return@launch
            }

            val afterTranslate = SessionData.entries[index].copy(
                translatedText   = translated,
                isTranslating    = false,
                translationError = null,
                isGeneratingTts  = true
            )
            SessionData.entries[index] = afterTranslate
            notifyItemChanged(index)
            onEntryUpdated(index, afterTranslate)

            runTtsPhase(index)
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────────

    private fun formatTs(wallMs: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = wallMs }
        return "%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE)
        )
    }
}
