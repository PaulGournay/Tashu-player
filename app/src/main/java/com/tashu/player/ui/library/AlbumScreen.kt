package com.tashu.player.ui.library

import com.tashu.player.ui.theme.AppBackground
import com.tashu.player.ui.theme.glassColors
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.Track
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.theme.GlassCircle
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.bubbleClickable
import com.tashu.player.ui.theme.glassSource
import com.tashu.player.ui.theme.liquidGlass
import com.tashu.player.ui.theme.rememberArtworkSeed
import com.tashu.player.ui.theme.rememberGlassBackdrop
import com.tashu.player.ui.theme.scrollEdgeBlur

private enum class TrackSort(val label: String) { NUMBER("Numéro de piste"), TITLE("Titre"), DURATION("Durée"), PLAYS("Nombre d'écoutes") }

/**
 * Écran d'album, même langage que l'accueil (« Liquid Glass ») : nuage aux couleurs de la pochette en fond,
 * en-tête façon Apple Music (grande pochette, Lecture / Aléatoire), liste de titres épurée,
 * bouton retour et mini-lecteur en verre, bord de défilement flouté en haut.
 */
@Composable
fun AlbumScreen(vm: MainViewModel, albumId: Long, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val album by remember(albumId) { vm.album(albumId) }.collectAsStateWithLifecycle(null)
    val tracks by remember(albumId) { vm.albumTracks(albumId) }.collectAsStateWithLifecycle(emptyList())
    val playingId = vm.player.state.collectAsStateWithLifecycle().value.mediaId
    val artwork = album?.artworkPath ?: tracks.firstOrNull()?.artworkPath
    val seed by rememberArtworkSeed(artwork)
    val backdrop = rememberGlassBackdrop()
    val list = rememberLazyListState()

    var sort by rememberSaveable { mutableStateOf(TrackSort.NUMBER) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val sorted = remember(tracks, sort, descending) {
        val base = when (sort) {
            TrackSort.NUMBER -> tracks.sortedWith(compareBy<Track>({ it.discNumber ?: 0 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.title }))
            TrackSort.TITLE -> tracks.sortedBy { it.title.lowercase() }
            TrackSort.DURATION -> tracks.sortedBy { it.durationMs }
            TrackSort.PLAYS -> tracks.sortedBy { it.playCount }
        }
        if (descending) base.reversed() else base
    }
    // Le titre compact apparaît en haut une fois l'en-tête dépassé.
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 700 } }
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, label = "title")

    TashuTheme(seed = seed) {
        val g = glassColors()
        CompositionLocalProvider(LocalContentColor provides g.ink) {
            val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val bottomSpace = nav + 24.dp + if (playingId != null) 80.dp else 0.dp

            Box(Modifier.fillMaxSize().background(g.base)) {
                Box(Modifier.fillMaxSize().glassSource(backdrop)) {
                    AppBackground(artwork)
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = status + 56.dp, bottom = bottomSpace),
                    ) {
                        item(key = "header") {
                            AlbumHeader(
                                album = album, artwork = artwork, tracks = tracks,
                                onPlay = { if (sorted.isNotEmpty()) { vm.play(sorted, 0); onOpenPlayer() } },
                                onShuffle = { vm.player.playShuffled(sorted); onOpenPlayer() },
                                onPlayLast = { vm.player.enqueue(sorted, next = false) },
                            )
                        }
                        item(key = "sort") {
                            SortRow(
                                tracks = tracks, sort = sort, descending = descending,
                                onSort = { s -> if (s == sort) descending = !descending else { sort = s; descending = false } },
                                onToggleDirection = { descending = !descending },
                            )
                        }
                        itemsIndexed(sorted, key = { _, t -> t.id }) { i, t ->
                            TrackRow(
                                track = t,
                                number = if (sort == TrackSort.NUMBER && !descending) t.trackNumber ?: (i + 1) else i + 1,
                                albumArtist = album?.artist,
                                playing = t.id == playingId,
                                onClick = { vm.play(sorted, i); onOpenPlayer() },
                                onFavorite = { vm.toggleFavorite(t) },
                                onPlayNext = { vm.player.enqueue(listOf(t), next = true) },
                                onPlayLast = { vm.player.enqueue(listOf(t), next = false) },
                            )
                        }
                    }
                }

                // Bord de défilement flouté, puis barre : retour en verre + titre compact.
                Box(Modifier.fillMaxWidth().height(status + 84.dp).scrollEdgeBlur(backdrop))
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassCircle(backdrop, Icons.AutoMirrored.Rounded.ArrowBack, "Retour", onClick = onBack)
                    Text(
                        album?.title.orEmpty(),
                        fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp).graphicsLayer { alpha = titleAlpha },
                    )
                    Spacer(Modifier.width(40.dp))
                }

                AnimatedVisibility(
                    playingId != null, enter = fadeIn(), exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    GlassMiniPlayer(vm, backdrop, onOpenPlayer)
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    album: Album?,
    artwork: String?,
    tracks: List<Track>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onPlayLast: () -> Unit,
) {
    val g = glassColors()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val shape = RoundedCornerShape(14.dp)
        AsyncImage(
            model = artwork, contentDescription = "Pochette", contentScale = ContentScale.Crop,
            modifier = Modifier
                .padding(top = 8.dp)
                .size(248.dp)
                .shadow(30.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(g.fill(0.06f)),
        )
        Text(
            album?.title.orEmpty(),
            fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = g.ink,
            textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            album?.artist.orEmpty(),
            fontSize = 19.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            listOfNotNull(album?.year?.toString(), titlesLabel(tracks), totalDuration(tracks).takeIf { tracks.isNotEmpty() }).joinToString(" · "),
            fontSize = 13.sp, color = g.ink.copy(alpha = 0.55f), modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HeaderButton(Icons.Rounded.PlayArrow, "Lecture", primary = true, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f), onClick = onPlay)
            HeaderButton(Icons.Rounded.Shuffle, "Aléatoire", primary = false, enabled = tracks.isNotEmpty(), modifier = Modifier.weight(1f), onClick = onShuffle)
        }
        Row(
            Modifier
                .padding(top = 10.dp)
                .bubbleClickable(pressedScale = 0.94f, onClick = onPlayLast)
                .clip(CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = g.ink.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            Text("Jouer en dernier", fontSize = 13.sp, color = g.ink.copy(alpha = 0.7f), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

/** Lecture = pastille blanche pleine ; Aléatoire = pastille de verre. */
@Composable
private fun HeaderButton(icon: ImageVector, label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val g = glassColors()
    Row(
        modifier
            .bubbleClickable(pressedScale = 0.94f) { if (enabled) onClick() }
            .height(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (primary) g.ink else g.ink.copy(alpha = 0.12f))
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (primary) g.onInk else g.ink
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun SortRow(tracks: List<Track>, sort: TrackSort, descending: Boolean, onSort: (TrackSort) -> Unit, onToggleDirection: () -> Unit) {
    val g = glassColors()
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(titlesLabel(tracks), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.ink.copy(alpha = 0.55f))
        Spacer(Modifier.weight(1f))
        Box {
            var open by remember { mutableStateOf(false) }
            Text(
                sort.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.ink, maxLines = 1,
                modifier = Modifier
                    .bubbleClickable(pressedScale = 0.92f) { open = true }
                    .clip(CircleShape)
                    .background(g.fill(0.10f))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false },
                containerColor = g.menu, shape = RoundedCornerShape(16.dp),
            ) {
                TrackSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label, color = if (s == sort) MaterialTheme.colorScheme.primary else g.ink) },
                        onClick = { onSort(s); open = false },
                    )
                }
            }
        }
        Box(
            Modifier.padding(start = 6.dp).size(34.dp).bubbleClickable(pressedScale = 0.85f, onClick = onToggleDirection),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = if (descending) "Ordre décroissant, inverser" else "Ordre croissant, inverser",
                tint = g.ink.copy(alpha = 0.8f), modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Ligne de titre façon Apple Music : numéro (ou égaliseur si en lecture), titre, artiste invité, durée, favori, menu. */
@Composable
private fun TrackRow(
    track: Track,
    number: Int,
    albumArtist: String?,
    playing: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLast: () -> Unit,
) {
    val g = glassColors()
    val accent = MaterialTheme.colorScheme.primary
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .bubbleClickable(pressedScale = 0.98f, onClick = onClick)
                .padding(start = 24.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                if (playing) Icon(Icons.Rounded.Equalizer, contentDescription = "En lecture", tint = accent, modifier = Modifier.size(18.dp))
                else Text(number.toString(), fontSize = 15.sp, color = g.ink.copy(alpha = 0.45f))
            }
            Column(Modifier.weight(1f).padding(start = 6.dp, end = 8.dp)) {
                Text(
                    track.title, fontSize = 16.sp, fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (playing) accent else g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // L'artiste n'est rappelé que s'il diffère de celui de l'album (featurings, compilations).
                if (!track.artist.equals(albumArtist, ignoreCase = true)) {
                    Text(track.artist, fontSize = 13.sp, color = g.ink.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(formatDuration(track.durationMs), fontSize = 13.sp, color = g.ink.copy(alpha = 0.45f))
            val fav = track.rating >= 5
            Box(Modifier.size(40.dp).bubbleClickable(pressedScale = 0.8f, onClick = onFavorite), contentAlignment = Alignment.Center) {
                Icon(
                    if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = if (fav) "Retirer des favoris" else "Ajouter aux favoris",
                    tint = if (fav) accent else g.ink.copy(alpha = 0.35f), modifier = Modifier.size(20.dp),
                )
            }
            Box {
                var open by remember { mutableStateOf(false) }
                Box(Modifier.size(36.dp).bubbleClickable(pressedScale = 0.8f) { open = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MoreHoriz, contentDescription = "Plus d'options pour ${track.title}", tint = g.ink.copy(alpha = 0.6f))
                }
                DropdownMenu(
                    expanded = open, onDismissRequest = { open = false },
                    containerColor = g.menu, shape = RoundedCornerShape(16.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text("Lire ensuite", color = g.ink) },
                        leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null, tint = g.ink) },
                        onClick = { onPlayNext(); open = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Jouer en dernier", color = g.ink) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, tint = g.ink) },
                        onClick = { onPlayLast(); open = false },
                    )
                }
            }
        }
        Box(Modifier.padding(start = 58.dp, end = 16.dp).fillMaxWidth().height(0.5.dp).background(g.line(0.10f)))
    }
}

private fun titlesLabel(tracks: List<Track>) = if (tracks.size == 1) "1 titre" else "${tracks.size} titres"

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun totalDuration(tracks: List<Track>): String {
    val min = tracks.sumOf { it.durationMs } / 60_000
    return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
}
