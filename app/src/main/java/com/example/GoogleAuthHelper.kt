package com.example

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task

object GoogleAuthHelper {

    private const val TAG = "GoogleAuthHelper"

    /**
     * Build GoogleSignInClient with GoogleSignInOptions.DEFAULT_SIGN_IN,
     * requesting email and ID token.
     */
    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        var clientId = context.getString(R.string.default_web_client_id)
        if (clientId.isBlank() || clientId == "YOUR_WEB_CLIENT_ID") {
            clientId = "YOUR_WEB_CLIENT_ID"
        }

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()

        // Only attach requestIdToken if a realistic OAuth client ID is provided
        if (clientId.isNotBlank() && clientId != "YOUR_WEB_CLIENT_ID") {
            gsoBuilder.requestIdToken(clientId)
        }

        return GoogleSignIn.getClient(context, gsoBuilder.build())
    }

    /**
     * Prepares and launches sign-in intent after signing out previous session
     * so that the account chooser bottom sheet is always displayed.
     */
    fun startSignIn(
        client: GoogleSignInClient,
        launcher: (Intent) -> Unit
    ) {
        client.signOut().addOnCompleteListener {
            val signInIntent = client.signInIntent
            launcher(signInIntent)
        }
    }

    /**
     * Parses the result intent from registerForActivityResult.
     * ZERO hardcoded or dummy fallback emails.
     * Returns GoogleSignInAccount on success or null on failure/cancellation.
     */
    fun handleSignInResult(
        data: Intent?,
        onSuccess: (account: GoogleSignInAccount) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        if (data == null) {
            Log.w(TAG, "Sign-in result data intent was null")
            onError("Sign-in cancelled or returned no data")
            return
        }

        val task: Task<GoogleSignInAccount> = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null && !account.email.isNullOrBlank()) {
                Log.d(TAG, "Successfully signed in via Google Play Services: ${account.email}")
                onSuccess(account)
            } else {
                Log.w(TAG, "Account result was null or missing email")
                onError("No Google account selected")
            }
        } catch (e: ApiException) {
            val statusCode = e.statusCode
            Log.w(TAG, "Google Sign-In failed with status code $statusCode: ${e.message}")
            val message = when (statusCode) {
                12501 -> "Sign-in cancelled"
                12500 -> "Google Play Services sign-in error (12500)"
                7 -> "Network error connecting to Google Play Services"
                else -> "Google Sign-In failed (code $statusCode)"
            }
            onError(message)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error parsing Google Sign-In result", e)
            onError(e.message ?: "Authentication failed")
        }
    }

    /**
     * Sign out current Google account and clear local preferences.
     */
    fun signOut(
        context: Context,
        client: GoogleSignInClient,
        onComplete: () -> Unit
    ) {
        client.signOut().addOnCompleteListener {
            BrowserPreferences.clearSyncedAccount(context)
            onComplete()
        }
    }
}
