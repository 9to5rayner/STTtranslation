package com.example.groqtranscriber.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.groqtranscriber.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

/**
 * Single entry point for the app — Google Sign-In only.
 *
 * Why Google-only:
 *  - No password storage/reset flow to build or maintain.
 *  - Google accounts are pre-verified — Firebase reports
 *    [FirebaseUser.isEmailVerified] = true automatically, so the manual
 *    "check your inbox" gate that an email/password flow would need is
 *    not required here.
 *  - [FirebaseUser.displayName] is populated automatically from the
 *    Google profile — no separate nickname prompt is needed; it becomes
 *    the single source of truth for the name shown on chat bubbles,
 *    exactly as it did for email/password registration previously.
 *
 * WEB_CLIENT_ID
 * ─────────────
 * GoogleSignInOptions.requestIdToken() requires the **Web** OAuth client ID
 * (type 3), not the Android client ID — this is intentional and is how
 * Firebase verifies the token server-side. It comes from the
 * "oauth_client" array in google-services.json once Google Sign-In is
 * enabled in the Firebase Console.
 *
 * The value below was pulled from the google-services.json for project
 * "kiyolatte" — already in place, no action needed unless credentials are
 * later rotated in the Firebase Console.
 */
class AuthActivity : AppCompatActivity() {

    private lateinit var auth:               FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var tvStatus:        TextView
    private lateinit var btnGoogleSignIn: Button

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                handleGoogleSignInResult(task)
            } else {
                // User dismissed the account picker — not an error, just no-op.
                setLoading(false)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)

        auth = FirebaseAuth.getInstance()

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        tvStatus        = findViewById(R.id.tvAuthTitle)
        btnGoogleSignIn = findViewById(R.id.btnGoogleSignIn)

        btnGoogleSignIn.setOnClickListener { launchGoogleSignIn() }

        // If a session already exists, skip straight past this screen.
        // (Google accounts are always verified, so no reload()/isEmailVerified
        // check is needed here — unlike the previous email/password flow.)
        if (auth.currentUser != null) {
            proceedToApp()
        }
    }

    // ── Google Sign-In ───────────────────────────────────────────────────────

    private fun launchGoogleSignIn() {
        setLoading(true)
        // Force the account picker to show every time, rather than silently
        // re-using the last Google account — matters after an explicit
        // sign-out (see RoomActivity.btnSignOut), which also calls
        // googleSignInClient.signOut() for the same reason.
        googleSignInClient.signOut().addOnCompleteListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun handleGoogleSignInResult(
        task: com.google.android.gms.tasks.Task<com.google.android.gms.auth.api.signin.GoogleSignInAccount>
    ) {
        try {
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken == null) {
                setLoading(false)
                toast("Google sign-in failed — no ID token returned.")
                return
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener {
                    setLoading(false)
                    proceedToApp()
                }
                .addOnFailureListener { e ->
                    setLoading(false)
                    toast("Sign-in failed: ${e.message ?: "unknown error"}")
                }
        } catch (e: ApiException) {
            setLoading(false)
            // Status code 10 = DEVELOPER_ERROR, almost always a wrong/missing
            // WEB_CLIENT_ID or a SHA-1 fingerprint not registered in the
            // Firebase Console for this app.
            toast("Google sign-in error (code ${e.statusCode}).")
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private fun proceedToApp() {
        startActivity(Intent(this, LaunchActivity::class.java))
        finish()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setLoading(loading: Boolean) {
        btnGoogleSignIn.isEnabled = !loading
        tvStatus.text = if (loading) "Signing in…" else "Welcome"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        /**
         * Web OAuth client ID, pulled from google-services.json
         * ("client_type": 3 entry in the "oauth_client" array).
         * This must match the value Firebase generated when Google Sign-In
         * was enabled for project "kiyolatte" — if you ever regenerate
         * google-services.json (e.g. after rotating credentials), update
         * this string to match the new file's type-3 client_id.
         */
        private const val WEB_CLIENT_ID =
            "99859064289-q5ntou392sqsja1ed75163dm4l91mtfg.apps.googleusercontent.com"
    }
}
