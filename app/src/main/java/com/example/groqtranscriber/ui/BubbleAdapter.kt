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

/**
 * Renders each TranscriptEntry as a real chat-app bubble.
 *
 * OUTGOING (isIncoming = false) — LEFT-aligned, own nickname, navy/gold bubbles:
 *   [Me]  0:12  ·  tap to edit       [EDITED]  [✓ Sent / ⏳ Sending / ⚠️ Failed]
 *   🗨  navy bubble (spoken language)
 *   🗨  gold bubble (translated)                                       ▶  ↻(tts)
 *
 * INCOMING (isIncoming = true) — RIGHT-aligned, partner's nickname, teal/lavender bubbles:
 *                                                          0:12  [Partner]
 *                                          teal bubble (their spoken language)  🗨
 *                                  lavender bubble (translated for me)  🗨  ▶  ↻(tts)
 *
 * Pipeline gates enforced here:
 *   - retryTranslation: re-runs translation, then only proceeds to TTS if
 *     the new translation is non-blank (same gate as the main pipeline).
 *   - retryTts: only runs if translatedText is non-blank AND translationError
 *     is null (same gate as Phase 3 in RecordingActivity).
 */
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        BubbleVH(LayoutInflater.from(context).inflate(R.layout.item_chat_bubble, parent, false))

    override fun getItemCount() = SessionData.entries.size

    override fun onBindViewHolder(holder: BubbleVH, position: Int) =
        bind(holder, SessionData.entries[position], position)

    private fun bind(h: BubbleVH, e: TranscriptEntry, pos: Int) {

        // ── Left/right alignment ──────────────────────────────────────────────
        // Outgoing (own) = LEFT-aligned: hide the start spacer, show the end one.
        // Incoming (partner) = RIGHT-aligned: show the start spacer, hide the end one.
        h.vSpacerStart.visibility = if (e.isIncoming) View.VISIBLE else View.GONE
        h.vSpacerEnd.visibility   = if (e.isIncoming) View.GONE else View.VISIBLE

        // ── Nickname + timestamp ──────────────────────────────────────────────
        h.tvNickname.text = e.senderNickname.ifBlank { if (e.isIncoming) "Partner" else "Me" }
        h.tvNickname.setTextColor(
            context.getColor(if (e.isIncoming) R.color.teal_deep else R.color.navy_deep)
        )
        h.tvTimestamp.text = formatTs(e.timestampStart)

        h.tvEdited.visibility    = if (!e.isIncoming && e.isEdited) View.VISIBLE else View.GONE
        h.tvEditHint.visibility  = if (!e.isIncoming && e.originalText.isNotEmpty() &&
                                       !e.isTranscribing && !e.isTranslating &&
                                       !e.isGeneratingTts) View.VISIBLE else View.GONE

        // ── Send status (outgoing only) ───────────────────────────────────────
        h.tvSendStatus.visibility = if (!e.isIncoming && e.originalText.isNotEmpty()) {
            View.VISIBLE
        } else View.GONE

        h.tvSendStatus.text = when {
            e.isIncoming            -> ""
            e.sendError != null     -> "⚠️ Not sent"
            e.isSendingToFirebase   -> "⏳ Sending…"
            e.isSentToFirebase      -> "✓ Sent"
            else                    -> ""  // local-only (no room) or pre-send
        }

        // ── Transcribing placeholder ──────────────────────────────────────────
        h.llTranscribing.visibility = if (e.isTranscribing) View.VISIBLE else View.GONE

        // ── Original-language bubble ──────────────────────────────────────────
        val originalFlag = if (e.isIncoming) theirLanguage.flag else myLanguage.flag
        h.llOriginalRow.visibility = if (e.originalText.isNotEmpty()) View.VISIBLE else View.GONE
        h.tvOriginal.text = "$originalFlag ${e.originalText}"
        h.tvOriginal.setBackgroundResource(if (e.isIncoming) R.drawable.bubble_teal else R.drawable.bubble_navy)

        // ── "Translating…" row ────────────────────────────────────────────────
        h.llTranslating.visibility = if (e.isTranslating) View.VISIBLE else View.GONE

        // ── Translation error row (outgoing only) ─────────────────────────────
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

        // ── Tap to edit (outgoing only) ───────────────────────────────────────
        h.itemView.setOnClickListener {
            if (e.isIncoming) return@setOnClickListener
            val p = h.adapterPosition
            if (p == RecyclerView.NO_POSITION || p >= SessionData.entries.size) return@setOnClickListener
            val entry = SessionData.entries[p]
            if (entry.isTranscribing || entry.isTranslating || entry.isGeneratingTts) return@setOnClickListener
            showEditDialog(entry, p)
        }
    }

    // ── Translation retry ─────────────────────────────────────────────────────

    /**
     * Re-runs the translation phase for the entry at [index].
     * Gate: originalText must be non-blank (should always be true at this point).
     * On success, clears translationError and proceeds to TTS.
     * On failure, updates translationError and stops.
     *
     * Only ever wired to outgoing (own) bubbles, so the direction is always
     * myLanguage → theirLanguage.
     */
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
                // Translation still failing — record error, stop
                val failed = SessionData.entries[index].copy(
                    isTranslating    = false,
                    translationError = "Translation failed. Tap ↻ to retry again."
                )
                SessionData.entries[index] = failed
                notifyItemChanged(index)
                onEntryUpdated(index, failed)
                return@launch
            }

            // Translation succeeded — proceed to TTS
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

    /**
     * Re-runs the TTS phase for the entry at [index].
     * Gate: translatedText must be non-blank AND translationError must be null.
     */
    fun retryTts(index: Int) {
        if (index < 0 || index >= SessionData.entries.size) return
        val entry = SessionData.entries[index]

        // Gate check
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

        // Gate: don't attempt TTS if translation didn't succeed
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

    // ── Edit dialog (outgoing only) ───────────────────────────────────────────

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
