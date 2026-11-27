package com.tashu.player.ui.coverflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.PixelCopy
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.Engine
import com.google.android.filament.Fence
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.View
import com.google.android.filament.gltfio.FilamentInstance
import com.tashu.player.domain.model.Album
import com.tashu.player.playback.MediaItemMapper
import com.tashu.player.ui.MainViewModel
import dev.romainguy.kotlin.math.Float2
import dev.romainguy.kotlin.math.Float3
import dev.romainguy.kotlin.math.Float4
import dev.romainguy.kotlin.math.Mat4
import dev.romainguy.kotlin.math.Quaternion
import dev.romainguy.kotlin.math.dot
import dev.romainguy.kotlin.math.inverse
import dev.romainguy.kotlin.math.normalize
import dev.romainguy.kotlin.math.rotation
import dev.romainguy.kotlin.math.scale
import dev.romainguy.kotlin.math.translation
import io.github.sceneview.Scene
import io.github.sceneview.SceneView
import io.github.sceneview.collision.Box as CollisionBox
import io.github.sceneview.collision.Vector3 as CollisionVector
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.loaders.ModelLoader
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import io.github.sceneview.utils.readBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Walkman 3D (« Old Walkman », Charles48900, CC BY 4.0) avec la cassette (« Cassette tapes », FEDDYFAZBARE, CC BY 4.0)
 * déjà rangée dans le compartiment (nœud CassetteRig). Préparés dans Blender : Door (charnière en bas),
 * BtnPlay, CassetteRig > Label (étiquette remplacée par la pochette) / HubL / HubR. Gardé hors du dépôt public.
 */
internal const val WALKMAN_MODEL = "models/walkman.glb"

internal fun walkmanModelAvailable(context: Context): Boolean =
    runCatching { context.assets.list("models")?.contains("walkman.glb") == true }.getOrDefault(false)

/**
 * Chronologie, en secondes (`progress` avance linéairement, chaque geste a sa propre courbe). D'abord [TAPE_HANDOFF] :
 * les autres cassettes du carrousel s'écartent et s'effacent. Ensuite, sur l'horloge de la scène : 0 → 0,9 le walkman
 * entre par la droite, debout, et ouvre sa porte (0,35 → 0,95) ; 0 → 1,45 la cassette vient vers soi puis vole jusqu'au-dessus
 * de la porte ; 1,45 → 1,85 elle glisse dans la porte ; 1,85 → 2,3 la porte se referme (rebond et clic) ; 2,25 → 3,1 le
 * walkman se couche et remplit l'écran. La caméra suit la cassette.
 */
internal const val TAPE_HANDOFF = 0.35f
private const val STAGE_END = 3.1f
internal const val TAPE_LOADED = TAPE_HANDOFF + STAGE_END
private const val TAPE_LOAD_MS = 3450
private const val FLIGHT_END = 1.45f
private const val SLIDE_END = 1.85f
private const val CLOSE_END = 2.3f
private const val LIE_START = 2.25f
/** Instant où la porte claque (retour haptique, dans les deux sens). */
private const val DOOR_SHUT = TAPE_HANDOFF + 2.13f
/** Ouverture de la porte (degrés) et distance de glissement de la cassette dans la porte (mètres). */
private const val DOOR_OPEN_DEG = 38f
private const val SLIDE_IN = 0.12f

/** Focale de la caméra (mm, capteur de 24 mm : champ vertical de 46°). */
private const val FOCAL_MM = 28.0
private const val TAN_HALF_FOV = (12.0 / FOCAL_MM).toFloat()

/** Cassettes 3D du carrousel : une par album de la fenêtre dessinée (instances du modèle, textures et géométrie partagées). */
private const val CAROUSEL_SLOTS = 2 * CAROUSEL_WINDOW + 1
/** Recul (mètres, par album d'écart) des cassettes du carrousel, pour qu'elles se recouvrent comme les pochettes 2D. */
private const val CAROUSEL_DEPTH = 0.04f
/** Fondu des cassettes 2D (affichées pendant le chargement) sur leurs jumelles 3D. */
private const val CARDS_FADE_MS = 200

/**
 * Mode walkman en paysage, dans une seule scène 3D : le carrousel de cassettes (une instance du modèle par album visible),
 * puis, quand on touche la cassette centrale, elle vole jusqu'au walkman qui arrive debout, s'y range, et le walkman se
 * couche et remplit l'écran. Ses commandes : touche orange = lecture / pause, glisser à gauche / à droite = piste suivante /
 * précédente, toucher la porte = éjecter. La liste des titres se tire du bord gauche. Retour / éjection : tout se rejoue à
 * l'envers jusqu'à la place de la cassette dans le carrousel. [carousel] (le carrousel 2D) reçoit les gestes du carrousel et
 * montre ses cassettes dessinées tant que la scène se charge.
 */
