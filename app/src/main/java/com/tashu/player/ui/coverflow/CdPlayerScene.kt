package com.tashu.player.ui.coverflow

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Eject
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.Track
import com.tashu.player.playback.MediaItemMapper
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.nowplaying.rememberLivePosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Durée du chargement complet (pochette → CD → tiroir → lecture) ; l'éjection rejoue le chemin à l'envers. */
private const val LOAD_MS = 2600
/** Fin de l'animation sur l'échelle [CdPlayerScene.progress] : 4 étapes d'une unité chacune. */
internal const val CD_LOADED = 4f

private val LcdGreen = Color(0xFF7CFFB2)
private val LcdBack = Color(0xFF07140F)

/**
 * Lecteur CD en paysage. `progress` va de 0 à 4 :
 * 0→1 les autres pochettes s'effacent, l'album glisse à gauche en pivotant, le CD sort de la pochette
 * et la platine entre ; 1→2 le tiroir sort ; 2→3 le CD vole au-dessus du tiroir en s'inclinant puis s'y pose ;
 * 3→4 le tiroir rentre avec le disque, la pochette devient l'en-tête de la liste des titres, la piste 1 démarre.
 */
@Composable
internal fun CdPlayerScene(
    album: Album,
    vm: MainViewModel,
    progress: Animatable<Float, AnimationVector1D>,
    coverSize: Dp,
    coverTop: Dp,
    onEjected: () -> Unit,
) {
    val player by vm.player.state.collectAsStateWithLifecycle()
    val tracks by remember(album.id) { vm.albumTracks(album.id) }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var ejecting by remember { mutableStateOf(false) }
    val playingAlbumId by rememberUpdatedState(player.metadata.extras?.getLong(MediaItemMapper.EXTRA_ALBUM_ID, -1L))

    LaunchedEffect(album.id) {
        progress.snapTo(0f)
        progress.animateTo(CD_LOADED, tween(LOAD_MS, easing = LinearEasing))
    }
    // Disque chargé : lecture de la piste 1, sauf si cet album est déjà celui qui joue.
    LaunchedEffect(album.id, tracks.isNotEmpty()) {
        if (tracks.isEmpty()) return@LaunchedEffect
        snapshotFlow { progress.value >= CD_LOADED }.first { it }
        if (!ejecting && playingAlbumId != album.id) vm.play(tracks, 0)
    }

    fun eject() {
        if (ejecting) return
        ejecting = true
        if (player.isPlaying) vm.player.togglePlay()
        scope.launch {
            progress.animateTo(0f, tween((LOAD_MS * progress.value / CD_LOADED).roundToInt(), easing = LinearEasing))
            onEjected()
        }
    }
    BackHandler { eject() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val px = { dp: Dp -> with(density) { dp.toPx() } }
        val toDp = { v: Float -> with(density) { v.toDp() } }
        val p = progress.value
        val a = step(p, 0f)
        val b = step(p, 1f)
        val c = (p - 2f).coerceIn(0f, 1f)
        val d = step(p, 3f)

        // ---- Géométrie (px, repère de l'écran) ----
        val panelW = w * 0.36f
        val cover = px(coverSize)
        val thumb = px(72.dp)
        val pad = px(20.dp)
        val sleeve0 = Offset(w / 2f, px(coverTop) + cover / 2f)
        val sleeve1 = Offset(w * 0.25f, h * 0.46f)
        val sleeve3 = Offset(pad + thumb / 2f, pad + thumb / 2f)
        val sleeveCenter = lerp(lerp(sleeve0, sleeve1, a), sleeve3, d)
        val sleeveSize = lerp(lerp(cover, cover * 0.82f, a), thumb, d)
        val sleeveRotY = lerp(22f * a, 0f, d)

        val rightW = w - panelW
        val deckW = minOf(rightW * 0.86f, h * 0.44f / 0.40f)
        val deckH = deckW * 0.40f
        val deckLeft = panelW + (rightW - deckW) / 2f
        val deckTop = h * 0.40f - deckH / 2f
        val appear = FastOutSlowInEasing.transform(((p - 0.3f) / 0.7f).coerceIn(0f, 1f))

        val slotY = deckTop + deckH * 0.24f
        val slotL = deckLeft + deckW * 0.05f
        val slotW = deckW * 0.55f
        val trayW = slotW * 0.94f
        val trayCx = slotL + slotW / 2f
        val trayDepth = deckH * 0.95f
        val open = b * (1f - d)
        val discInTray = trayW * 0.62f
        fun trayDiscY(openFraction: Float) = slotY + trayDepth * openFraction - trayDepth * 0.45f

        // ---- Platine ----
        HiFiDeck(
            trackNumber = tracks.indexOfFirst { it.id == player.mediaId }.takeIf { it >= 0 }?.plus(1),
            trackCount = tracks.size,
            positionMs = rememberLivePosition(player.positionMs, player.isPlaying).value,
            status = when {
                open > 0.05f -> "OPEN"
                ejecting -> "EJECT"
                p < CD_LOADED -> "READING"
                player.isPlaying -> "▶ PLAY"
                player.positionMs > 0 -> "❚❚ PAUSE"
                else -> "■ STOP"
            },
            playing = player.isPlaying,
            enabled = p >= CD_LOADED && !ejecting,
            lcdTextSize = with(density) { (deckH * 0.15f).toSp() },
            onPrevious = { vm.player.previous() },
            onPlayPause = { vm.player.togglePlay() },
            onStop = { if (player.isPlaying) vm.player.togglePlay(); vm.player.seekTo(0) },
            onNext = { vm.player.next() },
            onEject = ::eject,
            modifier = Modifier
                .offset { IntOffset(deckLeft.roundToInt(), deckTop.roundToInt()) }
                .size(toDp(deckW), toDp(deckH))
                .graphicsLayer {
                    alpha = appear
                    translationX = (1f - appear) * rightW * 0.5f
                    rotationY = (1f - appear) * -35f
                    cameraDistance = 18f * density.density
                },
        )

        // ---- Tiroir (et disque posé dedans), coupé au niveau de la fente ----
        val boxLeft = slotL - slotW * 0.1f
        Box(
            Modifier
                .zIndex(0.5f)
                .offset { IntOffset(boxLeft.roundToInt(), slotY.roundToInt()) }
                .size(toDp(slotW * 1.2f), toDp(trayDepth + px(12.dp)))
                .clipToBounds(),
        ) {
            if (open > 0f) Tray(open, trayDepth, trayW, inset = (slotW * 1.2f - trayW) / 2f, Modifier.fillMaxSize())
            if (p >= 3f) {
                CompactDisc(
                    album.artworkPath,
                    Modifier
                        .offset {
                            IntOffset((trayCx - boxLeft - discInTray / 2f).roundToInt(), (trayDiscY(open) - slotY - discInTray / 2f).roundToInt())
                        }
                        .size(toDp(discInTray))
                        .graphicsLayer { rotationX = 68f; cameraDistance = 14f * density.density },
                )
            }
        }

        // ---- Disque en vol : sort de la pochette (derrière elle), puis rejoint le tiroir ----
        if (p < 3f) {
            val slide = FastOutSlowInEasing.transform(((p - 0.25f) / 0.75f).coerceIn(0f, 1f))
            val outPos = sleeveCenter + Offset(sleeveSize * 0.62f * slide, 0f)
            val outSize = sleeveSize * 0.92f
            val move = FastOutSlowInEasing.transform((c / 0.75f).coerceIn(0f, 1f))
            val drop = FastOutSlowInEasing.transform(((c - 0.75f) / 0.25f).coerceIn(0f, 1f))
            val target = Offset(trayCx, trayDiscY(1f))
            val above = target - Offset(0f, h * 0.20f)
            val pos = lerp(lerp(outPos, above, move), target, drop)
            val size = lerp(outSize, discInTray, move)
            if (slide > 0f) {
                CompactDisc(
                    album.artworkPath,
                    Modifier
                        .zIndex(if (p < 1f) 1f else 3f)
                        .offset { IntOffset((pos.x - size / 2f).roundToInt(), (pos.y - size / 2f).roundToInt()) }
                        .size(toDp(size))
                        .graphicsLayer {
                            rotationX = 68f * move
                            rotationZ = 200f * slide + 160f * c
                            cameraDistance = 14f * density.density
                        },
                )
            }
        }

        // ---- Pochette ----
        Box(
            Modifier
                .zIndex(2f)
                .offset { IntOffset((sleeveCenter.x - sleeveSize / 2f).roundToInt(), (sleeveCenter.y - sleeveSize / 2f).roundToInt()) }
                .size(toDp(sleeveSize))
                .graphicsLayer { rotationY = sleeveRotY; cameraDistance = 20f * density.density }
                .shadow(12.dp, RoundedCornerShape(3.dp)),
        ) {
            Artwork(album.artworkPath, Modifier.fillMaxSize())
        }

        // ---- Liste des titres à gauche ----
        if (d > 0f) {
            TrackPanel(
                album = album,
                tracks = tracks,
                playingId = player.mediaId,
                headerSpace = thumb,
                onPlay = { vm.play(tracks, it) },
                modifier = Modifier
                    .zIndex(1.5f)
                    .width(toDp(panelW))
                    .fillMaxHeight()
                    .graphicsLayer { alpha = d; translationX = -(1f - d) * panelW * 0.25f },
            )
            val current = tracks.firstOrNull { it.id == player.mediaId }
            Column(
                Modifier
                    .offset { IntOffset(panelW.roundToInt(), (slotY + trayDepth + px(12.dp)).roundToInt()) }
                    .width(toDp(rightW))
                    .graphicsLayer { alpha = d },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(current?.title.orEmpty(), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(current?.artist.orEmpty(), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun step(p: Float, start: Float) = FastOutSlowInEasing.transform((p - start).coerceIn(0f, 1f))
private fun lerp(x: Float, y: Float, t: Float) = x + (y - x) * t
private fun lerp(x: Offset, y: Offset, t: Float) = Offset(lerp(x.x, y.x, t), lerp(x.y, y.y, t))

/** Face imprimée d'un CD : pochette en étiquette, anneau transparent, trou central, reflet irisé. */
@Composable
private fun CompactDisc(artworkPath: String?, modifier: Modifier) {
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        Artwork(artworkPath, Modifier.fillMaxSize().clip(CircleShape))
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(
                Brush.sweepGradient(
                    listOf(
                        Color.Transparent, Color(0x40FF6EC7), Color.Transparent, Color(0x4046E6FF),
                        Color.Transparent, Color(0x40FFF176), Color.Transparent,
                    ),
                ),
                radius = r,
            )
            drawCircle(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.28f), Color.Transparent, Color.White.copy(alpha = 0.12f))), radius = r)
            drawCircle(Color(0xFFE6E6E6), radius = r * 0.985f, style = Stroke(r * 0.03f))
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFFF2F2F2), Color(0xFF9A9A9A), Color(0xFFE0E0E0)), radius = r * 0.33f, center = center),
                radius = r * 0.33f,
            )
            drawCircle(Color(0xFFBDBDBD), radius = r * 0.20f, style = Stroke(r * 0.012f))
            drawCircle(Color.Black, radius = r * 0.075f, blendMode = BlendMode.Clear)
        }
    }
}

