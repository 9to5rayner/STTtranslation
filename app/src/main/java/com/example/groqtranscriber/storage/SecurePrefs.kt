package com.example.groqtranscriber.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Drop-in replacement for `getSharedPreferences("GroqPrefs", MODE_PRIVATE)`.
 *
 * WHY THIS EXISTS
 * ────────────────
 * The kie.ai API key was previously stored as plain text inside
 * SharedPreferences — readable as plaintext XML at
 * `/data/data/<pkg>/shared_prefs/GroqPrefs.xml` on a rooted device, via
 * `adb backup` if backups aren't disabled, or via `run-as` on a debug build.
 *
 * [SecurePrefs.get] returns an [EncryptedSharedPreferences] instance instead.
 * Every value is AES-256-GCM encrypted before touching disk, using a key
 * that lives in the Android Keystore (hardware-backed on most devices) and
 * is never directly exposed to the app's process.
 *
 * WHAT THIS DOES *NOT* PROTECT AGAINST
 * ──────────────────────────────────────
 * A fully rooted/compromised device where an attacker can hook the running
 * process (Frida, Xposed) and read the decrypted value from memory at the
 * moment the app uses it. No on-device storage scheme can fully prevent
 * that — it's a structural limit of holding a secret inside an app the
 * user controls. For a stronger guarantee the key would need to never
 * leave a server (a backend proxy), which is a separate, larger change.
 *
 * NEW FILE NAME — INTENTIONAL
 * ────────────────────────────
 * Uses "GroqPrefsSecure" rather than reusing the old "GroqPrefs" name.
 * EncryptedSharedPreferences expects its own on-disk format; pointing it at
 * the old plaintext XML file would fail to parse rather than transparently
 * upgrading it. Since this app hasn't shipped to real users yet (per
 * earlier decision during the auth migration), there's no migration step —
 * the old plaintext file is simply abandoned, and the user re-enters their
 * API key once after updating.
 *
 * USAGE
 * ─────
 * Same shape as the SharedPreferences API everywhere it's used:
 *   val prefs = SecurePrefs.get(context)
 *   val key   = prefs.getString("api_key", "")
 *   prefs.edit().putString("api_key", newKey).apply()
 */
object SecurePrefs {

    private const val SECURE_FILE_NAME = "GroqPrefsSecure"

    @Volatile private var cached: SharedPreferences? = null

    /**
     * Returns the encrypted SharedPreferences instance, creating it on first
     * call. Safe to call from any thread; the underlying Keystore key
     * generation is cheap and idempotent, but callers on a hot path should
     * still prefer calling this once and reusing the reference within an
     * Activity rather than calling it repeatedly in a loop.
     */
    fun get(context: Context): SharedPreferences {
        return cached ?: synchronized(this) {
            cached ?: buildEncryptedPrefs(context.applicationContext).also { cached = it }
        }
    }

    private fun buildEncryptedPrefs(appContext: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            SECURE_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
