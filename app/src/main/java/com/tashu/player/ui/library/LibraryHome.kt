package com.tashu.player.ui.library

import com.tashu.player.ui.theme.AppBackground
import com.tashu.player.ui.theme.GlassColors
import com.tashu.player.ui.theme.glassColors
import com.tashu.player.ui.theme.GlassBackdrop
import com.tashu.player.ui.theme.glassSource
import com.tashu.player.ui.theme.liquidGlass
import com.tashu.player.ui.theme.scrollEdgeBlur
import com.tashu.player.ui.theme.rememberGlassBackdrop
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.TransformOrigin
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.Artist
import com.tashu.player.domain.model.Track
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.SearchResults
import com.tashu.player.ui.menu.AppDrawer
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.GlassCircle
import com.tashu.player.ui.theme.bubbleClickable
import com.tashu.player.ui.theme.bubbleSpring
import com.tashu.player.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.launch

private enum class MainTab(val label: String, val icon: ImageVector) {
    HOME("Accueil", Icons.Rounded.Home),
    SEARCH("Recherche", Icons.Rounded.Search),
    LIBRARY("Bibliothèque", Icons.AutoMirrored.Rounded.LibraryBooks),
}

private enum class LibrarySection(val label: String) { ALBUMS("Albums"), TRACKS("Morceaux"), FOLDERS("Dossiers"), PLAYLISTS("Listes") }

private enum class SearchFilter(val label: String) { ALL("Tout"), TRACKS("Morceaux"), ALBUMS("Albums"), ARTISTS("Artistes") }


/**
 * Écran principal, inspiré du concept « Liquid Glass » : fond sombre teinté par la pochette en cours,
 * pastilles de filtres, cartes compactes, et mini-lecteur + barre de navigation flottants en verre dépoli.
 */
