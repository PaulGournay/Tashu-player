package com.tashu.player.ui.nowplaying

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Close
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.tashu.player.domain.model.NowPlayingStyle
import com.tashu.player.domain.repository.LyricsState
import com.tashu.player.playback.AudioInfo
import com.tashu.player.playback.PlayerUiState
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.rememberArtworkSeed

/**
 * Lecteur plein écran. Le thème entier est régénéré depuis la pochette courante.
 * Trois styles : CLASSIC (pochette + contrôles), IMMERSIVE (pochette floutée plein cadre),
 * MINIMAL (typographie dominante, petite pochette). Le "chemin du signal" (source → sortie)
 * est l'élément distinctif : il dit exactement ce qui arrive au DAC.
 */
@Composable
fun NowPlayingScreen(onClose: () -> Unit, vm: NowPlayingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val style by vm.style.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsSearch by vm.search.collectAsStateWithLifecycle()
    val lyricsOnline by vm.lyricsOnline.collectAsStateWithLifecycle()
    val lyricsScale by vm.lyricsFontScale.collectAsStateWithLifecycle()
    val lyricsFullScale by vm.lyricsFullscreenFontScale.collectAsStateWithLifecycle()
    val rating by vm.rating.collectAsStateWithLifecycle()
    val artworkPath = state.metadata.artworkUri?.path
    val seed by rememberArtworkSeed(artworkPath)
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var coverFlipped by rememberSaveable { mutableStateOf(false) }
    var fullscreenLyrics by rememberSaveable { mutableStateOf(false) }

    @Composable
    fun lyricsPanel(fullscreen: Boolean, modifier: Modifier) = LyricsPanel(
        ui = lyrics, player = state,
        fontScale = if (fullscreen) lyricsFullScale else lyricsScale,
        onlineEnabled = lyricsOnline, fullscreen = fullscreen,
        onSeek = { vm.player.seekTo(it) }, onShift = { vm.shiftLyrics(it) }, onSearch = { vm.openSearch() },
        onIgnore = { vm.ignoreLyrics() }, onReset = { vm.resetLyrics() }, onToggleOnline = { vm.setLyricsOnline(it) },
        onFontScale = { vm.setLyricsFontScale(fullscreen, it) },
        onFullscreen = if (fullscreen) null else ({ fullscreenLyrics = true }),
        modifier = modifier,
    )

    TashuTheme(seed = seed) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                if (style == NowPlayingStyle.IMMERSIVE && artworkPath != null) ImmersiveBackdrop(artworkPath)

                Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 24.dp),
                ) {
                    TopBar(
                        onClose = onClose,
                        album = state.metadata.albumTitle?.toString(),
                        onCycleStyle = { vm.setStyle(NowPlayingStyle.entries[(style.ordinal + 1) % NowPlayingStyle.entries.size]) },
                    )

                    AnimatedContent(
                        targetState = showLyrics,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = "artOrLyrics",
                    ) { lyricsVisible ->
                        if (lyricsVisible) {
                            lyricsPanel(fullscreen = false, Modifier.fillMaxSize())
                        } else {
                            // Plus grand carré qui tient dans la zone restante (largeur ET hauteur).
                            BoxWithConstraints(Modifier.fillMaxSize().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                val scale = if (style == NowPlayingStyle.MINIMAL) 0.55f else 1f
                                // Toucher la pochette : elle se retourne sur le visualiseur (et inversement).
                                FlippableArtwork(
                                    flipped = coverFlipped,
                                    onToggle = { coverFlipped = !coverFlipped },
                                    modifier = Modifier.size(minOf(maxWidth * scale, maxHeight)),
                                    front = { Artwork(artworkPath, Modifier.fillMaxSize()) },
                                    back = { CoverVisualizer(artworkPath, vm.spectrum, Modifier.fillMaxSize()) },
                                )
                            }
                        }
                    }

                    TitleBlock(state, style, rating, onRate = { vm.rate(it) })
                    Spacer(Modifier.height(4.dp))
                    SignalPath(state.audio)
                    Spacer(Modifier.height(8.dp))
                    SeekBar(state, onSeek = { vm.player.seekTo(it) })
                    Transport(state, vm)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        IconToggleButton(checked = showLyrics, onCheckedChange = { showLyrics = it }) {
                            Icon(
                                Icons.Rounded.Lyrics, contentDescription = "Paroles",
                                tint = if (showLyrics || lyrics.state is LyricsState.Found) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { showQueue = true }) {
                            Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "File d'attente")
                        }
                    }
                }
            }
        }
        if (fullscreenLyrics) {
            FullscreenLyrics(
                artworkPath = artworkPath,
                title = state.metadata.title?.toString().orEmpty(),
                artist = state.metadata.artist?.toString().orEmpty(),
                onClose = { fullscreenLyrics = false },
                controls = {
                    SeekBar(state, onSeek = { vm.player.seekTo(it) })
                    Transport(state, vm)
                },
                content = { lyricsPanel(fullscreen = true, it) },
            )
        }
        if (showQueue) {
            QueueSheet(
                state = state,
                onSkipTo = { vm.player.skipTo(it) },
                onRemove = { vm.player.removeFromQueue(it) },
                onDismiss = { showQueue = false },
            )
        }
        lyricsSearch?.let { search ->
            LyricsSearchSheet(search, onSearch = vm::runSearch, onPick = { vm.pick(it) }, onDismiss = vm::closeSearch)
        }
    }
}

