package com.tashu.player.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
 * [tint] assombrit à peine la vitre pour garder le texte lisible.
 */
fun Modifier.liquidGlass(
    backdrop: GlassBackdrop,
    corner: Dp = Dp.Infinity,
    tint: Color = Color.Black.copy(alpha = 0.10f),
    lens: Dp = 22.dp,
    frost: Dp = 0.dp,
): Modifier = composed {
    if (isMinimal()) {
        // Minimaliste : aplat sombre et liseré fin, sans réfraction ni reflets.
        val shape = if (corner == Dp.Infinity) CircleShape else RoundedCornerShape(corner)
        return@composed this.clip(shape).background(MinimalSurface).border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
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
            drawRoundRect(if (shader == null) Color.Black.copy(alpha = 0.35f) else tint, cornerRadius = cr)
            // Ombre interne : le verre paraît épais.
            drawRoundRect(
                Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.10f)),
                cornerRadius = cr,
            )
            // Liseré spéculaire : vif en haut à gauche, contre-reflet en bas à droite, presque éteint ailleurs.
            val stroke = 1.2.dp.toPx()
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
    if (isMinimal()) {
        return@composed this.background(Brush.verticalGradient(0f to MinimalBackground, 0.75f to MinimalBackground, 1f to Color.Transparent))
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
            // Léger voile pour que le titre ressorte, sans bande noire.
            drawRect(Color.Black.copy(alpha = 0.12f))
            drawRect(
                Brush.verticalGradient(0f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent),
                blendMode = BlendMode.DstIn,
            )
            drawContent()
        }
}

internal val GlassBorder = Color.White.copy(alpha = 0.14f)

/** Couleurs du mode minimaliste : fond presque noir, surfaces gris anthracite (proches d'iOS). */
internal val MinimalBackground = Color(0xFF0B0B0D)
internal val MinimalSurface = Color(0xFF1C1C1E)

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
    ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size((size / 2).dp)) }
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

// ---------------------------------------------------------------- Nuage de pochette

/**
 * Fond vivant : de grandes nappes de couleur tirées de la pochette dérivent lentement sur tout l'écran,
 * par-dessus la pochette elle-même très floutée et en lente rotation. Les couleurs glissent d'un morceau à l'autre.
 */
@Composable
internal fun ArtworkCloud(artworkPath: String?, modifier: Modifier = Modifier) {
    if (isMinimal()) {
        Box(modifier.fillMaxSize().background(MinimalBackground))
        return
    }
    val palette by rememberArtworkPalette(artworkPath)
    val base = palette.ifEmpty { listOf(Color(0xFF2B3A55), Color(0xFF4F6D7A), Color(0xFF3A2B55)) }
    val colors = List(5) { i -> animateColorAsState(base[i % base.size], tween(1600), label = "cloud$i").value }
    // Une boucle de 60 s ; les fréquences entières gardent le mouvement continu au raccord.
    val t by rememberInfiniteTransition(label = "cloud").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t",
    )
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // Carré plus grand que la diagonale de l'écran : en tournant, il couvre toujours tout (pas de coin visible).
        val side = sqrt(maxWidth.value * maxWidth.value + maxHeight.value * maxHeight.value).dp * 1.15f
        if (artworkPath != null && Build.VERSION.SDK_INT >= 31) {
            Crossfade(
                artworkPath, animationSpec = tween(1200), label = "cloudArt",
                modifier = Modifier.wrapContentSize(unbounded = true).requiredSize(side),
            ) { path ->
                AsyncImage(
                    model = path, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = t * (180f / PI.toFloat())
                            alpha = 0.5f
                        }
                        .blur(90.dp, BlurredEdgeTreatment.Rectangle),
                )
            }
        }
        val fx = intArrayOf(1, 2, 1, 3, 2)
        val fy = intArrayOf(1, 1, 2, 1, 3)
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = maxOf(w, h) * 0.55f
            colors.forEachIndexed { i, c ->
                val ph = i * 1.3f
                val center = Offset(
                    w * (0.5f + 0.38f * sin(t * fx[i] + ph)),
                    h * (0.45f + 0.36f * cos(t * fy[i] + ph * 1.7f)),
                )
                drawCircle(
                    Brush.radialGradient(listOf(c.copy(alpha = 0.42f), Color.Transparent), center = center, radius = r),
                    radius = r, center = center, blendMode = BlendMode.Screen,
                )
            }
        }
        // Voile pour garder le texte lisible, plus dense en bas (barre de navigation).
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.25f),
                    0.55f to Color.Black.copy(alpha = 0.50f),
                    1f to Color.Black.copy(alpha = 0.78f),
                )
            )
        )
    }
}
