package com.tashu.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.scale
import com.tashu.player.domain.model.BackgroundStyle
import com.tashu.player.playback.dsp.MusicPulse
import android.os.SystemClock
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import kotlin.math.exp
import com.tashu.player.domain.model.ThemeColorSource
import com.tashu.player.domain.model.ThemeStyle
import com.tashu.player.domain.model.UiStyle
import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import kotlin.math.sqrt
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- Verre « Clear » (Liquid Glass d'iOS)

/**
 * Ce que le verre laisse voir : le contenu enregistré par [glassSource]. Les éléments en verre
 * le redessinent à travers une lentille (réfraction sur les bords), sans le flouter.
 */
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/** Marque le contenu qui défile sous le verre (fond, listes). */
fun Modifier.glassSource(backdrop: GlassBackdrop): Modifier = this
    .onGloballyPositioned { backdrop.origin = it.positionInWindow() }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Lentille AGSL : à l'intérieur de la forme, le fond est net ; près du bord il est « tiré » vers le centre
 * selon un profil de dôme, comme à travers l'épaisseur d'un verre bombé, avec une légère dispersion des couleurs.
 */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float depth;
uniform float strength;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

half4 main(float2 coord) {
    float2 c = size * 0.5;
    float2 p = coord - c;
    float d = -sdRoundRect(p, c, radius);
    half4 base = content.eval(coord);
    if (d >= depth || d < 0.0) {
        // Centre : vitre parfaitement nette, juste un peu plus vive.
        float l = dot(base.rgb, half3(0.299, 0.587, 0.114));
        return half4(mix(half3(l), base.rgb, 1.12), base.a);
    }
    float e = 0.75;
    float2 n = normalize(float2(
        sdRoundRect(p + float2(e, 0.0), c, radius) - sdRoundRect(p - float2(e, 0.0), c, radius),
        sdRoundRect(p + float2(0.0, e), c, radius) - sdRoundRect(p - float2(0.0, e), c, radius)) + 1e-5);
    float t = 1.0 - clamp(d / depth, 0.0, 1.0);
    float bend = strength * (1.0 - sqrt(max(1.0 - t * t, 0.0)));
    float2 off = -n * bend;
    half4 col;
    col.r = content.eval(coord + off * 1.10).r;
    col.g = content.eval(coord + off).g;
    col.b = content.eval(coord + off * 0.90).b;
    col.a = content.eval(coord + off).a;
    float l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    return half4(mix(half3(l), col.rgb, 1.12), col.a);
}
"""

/**
 * Verre « Clear » : fond net vu à travers une lentille, liseré lumineux (reflet en haut à gauche,
 * contre-reflet en bas à droite) et ombre interne légère. [corner] = Dp.Infinity pour une pilule ou un cercle.
 * [veil] voile à peine la vitre pour garder le texte lisible (fumé en mode sombre, blanc en mode clair).
 */
fun Modifier.liquidGlass(
    backdrop: GlassBackdrop,
    corner: Dp = Dp.Infinity,
    veil: Float = 0.10f,
    lens: Dp = 22.dp,
    frost: Dp = 0.dp,
): Modifier = composed {
    val g = glassColors()
    if (isMinimal()) {
        // Minimaliste : aplat et liseré fin, sans réfraction ni reflets.
        val shape = if (corner == Dp.Infinity) CircleShape else RoundedCornerShape(corner)
        return@composed this.clip(shape).background(g.flatSurface).border(0.5.dp, g.line(0.10f), shape)
    }
    val inner = rememberGraphicsLayer()
    var pos by remember { mutableStateOf(Offset.Zero) }
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) RuntimeShader(LENS_SHADER) else null }
    var effectKey by remember { mutableStateOf<List<Float>?>(null) }
    this
        .onGloballyPositioned { pos = it.positionInWindow() }
        .drawWithContent {
            val w = size.width
            val h = size.height
            val r = minOf(corner.toPx(), minOf(w, h) / 2f)
            val depth = minOf(lens.toPx(), minOf(w, h) / 2f)
            if (shader != null && Build.VERSION.SDK_INT >= 33) {
                val key = listOf(w, h, r, depth, frost.value)
                if (key != effectKey) {
                    shader.setFloatUniform("size", w, h)
                    shader.setFloatUniform("radius", r)
                    shader.setFloatUniform("depth", depth)
                    shader.setFloatUniform("strength", depth * 1.15f)
                    val lensFx = AndroidRenderEffect.createRuntimeShaderEffect(shader, "content")
                    // Dépoli optionnel, appliqué AVANT la lentille, pour les panneaux qui portent beaucoup de texte.
                    val fx = if (frost > 0.dp) {
                        val px = frost.toPx()
                        AndroidRenderEffect.createChainEffect(lensFx, AndroidRenderEffect.createBlurEffect(px, px, android.graphics.Shader.TileMode.CLAMP))
                    } else lensFx
                    inner.renderEffect = fx.asComposeRenderEffect()
                    effectKey = key
                }
            }
            inner.setRoundRectOutline(Offset.Zero, size, r)
            inner.clip = true
            inner.record {
                translate(backdrop.origin.x - pos.x, backdrop.origin.y - pos.y) { drawLayer(backdrop.layer) }
            }
            drawLayer(inner)
            val cr = CornerRadius(r)
            // Sans lentille (Android < 13), un voile plus marqué remplace la réfraction.
            drawRoundRect(g.veil(if (shader == null) 0.35f else veil), cornerRadius = cr)
            // Ombre interne : le verre paraît épais.
            drawRoundRect(
                Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.10f)),
                cornerRadius = cr,
            )
            val stroke = 1.2.dp.toPx()
            // Verre clair : un contour sombre très léger détache la vitre d'un fond presque blanc.
            if (!g.dark) drawRoundRect(g.line(0.12f), topLeft = Offset(stroke / 2, stroke / 2), size = Size(w - stroke, h - stroke), cornerRadius = CornerRadius(maxOf(r - stroke / 2, 0f)), style = Stroke(stroke))
            // Liseré spéculaire : vif en haut à gauche, contre-reflet en bas à droite, presque éteint ailleurs.
            drawRoundRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.75f),
                    0.28f to Color.White.copy(alpha = 0.10f),
                    0.65f to Color.White.copy(alpha = 0.04f),
                    1f to Color.White.copy(alpha = 0.45f),
                    start = Offset.Zero, end = Offset(w, h),
                ),
                topLeft = Offset(stroke / 2, stroke / 2), size = Size(w - stroke, h - stroke),
                cornerRadius = CornerRadius(maxOf(r - stroke / 2, 0f)), style = Stroke(stroke),
            )
            drawContent()
        }
}

/**
 * Bord de défilement (iOS 26) : redessine le fond très flouté, avec un masque qui va d'opaque (haut)
 * à transparent (bas). Le contenu qui passe dessous se dissout dans le flou au lieu d'être coupé net.
 */
fun Modifier.scrollEdgeBlur(backdrop: GlassBackdrop, radius: Dp = 28.dp): Modifier = composed {
    val g = glassColors()
    if (isMinimal()) {
        return@composed this.background(Brush.verticalGradient(0f to g.flat, 0.75f to g.flat, 1f to Color.Transparent))
    }
    val inner = rememberGraphicsLayer()
    var pos by remember { mutableStateOf(Offset.Zero) }
    this
        .onGloballyPositioned { pos = it.positionInWindow() }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            if (Build.VERSION.SDK_INT >= 31) {
                val px = radius.toPx()
                inner.renderEffect = AndroidRenderEffect.createBlurEffect(px, px, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
            }
            inner.record { translate(backdrop.origin.x - pos.x, backdrop.origin.y - pos.y) { drawLayer(backdrop.layer) } }
            drawLayer(inner)
            // Léger voile pour que le titre ressorte, sans bande marquée.
            drawRect(g.veil(0.12f))
            drawRect(
                Brush.verticalGradient(0f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
            drawContent()
        }
}

/** Couleurs posées sur le fond et le verre : verre fumé et encre blanche en mode sombre, verre dépoli blanc et encre sombre en mode clair. */
@Immutable
internal class GlassColors(val dark: Boolean) {
    /** Texte, icônes et liserés. */
    val ink = if (dark) Color.White else Color(0xFF16161B)
    /** Texte posé sur une pastille pleine couleur d'encre. */
    val onInk = if (dark) Color.Black else Color.White
    /** Sous le fond, visible pendant les transitions. */
    val base = if (dark) Color.Black else Color(0xFFF2F2F6)
    val menu = if (dark) Color(0xFF1C1C1E) else Color(0xFFFAFAFC)
    /** Minimaliste : aplat de fond et surfaces, proches d'iOS. */
    val flat = if (dark) Color(0xFF0B0B0D) else Color(0xFFF2F2F7)
    val flatSurface = if (dark) Color(0xFF1C1C1E) else Color.White

    /** Voile d'une vitre : assombrit en mode sombre, blanchit davantage en mode clair. */
    fun veil(alpha: Float) = if (dark) Color.Black.copy(alpha = alpha) else Color.White.copy(alpha = 0.25f + alpha * 0.6f)
    /** Remplissage d'une carte ou d'une pastille. */
    fun fill(alpha: Float) = Color.White.copy(alpha = if (dark) alpha else (0.2f + alpha * 3f).coerceAtMost(0.85f))
    /** Liseré ou séparateur. */
    fun line(alpha: Float) = if (dark) Color.White.copy(alpha = alpha) else ink.copy(alpha = alpha * 0.7f)
    val border get() = line(0.14f)
}

private val DarkGlass = GlassColors(dark = true)
private val LightGlass = GlassColors(dark = false)

@Composable
internal fun glassColors(): GlassColors = if (isAppInDarkTheme()) DarkGlass else LightGlass

@Composable
internal fun isMinimal(): Boolean = LocalThemeSettings.current.uiStyle == UiStyle.MINIMAL

/** Bouton rond en verre (menu, réduire, actualiser…). */
@Composable
internal fun GlassCircle(haze: GlassBackdrop, icon: ImageVector, label: String, size: Int = 40, onClick: () -> Unit) {
    Box(
        Modifier
            .bubbleClickable(onClick = onClick)
            .size(size.dp)
            .liquidGlass(haze),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = glassColors().ink, modifier = Modifier.size((size / 2).dp)) }
}

// ---------------------------------------------------------------- Effet « bulle »

/** Ressort souple et un peu rebondissant, façon iOS. */
internal fun <T> bubbleSpring(stiffness: Float = Spring.StiffnessMediumLow) = spring<T>(dampingRatio = 0.72f, stiffness = stiffness)

/** Rétrécit l'élément quand on appuie, puis le relâche avec un rebond. */
@Composable
internal fun Modifier.bubbleScale(source: InteractionSource, pressedScale: Float = 0.9f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = if (pressed) 1f else 0.42f, stiffness = if (pressed) Spring.StiffnessHigh else Spring.StiffnessMedium),
        label = "bubble",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Clic avec effet bulle, sans ondulation Material. À placer EN TÊTE de la chaîne de modificateurs
 * pour que le fond, le flou et le liseré rebondissent avec le contenu.
 */
@Composable
internal fun Modifier.bubbleClickable(pressedScale: Float = 0.9f, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return bubbleScale(source, pressedScale).clickable(interactionSource = source, indication = null, onClick = onClick)
}

// ---------------------------------------------------------------- Fond

/** Fond des écrans bibliothèque, album et lecteur, selon le réglage « Fond ». Le style minimaliste impose son aplat. */
@Composable
internal fun AppBackground(artworkPath: String?, modifier: Modifier = Modifier) {
    if (isMinimal()) {
        Box(modifier.fillMaxSize().background(glassColors().flat))
        return
    }
    when (LocalThemeSettings.current.background) {
        BackgroundStyle.CLOUD -> ArtworkCloud(artworkPath, modifier)
        BackgroundStyle.BLURRED_ARTWORK -> BlurredArtwork(artworkPath, modifier)
        BackgroundStyle.PLAIN -> Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
    }
}

/** Saturation appliquée à la pochette de fond selon le style des couleurs (null = couleurs d'origine). */
@Composable
private fun rememberStyleFilter(style: ThemeStyle): ColorFilter? = remember(style) {
    val saturation = when (style) {
        ThemeStyle.MONOCHROME -> 0f
        ThemeStyle.NEUTRAL -> 0.35f
        ThemeStyle.TONAL_SPOT -> 0.85f
        ThemeStyle.EXPRESSIVE -> 1.2f
        ThemeStyle.VIBRANT -> 1.5f
        ThemeStyle.FIDELITY, ThemeStyle.RAINBOW -> return@remember null
    }
    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) })
}

/** Pulsation de la musique jouée par l'app, fournie par [com.tashu.player.ui.MainActivity]. */
val LocalMusicPulse = staticCompositionLocalOf<MusicPulse> { error("MusicPulse non fourni") }

/** Périodes (s) de dérive horizontale, verticale, de grande boucle et de respiration de chaque nappe. */
private val DRIFT_X = floatArrayOf(10f, 15f, 12f, 20f, 8f)
private val DRIFT_Y = floatArrayOf(15f, 12f, 20f, 10f, 24f)
private val WANDER = floatArrayOf(30f, 40f, 24f, 60f, 30f)
private val BREATH = floatArrayOf(6f, 8f, 10f, 12f, 8f)
// Multiple de toutes les périodes ci-dessus : la boucle repart sans à-coup.
private const val CLOUD_LOOP_S = 120
private const val TWO_PI = (2 * PI).toFloat()

/**
 * Nuage vivant : cinq nappes de couleur (pochette, fond d'écran ou couleur fixe, passées au style des couleurs)
 * traversent l'écran et respirent sur des cycles de 6 à 24 s, plus une grande boucle de 24 à 60 s ; avec la pochette, elle-même très floutée tourne lentement dessous.
 * Vitesse réglable ; animation coupée = image fixe, aucune boucle d'images. Option : les nappes gonflent,
 * accélèrent et s'éclairent sur la musique ([MusicPulse]).
 * Rien n'est alloué image par image : chaque nappe garde son dégradé, seule sa matrice change.
 */
@Composable
private fun ArtworkCloud(artworkPath: String?, modifier: Modifier) {
    val settings = LocalThemeSettings.current
    val g = glassColors()
    val deep = g.dark && settings.amoled
    val target = rememberCloudColors(artworkPath)
    val colors = List(target.size) { i ->
        animateColorAsState(if (g.dark) target[i] else lerp(target[i], Color.White, 0.25f), tween(1600), label = "cloud$i").value
    }
    val blobAlpha = when { !g.dark -> 0.8f; deep -> 0.65f; else -> 0.8f }
    val blobs = remember(colors, blobAlpha) { colors.map { CloudBlob(it, blobAlpha) } }
    val blend = if (g.dark) BlendMode.Screen else BlendMode.SrcOver
    val pulse = LocalMusicPulse.current
    val reacts = settings.cloudAnimated && settings.cloudReactsToMusic
    val speed by rememberUpdatedState(settings.cloudSpeed)
    val listening by rememberUpdatedState(reacts)
    // Phase intégrée image par image : changer la vitesse change le rythme, jamais la position des nappes.
    var s by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    if (reacts) {
        DisposableEffect(pulse) {
            pulse.acquire()
            onDispose { pulse.release() }
        }
    }
    LaunchedEffect(settings.cloudAnimated) {
        if (!settings.cloudAnimated) {
            beat = 0f
            return@LaunchedEffect
        }
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { t ->
                val dt = ((t - last) / 1e9f).coerceAtMost(0.1f)
                last = t
                val target = if (listening) pulse.level(SystemClock.uptimeMillis()) else 0f
                beat += (target - beat) * (1f - exp(-dt / if (target > beat) 0.04f else 0.3f))
                s = (s + dt * speed * (1f + 0.8f * beat)) % CLOUD_LOOP_S
            }
        }
    }
    val ground = when { !g.dark -> g.base; deep -> Color.Black; else -> Color(0xFF111116) }
    BoxWithConstraints(modifier.fillMaxSize().background(ground), contentAlignment = Alignment.Center) {
        // Carré plus grand que la diagonale de l'écran : en tournant, il couvre toujours tout (pas de coin visible).
        val side = sqrt(maxWidth.value * maxWidth.value + maxHeight.value * maxHeight.value).dp * 1.15f
        if (settings.colorSource == ThemeColorSource.ARTWORK && artworkPath != null && Build.VERSION.SDK_INT >= 31) {
            val filter = rememberStyleFilter(settings.style)
            val artAlpha = if (g.dark) 0.4f else 0.35f
            Crossfade(
                artworkPath, animationSpec = tween(1200), label = "cloudArt",
                modifier = Modifier.wrapContentSize(unbounded = true).requiredSize(side),
            ) { path ->
                AsyncImage(
                    model = path, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = filter,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = s * 6f
                            alpha = artAlpha
                        }
                        .blur(90.dp, BlurredEdgeTreatment.Rectangle),
                )
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = maxOf(w, h) * 0.3f
            for (i in blobs.indices) {
                val ph = i * 1.3f
                val wander = TWO_PI * s / WANDER[i] + ph * 0.7f
                val cx = w * (0.5f + 0.42f * sin(TWO_PI * s / DRIFT_X[i] + ph) + 0.18f * cos(wander))
                val cy = h * (0.45f + 0.34f * cos(TWO_PI * s / DRIFT_Y[i] + ph * 1.7f) + 0.14f * sin(wander))
                val breath = sin(TWO_PI * s / BREATH[i] + ph * 2.3f)
                val radius = r * (1f + 0.25f * breath) * (1f + 0.2f * beat)
                val blob = blobs[i]
                blob.matrix.setScale(radius, radius)
                blob.matrix.postTranslate(cx, cy)
                blob.shader.setLocalMatrix(blob.matrix)
                drawCircle(blob.brush, radius = radius, center = Offset(cx, cy), alpha = minOf(1f, 0.75f + 0.25f * breath + 0.25f * beat), blendMode = blend)
            }
        }
        // Voile pour garder le texte lisible, plus dense en bas (barre de navigation).
        val veil = when {
            !g.dark -> Brush.verticalGradient(0f to Color.White.copy(alpha = 0.18f), 0.55f to Color.White.copy(alpha = 0.28f), 1f to Color.White.copy(alpha = 0.45f))
            deep -> Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.22f), 0.55f to Color.Black.copy(alpha = 0.42f), 1f to Color.Black.copy(alpha = 0.78f))
            else -> Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.12f), 0.55f to Color.Black.copy(alpha = 0.32f), 1f to Color.Black.copy(alpha = 0.55f))
        }
        // Sur les temps forts, le voile s'éclaircit un peu : le nuage s'illumine.
        Box(Modifier.fillMaxSize().drawBehind { drawRect(veil, alpha = 1f - 0.3f * beat) })
    }
}

/** Une nappe : dégradé radial de rayon 1, placé et agrandi par sa matrice locale à chaque image. */
private class CloudBlob(color: Color, alpha: Float) {
    val shader = android.graphics.RadialGradient(
        0f, 0f, 1f,
        intArrayOf(color.copy(alpha = alpha).toArgb(), color.copy(alpha = alpha * 0.55f).toArgb(), color.copy(alpha = 0f).toArgb()),
        floatArrayOf(0f, 0.5f, 1f), android.graphics.Shader.TileMode.CLAMP,
    )
    val brush = ShaderBrush(shader)
    val matrix = android.graphics.Matrix()
}

/** Pochette floue : la pochette en plein écran, très floutée et voilée vers le bas. */
@Composable
private fun BlurredArtwork(path: String?, modifier: Modifier) {
    val settings = LocalThemeSettings.current
    val g = glassColors()
    val deep = g.dark && settings.amoled
    Box(modifier.fillMaxSize().background(g.base)) {
        if (path != null) {
            AsyncImage(
                model = path, contentDescription = null, contentScale = ContentScale.Crop, colorFilter = rememberStyleFilter(settings.style),
                modifier = Modifier.fillMaxSize().scale(1.3f).then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(36.dp) else Modifier),
            )
        }
        val top = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        val veil = when {
            !g.dark -> Brush.verticalGradient(0f to top, 0.5f to Color.White.copy(alpha = 0.45f), 1f to Color.White.copy(alpha = 0.75f))
            deep -> Brush.verticalGradient(0f to top, 0.5f to Color.Black.copy(alpha = 0.45f), 1f to Color.Black.copy(alpha = 0.85f))
            else -> Brush.verticalGradient(0f to top, 0.5f to Color.Black.copy(alpha = 0.30f), 1f to Color.Black.copy(alpha = 0.65f))
        }
        Box(Modifier.fillMaxSize().background(veil))
    }
}
