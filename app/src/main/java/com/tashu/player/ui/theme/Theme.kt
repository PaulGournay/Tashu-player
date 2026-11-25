package com.tashu.player.ui.theme

import androidx.compose.ui.text.font.Font
import com.tashu.player.R
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.materialkolor.PaletteStyle
import com.tashu.player.domain.model.ThemeColorSource
import com.tashu.player.domain.model.ThemeMode
import com.tashu.player.domain.model.ThemeSettings
import com.tashu.player.domain.model.ThemeStyle
import com.materialkolor.rememberDynamicColorScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Couleur de repli quand aucune pochette n'est disponible : bleu-gris "bande magnétique". */
val DefaultSeed = Color(0xFF4F6D7A)

/** Réglages de thème choisis dans le menu, fournis par [com.tashu.player.ui.MainActivity]. */
val LocalThemeSettings = staticCompositionLocalOf { ThemeSettings() }

@Composable
fun isAppInDarkTheme(): Boolean = when (LocalThemeSettings.current.mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * Material You : une couleur source (pochette via Palette API, fond d'écran sur Android 12+,
 * ou couleur fixe, selon les réglages) alimente un schéma tonal Material 3 complet (HCT, via
 * MaterialKolor). La couleur source est animée : le thème glisse d'une pochette à l'autre en ~600 ms.
 */
@Composable
fun TashuTheme(
    seed: Color = DefaultSeed,
    content: @Composable () -> Unit,
) {
    val settings = LocalThemeSettings.current
    val darkTheme = isAppInDarkTheme()
    val source = when (settings.colorSource) {
        ThemeColorSource.ARTWORK -> seed
        ThemeColorSource.FIXED -> Color(settings.fixedColor)
        ThemeColorSource.WALLPAPER ->
            if (Build.VERSION.SDK_INT >= 31) colorResource(android.R.color.system_accent1_500) else seed
    }
    val animatedSeed by animateColorAsState(source, tween(600), label = "seed")
    val scheme = rememberDynamicColorScheme(
        seedColor = animatedSeed,
        isDark = darkTheme,
        isAmoled = darkTheme && settings.amoled,
        style = settings.style.toPaletteStyle(),
    )
    MaterialTheme(colorScheme = scheme, typography = TashuTypography, content = content)
}

private fun ThemeStyle.toPaletteStyle(): PaletteStyle = when (this) {
    ThemeStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
    ThemeStyle.VIBRANT -> PaletteStyle.Vibrant
    ThemeStyle.EXPRESSIVE -> PaletteStyle.Expressive
    ThemeStyle.NEUTRAL -> PaletteStyle.Neutral
    ThemeStyle.FIDELITY -> PaletteStyle.Fidelity
    ThemeStyle.RAINBOW -> PaletteStyle.Rainbow
    ThemeStyle.MONOCHROME -> PaletteStyle.Monochrome
}

/**
 * Inter partout : grotesque neutre et nette, très proche du SF d'Apple (licence SIL OFL).
 * Approche « iOS » : interlettrage resserré sur les grandes tailles, titres en semi-gras.
 * Échelle typographique : 34 / 24 / 18 / 15 / 13.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_semibold, FontWeight.Bold),
)
val TashuTypography = Typography().run {
    fun TextStyle.inter(tracking: Float = letterSpacing.value) = copy(fontFamily = Inter, letterSpacing = tracking.sp)
    copy(
        displayLarge = displayLarge.inter(-1.2f), displayMedium = displayMedium.inter(-1f),
        displaySmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
        headlineLarge = headlineLarge.inter(-0.6f), headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold).inter(-0.5f),
        headlineSmall = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold).inter(-0.4f),
        titleMedium = titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold).inter(-0.2f),
        titleSmall = titleSmall.inter(-0.1f),
        bodyLarge = bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp).inter(-0.2f),
        bodyMedium = bodyMedium.inter(-0.1f), bodySmall = bodySmall.inter(0f),
        labelLarge = labelLarge.inter(-0.1f),
        labelMedium = labelMedium.copy(fontSize = 13.sp).inter(0f),
        labelSmall = labelSmall.inter(0.1f),
    )
}

/** Extrait la couleur source d'une pochette locale (décodage sous-échantillonné, hors main thread). */
@Composable
fun rememberArtworkSeed(artworkPath: String?): State<Color> = produceState(DefaultSeed, artworkPath) {
    value = withContext(Dispatchers.Default) {
        artworkPath ?: return@withContext DefaultSeed
        runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bmp = BitmapFactory.decodeFile(artworkPath, opts) ?: return@withContext DefaultSeed
            val palette = Palette.from(bmp).maximumColorCount(16).generate()
            bmp.recycle()
            val swatch = palette.vibrantSwatch ?: palette.mutedSwatch ?: palette.dominantSwatch
            swatch?.let { Color(it.rgb) } ?: DefaultSeed
        }.getOrDefault(DefaultSeed)
    }
}

/** Couleurs marquantes d'une pochette (vibrante, claire, sombre, atténuée, dominante), sans doublons. */
@Composable
fun rememberArtworkPalette(artworkPath: String?): State<List<Color>> = produceState(emptyList<Color>(), artworkPath) {
    value = withContext(Dispatchers.Default) {
        artworkPath ?: return@withContext emptyList()
        runCatching {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bmp = BitmapFactory.decodeFile(artworkPath, opts) ?: return@withContext emptyList()
            val p = Palette.from(bmp).maximumColorCount(24).generate()
            bmp.recycle()
            listOfNotNull(p.vibrantSwatch, p.lightVibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch, p.lightMutedSwatch, p.dominantSwatch)
                .map { Color(it.rgb) }
                .distinct()
        }.getOrDefault(emptyList())
    }
}