@Composable
internal fun WalkmanDeck(
    albums: List<Album>,
    position: Animatable<Float, AnimationVector1D>,
    tapeAlbum: Album?,
    vm: MainViewModel,
    progress: Animatable<Float, AnimationVector1D>,
    coverSize: Dp,
    coverTop: Dp,
    centerGapPx: Float,
    stackGapPx: Float,
    cardsAlpha: Animatable<Float, AnimationVector1D>,
    flipAngles: Map<Long, Float>,
    carousel: @Composable () -> Unit,
    onEjected: () -> Unit,
) {
    val player by vm.player.state.collectAsStateWithLifecycle()
    val albumId = tapeAlbum?.id
    val tracks by remember(albumId) { if (albumId == null) flowOf(emptyList()) else vm.albumTracks(albumId) }
        .collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val hostView = LocalView.current
    // Sans clé : la scène peut appeler l'éjection d'une composition précédente, qui doit lire le même état.
    var ejecting by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var othersGone by remember { mutableStateOf(false) }
    var labelFor by remember { mutableStateOf<Long?>(null) }
    val playingAlbumId by rememberUpdatedState(player.metadata.extras?.getLong(MediaItemMapper.EXTRA_ALBUM_ID, -1L))
    val loaded by remember { derivedStateOf { progress.value >= TAPE_LOADED } }
    val staged by remember(albumId) { derivedStateOf { albumId != null && progress.value > TAPE_HANDOFF } }
    val tapeIndex by rememberUpdatedState(albums.indexOfFirst { it.id == albumId })

    LaunchedEffect(shown) {
        if (shown) cardsAlpha.animateTo(0f, tween(CARDS_FADE_MS, easing = LinearEasing))
    }
    // Tout de suite, les autres cassettes s'écartent ; la cassette touchée part quand la scène et son étiquette sont prêtes.
    LaunchedEffect(albumId) {
        if (albumId != null) progress.animateTo(TAPE_HANDOFF, tween((TAPE_HANDOFF * 1000).roundToInt(), easing = LinearEasing))
    }
    val ready = albumId != null && shown && labelFor == albumId
    LaunchedEffect(albumId, ready) {
        if (!ready) return@LaunchedEffect
        snapshotFlow { abs(position.value - tapeIndex) < 0.01f && progress.value >= TAPE_HANDOFF }.first { it }
        if (!ejecting) progress.animateTo(TAPE_LOADED, tween((STAGE_END * 1000).roundToInt(), easing = LinearEasing))
    }
    LaunchedEffect(albumId, tracks.isNotEmpty()) {
        if (albumId == null || tracks.isEmpty()) return@LaunchedEffect
        snapshotFlow { progress.value >= TAPE_LOADED }.first { it }
        if (!ejecting && playingAlbumId != albumId) vm.play(tracks, 0)
    }
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value >= DOOR_SHUT }.distinctUntilChanged().drop(1).collect {
            hostView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    val drawer = remember { Animatable(0f) }
    fun settleDrawer(open: Boolean) {
        scope.launch { drawer.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) }
    }

    fun eject() {
        if (ejecting) return
        ejecting = true
        settleDrawer(false)
        if (player.isPlaying) vm.player.togglePlay()
        scope.launch {
            progress.animateTo(0f, tween((TAPE_LOAD_MS * 0.85f * progress.value / TAPE_LOADED).roundToInt(), easing = LinearEasing))
            ejecting = false
            onEjected()
        }
    }
    BackHandler(enabled = albumId != null) { eject() }
    val drawerShown by remember { derivedStateOf { drawer.value > 0f || drawer.targetValue > 0f } }
    BackHandler(enabled = drawerShown) { settleDrawer(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val coverPx = with(density) { coverSize.toPx() }
        val card = CardRect(cx = w / 2f, cy = with(density) { coverTop.toPx() } + coverPx / 2f, height = coverPx, viewW = w, viewH = h)
        val controls = loaded && !ejecting

        TapeDeck3D(
            albums = albums,
            position = position,
            centerGapPx = centerGapPx,
            stackGapPx = stackGapPx,
            card = card,
            tapeAlbum = tapeAlbum,
            progress = progress,
            flipAngles = flipAngles,
            warming = { cardsAlpha.value > 0f },
            spinning = player.isPlaying && loaded,
            controlsEnabled = controls,
            onShown = { shown = true },
            onOthersGone = { othersGone = it },
            onLabel = { labelFor = it },
            onPlayButton = { vm.player.togglePlay() },
            onDoor = ::eject,
            onNext = { vm.player.next() },
            onPrevious = { vm.player.previous() },
            modifier = Modifier.fillMaxSize(),
        )

        // L'image de la scène peut suivre l'interface avec retard : le voile reste tant que la scène n'a pas rendu une image
        // sans les autres cassettes.
        if (!staged || !othersGone) {
            CarouselShade(
                windowX = { w / 2f + carouselOffset(tapeIndex - position.value, centerGapPx, stackGapPx) },
                windowHalf = coverPx / CASSETTE_RATIO / 2f,
                fade = { if (albumId != null) (progress.value / TAPE_HANDOFF).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (!staged) carousel()

        if (tapeAlbum != null) {
            // Titre en cours, quelques secondes à chaque changement de piste.
            var toast by remember { mutableStateOf(false) }
            LaunchedEffect(player.mediaId, loaded) {
                if (!loaded || player.mediaId == null) return@LaunchedEffect
                toast = true
                delay(3000)
                toast = false
            }
            val toastAlpha by animateFloatAsState(if (toast && !ejecting) 1f else 0f, tween(if (toast) 250 else 700), label = "toast")
            val current = tracks.firstOrNull { it.id == player.mediaId }
            if (toastAlpha > 0f && current != null) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                        .widthIn(max = 420.dp)
                        .graphicsLayer { alpha = toastAlpha; translationY = (1f - toastAlpha) * 12.dp.toPx() }
                        .background(Color(0xCC0E0E11), RoundedCornerShape(14.dp))
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(current.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    Text(current.artist, color = Color.White.copy(alpha = 0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }

            // ---- Volet des titres, tiré depuis le bord gauche ----
            val panelW = minOf(w * 0.42f, with(density) { 400.dp.toPx() })
            val dragDrawer = Modifier.pointerInput(panelW) {
                val tracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking(); scope.launch { drawer.stop() } },
                    onDragEnd = {
                        val v = tracker.calculateVelocity().x
                        settleDrawer(if (abs(v) > 600f) v > 0f else drawer.value > 0.5f)
                    },
                    onDragCancel = { settleDrawer(drawer.value > 0.5f) },
                ) { change, dx ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    scope.launch { drawer.snapTo((drawer.value + dx / panelW).coerceIn(0f, 1f)) }
                }
            }
            if (controls) {
                // Poignée : zone exclue du geste « retour » du système, sinon le glissement depuis le bord éjecterait.
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(32.dp, 200.dp)
                        .systemGestureExclusion()
                        .then(dragDrawer)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { settleDrawer(true) },
                ) {
                    Box(
                        Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = 6.dp)
                            .size(5.dp, 56.dp)
                            .graphicsLayer { alpha = 1f - drawer.value }
                            .background(Color.White.copy(alpha = 0.45f), RoundedCornerShape(3.dp)),
                    )
                }
            }
            if (drawerShown) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = drawer.value }
                        .background(Color.Black.copy(alpha = 0.45f))
                        .pointerInput(Unit) { detectTapGestures { settleDrawer(false) } }
                        .then(dragDrawer),
                )
                val thumb = with(density) { 72.dp.toPx() }
                Box(
                    Modifier
                        .width(with(density) { panelW.toDp() })
                        .fillMaxHeight()
                        .graphicsLayer { translationX = -(1f - drawer.value) * panelW }
                        .shadow(16.dp)
                        .background(Color(0xFF101013))
                        .then(dragDrawer),
                ) {
                    TrackPanel(
                        album = tapeAlbum,
                        tracks = tracks,
                        playingId = player.mediaId,
                        headerSpace = thumb,
                        onPlay = { vm.play(tracks, it); settleDrawer(false) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    Artwork(tapeAlbum.artworkPath, Modifier.offset(20.dp, 20.dp).size(72.dp).clip(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

/**
 * Ce que le carrousel 2D obtient par transparence, posé en noir sur la scène 3D (fond noir) : les bords qui se fondent dans
 * le noir et, quand une cassette est choisie, toutes les autres qui s'effacent ([fade] 0 → 1) autour de [windowX].
 */
@Composable
private fun CarouselShade(windowX: () -> Float, windowHalf: Float, fade: () -> Float, modifier: Modifier) {
    Canvas(modifier) {
        val edge = size.width * 0.12f
        drawRect(
            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = 0f, endX = edge),
            size = Size(edge, size.height),
        )
        drawRect(
            Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = size.width - edge, endX = size.width),
            topLeft = Offset(size.width - edge, 0f), size = Size(edge, size.height),
        )
        val f = fade()
        if (f > 0f) {
            val shade = Color.Black.copy(alpha = f)
            val feather = 24.dp.toPx()
            val l = windowX() - windowHalf
            val r = windowX() + windowHalf
            drawRect(shade, size = Size((l - feather).coerceAtLeast(0f), size.height))
            drawRect(
                Brush.horizontalGradient(0f to shade, 1f to Color.Transparent, startX = l - feather, endX = l),
                topLeft = Offset(l - feather, 0f), size = Size(feather, size.height),
            )
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to shade, startX = r, endX = r + feather),
                topLeft = Offset(r, 0f), size = Size(feather, size.height),
            )
            drawRect(shade, topLeft = Offset(r + feather, 0f), size = Size((size.width - r - feather).coerceAtLeast(0f), size.height))
        }
    }
}

/** Place et taille (px) de la cassette du carrousel, dans une vue de [viewW] × [viewH] px. */
private data class CardRect(val cx: Float, val cy: Float, val height: Float, val viewW: Float, val viewH: Float)

/** Fermeture de la porte : rapide, avec un petit rebond amorti en fin de course. */
private fun doorCloseEase(t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    val base = FastOutSlowInEasing.transform((t / 0.7f).coerceIn(0f, 1f))
    val tail = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
    // léger rebond : la porte se rouvre de quelques degrés puis se rabat.
    return base - 0.06f * sin(tail * PI.toFloat() * 2f) * exp(-3f * tail) * (if (t > 0.55f) 1f else 0f)
}

private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
private fun smooth(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
private fun smoother(x: Float) = x.coerceIn(0f, 1f).let { it * it * it * (it * (it * 6f - 15f) + 10f) }
private fun ease(x: Float) = FastOutSlowInEasing.transform(x.coerceIn(0f, 1f))
private fun mix(a: Float3, b: Float3, t: Float) = a + (b - a) * t
private fun softMax(a: Float, b: Float) = (a + b + sqrt((a - b) * (a - b) + 1e-4f)) / 2f
private fun softMin(a: Float, b: Float) = (a + b - sqrt((a - b) * (a - b) + 1e-4f)) / 2f
private fun bezier(a: Float3, b: Float3, c: Float3, d: Float3, t: Float): Float3 {
    val u = 1f - t
    return a * (u * u * u) + b * (3f * u * u * t) + c * (3f * u * t * t) + d * (t * t * t)
}
private fun axisAngle(axis: Float3, degrees: Float) = Quaternion.fromAxisAngle(axis, degrees)
private val AXIS_Y = Float3(0f, 1f, 0f)
private val AXIS_Z = Float3(0f, 0f, 1f)

private fun slerpShortest(a: Quaternion, b0: Quaternion, t: Float): Quaternion {
    var b = b0
    var d = dot(a, b)
    if (d < 0f) { b = -b; d = -d }
    if (d > 0.9995f) return normalize(a + (b - a) * t)
    val th = acos(d)
    val s = sin(th)
    return a * (sin((1f - t) * th) / s) + b * (sin(t * th) / s)
}

/** Courbe lisse (Hermite, tangentes de Catmull-Rom) passant par des clés (temps, valeur), à pente nulle aux extrémités. */
private class Keys(vararg keys: Pair<Float, Float>) {
    private val t = keys.map { it.first }
    private val v = keys.map { it.second }
    private val m = List(keys.size) { i ->
        if (i == 0 || i == keys.lastIndex) 0f else (v[i + 1] - v[i - 1]) / (t[i + 1] - t[i - 1])
    }

    fun at(p: Float): Float {
        if (p <= t.first()) return v.first()
        if (p >= t.last()) return v.last()
        var i = 0
        while (p > t[i + 1]) i++
        val dt = t[i + 1] - t[i]
        val s = (p - t[i]) / dt
        val s2 = s * s
        val s3 = s2 * s
        return (2 * s3 - 3 * s2 + 1) * v[i] + (s3 - 2 * s2 + s) * dt * m[i] + (-2 * s3 + 3 * s2) * v[i + 1] + (s3 - s2) * dt * m[i + 1]
    }
}

/**
 * Mise en scène, en mètres, repère du monde (Y en haut, la caméra regarde vers -Z au départ).
 * Le modèle est debout face +X : un quart de tour autour de Y le tourne vers la caméra ; couché = encore un quart de tour
 * (sens horaire à l'écran) autour de Z, l'étiquette de la cassette se retrouve alors à l'endroit.
 */
private class WalkmanStage(
    card: CardRect,
    private val rigPos: Float3,
    private val rigQ: Quaternion,
    private val rigScale: Float3,
    private val hinge: Float3,
    private val center: Float3,
    halfExtent: Float3,
) {
    private val qStand = axisAngle(AXIS_Y, -90f)
    private val qLie = axisAngle(AXIS_Z, -90f) * qStand
    private val recvPos = Float3(0.085f, -0.01f, -0.05f)
    private val entryOffset = Float3(0.36f, -0.03f, -0.08f)
    private val recvYaw = -24f
    private val entryYaw = -50f
    private val rigBase = translation(rigPos) * rotation(rigQ) * scale(rigScale)

    // Départ : la cassette 3D se projette exactement sur la cassette du carrousel (face avant dans le plan z = 0).
    private val cassetteLength = 0.1016f * rigScale.x
    private val d0 = cassetteLength * card.viewH / (2f * TAN_HALF_FOV * card.height)
    private val metersPerPx = 2f * d0 * TAN_HALF_FOV / card.viewH
    private val cardPos = Float3((card.cx - card.viewW / 2f) * metersPerPx, (card.viewH / 2f - card.cy) * metersPerPx, -0.0053f * rigScale.z)
    private val cardQ = qStand * rigQ

    // Arrivée : le walkman couché remplit la hauteur de l'écran (petites marges), légèrement vu de droite.
    private val finalDistance = (2f * halfExtent.z) / 0.92f / (2f * TAN_HALF_FOV) + halfExtent.x
    private val az = Keys(0f to 0f, 0.8f to -12f, 1.7f to -16f, 2.3f to -6f, STAGE_END to 5f)
    private val el = Keys(0f to 0f, 0.8f to 4f, 1.7f to 12f, 2.3f to 8f, STAGE_END to 4f)
    private val dist = Keys(0f to d0, 0.8f to 0.28f, 1.7f to 0.215f, 2.3f to 0.20f, STAGE_END to finalDistance)

    var modelQ: Quaternion = qStand
        private set

    fun model(p: Float): Mat4 {
        val a = ease(seg(p, 0f, 0.9f))
        val lie = smoother(seg(p, LIE_START, STAGE_END))
        val q = slerpShortest(axisAngle(AXIS_Y, recvYaw + (1f - a) * entryYaw) * qStand, qLie, lie)
        modelQ = q
        return translation(recvPos + entryOffset * (1f - a)) * rotation(q) * translation(-center)
    }

    fun warmUp(): Mat4 = translation(cardPos + Float3(0f, 0f, -0.03f)) * rotation(qStand) * scale(Float3(0.2f)) * translation(-center)

    /** Cassette rangée, porte fermée (transformation locale du nœud CassetteRig). */
    val restingCassette: Mat4 get() = rigBase

    /**
     * Cassette du carrousel (repère du monde), comme la pochette 2D : décalée de [dxPx] px depuis le centre, pivotée de
     * [degrees] autour de la verticale, réduite à [s]. Au centre, c'est exactement la cassette de départ du vol.
     */
    fun carouselCassette(dxPx: Float, degrees: Float, s: Float, depth: Float): Mat4 {
        val flat = cardPos + Float3(dxPx * metersPerPx, 0f, 0f)
        // Le pivot de la pochette 2D se mesure face à elle ; ici, la caméra voit de biais les cassettes écartées du centre.
        val sight = Math.toDegrees(atan2(flat.x, d0 - flat.z).toDouble()).toFloat()
        // Reculée de [depth] sur sa ligne de visée et agrandie d'autant : même image, mais les cassettes proches du centre
        // passent vraiment devant les suivantes (en 2D, l'ordre de dessin s'en charge).
        val k = 1f + depth / (d0 - flat.z)
        val eye = Float3(0f, 0f, d0)
        val pos = eye + (flat - eye) * k
        return translation(pos) * rotation(axisAngle(AXIS_Y, degrees - sight) * cardQ) * scale(rigScale * (s * k))
    }

    fun doorDegrees(p: Float) =
        if (p < FLIGHT_END) DOOR_OPEN_DEG * smooth(seg(p, 0.35f, 0.95f))
        else DOOR_OPEN_DEG * (1f - doorCloseEase(seg(p, SLIDE_END, CLOSE_END)))

    private fun slide(p: Float) = SLIDE_IN * (1f - ease(seg(p, FLIGHT_END, SLIDE_END)))

    /** Cassette dans la porte (repère du modèle) : porte ouverte de [deg], cassette sortie de [s] le long de la porte. */
    private fun inDoor(deg: Float, s: Float) =
        translation(hinge) * rotation(axisAngle(AXIS_Z, -deg)) * translation(-hinge) * translation(Float3(0f, s, 0f)) * rigBase

    /** Transformation locale du nœud CassetteRig (relative au modèle de matrice [model]). */
    fun cassetteLocal(p: Float, model: Mat4): Mat4 {
        if (p >= FLIGHT_END) return inDoor(doorDegrees(p), slide(p))
        return inverse(model) * cassetteWorldInFlight(p, model)
    }

    private fun cassetteWorldInFlight(p: Float, model: Mat4): Mat4 {
        val pre = model * inDoor(DOOR_OPEN_DEG, SLIDE_IN)
        val prePos = (pre * Float4(0f, 0f, 0f, 1f)).xyz
        val preQ = modelQ * axisAngle(AXIS_Z, -DOOR_OPEN_DEG) * rigQ
        val f = smoother(seg(p, 0.05f, FLIGHT_END))
        val fr = smoother(seg(p, 0.2f, 1.35f))
        // Prise en main : la cassette vient vers soi en s'inclinant un peu, puis repart.
        val lift = ease(seg(p, 0f, 0.3f)) * (1f - smooth(seg(p, 0.3f, 0.9f)))
        val pos = bezier(cardPos, cardPos + Float3(0.02f, 0.012f, 0.03f), prePos + Float3(-0.02f, 0.05f, 0.02f), prePos, f) +
            Float3(0f, -0.004f, 0.012f) * lift
        val q = axisAngle(Float3(1f, 0f, 0f), 8f * lift) * slerpShortest(cardQ, preQ, fr)
        return translation(pos) * rotation(q) * scale(rigScale)
    }

    private fun cassettePosition(p: Float): Float3 {
        val q = p.coerceAtLeast(0f)
        val m = model(q)
        val world = if (q >= FLIGHT_END) m * inDoor(doorDegrees(q), slide(q)) else cassetteWorldInFlight(q, m)
        return (world * Float4(0f, 0f, 0f, 1f)).xyz
    }

    /** Œil et cible de la caméra. Elle suit la cassette avec un retard amorti, puis se cale face au walkman couché. */
    fun camera(p: Float, timeSeconds: Double): Pair<Position, Position> {
        val follow = cassettePosition(p - 0.1f) * 0.4f + cassettePosition(p - 0.22f) * 0.3f +
            cassettePosition(p - 0.35f) * 0.2f + cassettePosition(p - 0.5f) * 0.1f
        val weight = smooth(seg(p, 0f, 0.6f)) * (1f - smooth(seg(p, 2.1f, 2.8f)))
        val base = recvPos * smooth(seg(p, 2.1f, STAGE_END))
        // En vol, surtout la cassette ; à la fermeture, surtout le walkman.
        val onWalkman = 0.45f + 0.3f * smooth(seg(p, 1.5f, 2.1f))
        val target = mix(base, mix(follow, recvPos, onWalkman), weight)
        val d = dist.at(p)
        // En vol, la cassette reste dans l'image : la cible remonte (ou descend) si elle allait sortir par le haut (le bas).
        val cassetteY = cassettePosition(p).y
        val inFrame = smooth(seg(p, 0.05f, 0.4f)) * (1f - smooth(seg(p, 1.5f, 1.9f)))
        val framedY = softMin(softMax(target.y, cassetteY + 0.07f - 0.7f * d * TAN_HALF_FOV), cassetteY - 0.07f + 0.7f * d * TAN_HALF_FOV)
        target.y += (framedY - target.y) * inFrame
        // Une fois couché, un très léger balancement garde le relief vivant.
        val sway = smooth(seg(p, 2.7f, STAGE_END))
        val a = Math.toRadians(az.at(p) + sway * 0.9 * sin(timeSeconds * 0.23)).toFloat()
        val e = Math.toRadians(el.at(p) + sway * 0.6 * sin(timeSeconds * 0.19)).toFloat()
        val eye = target + Float3(sin(a) * cos(e), sin(e), cos(a) * cos(e)) * d
        return eye to target
    }

    init {
        model(0f)
    }
}


/** Le modèle, chargé une fois en [CAROUSEL_SLOTS] + 1 instances qui partagent géométrie, matériaux et textures. */
private class DeckAsset(private val modelLoader: ModelLoader, buffer: Buffer) {
    val instances = arrayOfNulls<FilamentInstance>(CAROUSEL_SLOTS + 1)
    private val asset = modelLoader.assetLoader.createInstancedAsset(buffer, instances)!!

    init {
        // Textures décodées en tâche de fond, envoyées au GPU image par image (asyncUpdateLoad).
        check(modelLoader.resourceLoader.asyncBeginLoad(asset))
    }

    val loaded get() = modelLoader.resourceLoader.asyncGetLoadProgress() >= 1f

    fun destroy() {
        modelLoader.resourceLoader.asyncCancelLoad()
        modelLoader.assetLoader.destroyAsset(asset)
    }
}

/** Instance 0 : le walkman (sa cassette part du carrousel) ; les suivantes : les cassettes du carrousel. */
private class Deck(engine: Engine, instances: Array<FilamentInstance?>) {
    val walkman = ModelNode(modelInstance = instances[0]!!, autoAnimate = false).apply {
        // Seules la touche orange et la porte reçoivent les touchers (boîtes englobantes, la touche agrandie).
        isTouchable = false
        collisionShape = null
        renderableNodes.filter { it.name == "BtnPlay" || it.name == "Door" }.forEach { node ->
            val box = node.axisAlignedBoundingBox
            val grow = if (node.name == "BtnPlay") 1.8f else 1f
            node.collisionShape = CollisionBox(
                CollisionVector(box.halfExtent[0] * 2f * grow, box.halfExtent[1] * 2f * grow, box.halfExtent[2] * 2f * grow),
                CollisionVector(box.center[0], box.center[1], box.center[2]),
            )
            node.isTouchable = true
        }
        isVisible = false
    }
    val door = walkman.nodes.first { it.name == "Door" }
    val rig = walkman.nodes.first { it.name == "CassetteRig" }
    val hubs = walkman.nodes.filter { it.name == "HubL" || it.name == "HubR" }
    val hubBase = hubs.associateWith { it.rotation }
    val btn = walkman.nodes.first { it.name == "BtnPlay" }
    val btnBase = btn.position
    val label = walkman.renderableNodes.first { it.name == "Label" }
    val rigPos = rig.position
    val rigQ = rig.quaternion
    val rigScale = rig.scale
    val hinge = door.position
    val slots = instances.drop(1).map { CarouselSlot(ModelNode(modelInstance = it!!, autoAnimate = false), engine) }
    /** Dans la scène : le walkman entier, et seulement la cassette (CassetteRig) des autres instances. */
    val nodes: List<Node> = listOf(walkman) + slots.map { it.rig }
}

/** Une cassette du carrousel, attribuée à l'album [albumId] tant qu'il est dans la fenêtre dessinée. */
private class CarouselSlot(model: ModelNode, engine: Engine) {
    val rig = model.nodes.first { it.name == "CassetteRig" }
    val label = model.renderableNodes.first { it.name == "Label" }
    val texture = TextureSlot(engine)
    var albumId: Long? = null
    var labelled = false
    var job: Job? = null

    init {
        model.isTouchable = false
        model.collisionShape = null
        model.nodes.forEach { it.isTouchable = false; it.collisionShape = null }
        rig.isVisible = false
    }
}

/** Texture d'un pixel, branchée sur les étiquettes libérées avant la destruction de leur texture. */
private fun blankTexture(engine: Engine): Texture =
    Texture.Builder().width(1).height(1).levels(1).sampler(Texture.Sampler.SAMPLER_2D).format(Texture.InternalFormat.SRGB8_A8)
        .build(engine).apply {
            val pixel = ByteBuffer.allocateDirect(4).put(byteArrayOf(46, 46, 52, -1)).flip()
            setImage(engine, 0, Texture.PixelBufferDescriptor(pixel, Texture.Format.RGBA, Texture.Type.UBYTE))
        }

/**
 * Scène 3D plein écran (sous la fenêtre : le carrousel 2D, le volet et le titre se dessinent par-dessus). Toute l'animation
 * est calculée à chaque image depuis [position] (carrousel) et [progress] (walkman), sans recomposition. Commandes du
 * walkman actives quand [controlsEnabled]. [onShown] : le carrousel 3D est réellement à l'écran. [onOthersGone] : le GPU a
 * rendu (true) une image où seule la cassette choisie reste du carrousel, ou les autres cassettes reviennent (false).
 */
@Composable
private fun TapeDeck3D(
    albums: List<Album>,
    position: Animatable<Float, AnimationVector1D>,
    centerGapPx: Float,
    stackGapPx: Float,
    card: CardRect,
    tapeAlbum: Album?,
    progress: Animatable<Float, AnimationVector1D>,
    flipAngles: Map<Long, Float>,
    warming: () -> Boolean,
    spinning: Boolean,
    controlsEnabled: Boolean,
    onShown: () -> Unit,
    onOthersGone: (Boolean) -> Unit,
    onLabel: (Long) -> Unit,
    onPlayButton: () -> Unit,
    onDoor: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val scope = rememberCoroutineScope()
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val view = rememberView(engine) {
        SceneView.createView(engine).apply {
            antiAliasing = View.AntiAliasing.FXAA
            multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
            ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
                enabled = true; radius = 0.015f; intensity = 1.1f; quality = View.QualityLevel.HIGH
            }
            dithering = View.Dithering.TEMPORAL
        }
    }
    val cameraNode = rememberCameraNode(engine) { focalLength = FOCAL_MM }

    var asset by remember { mutableStateOf<DeckAsset?>(null) }
    var deck by remember { mutableStateOf<Deck?>(null) }
    DisposableEffect(modelLoader) { onDispose { asset?.destroy() } }
    LaunchedEffect(modelLoader) {
        val buffer = withContext(Dispatchers.IO) { context.assets.readBuffer(WALKMAN_MODEL) }
        val loading = DeckAsset(modelLoader, buffer).also { asset = it }
        while (!loading.loaded) withFrameNanos { modelLoader.resourceLoader.asyncUpdateLoad() }
        deck = Deck(engine, loading.instances)
    }
    val stage = remember(deck, card) {
        deck?.let { d ->
            val bounds = d.walkman.modelInstance.asset.boundingBox
            WalkmanStage(
                card = card,
                rigPos = d.rigPos,
                rigQ = d.rigQ,
                rigScale = d.rigScale,
                hinge = d.hinge,
                center = Float3(bounds.center[0], bounds.center[1], bounds.center[2]),
                halfExtent = Float3(bounds.halfExtent[0], bounds.halfExtent[1], bounds.halfExtent[2]),
            )
        }
    }

    // Étiquettes : celle du walkman en grand (1536 px), celles du carrousel à leur taille d'affichage, partagées avec les
    // cassettes 2D ; la texture d'une cassette qui sort de la fenêtre est détruite.
    val labelW = carouselLabelWidth(card.height)
    val blank = remember(engine) { blankTexture(engine) }
    val sampler = remember { TextureSampler() }
    val walkmanLabel = remember(engine) { TextureSlot(engine) }
    var fence by remember { mutableStateOf<Fence?>(null) }
    var goneFence by remember { mutableStateOf<Fence?>(null) }
    var gone by remember { mutableStateOf(false) }
    // Une barrière créée dans onFrame passe avant le rendu de cette image : elle se pose à l'image suivante.
    var stagedFrames by remember { mutableStateOf(0) }
    var labelledFrames by remember { mutableStateOf(0) }
    DisposableEffect(engine) {
        onDispose {
            fence?.let(engine::destroyFence)
            goneFence?.let(engine::destroyFence)
            deck?.slots?.forEach { it.texture.release() }
            walkmanLabel.release()
            engine.destroyTexture(blank)
        }
    }
    LaunchedEffect(deck, tapeAlbum) {
        val d = deck ?: return@LaunchedEffect
        val album = tapeAlbum ?: return@LaunchedEffect
        val bmp = withContext(Dispatchers.Default) { cassetteLabel(context, album.artworkPath, album.title, album.artist, 1536) }
        walkmanLabel.set(d.label.materialInstance, bmp, "baseColorMap")
        // Papier mat : moins de reflet blanc sur la pochette (elle reste fidèle à ses couleurs).
        d.label.materialInstance.setParameter("roughnessFactor", 0.9f)
        onLabel(album.id)
    }

    fun free(slot: CarouselSlot) {
        slot.job?.cancel()
        slot.albumId = null
        slot.labelled = false
        slot.rig.isVisible = false
        slot.label.materialInstance.setParameter("baseColorMap", blank, sampler)
        slot.texture.release()
    }

    fun assign(slot: CarouselSlot, album: Album) {
        slot.albumId = album.id
        slot.job = scope.launch {
            val bmp = carouselLabel(context, album, labelW)
            slot.texture.set(slot.label.materialInstance, bmp, "baseColorMap")
            slot.label.materialInstance.setParameter("roughnessFactor", 0.9f)
            slot.labelled = true
        }
    }

    // Le carrousel 3D est affiché quand une image avec toutes ses étiquettes est sortie du GPU, puis réellement à l'écran
    // (l'affichage d'une SurfaceView suit le GPU avec retard) : alors seulement les cassettes 2D s'effacent.
    var sceneView by remember { mutableStateOf<SceneView?>(null) }
    var shownState by remember { mutableStateOf(0) }
    LaunchedEffect(shownState == 2) {
        if (shownState != 2) return@LaunchedEffect
        val surface = sceneView ?: return@LaunchedEffect
        val y = card.cy.roundToInt()
        val probe = Bitmap.createBitmap(card.viewW.roundToInt(), 1, Bitmap.Config.ARGB_8888)
        val row = IntArray(probe.width)
        val area = Rect(0, y, probe.width, y + 1)
        val main = Handler(Looper.getMainLooper())
        while (true) {
            val result = suspendCancellableCoroutine { cont -> PixelCopy.request(surface, area, probe, { cont.resume(it) }, main) }
            if (result == PixelCopy.SUCCESS) {
                probe.getPixels(row, 0, probe.width, 0, 0, probe.width, 1)
                if (row.any { android.graphics.Color.alpha(it) > 0 }) break
            }
            delay(40)
        }
        probe.recycle()
        shownState = 3
        onShown()
    }

    var pressedAt by remember { mutableStateOf(0L) }
    var boostUntil by remember { mutableStateOf(0L) }
    var boostDir by remember { mutableStateOf(1f) }
    var down by remember { mutableStateOf(Offset.Zero) }
    var downAt by remember { mutableStateOf(0L) }
    val swipeMin = with(LocalDensity.current) { 80.dp.toPx() }
    val enabled by rememberUpdatedState(controlsEnabled)
    val onPlay by rememberUpdatedState(onPlayButton)
    val onDoorTap by rememberUpdatedState(onDoor)
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)
    val gestures = remember {
        object : GestureDetector.SimpleOnGestureListener() {
            // Au relâché, sans attendre le délai du double toucher : la touche répond tout de suite.
            override fun onSingleTapUp(e: MotionEvent, node: Node?) {
                if (!enabled) return
                when (node?.name) {
                    "BtnPlay" -> {
                        pressedAt = SystemClock.uptimeMillis()
                        hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onPlay()
                    }
                    "Door" -> onDoorTap()
                }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) = Unit
        }
    }

    val albumList by rememberUpdatedState(albums)
    val tape by rememberUpdatedState(tapeAlbum)
    val warm by rememberUpdatedState(warming)
    val spin by rememberUpdatedState(spinning)
    var angle by remember { mutableStateOf(0f) }
    var speed by remember { mutableStateOf(0f) }

    Scene(
        // Sous la fenêtre (et non au-dessus comme par défaut quand le fond est transparent) : l'interface reste visible.
        onViewCreated = { sceneView = this; setZOrderOnTop(false) },
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        view = view,
        cameraNode = cameraNode,
        childNodes = deck?.nodes ?: emptyList(),
        isOpaque = false,
        cameraManipulator = null,
        onGestureListener = gestures,
        onTouchEvent = { e, _ ->
            // Glisser franchement vers la gauche = piste suivante, vers la droite = précédente ; les bobines défilent un
            // instant (avance / retour rapide).
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = Offset(e.x, e.y); downAt = e.eventTime }
                MotionEvent.ACTION_UP -> {
                    val dx = e.x - down.x
                    val dy = e.y - down.y
                    if (enabled && abs(dx) > swipeMin && abs(dx) > 2f * abs(dy) && e.eventTime - downAt < 700) {
                        boostDir = if (dx < 0f) 1f else -1f
                        boostUntil = SystemClock.uptimeMillis() + 450
                        hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (dx < 0f) next() else previous()
                    }
                }
            }
            false
        },
        onFrame = onFrame@{ frameTimeNanos ->
            val d = deck ?: return@onFrame
            val st = stage ?: return@onFrame
            val tapeId = tape?.id
            val prog = progress.value
            // Pendant le vol, la cassette est celle du walkman ; celles du carrousel sont cachées.
            val staged = tapeId != null && prog > TAPE_HANDOFF
            val p = if (tapeId != null) (prog - TAPE_HANDOFF).coerceAtLeast(0f) else 0f

            // ---- Carrousel : une instance par album de la fenêtre, posée comme la pochette 2D ----
            val list = albumList
            val center = position.value
            val first = (center - CAROUSEL_WINDOW).toInt().coerceAtLeast(0)
            val last = (center + CAROUSEL_WINDOW).toInt().coerceAtMost(list.lastIndex)
            val wanted = (first..last).map { list[it].id }
            d.slots.forEach { if (it.albumId != null && it.albumId !in wanted) free(it) }
            val fade = if (tapeId != null) (prog / TAPE_HANDOFF).coerceIn(0f, 1f) else 0f
            var allLabelled = true
            for (i in first..last) {
                val album = list[i]
                val slot = d.slots.firstOrNull { it.albumId == album.id }
                    ?: d.slots.first { it.albumId == null }.also { assign(it, album) }
                val dd = i - center
                val tilt = dd.coerceIn(-1f, 1f)
                val turn = flipAngles[album.id] ?: 0f
                // Les autres cassettes s'écartent (et s'effacent sous CarouselShade).
                val spread = if (tapeId != null && album.id != tapeId) sign(dd) * card.height * 0.5f * fade else 0f
                // Retournée (« i ») comme la pochette 2D ; passé la tranche, la face arrière 2D (opaque, plus large) la couvre.
                slot.rig.transform = st.carouselCassette(
                    carouselOffset(dd, centerGapPx, stackGapPx) + spread,
                    tilt * CAROUSEL_TILT_DEG + turn,
                    1f - CAROUSEL_SHRINK * abs(tilt) + 0.12f * (turn / 180f),
                    CAROUSEL_DEPTH * abs(dd),
                )
                slot.rig.isVisible = slot.labelled && !staged
                allLabelled = allLabelled && slot.labelled
            }

            // ---- Walkman ----
            // Pendant le chargement (carrousel 2D encore affiché), il est dessiné en tout petit derrière la cassette
            // centrale : ses shaders sont compilés d'avance, son entrée ne saccade pas.
            val warmUp = warm()
            d.walkman.isVisible = staged || warmUp
            if (staged) {
                val m = st.model(p)
                d.walkman.transform = m
                d.door.rotation = Rotation(z = -st.doorDegrees(p))
                d.rig.transform = st.cassetteLocal(p, m)
            } else if (warmUp) {
                d.walkman.transform = st.warmUp()
                d.door.rotation = Rotation(z = 0f)
                d.rig.transform = st.restingCassette
            }
            val (eye, target) = st.camera(p, frameTimeNanos / 1_000_000_000.0)
            cameraNode.position = eye
            cameraNode.lookAt(target)

            val now = SystemClock.uptimeMillis()
            val goal = if (now < boostUntil) boostDir * 30f else if (spin) 6f else 0f
            speed += (goal - speed) * 0.08f
            angle = (angle + speed) % 360f
            d.hubs.forEach { hub -> hub.rotation = d.hubBase.getValue(hub).let { Rotation(it.x, it.y + angle, it.z) } }

            val elapsed = now - pressedAt
            val depth = if (elapsed in 0 until 220) {
                val u = elapsed / 220f
                0.002f * (if (u < 0.35f) u / 0.35f else (1f - u) / 0.65f)
            } else 0f
            d.btn.position = Position(d.btnBase.x, d.btnBase.y - depth, d.btnBase.z)

            stagedFrames = if (staged) stagedFrames + 1 else 0
            labelledFrames = if (allLabelled) labelledFrames + 1 else 0
            if (staged && !gone && stagedFrames >= 2) {
                val f = goneFence ?: engine.createFence().also { goneFence = it }
                if (f.wait(Fence.Mode.DONT_FLUSH, 0) == Fence.FenceStatus.CONDITION_SATISFIED) {
                    engine.destroyFence(f)
                    goneFence = null
                    gone = true
                    onOthersGone(true)
                }
            } else if (!staged && gone) {
                gone = false
                onOthersGone(false)
            } else if (!staged) goneFence?.let { f ->
                engine.destroyFence(f)
                goneFence = null
            }

            if (shownState == 0 && labelledFrames >= 2) {
                fence = engine.createFence()
                shownState = 1
            } else if (shownState == 1) fence?.let { f ->
                if (f.wait(Fence.Mode.DONT_FLUSH, 0) == Fence.FenceStatus.CONDITION_SATISFIED) {
                    engine.destroyFence(f)
                    fence = null
                    shownState = 2
                }
            }
        },
    )
    DisposableEffect(Unit) { onDispose { sceneView?.let(::releaseSceneView) } }
}
