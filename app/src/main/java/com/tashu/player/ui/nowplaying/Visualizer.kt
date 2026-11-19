package com.tashu.player.ui.nowplaying

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tashu.player.playback.dsp.SpectrumAnalyzer
import com.tashu.player.ui.theme.rememberArtworkPalette
import kotlin.math.max
import kotlin.math.pow

/** Pochette qui se retourne au toucher : [front] d'un côté, [back] de l'autre. */
@Composable
fun FlippableArtwork(
    flipped: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
    front: @Composable () -> Unit,
    back: @Composable () -> Unit,
) {
    val rotation by animateFloatAsState(if (flipped) 180f else 0f, tween(520), label = "coverFlip")
    val noRipple = remember { MutableInteractionSource() }
    Box(
        modifier
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 14f * density
            }
            .clickable(interactionSource = noRipple, indication = null, onClick = onToggle),
    ) {
        if (rotation <= 90f) front()
        else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { back() }
    }
}

/**
 * Visualiseur au dos de la pochette : barres de spectre en miroir autour de la ligne médiane,
 * dégradé construit avec les couleurs extraites de la pochette, halo qui pulse avec les basses,
 * fond = pochette floutée et assombrie. Montée rapide, retombée douce, petits repères de crête.
 */
@Composable
fun CoverVisualizer(artworkPath: String?, analyzer: SpectrumAnalyzer, modifier: Modifier = Modifier) {
    val palette by rememberArtworkPalette(artworkPath)
    val fallback = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.secondary)
    val colors = palette.ifEmpty { fallback }

    // L'analyse ne tourne que pendant que le visualiseur est affiché.
    DisposableEffect(analyzer) {
        analyzer.active = true
        onDispose { analyzer.active = false }
    }

    val n = SpectrumAnalyzer.BAND_COUNT
    val level = remember { FloatArray(n) }
    val peak = remember { FloatArray(n) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(analyzer) {
        while (true) {
            withFrameMillis { t ->
                // Données périmées (pause, fin de piste) : les barres retombent à zéro.
                val fresh = SystemClock.uptimeMillis() - analyzer.updatedAt < 250
                val src = analyzer.bands
                for (i in 0 until n) {
                    val target = if (fresh) src[i] else 0f
                    level[i] = if (target > level[i]) level[i] + (target - level[i]) * 0.55f else level[i] * 0.86f + target * 0.14f
                    peak[i] = max(level[i], peak[i] - 0.006f)
                }
                frame = t
            }
        }
    }

    Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
        if (artworkPath != null) {
            AsyncImage(
                model = artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier)
                    .graphicsLayer { alpha = 0.45f },
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.65f)))))

        Canvas(Modifier.fillMaxSize()) {
            if (frame < 0L) return@Canvas // lecture de [frame] : redessin à chaque image
            val w = size.width
            val h = size.height
            val cy = h / 2f
            val step = w * 0.9f / n
            val barW = step * 0.62f
            val left = w * 0.05f

            // Halo central piloté par les basses.
            val bass = (0 until 6).sumOf { level[it].toDouble() }.toFloat() / 6f
            drawCircle(
                Brush.radialGradient(
                    listOf(colors.first().copy(alpha = 0.18f + 0.45f * bass), Color.Transparent),
                    center = Offset(w / 2f, cy), radius = w * (0.35f + 0.25f * bass),
                ),
                radius = w * (0.35f + 0.25f * bass), center = Offset(w / 2f, cy),
            )

            for (i in 0 until n) {
                val color = paletteAt(colors, i / (n - 1f))
                val v = level[i].pow(1.35f)
                val half = max(2.dp.toPx(), v * h * 0.40f)
                val x = left + i * step + (step - barW) / 2f
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.35f), color, color.copy(alpha = 0.35f)),
                        startY = cy - half, endY = cy + half,
                    ),
                    topLeft = Offset(x, cy - half),
                    size = Size(barW, half * 2f),
                    cornerRadius = CornerRadius(barW / 2f),
                )
                // Repères de crête, en haut et en bas.
                val p = max(half, peak[i].pow(1.35f) * h * 0.40f) + 3.dp.toPx()
                drawRoundRect(color.copy(alpha = 0.8f), Offset(x, cy - p - 2.dp.toPx()), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                drawRoundRect(color.copy(alpha = 0.4f), Offset(x, cy + p), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
            }
        }
    }
}

/** Couleur interpolée le long de la palette (graves à gauche -> aigus à droite). */
private fun paletteAt(colors: List<Color>, t: Float): Color {
    if (colors.size == 1) return colors[0]
    val pos = t.coerceIn(0f, 1f) * (colors.size - 1)
    val i = pos.toInt().coerceAtMost(colors.size - 2)
    return lerp(colors[i], colors[i + 1], pos - i)
}

