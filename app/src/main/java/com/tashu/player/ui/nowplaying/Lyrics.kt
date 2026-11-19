package com.tashu.player.ui.nowplaying

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tashu.player.domain.lyrics.LrcParser
import com.tashu.player.domain.lyrics.LyricLine
import com.tashu.player.domain.lyrics.ParsedLyrics
import com.tashu.player.domain.repository.LyricsOption
import com.tashu.player.domain.repository.LyricsSource
import com.tashu.player.domain.repository.LyricsState
import com.tashu.player.playback.PlayerUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val FOCAL = 0.4f
private const val RESUME_AUTO_SCROLL_MS = 3_000L

/** Position de lecture interpolée à chaque image entre deux relevés du lecteur (toutes les 250 ms). */
@Composable
fun rememberLivePosition(positionMs: Long, playing: Boolean): State<Long> {
    val live = remember { mutableLongStateOf(positionMs) }
    LaunchedEffect(positionMs, playing) {
        live.longValue = positionMs
        if (!playing) return@LaunchedEffect
        val start = withFrameMillis { it }
        while (true) withFrameMillis { now -> live.longValue = positionMs + (now - start) }
    }
    return live
}

/** Garde l'écran allumé tant que des paroles synchronisées sont affichées. */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Zoom à deux doigts sur les paroles ; la taille est enregistrée à la fin du geste. */
private fun Modifier.pinchToScale(scale: () -> Float, onScale: (Float) -> Unit, onEnd: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoomed = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onScale((scale() * zoom).coerceIn(0.6f, 2.2f))
                    zoomed = true
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
        if (zoomed) onEnd()
    }
}

/**
 * Zone des paroles du lecteur : en-tête (source, décalage, menu) puis paroles.
 * [fullscreen] : vue plein écran, texte plus grand et aligné à gauche.
 */
@Composable
fun LyricsPanel(
    ui: LyricsUi,
    player: PlayerUiState,
    fontScale: Float,
    onlineEnabled: Boolean,
    fullscreen: Boolean,
    onSeek: (Long) -> Unit,
    onShift: (Long) -> Unit,
    onSearch: () -> Unit,
    onIgnore: () -> Unit,
    onReset: () -> Unit,
    onToggleOnline: (Boolean) -> Unit,
    onFontScale: (Float) -> Unit,
    onFullscreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        LyricsHeader(ui, onlineEnabled, onShift, onSearch, onIgnore, onReset, onToggleOnline, onFullscreen)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = ui.state) {
                LyricsState.Loading -> LyricsMessage("Recherche des paroles…", loading = true)
                LyricsState.Instrumental -> LyricsMessage("Morceau instrumental", action = "Chercher des paroles" to onSearch)
                is LyricsState.None -> LyricsMessage(
                    when {
                        s.ignored -> "Paroles masquées pour ce morceau."
                        s.error != null -> s.error
                        else -> "Pas de paroles trouvées."
                    },
                    action = if (s.ignored) "Afficher les paroles" to onReset else "Chercher des paroles" to onSearch,
                )
                is LyricsState.Found -> when {
                    ui.parsed != null -> SyncedLyrics(ui.parsed, player, fontScale, fullscreen, onSeek, onFontScale)
                    else -> PlainLyrics(ui.plain.orEmpty(), fontScale, fullscreen, onFontScale)
                }
            }
        }
    }
}

@Composable
private fun LyricsHeader(
    ui: LyricsUi,
    onlineEnabled: Boolean,
    onShift: (Long) -> Unit,
    onSearch: () -> Unit,
    onIgnore: () -> Unit,
    onReset: () -> Unit,
    onToggleOnline: (Boolean) -> Unit,
    onFullscreen: (() -> Unit)?,
) {
    val found = ui.state as? LyricsState.Found
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            found?.source?.label.orEmpty(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (found != null && ui.parsed != null) {
            TextButton(onClick = { onShift(-OFFSET_STEP_MS) }) { Text("−0,1 s") }
            AssistChip(onClick = { onShift(0) }, label = { Text(formatOffset(found.offsetMs)) })
            TextButton(onClick = { onShift(OFFSET_STEP_MS) }) { Text("+0,1 s") }
        }
        onFullscreen?.let { IconButton(onClick = it) { Icon(Icons.Rounded.Fullscreen, contentDescription = "Paroles en plein écran") } }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Options des paroles") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Chercher d'autres paroles…") }, onClick = { menu = false; onSearch() })
                if (found?.source == LyricsSource.MANUAL || found?.source == LyricsSource.ONLINE ||
                    (ui.state as? LyricsState.None)?.ignored == true || ui.state == LyricsState.Instrumental
                ) {
                    DropdownMenuItem(text = { Text("Revenir aux paroles automatiques") }, onClick = { menu = false; onReset() })
                }
                if (found != null) DropdownMenuItem(text = { Text("Masquer les paroles de ce morceau") }, onClick = { menu = false; onIgnore() })
                DropdownMenuItem(
                    text = { Text("Chercher en ligne (LRCLIB)") },
                    trailingIcon = { Checkbox(checked = onlineEnabled, onCheckedChange = null) },
                    onClick = { onToggleOnline(!onlineEnabled) },
                )
            }
        }
    }
}

