package com.tashu.player.ui

import android.Manifest
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.tashu.player.drive.auth.AuthState
import com.tashu.player.ui.navigation.TashuNavHost
import com.tashu.player.ui.navigation.Routes
import com.tashu.player.domain.model.ThemeSettings
import com.tashu.player.domain.repository.SettingsRepository
import com.tashu.player.ui.theme.LocalMusicPulse
import com.tashu.player.ui.theme.LocalThemeSettings
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.isAppInDarkTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.tashu.player.drive.auth.GoogleAuthManager
import com.tashu.player.playback.PlayerConnection
import com.tashu.player.playback.dsp.MusicPulse

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var auth: GoogleAuthManager
    @Inject lateinit var player: PlayerConnection
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var pulse: MusicPulse

    private val consentLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        auth.onConsentResult(result.data)
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        player.connect()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            val authState by auth.state.collectAsStateWithLifecycle()
            val theme by settings.themeSettings.collectAsStateWithLifecycle(ThemeSettings())
            val nav = rememberNavController()

            // Consentement drive.readonly demandé par Google (première connexion ou grant révoqué).
            LaunchedEffect(authState) {
                (authState as? AuthState.NeedsConsent)?.let {
                    consentLauncher.launch(IntentSenderRequest.Builder(it.pendingIntent.intentSender).build())
                }
            }
            LaunchedEffect(Unit) {
                if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) nav.navigate(Routes.NOW_PLAYING)
            }

            CompositionLocalProvider(LocalThemeSettings provides theme, LocalMusicPulse provides pulse) {
                // Icônes des barres système claires ou sombres selon le mode choisi dans le menu.
                val dark = isAppInDarkTheme()
                LaunchedEffect(dark) {
                    val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                TashuTheme {
                    TashuNavHost(nav, signedIn = authState is AuthState.SignedIn)
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