@Composable
fun LibraryScreen(
    vm: MainViewModel,
    onOpenAlbum: (Long) -> Unit,
    onOpenPlayer: () -> Unit,
    onManageFolders: () -> Unit,
    onPersonalize: () -> Unit,
    onStats: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(MainTab.HOME) }
    var section by rememberSaveable { mutableStateOf(LibrarySection.ALBUMS) }
    val scope = rememberCoroutineScope()
    val playerState by vm.player.state.collectAsStateWithLifecycle()
    val scan by vm.scanProgress.collectAsStateWithLifecycle()
    val seed by rememberArtworkSeed(playerState.metadata.artworkUri?.path)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val haze = rememberGlassBackdrop()
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && tab != MainTab.HOME) { tab = MainTab.HOME }

    TashuTheme(seed = seed) {
        val g = glassColors()
        ModalNavigationDrawer(
            drawerState = drawer,
            gesturesEnabled = drawer.isOpen,
            scrimColor = Color.Black.copy(alpha = 0.35f),
            drawerContent = {
                AppDrawer(
                    backdrop = haze,
                    onLibrary = { scope.launch { drawer.close() } },
                    onManageFolders = { scope.launch { drawer.close() }; onManageFolders() },
                    // Le menu reste ouvert : on y suit l'indexation en direct.
                    scan = scan,
                    onRescan = { vm.rescan() },
                    onPersonalize = { scope.launch { drawer.close() }; onPersonalize() },
                    onStats = { scope.launch { drawer.close() }; onStats() },
                )
            },
        ) {
            val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            val hasPlayer = playerState.mediaId != null
            val topSpace = status + if (tab == MainTab.LIBRARY) 112.dp else 64.dp
            val bottomSpace = nav + 84.dp + if (hasPlayer) 72.dp else 0.dp
            val contentPadding = PaddingValues(top = topSpace, bottom = bottomSpace)

            Box(Modifier.fillMaxSize().background(g.base)) {
                // Pas de Scaffold/Surface ici : on fournit nous-mêmes la couleur de texte des listes réutilisées.
                Box(Modifier.fillMaxSize().glassSource(haze)) {
                    // Fond choisi dans le menu (nuage, pochette floue ou uni), sur toute la page : le verre le laisse deviner.
                    AppBackground(playerState.metadata.artworkUri?.path)
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            (fadeIn(tween(220)) + scaleIn(bubbleSpring(), initialScale = 0.94f)) togetherWith
                                (fadeOut(tween(140)) + scaleOut(tween(180), targetScale = 1.03f))
                        },
                        label = "tab",
                    ) { shown ->
                    when (shown) {
                        MainTab.HOME -> HomeContent(vm, contentPadding, onOpenAlbum) { artist ->
                            vm.searchQuery.value = artist
                            tab = MainTab.SEARCH
                        }
                        MainTab.SEARCH -> SearchContent(vm, contentPadding, playerState.mediaId, onOpenAlbum)
                        MainTab.LIBRARY -> when (section) {
                            LibrarySection.ALBUMS -> AlbumsGrid(vm, contentPadding, onOpenAlbum)
                            LibrarySection.TRACKS -> TrackList(vm.tracks.collectAsStateWithLifecycle().value, contentPadding, playerState.mediaId) { l, i -> vm.play(l, i) }
                            LibrarySection.FOLDERS -> FolderBrowser(vm, contentPadding)
                            LibrarySection.PLAYLISTS -> SmartPlaylists(vm, contentPadding)
                        }
                    }
                    }
                } }

                // Bord de défilement façon iOS 26 : sous l'en-tête, le contenu se floute progressivement et se fond dans le nuage.
                Box(Modifier.fillMaxWidth().height(topSpace + 8.dp).scrollEdgeBlur(haze))
                // ---- En-tête ----
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GlassCircle(haze, Icons.Rounded.Menu, "Ouvrir le menu") { scope.launch { drawer.open() } }
                        Text(
                            tab.label,
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = g.ink, modifier = Modifier.weight(1f).padding(start = 14.dp),
                        )
                        if (tab == MainTab.LIBRARY && section == LibrarySection.ALBUMS) {
                            val columns by vm.albumGridColumns.collectAsStateWithLifecycle()
                            GlassPill(haze, onClick = vm::cycleAlbumGridColumns) {
                                Icon(if (columns == 1) Icons.Rounded.ViewAgenda else Icons.Rounded.GridView, null, tint = g.ink, modifier = Modifier.size(18.dp))
                                Text("$columns", color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                            }
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    if (tab == MainTab.LIBRARY) {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LibrarySection.entries.forEach { s -> Chip(s.label, selected = s == section) { section = s } }
                        }
                    }
                }

                // ---- Mini-lecteur et barre de navigation flottants ----
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    AnimatedVisibility(hasPlayer, enter = fadeIn(), exit = fadeOut()) {
                        GlassMiniPlayer(vm, haze, onOpenPlayer)
                    }
                    GlassNavBar(haze, tab) { tab = it }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Verre