/** Plateau du tiroir vu de face, qui s'avance vers l'utilisateur ([open] de 0 à 1). */
@Composable
private fun Tray(open: Float, depth: Float, trayW: Float, inset: Float, modifier: Modifier) {
    Canvas(modifier) {
        val visible = depth * open
        val spread = trayW * 0.05f * open
        val top = Path().apply {
            moveTo(inset, 0f)
            lineTo(inset + trayW, 0f)
            lineTo(inset + trayW + spread, visible)
            lineTo(inset - spread, visible)
            close()
        }
        drawPath(top, Brush.verticalGradient(listOf(Color(0xFF101012), Color(0xFF2C2C31)), endY = visible.coerceAtLeast(1f)))
        val recessW = trayW * 0.70f
        val recessH = recessW * 0.37f
        val recessCy = visible - depth * 0.45f
        drawOval(
            Color(0xFF060607),
            topLeft = Offset(inset + (trayW - recessW) / 2f, recessCy - recessH / 2f),
            size = Size(recessW, recessH),
        )
        val front = 10.dp.toPx() * open
        drawRect(
            Brush.verticalGradient(listOf(Color(0xFF3A3A40), Color(0xFF1A1A1D)), startY = visible, endY = visible + front),
            topLeft = Offset(inset - spread, visible),
            size = Size(trayW + 2 * spread, front),
        )
    }
}

