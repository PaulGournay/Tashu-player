package com.tashu.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.Track
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.rememberArtworkSeed

private enum class TrackSort(val label: String) { NUMBER("Numéro de piste"), TITLE("Titre"), DURATION("Durée"), PLAYS("Nombre d'écoutes") }

/**
 * Écran d'album : en-tête (pochette, titre, artiste, date, aléatoire, « Jouer en dernier »),
 * ligne « N titres - durée » avec tri, puis une carte par morceau
 * (vignette numérotée, titre, artiste, « album • année », durée, favori, menu).
 * Toutes les couleurs viennent du thème calculé sur la pochette.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(vm: MainViewModel, albumId: Long, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val album by remember(albumId) { vm.album(albumId) }.collectAsStateWithLifecycle(null)
    val tracks by remember(albumId) { vm.albumTracks(albumId) }.collectAsStateWithLifecycle(emptyList())
    val playingId = vm.player.state.collectAsStateWithLifecycle().value.mediaId
    val artwork = album?.artworkPath ?: tracks.firstOrNull()?.artworkPath
    val seed by rememberArtworkSeed(artwork)

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

    TashuTheme(seed = seed) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Retour") } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            },
            bottomBar = { if (playingId != null) MiniPlayer(vm, onOpenPlayer) },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().consumeWindowInsets(padding),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item(key = "header") {
                    AlbumHeader(
                        album = album, artwork = artwork, tracks = tracks,
                        sort = sort, descending = descending,
                        onSort = { s -> if (s == sort) descending = !descending else { sort = s; descending = false } },
                        onToggleDirection = { descending = !descending },
                        onShuffle = { vm.player.playShuffled(sorted); onOpenPlayer() },
                        onPlayLast = { vm.player.enqueue(sorted, next = false) },
                    )
                }
                itemsIndexed(sorted, key = { _, t -> t.id }) { i, t ->
                    TrackRow(
                        track = t,
                        playing = t.id == playingId,
                        onClick = { vm.play(sorted, i); onOpenPlayer() },
                        onFavorite = { vm.toggleFavorite(t) },
                        onPlayNext = { vm.player.enqueue(listOf(t), next = true) },
                        onPlayLast = { vm.player.enqueue(listOf(t), next = false) },
                    )
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
    sort: TrackSort,
    descending: Boolean,
    onSort: (TrackSort) -> Unit,
    onToggleDirection: () -> Unit,
    onShuffle: () -> Unit,
    onPlayLast: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = artwork, contentDescription = "Pochette", contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(132.dp)
                        .shadow(10.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                Column(Modifier.weight(1f).padding(start = 20.dp)) {
                    Text(
                        album?.title.orEmpty(),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(album?.artist.orEmpty(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    album?.year?.let {
                        Text(it.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            // Boutons sur leur propre ligne : le libellé reste entier même avec une grande police système.
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = onShuffle, enabled = tracks.isNotEmpty()) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = "Lecture aléatoire")
                }
                FilledTonalButton(onClick = onPlayLast, enabled = tracks.isNotEmpty(), modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Jouer en dernier", modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(20.dp))
                Text(
                    "${if (tracks.size == 1) "1 titre" else "${tracks.size} titres"} - ${totalDuration(tracks)}",
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, softWrap = false,
                    modifier = Modifier.padding(start = 8.dp),
                )
                Spacer(Modifier.weight(1f))
                Box {
                    var open by remember { mutableStateOf(false) }
                    FilledTonalButton(
                        onClick = { open = true },
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(sort.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        TrackSort.entries.forEach { s ->
                            DropdownMenuItem(text = { Text(s.label) }, onClick = { onSort(s); open = false })
                        }
                    }
                }
                IconButton(onClick = onToggleDirection) {
                    Icon(
                        if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                        contentDescription = if (descending) "Ordre décroissant, inverser" else "Ordre croissant, inverser",
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    playing: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onPlayLast: () -> Unit,
) {
    Surface(
        color = if (playing) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp)) {
                AsyncImage(
                    model = track.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                track.trackNumber?.let {
                    Text(
                        it.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(topStart = 6.dp, bottomEnd = 10.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(track.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track.album, track.year?.toString()).joinToString(" • "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatDuration(track.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
                    val fav = track.rating >= 5
                    Icon(
                        if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (fav) "Retirer des favoris" else "Ajouter aux favoris",
                        tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box {
                var open by remember { mutableStateOf(false) }
                IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Plus d'options pour ${track.title}") }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(
                        text = { Text("Lire ensuite") },
                        leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null) },
                        onClick = { onPlayNext(); open = false },
                    )
                    DropdownMenuItem(
                        text = { Text("Jouer en dernier") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                        onClick = { onPlayLast(); open = false },
                    )
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun totalDuration(tracks: List<Track>): String {
    val min = tracks.sumOf { it.durationMs } / 60_000
    return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)}" else "$min min"
}
