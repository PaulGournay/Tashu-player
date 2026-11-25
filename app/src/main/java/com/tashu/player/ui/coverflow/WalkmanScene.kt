package com.tashu.player.ui.coverflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.filament.View
import com.tashu.player.domain.model.Album
import com.tashu.player.playback.MediaItemMapper
import com.tashu.player.ui.MainViewModel
import dev.romainguy.kotlin.math.Float2
import io.github.sceneview.Scene
import io.github.sceneview.SceneView
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.node.Node
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Walkman 3D (« Old Walkman », Charles48900, CC BY 4.0) avec la cassette (« Cassette tapes », FEDDYFAZBARE, CC BY 4.0)
 * déjà rangée dans le compartiment (nœud CassetteRig). Préparés dans Blender : Door (charnière en bas),
 * BtnPlay, CassetteRig > Label (étiquette remplacée par la pochette) / HubL / HubR. Gardé hors du dépôt public.
 */
internal const val WALKMAN_MODEL = "models/walkman.glb"

internal fun walkmanModelAvailable(context: Context): Boolean =
    runCatching { context.assets.list("models")?.contains("walkman.glb") == true }.getOrDefault(false)

/** Fin de l'animation : 0→1 entrée du walkman + ouverture, 1→2 la cassette entre, 2→3 fermeture et liste. */
internal const val TAPE_LOADED = 3f
private const val TAPE_LOAD_MS = 2600
/** Ouverture maximale du couvercle (degrés). */
private const val DOOR_OPEN_DEG = 62f

/**
 * Scène walkman en paysage. Les autres cassettes s'effacent, le walkman entre par la droite et ouvre son couvercle,
 * la cassette (étiquette = pochette) y glisse, le couvercle se referme avec un petit rebond, puis la lecture démarre.
 * Retour / éjection : tout se rejoue à l'envers.
 */