@Composable
private fun TopBar(onClose: () -> Unit, album: String?, onCycleStyle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Réduire le lecteur") }
        Text(
            album.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = onCycleStyle) { Icon(Icons.Rounded.Style, contentDescription = "Changer le style du lecteur") }
    }
}

@Composable
private fun ImmersiveBackdrop(path: String) {
    AsyncImage(
        model = path, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(60.dp) else Modifier),
    )
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface.copy(alpha = 0.92f), MaterialTheme.colorScheme.surface))
        )
    )
}

@Composable
private fun Artwork(path: String?, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        tonalElevation = 2.dp,
        shadowElevation = 12.dp,
        modifier = modifier,
    ) {
        if (path != null) {
            AsyncImage(model = path, contentDescription = "Pochette", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer))
        }
    }
}

@Composable
private fun TitleBlock(state: PlayerUiState, style: NowPlayingStyle, rating: Int, onRate: (Int) -> Unit) {
    val m = state.metadata
    // « J'aime » = note 5 (même convention que le cœur de l'écran album) ; le retirer remet 0.
    var liked by remember(rating) { mutableStateOf(rating >= 5) }
    Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                m.title?.toString().orEmpty(),
                style = if (style == NowPlayingStyle.MINIMAL) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                m.artist?.toString().orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { liked = !liked; onRate(if (liked) 5 else 0) }) {
            Icon(
                if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (liked) "Retirer des titres aimés" else "J'aime",
                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
}

/** "FLAC 24 bits, 96 kHz, 2 ch, 2 304 kb/s" puis la sortie réelle (fréquence + périphérique). */
@Composable
private fun SignalPath(a: AudioInfo) {
    val source = buildList {
        a.codec?.let(::add)
        a.bitDepth?.let { add("$it bits") }
        a.sampleRateHz?.let { add(khz(it)) }
        a.channels?.let { add(if (it == 2) "stéréo" else "$it canaux") }
        a.bitrateKbps?.let { add("$it kb/s") }
    }.joinToString(", ")
    val output = buildString {
        append("Sortie ")
        append(a.outputSampleRateHz?.let(::khz) ?: "en attente")
        if (a.outputSampleRateHz != null && a.sampleRateHz != null && a.outputSampleRateHz != a.sampleRateHz) append(" (rééchantillonné)")
        append(" vers ")
        append(a.outputDevice ?: "le haut-parleur du téléphone")
    }
    // Discret par défaut : une ligne courte et pâle (format, fréquence, débit). Toucher = détail complet.
    var expanded by rememberSaveable { mutableStateOf(false) }
    val short = listOfNotNull(a.codec, a.sampleRateHz?.let(::khz), a.bitrateKbps?.let { "$it kb/s" }).joinToString("  ")
    val faded = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 2.dp)
            .semantics { contentDescription = "Format audio : $source. $output" },
    ) {
        Text(
            if (expanded) source.ifEmpty { "Format inconnu" } else short,
            style = MaterialTheme.typography.labelSmall, color = faded, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (expanded) Text(output, style = MaterialTheme.typography.labelSmall, color = faded)
    }
}

private fun khz(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz" else "%.1f kHz".format(hz / 1000f)

@Composable
private fun SeekBar(state: PlayerUiState, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = dragging ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        if (state.isBuffering) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        Slider(
            value = fraction,
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek((it * duration).toLong()) }; dragging = null },
            colors = SliderDefaults.colors(thumbColor = MaterialTheme.colorScheme.primary, activeTrackColor = MaterialTheme.colorScheme.primary),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(fmt((fraction * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("-" + fmt(duration - (fraction * duration).toLong()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun fmt(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun Transport(state: PlayerUiState, vm: NowPlayingViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconToggleButton(checked = state.shuffle, onCheckedChange = { vm.player.toggleShuffle() }) {
            Icon(Icons.Rounded.Shuffle, contentDescription = "Lecture aléatoire",
                tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { vm.player.previous() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipPrevious, contentDescription = "Précédent", modifier = Modifier.size(36.dp))
        }
        FilledIconButton(
            onClick = { vm.player.togglePlay() },
            shape = CircleShape,
            modifier = Modifier.size(76.dp).clip(CircleShape),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(
                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                modifier = Modifier.size(40.dp),
            )
        }
        IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Rounded.SkipNext, contentDescription = "Suivant", modifier = Modifier.size(36.dp))
        }
        IconButton(onClick = { vm.player.cycleRepeat() }) {
            Icon(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                contentDescription = "Répétition",
                tint = if (state.repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** File d'attente en cours : toucher = lire ce morceau, croix = le retirer. S'ouvre calée sur le morceau courant. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueSheet(state: PlayerUiState, onSkipTo: (Int) -> Unit, onRemove: (Int) -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (state.queueIndex - 1).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Text(
            if (state.queue.size == 1) "File d'attente, 1 morceau" else "File d'attente, ${state.queue.size} morceaux",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (state.queue.isEmpty()) {
            Text(
                "La file est vide. Lancez un album ou un morceau pour la remplir.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(state.queue, key = { i, item -> "$i:${item.mediaId}" }) { i, item ->
                val current = i == state.queueIndex
                val m = item.mediaMetadata
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                        .clickable { onSkipTo(i) }
                        .padding(start = 24.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = m.artworkUri?.path, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(
                            m.title?.toString().orEmpty(), style = MaterialTheme.typography.bodyLarge,
                            color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            m.artist?.toString().orEmpty(), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!current) {
                        IconButton(onClick = { onRemove(i) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Retirer ${m.title} de la file")
                        }
                    }
                }
            }
        }
    }
}
