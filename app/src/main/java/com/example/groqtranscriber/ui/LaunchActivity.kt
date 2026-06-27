package com.example.groqtranscriber.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.groqtranscriber.R
import com.example.groqtranscriber.api.ApiProvider

class LaunchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch)

        val etApiKey    = findViewById<EditText>(R.id.etApiKey)
        val rgLanguage  = findViewById<RadioGroup>(R.id.rgLanguage)
        val spProvider  = findViewById<Spinner>(R.id.spProvider)
        val btnLaunch   = findViewById<Button>(R.id.btnLaunch)

        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)

        // ── Restore saved API key ──────────────────────────────────────────
        etApiKey.setText(prefs.getString("api_key", ""))

        // ── Populate provider spinner ──────────────────────────────────────
        val providerNames = ApiProvider.entries.map { it.displayName }
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            providerNames
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spProvider.adapter = spinnerAdapter

        // Restore previously chosen provider
        val savedProvider = prefs.getString("api_provider", ApiProvider.GOOGLE_GEMINI.displayName)
        val savedIndex = providerNames.indexOf(savedProvider).coerceAtLeast(0)
        spProvider.setSelection(savedIndex)

        // ── Launch ─────────────────────────────────────────────────────────
        btnLaunch.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter a valid API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val selectedProvider = ApiProvider.fromDisplayName(
                spProvider.selectedItem?.toString() ?: ApiProvider.GOOGLE_GEMINI.displayName
            )

            val selectedLang = if (rgLanguage.checkedRadioButtonId == R.id.rbJapanese) {
                "Japanese"
            } else {
                "English"
            }

            // Persist choices
            prefs.edit()
                .putString("api_key", key)
                .putString("api_provider", selectedProvider.displayName)
                .apply()

            startActivity(
                Intent(this, RecordingActivity::class.java).apply {
                    putExtra("TARGET_LANG", selectedLang)
                    putExtra("API_PROVIDER", selectedProvider.name) // pass enum name
                }
            )
        }
    }
}