@Composable
private fun GlassPill(haze: GlassBackdrop, onClick: () -> Unit, content: @Composable () -> Unit) {
    Row(
        Modifier
            .bubbleClickable(onClick = onClick)
            .height(40.dp)
            .liquidGlass(haze)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val g = glassColors()
    Text(
        label,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else g.ink,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .bubbleClickable(pressedScale = 0.92f, onClick = onClick)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else g.ink.copy(alpha = 0.09f))
            .border(1.dp, if (selected) Color.Transparent else g.border, CircleShape)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun GlassNavBar(haze: GlassBackdrop, current: MainTab, onSelect: (MainTab) -> Unit) {
    val g = glassColors()
    val shape = RoundedCornerShape(32.dp)
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .liquidGlass(haze, corner = 32.dp, lens = 26.dp, veil = 0.38f, frost = 3.dp),
    ) {
        val n = MainTab.entries.size
        val slot = maxWidth / n
        // La goutte se déplace avec un ressort et s'étire pendant le trajet, comme une bulle de liquide.
        val x by animateDpAsState(slot * current.ordinal, bubbleSpring(Spring.StiffnessMedium), label = "dropX")
        val target = slot * current.ordinal
        val stretch = 1f + (kotlin.math.abs((x - target).value) / slot.value).coerceAtMost(1f) * 0.35f
        Box(
            Modifier
                .offset(x = x + 6.dp)
                .padding(vertical = 6.dp)
                .width(slot - 12.dp)
                .fillMaxHeight()
                .graphicsLayer { scaleX = stretch; scaleY = 1f / kotlin.math.sqrt(stretch) }
                .clip(CircleShape)
                .background(g.fill(0.16f))
                .border(1.dp, Brush.linearGradient(listOf(g.ink.copy(alpha = 0.6f), g.ink.copy(alpha = 0.05f), g.ink.copy(alpha = 0.35f))), CircleShape),
        )
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MainTab.entries.forEach { t ->
                val selected = t == current
                val tint by animateColorAsState(if (selected) g.ink else g.ink.copy(alpha = 0.55f), label = "tint")
                Column(
                    Modifier
                        .bubbleClickable(pressedScale = 0.85f) { onSelect(t) }
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(t.label, fontSize = 11.sp, color = tint, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }
    }
}

@Composable
internal fun GlassMiniPlayer(vm: MainViewModel, haze: GlassBackdrop, onOpen: () -> Unit) {
    val g = glassColors()
    val s by vm.player.state.collectAsStateWithLifecycle()
    val shape = RoundedCornerShape(20.dp)
    // Atterrissage après réduction du lecteur : part un peu au-dessus, étiré, puis retombe et rebondit en s'écrasant.
    val drop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        vm.miniPlayerLandedAt.collect { at ->
            val since = SystemClock.uptimeMillis() - at
            if (since > 900) return@collect
            drop.snapTo(1f)
            delay((240 - since).coerceAtLeast(0))
            drop.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = 420f))
        }
    }
    Box(
        Modifier
            .graphicsLayer {
                val d = drop.value
                translationY = -d * 18.dp.toPx()
                // Étiré en tombant (d > 0), écrasé au contact (d < 0, dépassement du ressort).
                scaleY = 1f + d * 0.03f + minOf(d, 0f) * 0.12f
                scaleX = 1f - d * 0.015f - minOf(d, 0f) * 0.06f
                transformOrigin = TransformOrigin(0.5f, 1f)
            }
            .bubbleClickable(pressedScale = 0.96f, onClick = onOpen)
            .fillMaxWidth()
            .liquidGlass(haze, corner = 20.dp, veil = 0.42f, frost = 3.dp),
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = s.metadata.artworkUri?.path, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(g.fill(0.08f)),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(s.metadata.title?.toString().orEmpty(), color = g.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.metadata.artist?.toString().orEmpty(), color = g.ink.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(
                Modifier.bubbleClickable(pressedScale = 0.8f) { vm.player.togglePlay() }.size(40.dp).clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (s.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = if (s.isPlaying) "Pause" else "Lecture", tint = g.ink)
            }
        }
        // Progression : un trait discret au bas de la pastille.
        val progress = if (s.durationMs > 0) (s.positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp).fillMaxWidth().height(2.dp).background(g.line(0.12f))) {
            Box(Modifier.fillMaxWidth(progress).height(2.dp).background(MaterialTheme.colorScheme.primary))
        }
    }
}

// ---------------------------------------------------------------- Accueil

