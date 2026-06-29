package com.example.groqtranscriber.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.groqtranscriber.R
import com.example.groqtranscriber.model.Language

class LaunchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch)

        val etApiKey   = findViewById<EditText>(R.id.etApiKey)
        val rgLanguage = findViewById<RadioGroup>(R.id.rgLanguage)
        val btnLaunch  = findViewById<Button>(R.id.btnLaunch)

        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)

        // ── Restore saved API key ──────────────────────────────────────────
        etApiKey.setText(prefs.getString("api_key", ""))

        // ── Restore previously chosen spoken language ──────────────────────
        val savedLang = Language.fromName(prefs.getString("my_language", Language.INDONESIAN.name))
        if (savedLang == Language.ENGLISH) {
            rgLanguage.check(R.id.rbEnglish)
        } else {
            rgLanguage.check(R.id.rbIndonesian)
        }

        // ── Continue → RoomActivity ──────────────────────────────────────────
        btnLaunch.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter a valid kie.ai API key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val myLanguage = if (rgLanguage.checkedRadioButtonId == R.id.rbEnglish) {
                Language.ENGLISH
            } else {
                Language.INDONESIAN
            }

            // Persist choices
            prefs.edit()
                .putString("api_key", key)
                .putString("my_language", myLanguage.name)
                .apply()

            startActivity(
                Intent(this, RoomActivity::class.java).apply {
                    putExtra("MY_LANGUAGE", myLanguage.name)
                }
            )
        }
    }
}