/** Positif = paroles plus tôt (convention `[offset:]` des fichiers LRC). */
private fun formatOffset(ms: Long): String =
    if (ms == 0L) "Décalage 0 s" else "%+.1f s".format(ms / 1000f).replace('.', ',')

private const val OFFSET_STEP_MS = 100L

@Composable
private fun LyricsMessage(text: String, loading: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(28.dp).padding(bottom = 4.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.let { (label, onClick) ->
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text(label) }
        }
    }
}

/**
 * Paroles synchronisées, à la manière de Namida : ligne courante à 40 % de la hauteur, voisines
 * de plus en plus estompées, réduites et floutées ; mots surlignés au fil du chant (LRC enrichi).
 * Un défilement manuel suspend le suivi, qui reprend 3 s après si la lecture continue.
 * Toucher une ligne y place la lecture ; appui long = copier la ligne.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SyncedLyrics(
    lyrics: ParsedLyrics,
    player: PlayerUiState,
    fontScale: Float,
    fullscreen: Boolean,
    onSeek: (Long) -> Unit,
    onFontScale: (Float) -> Unit,
) {
    KeepScreenOn()
    val position = rememberLivePosition(player.positionMs, player.isPlaying)
    val current by remember(lyrics) { derivedStateOf { lyrics.indexAt(position.value) } }
    val currentTime = lyrics.lines.getOrNull(current)?.timeMs
    val listState = rememberLazyListState()
    var autoScroll by remember { mutableStateOf(true) }
    val playing by rememberUpdatedState(player.isPlaying)
    val clipboard = LocalClipboardManager.current
    var scale by remember(fontScale) { mutableFloatStateOf(fontScale) }

    LaunchedEffect(listState) {
        var resume: Job? = null
        listState.interactionSource.interactions.collect { i ->
            when (i) {
                is DragInteraction.Start -> { resume?.cancel(); autoScroll = false }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    resume?.cancel()
                    resume = launch { delay(RESUME_AUTO_SCROLL_MS); if (playing) autoScroll = true }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().pinchToScale({ scale }, { scale = it }, { onFontScale(scale) })) {
        val viewport = constraints.maxHeight
        LaunchedEffect(current, autoScroll, viewport) {
            if (!autoScroll || viewport == 0) return@LaunchedEffect
            val target = current.coerceAtLeast(0)
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target }
            if (visible == null) listState.scrollToItem(target)
            val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
            val itemTop = item.offset - listState.layoutInfo.viewportStartOffset
            val delta = itemTop + item.size / 2f - viewport * FOCAL
            val long = abs(delta) > viewport * 0.25f
            listState.animateScrollBy(delta, tween(if (long) 620 else 300, easing = FastOutSlowInEasing))
        }

        val baseSize = if (fullscreen) 30.sp else MaterialTheme.typography.headlineSmall.fontSize
        val align = if (fullscreen) TextAlign.Start else TextAlign.Center
        val onSurface = MaterialTheme.colorScheme.onSurface
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = (maxHeight * FOCAL), bottom = maxHeight * (1 - FOCAL)),
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val active = currentTime != null && line.timeMs == currentTime
                val distance = if (current < 0) i + 1 else abs(i - current)
                val alpha by animateFloatAsState(
                    when { active -> 1f; distance == 1 -> 0.5f; distance == 2 -> 0.4f; else -> 0.25f },
                    tween(300), label = "alpha",
                )
                val lineScale by animateFloatAsState(
                    if (active) 1f else 0.92f,
                    spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow), label = "scale",
                )
                val blur = if (Build.VERSION.SDK_INT >= 31 && autoScroll && !active) (minOf(distance, 4) * 0.4f).dp else 0.dp
                Text(
                    text = if (active && line.words.isNotEmpty()) karaoke(line, position.value, onSurface) else AnnotatedString(line.text.ifBlank { "♪" }),
                    fontSize = baseSize * scale,
                    lineHeight = 1.25.em,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
                    color = onSurface,
                    textAlign = align,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            this.alpha = alpha
                            scaleX = lineScale; scaleY = lineScale
                            transformOrigin = TransformOrigin(if (fullscreen) 0f else 0.5f, 0.5f)
                        }
                        .blur(blur)
                        .combinedClickable(
                            onClick = { line.timeMs?.let { onSeek(it); autoScroll = true } },
                            onLongClick = { if (line.text.isNotBlank()) clipboard.setText(AnnotatedString(line.text)) },
                        )
                        .padding(horizontal = if (fullscreen) 8.dp else 28.dp, vertical = 10.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = !autoScroll,
            enter = fadeIn(tween(300, delayMillis = 1_200)), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
        ) {
            AssistChip(
                onClick = { autoScroll = true },
                label = { Text("Revenir à la ligne") },
                leadingIcon = { Icon(Icons.Rounded.MyLocation, contentDescription = null) },
            )
        }
    }
}

private fun karaoke(line: LyricLine, positionMs: Long, color: Color): AnnotatedString = buildAnnotatedString {
    line.words.forEach { w ->
        withStyle(SpanStyle(color = if (w.startMs <= positionMs) color else color.copy(alpha = 0.35f))) { append(w.text) }
    }
}

@Composable
private fun PlainLyrics(text: String, fontScale: Float, fullscreen: Boolean, onFontScale: (Float) -> Unit) {
    var scale by remember(fontScale) { mutableFloatStateOf(fontScale) }
    val base = if (fullscreen) 22.sp else MaterialTheme.typography.bodyLarge.fontSize
    Text(
        text,
        fontSize = base * scale,
        lineHeight = 1.8.em,
        textAlign = if (fullscreen) TextAlign.Start else TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxSize()
            .pinchToScale({ scale }, { scale = it }, { onFontScale(scale) })
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
    )
}

/** Recherche manuelle : sources de la piste puis résultats LRCLIB ; aperçu avant de choisir. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsSearchSheet(ui: LyricsSearchUi, onSearch: (String) -> Unit, onPick: (LyricsOption) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf(ui.query) }
    var expanded by remember(ui.results) { mutableStateOf<Int?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().imePadding()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Titre et artiste") },
                singleLine = true,
                trailingIcon = { IconButton(onClick = { onSearch(query) }) { Icon(Icons.Rounded.Search, contentDescription = "Chercher") } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            if (!ui.loading && ui.error == null && ui.results.isEmpty()) {
                Text("Aucun résultat. Essayez avec moins de mots.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(ui.results) { i, option ->
                    val c = option.candidate
                    Column {
                        ListItem(
                            overlineContent = { Text(option.source.label) },
                            headlineContent = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(c.artist, c.album, c.durationMs?.let(::mmss)).joinToString(", "),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                Text(
                                    if (c.synced) "Synchronisées" else "Texte",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (c.synced) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.clickable { expanded = if (expanded == i) null else i },
                        )
                        if (expanded == i) {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        LrcParser.toPlain(c.text.orEmpty()).lineSequence().take(8).joinToString("\n"),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Button(onClick = { onPick(option) }, modifier = Modifier.padding(top = 8.dp)) { Text("Utiliser ces paroles") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun mmss(ms: Long): String = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** Paroles en plein écran sur la pochette floutée, avec les commandes de lecture. */
@Composable
fun FullscreenLyrics(
    artworkPath: String?,
    title: String,
    artist: String,
    onClose: () -> Unit,
    controls: @Composable () -> Unit,
    content: @Composable (Modifier) -> Unit,
) {
    BackHandler(onBack = onClose)
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (artworkPath != null) {
                AsyncImage(
                    model = artworkPath, contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(80.dp) else Modifier),
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)))
            }
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Quitter le plein écran") }
                    Column(Modifier.weight(1f).padding(start = 4.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(artist, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                content(Modifier.weight(1f).fillMaxWidth())
                controls()
            }
        }
    }
}
