package com.example.groqtranscriber.export

import android.content.Context
import com.example.groqtranscriber.model.TranscriptEntry
import java.io.File
import java.util.Locale

object ExportHelper {

    fun buildTxt(entries: List<TranscriptEntry>, lang: String): String {
        return entries.joinToString("\n\n") { 
            "[Indonesian]: ${it.originalText}\n[$lang]: ${it.translatedText}" 
        }
    }

    fun buildSrt(entries: List<TranscriptEntry>): String {
        val sb = StringBuilder()
        entries.forEachIndexed { index, entry ->
            sb.append("${index + 1}\n")
            sb.append("${formatSrtTime(entry.timestampStart)} --> ${formatSrtTime(entry.timestampEnd)}\n")
            sb.append("${entry.translatedText}\n\n")
        }
        return sb.toString()
    }

    fun createTempFile(context: Context, content: String, filename: String): File {
        val file = File(context.cacheDir, filename)
        file.writeText(content)
        return file
    }

    private fun formatSrtTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}