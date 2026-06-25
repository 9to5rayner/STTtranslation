package com.example.groqtranscriber.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.groqtranscriber.R

class LaunchActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launch)

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val rgLanguage = findViewById<RadioGroup>(R.id.rgLanguage)
        val btnLaunch = findViewById<Button>(R.id.btnLaunch)

        // Retrieve saved API key if it exists
        val prefs = getSharedPreferences("GroqPrefs", Context.MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))

        btnLaunch.setOnClickListener {
            val key = etApiKey.text.toString().trim()
            if (key.isEmpty()) {
                Toast.makeText(this, "Please enter a valid Groq API Key", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Secure key locally
            prefs.edit().putString("api_key", key).apply()

            // Determine target language setup
            val selectedLang = if (rgLanguage.checkedRadioButtonId == R.id.rbJapanese) "Japanese" else "English"
            
            val intent = Intent(this, RecordingActivity::class.java).apply {
                putExtra("TARGET_LANG", selectedLang)
            }
            startActivity(intent)
        }
    }
}