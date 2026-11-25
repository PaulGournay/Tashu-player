package com.tashu.player.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tashu.player.domain.model.Track
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.menu.AppDrawer
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.AlbumSort
import com.tashu.player.domain.model.AlbumSortOrder
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun AlbumsGrid(vm: MainViewModel, padding: PaddingValues, onOpenAlbum: (Long) -> Unit) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val order by vm.albumSortOrder.collectAsStateWithLifecycle()
    val columns by vm.albumGridColumns.collectAsStateWithLifecycle()
    // Espacements et textes adaptés à la densité : à 8 par ligne, pochettes seules.
    val gap = when { columns >= 6 -> 4.dp; columns >= 4 -> 8.dp; columns == 3 -> 12.dp; else -> 16.dp }
    if (albums.isEmpty()) {
        EmptyHint("Aucun album pour l'instant. L'indexation de vos dossiers Drive les fera apparaître ici.", padding)
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        contentPadding = PaddingValues(
            start = if (columns >= 6) 8.dp else 16.dp, end = if (columns >= 6) 8.dp else 16.dp,
            top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(when { columns == 1 -> 2.dp; columns >= 4 -> gap; else -> 20.dp }),
    ) {
        item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
            AlbumSortBar(
                order = order,
                count = albums.size,
                onSelect = vm::selectAlbumSort,
                onToggleDirection = vm::toggleAlbumSortDirection,
            )
        }
        items(albums, key = { it.id }) { a ->
            if (columns == 1) AlbumListRow(a, order.sort) { onOpenAlbum(a.id) } else
            Column(Modifier.clickable { onOpenAlbum(a.id) }) {
                AsyncImage(
                    model = a.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
                when {
                    columns >= 5 -> Unit // pochettes seules
                    columns >= 3 -> Text(
                        a.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    else -> {
                        Text(
                            a.title,
                            style = if (columns == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(a.artist, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        albumSortDetail(a, order.sort)?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Affichage « 1 par ligne » : ligne fine, pochette à gauche, infos à droite. */
@Composable
private fun AlbumListRow(a: Album, sort: AlbumSort, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = a.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(a.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val minutes = a.durationMs / 60_000
            val facts = listOfNotNull(
                a.year?.toString(),
                if (a.trackCount == 1) "1 morceau" else "${a.trackCount} morceaux",
                if (minutes >= 60) "${minutes / 60} h ${"%02d".format(minutes % 60)}" else "$minutes min",
            ).joinToString(", ")
            Text(
                albumSortDetail(a, sort)?.takeIf { sort != AlbumSort.YEAR && sort != AlbumSort.TRACK_COUNT && sort != AlbumSort.DURATION } ?: facts,
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** « Trier par : Artiste » (menu des critères) + bouton de sens. Re-choisir le critère actif inverse le sens. */
@Composable
private fun AlbumSortBar(
    order: AlbumSortOrder,
    count: Int,
    onSelect: (AlbumSort) -> Unit,
    onToggleDirection: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (count == 1) "1 album" else "$count albums",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menuOpen = true }) {
                Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(order.sort.label, modifier = Modifier.padding(start = 8.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                AlbumSort.entries.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s.label) },
                        trailingIcon = {
                            if (s == order.sort) Icon(
                                if (order.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                                contentDescription = if (order.descending) "Décroissant" else "Croissant",
                            )
                        },
                        onClick = { onSelect(s); menuOpen = false },
                    )
                }
            }
        }
        IconButton(onClick = onToggleDirection) {
            Icon(
                if (order.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                contentDescription = if (order.descending) "Ordre décroissant, inverser" else "Ordre croissant, inverser",
            )
        }
    }
}

/** Valeur du critère de tri affichée sous l'album quand elle n'est pas déjà visible (titre, artiste). */
private fun albumSortDetail(a: Album, sort: AlbumSort): String? = when (sort) {
    AlbumSort.TITLE, AlbumSort.ARTIST -> null
    AlbumSort.YEAR -> a.year?.toString() ?: "Année inconnue"
    AlbumSort.DATE_ADDED -> a.dateAdded.takeIf { it > 0 }?.let { "Ajouté le " + DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRENCH).format(Date(it)) }
    AlbumSort.MOST_PLAYED -> when (a.playCount) { 0 -> "Jamais écouté"; 1 -> "1 écoute"; else -> "${a.playCount} écoutes" }
    AlbumSort.LAST_PLAYED -> a.lastPlayedAt?.let { "Écouté le " + DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRENCH).format(Date(it)) } ?: "Jamais écouté"
    AlbumSort.TRACK_COUNT -> if (a.trackCount == 1) "1 morceau" else "${a.trackCount} morceaux"
    AlbumSort.DURATION -> (a.durationMs / 60_000).let { m -> if (m >= 60) "${m / 60} h ${"%02d".format(m % 60)}" else "$m min" }
}

@Composable
fun TrackList(tracks: List<Track>, padding: PaddingValues, playingId: String?, onPlay: (List<Track>, Int) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            val playing = t.id == playingId
            ListItem(
                headlineContent = {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                },
                supportingContent = { Text("${t.artist}, ${t.album}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = {
                    Text(
                        listOfNotNull(t.codec.label.takeIf { t.codec.lossless }, t.sampleRateHz?.takeIf { it > 48_000 }?.let { "${it / 1000}k" }).joinToString(" "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary,
                    )
                },
                modifier = Modifier.clickable { onPlay(tracks, i) },
            )
        }
    }
}

/** Mode Fichiers : arborescence Drive telle qu'indexée. */
@Composable
internal fun FolderBrowser(vm: MainViewModel, padding: PaddingValues) {
    val stack = remember { mutableStateListOf<Pair<String, String>>() } // (id, nom)
    val currentId = stack.lastOrNull()?.first
    val roots by vm.rootFolders.collectAsStateWithLifecycle()
    val folders by remember(currentId) {
        currentId?.let(vm::subFolders) ?: flowOf(emptyList())
    }.collectAsStateWithLifecycle(emptyList())
    val tracks by remember(currentId) { currentId?.let(vm::folderTracks) ?: flowOf(emptyList()) }.collectAsStateWithLifecycle(emptyList())
    val playingId = vm.player.state.collectAsStateWithLifecycle().value.mediaId

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        if (stack.isNotEmpty()) item {
            ListItem(
                headlineContent = { Text(stack.joinToString(" / ") { it.second }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Dossier parent") },
                modifier = Modifier.clickable { stack.removeAt(stack.lastIndex) },
            )
        }
        if (currentId == null) {
            if (roots.isEmpty()) item { Text("Les dossiers apparaissent après la première indexation.", Modifier.padding(16.dp)) }
            items(roots, key = { it.id }) { f ->
                ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(Icons.Rounded.Folder, null) },
                    modifier = Modifier.clickable { stack.add(f.id to f.name) })
            }
        }
        items(folders, key = { it.id }) { f ->
            ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(Icons.Rounded.Folder, null) },
                modifier = Modifier.clickable { stack.add(f.id to f.name) })
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            ListItem(
                headlineContent = { Text(t.title, color = if (t.id == playingId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                supportingContent = { Text(t.artist) },
                modifier = Modifier.clickable { vm.play(tracks, i) },
            )
        }
    }
}

@Composable
internal fun SmartPlaylists(vm: MainViewModel, padding: PaddingValues) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        MainViewModel.SMART_PRESETS.forEachIndexed { idx, (name, def) ->
            item(key = "smart$idx") {
                val tracks by remember(def) { vm.smart(def) }.collectAsStateWithLifecycle(emptyList())
                ListItem(
                    headlineContent = { Text(name) },
                    supportingContent = { Text(if (tracks.size == 1) "1 morceau" else "${tracks.size} morceaux") },
                    trailingContent = {
                        IconButton(onClick = { if (tracks.isNotEmpty()) vm.play(tracks, 0, queueName = name) }, enabled = tracks.isNotEmpty()) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Lire $name dans une nouvelle file")
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun MiniPlayer(vm: MainViewModel, onOpen: () -> Unit) {
    val s by vm.player.state.collectAsStateWithLifecycle()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.navigationBarsPadding()) {
            LinearProgressIndicator(
                progress = { if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = s.metadata.artworkUri?.path, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.metadata.title?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.metadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { vm.player.togglePlay() }) {
                    Icon(if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (s.isPlaying) "Pause" else "Lecture")
                }
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, padding: PaddingValues) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