@Composable
private fun HomeContent(vm: MainViewModel, padding: PaddingValues, onOpenAlbum: (Long) -> Unit, onOpenArtist: (String) -> Unit) {
    val g = glassColors()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val artists by vm.artists.collectAsStateWithLifecycle()
    if (albums.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Votre bibliothèque est vide. Choisissez vos dossiers Drive dans le menu pour l'indexer.", color = g.ink.copy(alpha = 0.7f))
        }
        return
    }
    val recent = remember(albums) {
        albums.sortedWith(compareByDescending<Album> { it.lastPlayedAt ?: 0L }.thenByDescending { it.dateAdded }).take(8)
    }
    val added = remember(albums) { albums.sortedByDescending { it.dateAdded }.take(15) }
    val forgotten by vm.rediscover.collectAsStateWithLifecycle()
    val coverByArtist by vm.artistCovers.collectAsStateWithLifecycle()
    val topArtists = remember(artists) { artists.sortedByDescending { it.trackCount }.take(15) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { a -> CompactCard(a, Modifier.weight(1f)) { onOpenAlbum(a.id) } }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        item { SectionTitle("Ajoutés récemment") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(added, key = { it.id }) { a -> AlbumCard(a, 148.dp) { onOpenAlbum(a.id) } }
            }
        }
        if (forgotten.isNotEmpty()) {
            item { SectionTitle("Pas écouté depuis longtemps") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(forgotten, key = { it.id }) { a -> AlbumCard(a, 148.dp) { onOpenAlbum(a.id) } }
                }
            }
        }
        item { SectionTitle("Vos artistes") }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                items(topArtists, key = { it.id }) { ar -> ArtistCircle(ar, coverByArtist[ar.name], 112.dp) { onOpenArtist(ar.name) } }
            }
        }
    }
}

