package com.example.groqtranscriber.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.groqtranscriber.R
import com.example.groqtranscriber.export.ExportHelper
import com.example.groqtranscriber.model.SessionData
import java.io.File

class ExportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_export)

        val tvPreview = findViewById<TextView>(R.id.tvPreview)
        val btnTxt = findViewById<Button>(R.id.btnShareTxt)
        val btnSrt = findViewById<Button>(R.id.btnShareSrt)
        val btnCopy = findViewById<Button>(R.id.btnCopy)

        val targetLang = intent.getStringExtra("TARGET_LANG") ?: "English"
        val txtContent = ExportHelper.buildTxt(SessionData.entries, targetLang)
        
        // Show session preview
        tvPreview.text = if (txtContent.isEmpty()) "No transcription captured." else txtContent

        btnTxt.setOnClickListener { 
            if (SessionData.entries.isEmpty()) return@setOnClickListener
            val file = ExportHelper.createTempFile(this, txtContent, "session.txt")
            shareFile(file, "text/plain") 
        }

        btnSrt.setOnClickListener { 
            if (SessionData.entries.isEmpty()) return@setOnClickListener
            val srtContent = ExportHelper.buildSrt(SessionData.entries)
            val file = ExportHelper.createTempFile(this, srtContent, "subtitles.srt")
            shareFile(file, "application/x-subrip") 
        }
        
        btnCopy.setOnClickListener {
            if (txtContent.isEmpty()) return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Transcription", txtContent))
            Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
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
}