@Composable
internal fun WalkmanScene(
    album: Album,
    vm: MainViewModel,
    progress: Animatable<Float, AnimationVector1D>,
    onEjected: () -> Unit,
) {
    val player by vm.player.state.collectAsStateWithLifecycle()
    val tracks by remember(album.id) { vm.albumTracks(album.id) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var ejecting by remember { mutableStateOf(false) }
    val playingAlbumId by rememberUpdatedState(player.metadata.extras?.getLong(MediaItemMapper.EXTRA_ALBUM_ID, -1L))

    LaunchedEffect(album.id) {
        progress.snapTo(0f)
        progress.animateTo(TAPE_LOADED, tween(TAPE_LOAD_MS, easing = LinearEasing))
    }
    LaunchedEffect(album.id, tracks.isNotEmpty()) {
        if (tracks.isEmpty()) return@LaunchedEffect
        snapshotFlow { progress.value >= TAPE_LOADED }.first { it }
        if (!ejecting && playingAlbumId != album.id) vm.play(tracks, 0)
    }

    fun eject() {
        if (ejecting) return
        ejecting = true
        if (player.isPlaying) vm.player.togglePlay()
        scope.launch {
            progress.animateTo(0f, tween((TAPE_LOAD_MS * progress.value / TAPE_LOADED).roundToInt(), easing = LinearEasing))
            onEjected()
        }
    }
    BackHandler { eject() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val toDp = { v: Float -> with(density) { v.toDp() } }
        val p = progress.value
        val a = FastOutSlowInEasing.transform(p.coerceIn(0f, 1f))
        val insert = (p - 1f).coerceIn(0f, 1f)
        val close = (p - 2f).coerceIn(0f, 1f)
        val panelW = w * 0.36f
        val rightW = w - panelW
        val stageH = h * 0.84f

        Walkman3D(
            appear = a,
            door = when {
                p < 1f -> FastOutSlowInEasing.transform(((p - 0.35f) / 0.65f).coerceIn(0f, 1f))
                p < 2f -> 1f
                else -> 1f - doorCloseEase(close)
            },
            insert = FastOutSlowInEasing.transform(insert),
            spinning = player.isPlaying && p >= TAPE_LOADED,
            artworkPath = album.artworkPath,
            title = album.title,
            artist = album.artist,
            controlsEnabled = p >= TAPE_LOADED && !ejecting,
            onPlayButton = { vm.player.togglePlay() },
            modifier = Modifier
                .offset { IntOffset(panelW.roundToInt(), 0) }
                .size(toDp(rightW), toDp(stageH)),
        )

        val d = FastOutSlowInEasing.transform(close)
        if (d > 0f) {
            TrackPanel(
                album = album,
                tracks = tracks,
                playingId = player.mediaId,
                headerSpace = with(density) { 72.dp.toPx() },
                onPlay = { vm.play(tracks, it) },
                modifier = Modifier
                    .zIndex(1.5f)
                    .width(toDp(panelW))
                    .fillMaxHeight()
                    .graphicsLayer { alpha = d; translationX = -(1f - d) * panelW * 0.25f },
            )
            // Vignette de l'en-tête : une petite cassette à la place de la pochette.
            CassetteCard(
                album.artworkPath,
                Modifier
                    .zIndex(2f)
                    .offset(20.dp, 20.dp)
                    .size(72.dp)
                    .graphicsLayer { alpha = d },
            )
            val current = tracks.firstOrNull { it.id == player.mediaId }
            Column(
                Modifier
                    .offset { IntOffset(panelW.roundToInt(), (stageH - with(density) { 40.dp.toPx() }).roundToInt()) }
                    .width(toDp(rightW))
                    .graphicsLayer { alpha = d },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(current?.title.orEmpty(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current?.artist.orEmpty(), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ControlStrip(
            playing = player.isPlaying,
            enabled = p >= TAPE_LOADED && !ejecting,
            onPrevious = { vm.player.previous() },
            onPlayPause = { vm.player.togglePlay() },
            onStop = { if (player.isPlaying) vm.player.togglePlay(); vm.player.seekTo(0) },
            onNext = { vm.player.next() },
            onEject = ::eject,
            ejectLabel = "Éjecter la cassette",
            modifier = Modifier
                .offset { IntOffset(panelW.roundToInt(), stageH.roundToInt()) }
                .size(toDp(rightW), toDp(h - stageH))
                .graphicsLayer { alpha = a },
        )
    }
}

/** Fermeture du couvercle : rapide, avec un petit rebond amorti en fin de course. */
private fun doorCloseEase(t: Float): Float {
    if (t <= 0f) return 0f
    if (t >= 1f) return 1f
    val base = FastOutSlowInEasing.transform((t / 0.7f).coerceIn(0f, 1f))
    val tail = ((t - 0.55f) / 0.45f).coerceIn(0f, 1f)
    // léger rebond : le couvercle se rouvre de quelques degrés puis se rabat.
    return base - 0.06f * sin(tail * PI.toFloat() * 2f) * exp(-3f * tail) * (if (t > 0.55f) 1f else 0f)
}

/**
 * Walkman 3D. @param appear entrée par la droite (0..1) ; @param door ouverture du couvercle (0..1) ;
 * @param insert 0 = cassette dehors, 1 = rangée ; @param spinning les moyeux tournent (lecture).
 * Toucher le walkman enfonce la touche orange (lecture / pause).
 */
@Composable
private fun Walkman3D(
    appear: Float,
    door: Float,
    insert: Float,
    spinning: Boolean,
    artworkPath: String?,
    title: String,
    artist: String,
    controlsEnabled: Boolean,
    onPlayButton: () -> Unit,
    modifier: Modifier,
) {
    val hostView = LocalView.current
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
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0.07f, 0.03f, 0.30f)
        lookAt(Position(0f, -0.004f, 0f))
    }
    val model = remember(modelLoader) {
        ModelNode(modelInstance = modelLoader.createModelInstance(WALKMAN_MODEL), autoAnimate = false).apply {
            isTouchable = true
        }
    }
    val doorNode = remember(model) { model.nodes.firstOrNull { it.name == "Door" } }
    val rig = remember(model) { model.nodes.firstOrNull { it.name == "CassetteRig" } }
    val hubs = remember(model) { model.nodes.filter { it.name == "HubL" || it.name == "HubR" } }
    val hubBase = remember(hubs) { hubs.associateWith { it.rotation } }
    val btn = remember(model) { model.nodes.firstOrNull { it.name == "BtnPlay" } }
    val btnBase = remember(btn) { btn?.position }
    val rigBase = remember(rig) { rig?.position }
    val label = remember(model) { model.renderableNodes.firstOrNull { it.name == "Label" } }

    val labelTexture = remember { TextureSlot(engine) }
    LaunchedEffect(artworkPath, label) {
        val bmp = withContext(Dispatchers.Default) { cassetteLabel(artworkPath, title, artist) }
        label?.materialInstance?.let { labelTexture.set(it, bmp, "baseColorMap") }
    }
    DisposableEffect(engine) { onDispose { labelTexture.release() } }

    var pressedAt by remember { mutableStateOf(0L) }
    val enabled by rememberUpdatedState(controlsEnabled)
    val onPress by rememberUpdatedState(onPlayButton)
    val gestures = remember {
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) {
                if (node == null || !enabled) return
                pressedAt = SystemClock.uptimeMillis()
                hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onPress()
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) = Unit
        }
    }

    val ap by rememberUpdatedState(appear)
    val dr by rememberUpdatedState(door)
    val ins by rememberUpdatedState(insert)
    val spin by rememberUpdatedState(spinning)
    var angle by remember { mutableStateOf(0f) }
    var speed by remember { mutableStateOf(0f) }

    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        view = view,
        cameraNode = cameraNode,
        childNodes = listOf(model),
        isOpaque = false,
        cameraManipulator = null,
        onGestureListener = gestures,
        onFrame = { frameTimeNanos ->
            val t = frameTimeNanos / 1_000_000_000.0
            // Légère respiration de la caméra pour garder le relief vivant.
            cameraNode.position = Position((0.07 + sin(t * 0.21) * 0.012).toFloat(), (0.03 + sin(t * 0.17) * 0.004).toFloat(), 0.30f)
            cameraNode.lookAt(Position(0f, -0.004f, 0f))

            val hidden = 1f - ap
            // Face avant du modèle = +X : un quart de tour la tourne vers la caméra.
            model.position = Position(x = hidden * 0.30f)
            model.rotation = Rotation(y = -90f + hidden * -35f)

            doorNode?.rotation = Rotation(z = -DOOR_OPEN_DEG * dr)

            if (rig != null && rigBase != null) {
                // Trajet de la cassette : arrive d'en haut, devant le walkman, puis glisse dans le compartiment.
                val out = 1f - ins
                val fwd = out * out * 0.13f
                val up = out * 0.075f
                rig.position = Position(rigBase.x + fwd, rigBase.y + up, rigBase.z)
                rig.isVisible = ap > 0.05f
            }

            speed += ((if (spin) 6f else 0f) - speed) * 0.05f
            angle = (angle + speed) % 360f
            hubs.forEach { hub -> hub.rotation = (hubBase[hub] ?: Rotation()).let { Rotation(it.x, it.y + angle, it.z) } }

            if (btn != null && btnBase != null) {
                val elapsed = SystemClock.uptimeMillis() - pressedAt
                val depth = if (elapsed in 0 until 220) {
                    val u = elapsed / 220f
                    0.002f * (if (u < 0.35f) u / 0.35f else (1f - u) / 0.65f)
                } else 0f
                btn.position = Position(btnBase.x, btnBase.y - depth, btnBase.z)
            }
        },
    )
}

