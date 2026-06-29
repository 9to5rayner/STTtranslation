package com.example.groqtranscriber.crypto

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Symmetric encryption for walkie-talkie room messages.
 *
 * KEY DERIVATION
 * ──────────────
 * The room code (shared out-of-band between the two devices) is used as the
 * password input to PBKDF2-HMAC-SHA256 with 100 000 iterations, producing a
 * 256-bit AES key.  Both devices derive the identical key independently —
 * no key-exchange round-trip is needed.
 *
 * The salt is a fixed, app-specific constant.  This is intentional: room
 * codes are ephemeral (new code per session), so the threat model is
 * unauthorised Firebase access, not an offline dictionary attack against a
 * long-lived password.  A fixed salt still forces an attacker to run PBKDF2
 * per room code guess rather than using precomputed tables.
 *
 * THREAD SAFETY
 * ─────────────
 * Key derivation is intentionally lazy — it runs on the first call to
 * [encrypt] or [decrypt], not at construction time.  Because the first
 * real call always comes from a coroutine on Dispatchers.IO (inside
 * FirebaseRepository.sendMessage / onChildAdded), the 50–200 ms PBKDF2
 * work never blocks the main thread.
 *
 * [secretKey] is @Volatile so the lazy initialisation is visible across
 * threads without requiring a full synchronized block on every call.
 *
 * ENCRYPTION
 * ──────────
 * AES-256-GCM with a random 96-bit IV per message.  GCM provides both
 * confidentiality and integrity — a tampered ciphertext will fail
 * authentication and be rejected before any plaintext is used.
 *
 * WIRE FORMAT
 * ───────────
 * Base64( IV[12 bytes] || GCM-ciphertext[n+16 bytes] )
 *
 * The result is a plain Base64 string that fits in any Firebase string field.
 * Encrypted fields replace originalText / translatedText in ChatMessage.
 *
 * USAGE
 * ─────
 *   val crypto = RoomCrypto(roomCode)         // cheap — no work yet
 *   val token  = crypto.encrypt("Hello")      // derives key on first call
 *   val plain  = crypto.decrypt(token)        // reuses derived key
 *
 * decrypt() returns null if the input is not a valid encrypted token (wrong
 * key, truncated data, tampered bytes) — callers should treat null as an
 * unreadable message and show a placeholder in the UI.
 */
class RoomCrypto(private val roomCode: String) {

    // Lazy: null until first encrypt/decrypt call.
    // @Volatile ensures the write is visible to all threads once set.
    @Volatile private var _secretKey: SecretKey? = null

    private val secretKey: SecretKey
        get() = _secretKey ?: deriveKey(roomCode).also { _secretKey = it }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Encrypts [plaintext] and returns a Base64-encoded token safe for
     * storage in Firebase.  Never returns null.
     *
     * The first call triggers PBKDF2 key derivation (~50–200 ms); subsequent
     * calls reuse the cached key and are fast.
     */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }

        val cipher = aesCipher().apply {
            init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        // Prepend IV so the receiver can reconstruct GCMParameterSpec
        val payload = iv + ciphertext
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /**
     * Decrypts a token produced by [encrypt].
     * Returns the original plaintext, or **null** if decryption fails for any
     * reason (wrong key, corrupted data, authentication tag mismatch).
     *
     * The first call triggers PBKDF2 key derivation if not already cached.
     */
    fun decrypt(token: String): String? {
        return try {
            val payload = Base64.decode(token, Base64.NO_WRAP)
            if (payload.size <= IV_LENGTH_BYTES) return null

            val iv         = payload.copyOfRange(0, IV_LENGTH_BYTES)
            val ciphertext = payload.copyOfRange(IV_LENGTH_BYTES, payload.size)

            val cipher = aesCipher().apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            // AEADBadTagException  → tampered data or wrong key
            // IllegalArgumentException → malformed Base64
            // Any other JCE failure
            null
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun aesCipher(): Cipher = Cipher.getInstance(TRANSFORMATION)

    companion object {
        private const val TRANSFORMATION    = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM     = "AES"
        private const val PBKDF2_ALGORITHM  = "PBKDF2WithHmacSHA256"
        private const val IV_LENGTH_BYTES   = 12      // 96-bit IV — NIST recommendation for GCM
        private const val GCM_TAG_BITS      = 128     // maximum authentication tag length
        private const val KEY_LENGTH_BITS   = 256
        private const val PBKDF2_ITERATIONS = 100_000

        /**
         * Fixed, app-specific salt.
         * Using the package name keeps this constant without a secrets file.
         * It is NOT secret — its purpose is to prevent precomputed rainbow
         * tables, which is satisfied even with a known salt at 100k iterations.
         */
        private val SALT: ByteArray =
            "com.example.groqtranscriber.roomkey".toByteArray(Charsets.UTF_8)

        private fun deriveKey(roomCode: String): SecretKey {
            // Normalise to ensure CREATE and JOIN sides always derive the same key
            val password = roomCode.trim().uppercase().toCharArray()
            val spec     = PBEKeySpec(password, SALT, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
            val factory  = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, KEY_ALGORITHM)
        }
    }
}
