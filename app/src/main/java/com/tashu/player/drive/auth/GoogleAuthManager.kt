package com.tashu.player.drive.auth

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.ClearCredentialStateRequest
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.tashu.player.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object SignedOut : AuthState
    /** Identité OK, mais consentement drive.readonly à demander (PendingIntent à lancer). */
    data class NeedsConsent(val pendingIntent: PendingIntent) : AuthState
    data class SignedIn(val email: String, val displayName: String?) : AuthState
}

/** Levée quand un consentement interactif est requis (ex : token révoqué) hors UI. */
class AuthRequiredException(msg: String) : IOException(msg)

/**
 * Authentification en deux temps (recommandation Google 2024+) :
 *  1. **Authentification** : Credential Manager + "Sign in with Google" → identité (ID token).
 *  2. **Autorisation** : `AuthorizationClient` → access token OAuth 2.0 scope `drive.readonly`.
 *
 * Le token d'accès (~1 h) est mis en cache ; [accessToken] le renouvelle silencieusement
 * (Google Play services garde le grant), [invalidate] force un nouveau token après un 401.
 */
@Singleton
class GoogleAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val credentialManager = CredentialManager.create(context)
    private val authorizationClient = Identity.getAuthorizationClient(context)
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenObtainedAt: Long = 0L

    private val _state = MutableStateFlow<AuthState>(
        prefs.getString(KEY_EMAIL, null)?.let { AuthState.SignedIn(it, prefs.getString(KEY_NAME, null)) }
            ?: AuthState.SignedOut
    )
    val state: StateFlow<AuthState> = _state
    private val _error = MutableStateFlow<String?>(null)
    /** Dernier échec d'autorisation Drive, affiché sur l'écran de connexion. */
    val error: StateFlow<String?> = _error

    private val driveScopeRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_READONLY_SCOPE)))
        .build()

    /** Étape 1 : doit être appelé avec un contexte Activity (bottom sheet Credential Manager). */
    suspend fun signIn(activity: Activity) {
        require(BuildConfig.WEB_CLIENT_ID.isNotBlank()) { "TASHU_WEB_CLIENT_ID manquant (voir README)" }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val credential = credentialManager.getCredential(activity, request).credential
        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "Credential inattendu : ${credential.type}"
        }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        prefs.edit().putString(KEY_EMAIL, google.id).putString(KEY_NAME, google.displayName).apply()
        authorize(google.id, google.displayName)
    }

    /** Étape 2 : autorisation Drive. Peut produire [AuthState.NeedsConsent]. */
    private suspend fun authorize(email: String, name: String?) {
        val result = authorizationClient.authorize(driveScopeRequest).await()
        if (result.hasResolution()) {
            _state.value = AuthState.NeedsConsent(result.pendingIntent!!)
        } else {
            storeToken(result)
            _state.value = AuthState.SignedIn(email, name)
        }
    }

    /** À appeler depuis l'ActivityResult de l'écran de consentement. */
    fun onConsentResult(data: Intent?) {
        // Écran fermé, accès refusé ou grant expiré non renouvelé (mode Test : tous les 7 jours) : retour à la connexion.
        val result = try {
            authorizationClient.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            _error.value = "Accès à Google Drive non accordé (code ${e.statusCode}). Reconnectez-vous pour réessayer."
            _state.value = AuthState.SignedOut
            return
        }
        _error.value = null
        storeToken(result)
        val email = prefs.getString(KEY_EMAIL, null) ?: return
        _state.value = AuthState.SignedIn(email, prefs.getString(KEY_NAME, null))
    }

    private fun storeToken(result: AuthorizationResult) {
        cachedToken = result.accessToken
        tokenObtainedAt = System.currentTimeMillis()
    }

    /** Access token valide, rafraîchi silencieusement si proche de l'expiration. Thread-safe. */
    suspend fun accessToken(): String = mutex.withLock {
        val token = cachedToken
        if (token != null && System.currentTimeMillis() - tokenObtainedAt < TOKEN_TTL_MS) return token
        val result = authorizationClient.authorize(driveScopeRequest).await()
        if (result.hasResolution()) {
            _state.value = AuthState.NeedsConsent(result.pendingIntent!!)
            throw AuthRequiredException("Consentement Google Drive requis")
        }
        storeToken(result)
        result.accessToken ?: throw AuthRequiredException("Aucun access token retourné")
    }

    /** Version bloquante pour les threads de chargement ExoPlayer (jamais le main thread). */
    fun accessTokenBlocking(): String = runBlocking(Dispatchers.IO) { accessToken() }

    /** Après un HTTP 401 : purge le token côté Play services et côté cache local. */
    suspend fun invalidate(badToken: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (cachedToken == badToken) {
                cachedToken = null
                runCatching { GoogleAuthUtil.clearToken(context, badToken) }
            }
        }
    }

    fun invalidateBlocking(badToken: String) = runBlocking { invalidate(badToken) }

    suspend fun signOut() {
        cachedToken?.let { t -> withContext(Dispatchers.IO) { runCatching { GoogleAuthUtil.clearToken(context, t) } } }
        cachedToken = null
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
        prefs.edit().clear().apply()
        _state.value = AuthState.SignedOut
    }

    @Suppress("unused")
    fun account(): Account? = prefs.getString(KEY_EMAIL, null)?.let { Account(it, "com.google") }

    companion object {
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_TTL_MS = 45 * 60 * 1000L
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "name"
    }
}