/**
 * Étiquette de la cassette (rapport 2,13:1, percée au centre pour la fenêtre) : pochette en fond,
 * bandeau clair en haut avec la face « A », le titre et l'artiste, façon cassette enregistrée à la main.
 */
private fun cassetteLabel(artworkPath: String?, title: String, artist: String): Bitmap {
    val w = 1024
    val h = 480
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(android.graphics.Color.rgb(232, 226, 214))
    val art = artworkPath?.let { runCatching { BitmapFactory.decodeFile(File(it).absolutePath) }.getOrNull() }
    if (art != null) {
        val targetRatio = w.toFloat() / h
        val srcH = (art.width / targetRatio).toInt().coerceAtMost(art.height)
        val srcW = (srcH * targetRatio).toInt().coerceAtMost(art.width)
        val src = Rect((art.width - srcW) / 2, (art.height - srcH) / 2, (art.width + srcW) / 2, (art.height + srcH) / 2)
        c.drawBitmap(art, src, RectF(0f, 0f, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        art.recycle()
    }
    // Bandeau d'écriture (haut de l'étiquette, au-dessus de la fenêtre).
    val band = h * 0.34f
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, 0f, band, android.graphics.Color.argb(235, 246, 242, 232), android.graphics.Color.argb(215, 246, 242, 232), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), band, p)
    p.shader = null
    p.color = android.graphics.Color.rgb(30, 30, 34)
    p.style = Paint.Style.STROKE; p.strokeWidth = 4f
    c.drawRect(24f, 22f, 24f + band - 44f, band - 22f, p)
    p.style = Paint.Style.FILL
    p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    p.textSize = band * 0.5f
    c.drawText("A", 24f + (band - 44f) / 2f - p.measureText("A") / 2f, band * 0.68f, p)
    val x0 = band + 6f
    p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    p.textSize = band * 0.36f
    c.drawText(ellipsize(title, p, w - x0 - 30f), x0, band * 0.48f, p)
    p.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    p.textSize = band * 0.22f
    p.color = android.graphics.Color.rgb(70, 70, 76)
    c.drawText(ellipsize(artist, p, w - x0 - 30f), x0, band * 0.82f, p)
    return out
}

