package com.tashu.player.ui.coverflow

import android.app.Activity
import java.io.File
import coil.request.ImageRequest
import coil.imageLoader
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import android.content.Context
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import android.view.HapticFeedbackConstants
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tashu.player.domain.model.Album
import com.tashu.player.playback.MediaItemMapper
import com.tashu.player.ui.MainViewModel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

private val Ink = Color(0xFF050505)
private val Graphite = Color(0xFF2A2A2E)

/**
 * Carrousel 3D de pochettes affiché en paysage : pochette centrale de face, voisines pivotées
 * et empilées, reflets sous chaque pochette. Glisser pour parcourir (avec inertie), toucher une
 * pochette latérale pour l'amener au centre, toucher la pochette centrale pour la retourner
 * et voir ses morceaux.
 */
@Composable
fun CoverFlowScreen(vm: MainViewModel) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val player by vm.player.state.collectAsStateWithLifecycle()
    ImmersiveSystemBars()

    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
            Text("Aucun album indexé", color = Color.White.copy(alpha = 0.6f))
        }
        return
    }

    // Départ sur l'album en cours de lecture, sinon sur le premier.
    val playingAlbumId = player.metadata.extras?.getLong(MediaItemMapper.EXTRA_ALBUM_ID, -1L)
    var savedIndex by rememberSaveable { mutableStateOf(albums.indexOfFirst { it.id == playingAlbumId }.coerceAtLeast(0)) }
    val position = remember { Animatable(savedIndex.toFloat()) }
    val scope = rememberCoroutineScope()
    var flippedId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Album dont le CD est dans la platine (null = carrousel) et avancement de son animation.
    var cdAlbumId by rememberSaveable { mutableStateOf<Long?>(null) }
    val cdProgress = remember { Animatable(0f) }
    val coverFade = { if (cdAlbumId == null) 0f else (cdProgress.value / 0.6f).coerceIn(0f, 1f) }

    LaunchedEffect(albums.size) {
        if (position.value > albums.lastIndex) position.snapTo(albums.lastIndex.toFloat())
    }
    BackHandler(enabled = flippedId != null) { flippedId = null }

    // Petit « clic » haptique à chaque pochette qui passe au centre (glissement, inertie ou toucher).
    val hapticView = LocalView.current
    LaunchedEffect(albums.size) {
        snapshotFlow { position.value.roundToInt().coerceIn(0, albums.lastIndex) }
            .distinctUntilChanged()
            .drop(1)
            .collect {
                hapticView.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
                    else HapticFeedbackConstants.CLOCK_TICK,
                )
            }
    }

    fun settleTo(index: Int) {
        val target = index.coerceIn(0, albums.lastIndex)
        savedIndex = target
        scope.launch { position.animateTo(target.toFloat(), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow)) }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val cover: Dp = minOf(maxHeight * 0.60f, maxWidth * 0.36f)
        val topOffset: Dp = maxHeight * 0.06f
        val density = LocalDensity.current
        val coverPx = with(density) { cover.toPx() }
        val centerGapPx = coverPx * 0.62f   // décalage pochette centrale -> première voisine
        val stackGapPx = coverPx * 0.14f    // écart entre pochettes empilées
        val dragUnitPx = coverPx * 0.40f    // glissement nécessaire pour avancer d'un album

        val dragModifier = Modifier.pointerInput(albums.size, flippedId, cdAlbumId) {
            if (flippedId != null || cdAlbumId != null) return@pointerInput
            val tracker = VelocityTracker()
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); scope.launch { position.stop() } },
                onDragEnd = {
                    val velocity = tracker.calculateVelocity().x
                    settleTo((position.value - velocity / dragUnitPx * 0.18f).roundToInt())
                },
                onDragCancel = { settleTo(position.value.roundToInt()) },
            ) { change, dx ->
                tracker.addPosition(change.uptimeMillis, change.position)
                val next = (position.value - dx / dragUnitPx).coerceIn(-0.4f, albums.lastIndex + 0.4f)
                scope.launch { position.snapTo(next) }
            }
        }

        // Préchargement de toutes les pochettes à la taille d'affichage (une fois par ouverture).
        val coverDecodePx = coverPx.roundToInt().coerceIn(256, 1024)
        val context = LocalContext.current
        LaunchedEffect(albums, coverDecodePx) {
            val loader = context.imageLoader
            albums.mapNotNull { it.artworkPath }.forEach { loader.enqueue(coverRequest(context, it, coverDecodePx)) }
        }

        CompositionLocalProvider(LocalCoverPx provides coverDecodePx) {
        Box(
            dragModifier
                .fillMaxSize()
                // Les pochettes se fondent dans le noir vers les bords au lieu d'apparaître d'un coup.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.horizontalGradient(
                            0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            val center = position.value
            val first = (center - 12).toInt().coerceAtLeast(0)
            val last = (center + 12).toInt().coerceAtMost(albums.lastIndex)
            for (i in first..last) {
                val album = albums[i]
                val d = i - center
                val x = if (abs(d) <= 1f) d * centerGapPx else sign(d) * (centerGapPx + (abs(d) - 1f) * stackGapPx)
                CoverItem(
                    album = album,
                    size = cover,
                    offsetPx = x,
                    distance = d,
                    flipped = flippedId == album.id,
                    vm = vm,
                    playingId = player.mediaId,
                    onTap = {
                        when {
                            cdAlbumId != null -> Unit
                            flippedId == album.id -> flippedId = null
                            abs(d) < 0.5f -> { flippedId = null; settleTo(i); cdAlbumId = album.id }
                            else -> { flippedId = null; settleTo(i) }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = topOffset)
                        .graphicsLayer { alpha = if (cdAlbumId == album.id) 0f else 1f - coverFade() },
                )
            }
        }
        }

        // Couche d'interface hors du fondu des bords (boutons et textes toujours nets).
        val center = position.value
        if (cdAlbumId == null) Box(Modifier.fillMaxSize()) {
            // Coins bas : lecture/pause à gauche, « i » à droite (retourne la pochette centrale).
            IconButton(
                onClick = { vm.player.togglePlay() },
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 8.dp),
            ) {
                Icon(
                    if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (player.isPlaying) "Pause" else "Lecture",
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = {
                    val id = albums[center.roundToInt().coerceIn(0, albums.lastIndex)].id
                    flippedId = if (flippedId == id) null else id
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp),
            ) {
                Box(
                    Modifier.size(22.dp).border(1.5.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("i", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                }
            }

            // Artiste puis titre de l'album central.
            val current = albums[center.roundToInt().coerceIn(0, albums.lastIndex)]
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp, start = 96.dp, end = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    current.artist, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
                Text(
                    current.title, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }

        cdAlbumId?.let { id -> albums.firstOrNull { it.id == id } }?.let { album ->
            CompositionLocalProvider(LocalCoverPx provides coverDecodePx) {
                CdPlayerScene(album, vm, cdProgress, cover, topOffset, onEjected = { cdAlbumId = null })
            }
        }
    }
}

@Composable
private fun CoverItem(
    album: Album,
    size: Dp,
    offsetPx: Float,
    distance: Float,
    flipped: Boolean,
    vm: MainViewModel,
    playingId: String?,
    onTap: () -> Unit,
    modifier: Modifier,
) {
    val tilt = distance.coerceIn(-1f, 1f)
    val flip by animateFloatAsState(if (flipped) 180f else 0f, tween(450), label = "flip")
    val noRipple = remember { MutableInteractionSource() }

    Column(
        modifier
            .zIndex(if (flipped) 100f else -abs(distance))
            .offset { IntOffset(offsetPx.roundToInt(), 0) }
            .graphicsLayer {
                rotationY = tilt * 72f + flip
                val s = 1f - 0.14f * abs(tilt) + 0.12f * (flip / 180f)
                scaleX = s; scaleY = s
                cameraDistance = 22f * density
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size).clickable(interactionSource = noRipple, indication = null, onClick = onTap)) {
            if (flip <= 90f) {
                Artwork(album.artworkPath, Modifier.fillMaxSize())
            } else {
                // Face arrière, contre-pivotée pour être lisible.
                TrackListBack(album, vm, playingId, Modifier.fillMaxSize().graphicsLayer { rotationY = 180f })
            }
        }
        if (flip <= 90f) Reflection(album.artworkPath, size)
    }
}

@Composable
internal fun Artwork(path: String?, modifier: Modifier, alignment: Alignment = Alignment.Center) {
    if (path != null) {
        val context = LocalContext.current
        val px = LocalCoverPx.current
        val request = remember(path, px) { coverRequest(context, path, px) }
        AsyncImage(
            model = request, contentDescription = null, contentScale = ContentScale.Crop, alignment = alignment,
            // Fond gris pendant un éventuel chargement : jamais de trou noir.
            modifier = modifier.background(Graphite),
        )
    } else {
        Box(modifier.background(Graphite), contentAlignment = Alignment.Center) {
            Text("♪", color = Color.White.copy(alpha = 0.4f), fontSize = 40.sp)
        }
    }
}

/** Taille (px) à laquelle les pochettes sont décodées ; identique partout pour partager le cache mémoire. */
private val LocalCoverPx = staticCompositionLocalOf { 512 }

/** Même requête pour la pochette, son reflet et le préchargement → une seule image décodée en mémoire. */
private fun coverRequest(context: Context, path: String, px: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(File(path))
        .size(px, px)
        .allowRgb565(true)
        .crossfade(false)
        .memoryCacheKey("coverflow:$path:$px")
        .build()

/** Reflet : la pochette retournée verticalement, qui s'efface vers le bas. */
@Composable
private fun Reflection(path: String?, size: Dp) {
    Artwork(
        path,
        Modifier
            .size(size, size * 0.34f)
            .graphicsLayer {
                scaleY = -1f
                compositingStrategy = CompositingStrategy.Offscreen
            }
            .drawWithContent {
                drawContent()
                // Repère local retourné : le bas local est le haut visible du reflet.
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)),
                    blendMode = BlendMode.DstIn,
                )
            },
        // Le reflet montre le bas de la pochette (celui qui touche le « sol »).
        alignment = Alignment.BottomCenter,
    )
}

@Composable
private fun TrackListBack(album: Album, vm: MainViewModel, playingId: String?, modifier: Modifier) {
    val tracks by remember(album.id) { vm.albumTracks(album.id) }.collectAsStateWithLifecycle(emptyList())
    Column(modifier.background(Color(0xFF111114))) {
        Column(Modifier.fillMaxWidth().background(Graphite).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(album.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                val playing = t.id == playingId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (playing) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { vm.play(tracks, i) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        (t.trackNumber ?: (i + 1)).toString(), color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                    Text(
                        t.title, color = if (playing) Color(0xFF8AB4FF) else Color.White, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(
                        (t.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) },
                        color = Color.White.copy(alpha = 0.45f), fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
            }
        }
    }
}

/** Plein écran pendant le carrousel ; les barres reviennent par un glissement depuis le bord. */
@Composable
private fun ImmersiveSystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