/** Façade de la platine : fente du tiroir, marque, écran LCD et boutons de transport. */
@Composable
private fun HiFiDeck(
    trackNumber: Int?,
    trackCount: Int,
    positionMs: Long,
    status: String,
    playing: Boolean,
    enabled: Boolean,
    lcdTextSize: TextUnit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onNext: () -> Unit,
    onEject: () -> Unit,
    modifier: Modifier,
) {
    BoxWithConstraints(
        modifier
            .shadow(18.dp, RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF34343A), Color(0xFF1B1B1F), Color(0xFF141416))), RoundedCornerShape(10.dp))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)), RoundedCornerShape(10.dp)),
    ) {
        val dw = maxWidth
        val dh = maxHeight
        // Fente du tiroir.
        Box(
            Modifier
                .offset(x = dw * 0.05f, y = dh * 0.24f - 3.dp)
                .size(dw * 0.55f, 6.dp)
                .background(Color.Black, RoundedCornerShape(2.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(2.dp)),
        )
        Column(Modifier.offset(x = dw * 0.05f, y = dh * 0.50f)) {
            Text("TASHU", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text("COMPACT DISC PLAYER", color = Color.White.copy(alpha = 0.45f), fontSize = 8.sp, letterSpacing = 1.5.sp)
        }
        Box(
            Modifier
                .offset(x = dw * 0.05f, y = dh * 0.84f)
                .size(6.dp)
                .background(Color(0xFF4FC3F7), CircleShape),
        )
        // Écran LCD.
        Column(
            Modifier
                .offset(x = dw * 0.64f, y = dh * 0.12f)
                .size(dw * 0.32f, dh * 0.42f)
                .background(LcdBack, RoundedCornerShape(4.dp))
                .border(1.dp, Color.Black, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text(
                    trackNumber?.let { "%02d".format(it) } ?: "--",
                    color = LcdGreen, fontSize = lcdTextSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                )
                Text(
                    (positionMs / 1000).let { "%d:%02d".format(it / 60, it % 60) },
                    color = LcdGreen, fontSize = lcdTextSize, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(status, color = LcdGreen.copy(alpha = 0.8f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text(if (trackCount > 0) "$trackCount TR" else "", color = LcdGreen.copy(alpha = 0.55f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
        // Boutons de transport.
        Row(
            Modifier
                .offset(x = dw * 0.62f, y = dh * 0.62f)
                .size(dw * 0.36f, dh * 0.26f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val button = minOf(dw * 0.36f / 5.6f, dh * 0.24f)
            DeckButton(Icons.Rounded.SkipPrevious, "Piste précédente", button, enabled, onPrevious)
            DeckButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Lecture", button, enabled, onPlayPause)
            DeckButton(Icons.Rounded.Stop, "Stop", button, enabled, onStop)
            DeckButton(Icons.Rounded.SkipNext, "Piste suivante", button, enabled, onNext)
            DeckButton(Icons.Rounded.Eject, "Éjecter le CD", button, enabled, onEject)
        }
    }
}

@Composable
private fun DeckButton(icon: ImageVector, label: String, size: Dp, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .shadow(3.dp, CircleShape)
            .background(Brush.verticalGradient(listOf(Color(0xFF5A5A62), Color(0xFF2A2A2F))), CircleShape)
            .border(0.5.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White.copy(alpha = if (enabled) 0.9f else 0.35f), modifier = Modifier.size(size * 0.6f))
    }
}

/** Liste des titres du CD chargé ; la pochette animée vient se poser dans l'espace réservé de l'en-tête. */
@Composable
private fun TrackPanel(
    album: Album,
    tracks: List<Track>,
    playingId: String?,
    headerSpace: Float,
    onPlay: (Int) -> Unit,
    modifier: Modifier,
) {
    val header = with(LocalDensity.current) { headerSpace.toDp() }
    Column(
        modifier
            .background(Brush.horizontalGradient(listOf(Color(0xF0101013), Color(0xE0101013), Color(0x00101013))))
            .padding(start = 20.dp, top = 20.dp, end = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.size(header))
            Column(Modifier.padding(start = 14.dp)) {
                Text(album.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(album.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
                val playing = t.id == playingId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (playing) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                        .clickable { onPlay(i) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (playing) "▶" else (t.trackNumber ?: (i + 1)).toString(),
                        color = if (playing) LcdGreen else Color.White.copy(alpha = 0.45f), fontSize = 12.sp,
                        textAlign = TextAlign.End, modifier = Modifier.width(22.dp).padding(end = 8.dp),
                    )
                    Text(
                        t.title, color = if (playing) LcdGreen else Color.White, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Text(
                        (t.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) },
                        color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
            }
        }
    }
}