private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 0 && paint.measureText(text.substring(0, end) + "…") > maxWidth) end--
    return text.substring(0, end) + "…"
}

/**
 * Cassette dessinée en 2D (carrousel paysage, vignette) : boîtier sombre, étiquette = pochette,
 * fenêtre avec deux moyeux. Proportions mesurées sur le modèle 3D.
 */
@Composable
internal fun CassetteCard(artworkPath: String?, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val shape = RoundedCornerShape(6)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.545f)
                .shadow(10.dp, shape)
                .clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF2A2A2E), Color(0xFF151517)))),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val cw = maxWidth
                val ch = maxHeight
                // Étiquette : 4,4 %→95 % en largeur, 9,4 %→75 % en hauteur.
                Box(
                    Modifier
                        .offset(cw * 0.044f, ch * 0.094f)
                        .size(cw * 0.906f, ch * 0.656f)
                        .clip(RoundedCornerShape(3)),
                ) {
                    Artwork(artworkPath, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxWidth().fillMaxHeight(0.34f).background(Color(0xE6F6F2E8)))
                }
                ComposeCanvas(Modifier.fillMaxSize()) {
                    val sx = size.width / 500f
                    val sy = size.height / 320f
                    // Fenêtre et moyeux.
                    drawRoundRect(
                        Color(0xFF0E0E10), topLeft = Offset(105 * sx, 108 * sy), size = Size(290 * sx, 74 * sy),
                        cornerRadius = CornerRadius(10 * sx),
                    )
                    for (hx in listOf(140f, 355f)) {
                        val ctr = Offset(hx * sx, 145 * sy)
                        drawCircle(Color(0xFFE8E8EA), radius = 24 * sx, center = ctr)
                        drawCircle(Color(0xFF0E0E10), radius = 12 * sx, center = ctr)
                        drawCircle(Color.White.copy(alpha = 0.5f), radius = 24 * sx, center = ctr, style = Stroke(1.5f * sx))
                    }
                    drawRoundRect(Color(0xFF3A3A40), topLeft = Offset(190 * sx, 118 * sy), size = Size(115 * sx, 54 * sy), cornerRadius = CornerRadius(4 * sx))
                    // Bas trapézoïdal du boîtier et vis.
                    drawRect(Color.White.copy(alpha = 0.05f), topLeft = Offset(90 * sx, 255 * sy), size = Size(320 * sx, 65 * sy))
                    for (v in listOf(Offset(16f, 16f), Offset(484f, 16f), Offset(16f, 304f), Offset(484f, 304f), Offset(250f, 268f))) {
                        drawCircle(Color(0xFF55555C), radius = 5 * sx, center = Offset(v.x * sx, v.y * sy))
                    }
                }
            }
        }
    }
}