@Composable
private fun CompactCard(a: Album, modifier: Modifier, onClick: () -> Unit) {
    val g = glassColors()
    Row(
        modifier
            .bubbleClickable(pressedScale = 0.95f, onClick = onClick)
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(g.fill(0.08f))
            .border(1.dp, g.line(0.06f), RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(model = a.artworkPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).background(g.fill(0.06f)))
        Text(a.title, color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
private fun SectionTitle(text: String, action: (@Composable () -> Unit)? = null) {
    val g = glassColors()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = g.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
private fun AlbumCard(a: Album, size: Dp, onClick: () -> Unit) {
    val g = glassColors()
    Column(Modifier.bubbleClickable(pressedScale = 0.94f, onClick = onClick).width(size)) {
        AsyncImage(
            model = a.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(g.fill(0.06f)),
        )
        Text(a.title, color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(a.artist, color = g.ink.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ArtistCircle(artist: Artist, artwork: String?, size: Dp, onClick: () -> Unit) {
    val g = glassColors()
    Column(Modifier.bubbleClickable(pressedScale = 0.94f, onClick = onClick).width(size), horizontalAlignment = Alignment.CenterHorizontally) {
        AsyncImage(
            model = artwork, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(g.fill(0.06f)).border(1.dp, g.border, CircleShape),
        )
        Text(artist.name, color = g.ink, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------------------------------------------------------------- Recherche

@Composable
private fun SearchContent(vm: MainViewModel, padding: PaddingValues, playingId: String?, onOpenAlbum: (Long) -> Unit) {
    val g = glassColors()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
        item {
            // Champ de recherche en verre.
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(CircleShape)
                    .background(g.fill(0.10f))
                    .border(1.dp, g.border, CircleShape)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = g.ink.copy(alpha = 0.7f))
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    if (query.isEmpty()) Text("Artistes, morceaux, albums", color = g.ink.copy(alpha = 0.45f), fontSize = 16.sp)
                    BasicTextField(
                        value = query,
                        onValueChange = { vm.searchQuery.value = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = g.ink, fontSize = 16.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    Icon(Icons.Rounded.Close, "Effacer", tint = g.ink.copy(alpha = 0.7f), modifier = Modifier.clip(CircleShape).clickable { vm.searchQuery.value = "" })
                }
            }
        }
        if (query.isBlank()) return@LazyColumn
        item {
            Row(
                Modifier.padding(top = 14.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchFilter.entries.forEach { f ->
                    val n = when (f) {
                        SearchFilter.ALL -> null
                        SearchFilter.TRACKS -> results.tracks.size
                        SearchFilter.ALBUMS -> results.albums.size
                        SearchFilter.ARTISTS -> results.artists.size
                    }
                    Chip(if (n == null) f.label else "${f.label}  $n", selected = f == filter) { filter = f }
                }
            }
        }
        if (results.isEmpty) {
            item { Text("Aucun résultat pour « $query ».", color = g.ink.copy(alpha = 0.6f), modifier = Modifier.padding(24.dp)) }
            return@LazyColumn
        }
        searchSections(results, filter, playingId, vm, onOpenAlbum, onSeeAll = { filter = it }, g = g)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.searchSections(
    r: SearchResults,
    filter: SearchFilter,
    playingId: String?,
    vm: MainViewModel,
    onOpenAlbum: (Long) -> Unit,
    onSeeAll: (SearchFilter) -> Unit,
    g: GlassColors,
) {
    val all = filter == SearchFilter.ALL
    if ((all || filter == SearchFilter.ARTISTS) && r.artists.isNotEmpty()) {
        item { SectionTitle("Artistes · ${r.artists.size}") { if (all && r.artists.size > 3) SeeAll { onSeeAll(SearchFilter.ARTISTS) } } }
        if (all) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(r.artists, key = { it.first.id }) { (ar, art) -> ArtistCircle(ar, art, 96.dp) { vm.searchQuery.value = ar.name } }
                }
            }
        } else {
            items(r.artists, key = { it.first.id }) { (ar, art) ->
                Row(Modifier.fillMaxWidth().clickable { vm.searchQuery.value = ar.name }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(CircleShape).background(g.fill(0.06f)))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(ar.name, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("${ar.albumCount} album${if (ar.albumCount > 1) "s" else ""}, ${ar.trackCount} morceaux", color = g.ink.copy(alpha = 0.6f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
    if ((all || filter == SearchFilter.ALBUMS) && r.albums.isNotEmpty()) {
        item { SectionTitle("Albums · ${r.albums.size}") { if (all && r.albums.size > 3) SeeAll { onSeeAll(SearchFilter.ALBUMS) } } }
        if (all) {
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(r.albums, key = { it.id }) { a -> AlbumCard(a, 132.dp) { onOpenAlbum(a.id) } }
                }
            }
        } else {
            items(r.albums, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { onOpenAlbum(a.id) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = a.artworkPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(g.fill(0.06f)))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text(a.title, color = g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(a.artist, a.year?.toString()).joinToString(", "), color = g.ink.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
    if ((all || filter == SearchFilter.TRACKS) && r.tracks.isNotEmpty()) {
        item {
            SectionTitle("Morceaux · ${r.tracks.size}") {
                Row(
                    Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary).clickable { vm.play(r.tracks, 0) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                    Text("Lire les résultats", color = MaterialTheme.colorScheme.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        val shown = if (all) r.tracks.take(30) else r.tracks
        itemsIndexed(shown, key = { _, t -> t.id }) { i, t -> SearchTrackRow(t, t.id == playingId) { vm.play(r.tracks, i) } }
        if (all && r.tracks.size > shown.size) item { Box(Modifier.padding(16.dp)) { SeeAll { onSeeAll(SearchFilter.TRACKS) } } }
    }
}

@Composable
private fun SeeAll(onClick: () -> Unit) {
    val g = glassColors()
    Text(
        "Voir tout", color = g.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.bubbleClickable(onClick = onClick).clip(CircleShape).border(1.dp, g.border, CircleShape).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun SearchTrackRow(t: Track, playing: Boolean, onClick: () -> Unit) {
    val g = glassColors()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = t.artworkPath, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(g.fill(0.06f)))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(t.title, color = if (playing) MaterialTheme.colorScheme.primary else g.ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.artist, t.album).joinToString(" · "), color = g.ink.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text((t.durationMs / 1000).let { "%d:%02d".format(it / 60, it % 60) }, color = g.ink.copy(alpha = 0.5f), fontSize = 12.sp)
    }
}

