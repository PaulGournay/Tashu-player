package com.tashu.player.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tashu.player.domain.model.AlbumStat
import com.tashu.player.domain.model.ArtistStat
import com.tashu.player.domain.model.ListeningStats
import com.tashu.player.domain.model.MonthRecap
import com.tashu.player.domain.model.StatsPeriod
import com.tashu.player.domain.model.TrackStat
import com.tashu.player.domain.model.WeekListening
import com.tashu.player.domain.model.WrappedMonth
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.menu.GlassOption
import com.tashu.player.ui.menu.Section
import com.tashu.player.ui.theme.AppBackground
import com.tashu.player.ui.theme.GlassBackdrop
import com.tashu.player.ui.theme.GlassCircle
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.bubbleClickable
import com.tashu.player.ui.theme.glassColors
import com.tashu.player.ui.theme.glassSource
import com.tashu.player.ui.theme.liquidGlass
import com.tashu.player.ui.theme.rememberArtworkSeed
import com.tashu.player.ui.theme.rememberGlassBackdrop
import com.tashu.player.ui.theme.scrollEdgeBlur
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Statistiques d'écoute : période, temps total, Tashu Wrapped du mois, semaines, top morceaux, albums et artistes. */
@Composable
fun StatsScreen(vm: MainViewModel, onBack: () -> Unit, onOpenAlbum: (Long) -> Unit, onWrapped: (YearMonth) -> Unit) {
    val stats: StatsViewModel = hiltViewModel()
    val period by stats.period.collectAsStateWithLifecycle()
    val data by stats.stats.collectAsStateWithLifecycle()
    val weeks by stats.weeks.collectAsStateWithLifecycle()
    val months by stats.months.collectAsStateWithLifecycle()
    val wrapped by stats.wrappedMonth.collectAsStateWithLifecycle()
    val covers by vm.artistCovers.collectAsStateWithLifecycle()
    val artwork = vm.player.state.collectAsStateWithLifecycle().value.metadata.artworkUri?.path
    val seed by rememberArtworkSeed(artwork)
    val backdrop = rememberGlassBackdrop()

    TashuTheme(seed = seed) {
        val g = glassColors()
        val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        Box(Modifier.fillMaxSize().background(g.base)) {
            Box(Modifier.fillMaxSize().glassSource(backdrop)) { AppBackground(artwork) }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = status + 64.dp, bottom = nav + 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val all = months
                val st = data
                if (all != null && st != null && all.isEmpty()) {
                    EmptyCard(backdrop, "Aucune écoute pour l'instant", "Chaque morceau écouté au moins 30 secondes s'ajoute ici : temps d'écoute, morceaux, albums et artistes préférés.")
                } else if (all != null && st != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        StatsPeriod.entries.forEach { p ->
                            GlassOption(p.label, p == period, Modifier.weight(1f)) { stats.period.value = p }
                        }
                    }
                    Summary(backdrop, st, period)
                    WrappedCard(backdrop, all, wrapped, onPick = { stats.pickedMonth.value = it }, onOpen = onWrapped)
                    Section(backdrop, "Par semaine") { WeekChart(weeks) }
                    if (st.topTracks.isNotEmpty()) Section(backdrop, "Top morceaux") {
                        st.topTracks.forEachIndexed { i, t -> TrackRow(i + 1, t) { vm.play(st.topTracks.map { it.track }, i) } }
                    }
                    if (st.topAlbums.isNotEmpty()) Section(backdrop, "Top albums") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(end = 4.dp)) {
                            items(st.topAlbums, key = { it.album.id }) { a -> AlbumTile(a) { onOpenAlbum(a.album.id) } }
                        }
                    }
                    if (st.topArtists.isNotEmpty()) Section(backdrop, "Top artistes") {
                        st.topArtists.forEachIndexed { i, a -> ArtistRow(i + 1, a, covers[a.name]) }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(status + 84.dp).scrollEdgeBlur(backdrop))
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircle(backdrop, Icons.AutoMirrored.Rounded.ArrowBack, "Retour", onClick = onBack)
                Text(
                    "Statistiques", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                    color = g.ink, modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyCard(backdrop: GlassBackdrop, title: String, text: String) {
    val g = glassColors()
    Column(
        Modifier.fillMaxWidth().liquidGlass(backdrop, corner = 28.dp, veil = 0.55f, lens = 24.dp, frost = 22.dp).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.BarChart, contentDescription = null, tint = g.ink.copy(alpha = 0.7f), modifier = Modifier.size(40.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = g.ink)
        Text(text, fontSize = 14.sp, color = g.ink.copy(alpha = 0.65f))
    }
}

@Composable
private fun Summary(backdrop: GlassBackdrop, st: ListeningStats, period: StatsPeriod) {
    val g = glassColors()
    val t = st.total
    Section(backdrop, "Temps d'écoute") {
        if (t.listenedMs == 0L) {
            Text(
                if (period == StatsPeriod.YEAR) "Rien d'écouté depuis le 1er janvier." else "Rien d'écouté sur ${period.label}.",
                fontSize = 14.sp, color = g.ink.copy(alpha = 0.65f),
            )
            return@Section
        }
        Text(listeningTime(t.listenedMs), fontSize = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp, color = g.ink)
        Row(Modifier.fillMaxWidth()) {
            Figure(t.plays, if (t.plays == 1) "écoute" else "écoutes", Modifier.weight(1f))
            Figure(t.tracks, if (t.tracks == 1) "morceau" else "morceaux", Modifier.weight(1f))
            Figure(t.albums, if (t.albums == 1) "album" else "albums", Modifier.weight(1f))
            Figure(t.artists, if (t.artists == 1) "artiste" else "artistes", Modifier.weight(1f))
        }
    }
}

@Composable
private fun Figure(n: Int, label: String, modifier: Modifier) {
    val g = glassColors()
    Column(modifier) {
        Text("$n", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = g.ink)
        Text(label, fontSize = 12.sp, color = g.ink.copy(alpha = 0.6f))
    }
}

/** Entrée de Tashu Wrapped : le mois proposé, les autres mois assez écoutés, ou ce qu'il manque. */
@Composable
private fun WrappedCard(
    backdrop: GlassBackdrop,
    months: List<WrappedMonth>,
    selected: YearMonth?,
    onPick: (YearMonth) -> Unit,
    onOpen: (YearMonth) -> Unit,
) {
    val g = glassColors()
    val scheme = MaterialTheme.colorScheme
    val enough = months.filter { it.enough }.asReversed()
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (selected != null) Modifier.bubbleClickable(pressedScale = 0.97f) { onOpen(selected) } else Modifier)
            .liquidGlass(backdrop, corner = 28.dp, veil = 0.55f, lens = 24.dp, frost = 22.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(scheme.primary.copy(alpha = 0.30f), scheme.tertiary.copy(alpha = 0.18f))))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = g.ink, modifier = Modifier.size(18.dp))
            Text("TASHU WRAPPED", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = g.ink.copy(alpha = 0.7f), modifier = Modifier.padding(start = 8.dp))
        }
        if (selected == null) {
            val now = months.lastOrNull()?.takeIf { it.month == YearMonth.now() }
            Text("Ton récap du mois arrive bientôt", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = g.ink)
            Text(
                "Il faut ${MonthRecap.MIN_PLAYS} écoutes et ${MonthRecap.MIN_TRACKS} morceaux différents dans un mois. " +
                    "Ce mois-ci : ${plays(now?.plays ?: 0)}, ${count(now?.tracks ?: 0, "morceau", "morceaux")}.",
                fontSize = 13.sp, color = g.ink.copy(alpha = 0.7f),
            )
        } else {
            val info = enough.first { it.month == selected }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ton mois ${ofMonth(selected)}", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = g.ink)
                    Text("${plays(info.plays)}, ${count(info.tracks, "morceau", "morceaux")}", fontSize = 13.sp, color = g.ink.copy(alpha = 0.7f))
                }
                Text(
                    "Voir", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.onInk,
                    modifier = Modifier.clip(CircleShape).background(g.ink).padding(horizontal = 18.dp, vertical = 9.dp),
                )
            }
            if (enough.size > 1) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    enough.forEach { m -> GlassOption(monthName(m.month).replaceFirstChar { it.uppercase() }, m.month == selected) { onPick(m.month) } }
                }
            }
        }
    }
}

/**
 * Temps d'écoute des 12 dernières semaines : une barre par semaine, la semaine en cours en couleur pleine,
 * repères horaires discrets. La plus haute barre porte sa valeur ; toucher une barre affiche la sienne dessous.
 */
@Composable
private fun WeekChart(weeks: List<WeekListening>) {
    if (weeks.isEmpty()) return
    val g = glassColors()
    val primary = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    var picked by remember { mutableStateOf<Int?>(null) }
    val maxMs = weeks.maxOf { it.listenedMs }
    val best = weeks.indexOfFirst { it.listenedMs == maxMs }
    val stepMin = CHART_STEPS_MIN.first { maxMs / 60_000.0 / it <= 4 }
    val topMin = maxOf(stepMin, (Math.ceil(maxMs / 60_000.0 / stepMin) * stepMin).toInt())
    val dateFmt = remember { DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH) }
    val axisStyle = TextStyle(fontSize = 10.sp, color = g.ink.copy(alpha = 0.55f))
    val valueStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = g.ink)
    val summary = weeks.joinToString { "semaine du ${it.weekStart.format(dateFmt)} : ${listeningTime(it.listenedMs)}" }

    Text("12 dernières semaines", fontSize = 12.sp, color = g.ink.copy(alpha = 0.55f))
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .semantics { contentDescription = "Temps d'écoute par semaine. $summary" }
            .pointerInput(weeks) {
                detectTapGestures { o ->
                    val left = 34.dp.toPx()
                    val slot = (size.width - left) / weeks.size
                    val i = ((o.x - left) / slot).toInt()
                    picked = if (o.x < left || i !in weeks.indices || i == picked) null else i
                }
            },
    ) {
        val left = 34.dp.toPx()
        val top = 20.dp.toPx()
        val bottom = size.height - 20.dp.toPx()
        val slot = (size.width - left) / weeks.size
        val barW = slot * 0.62f
        val r = 4.dp.toPx()
        fun y(ms: Long) = bottom - (bottom - top) * (ms / 60_000f / topMin)
        // Repères : 0 et chaque pas, en pointillés discrets.
        var m = 0
        while (m <= topMin) {
            val yy = y(m * 60_000L)
            drawLine(g.line(if (m == 0) 0.25f else 0.10f), Offset(left, yy), Offset(size.width, yy), 1.dp.toPx(),
                pathEffect = if (m == 0) null else PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            val label = measurer.measure(axisLabel(m), axisStyle)
            drawText(label, topLeft = Offset(left - label.size.width - 6.dp.toPx(), yy - label.size.height / 2))
            m += stepMin
        }
        weeks.forEachIndexed { i, w ->
            val x = left + slot * i + (slot - barW) / 2
            val yy = y(w.listenedMs)
            val current = i == weeks.lastIndex
            val alpha = when { picked == i -> 1f; picked != null -> 0.35f; current -> 1f; else -> 0.6f }
            if (w.listenedMs > 0) {
                // Bout arrondi en haut, base droite sur l'axe : une seule forme, sans recouvrement.
                val cap = CornerRadius(minOf(r, (bottom - yy) / 2))
                val bar = Path().apply { addRoundRect(RoundRect(Rect(x, yy, x + barW, bottom), topLeft = cap, topRight = cap)) }
                drawPath(bar, primary.copy(alpha = alpha))
            }
            if (i == best && maxMs > 0 && picked == null) {
                val v = measurer.measure(shortTime(w.listenedMs), valueStyle)
                drawText(v, topLeft = Offset((x + barW / 2 - v.size.width / 2).coerceIn(left, size.width - v.size.width), yy - v.size.height - 2.dp.toPx()))
            }
            if ((weeks.lastIndex - i) % 3 == 0) {
                val d = measurer.measure(w.weekStart.format(dateFmt), axisStyle)
                drawText(d, topLeft = Offset((x + barW / 2 - d.size.width / 2).coerceAtMost(size.width - d.size.width), bottom + 4.dp.toPx()))
            }
        }
    }
    val p = picked
    Text(
        if (p != null) "Semaine du ${weeks[p].weekStart.format(dateFmt)} : ${listeningTime(weeks[p].listenedMs)}"
        else "Moyenne : ${listeningTime(weeks.sumOf { it.listenedMs } / weeks.size)} par semaine. Touchez une barre pour sa valeur.",
        fontSize = 13.sp, color = g.ink.copy(alpha = 0.75f),
    )
}

/** Pas des repères horaires, en minutes. */
private val CHART_STEPS_MIN = listOf(5, 10, 15, 30, 60, 120, 180, 300, 600, 1200, 3000, Int.MAX_VALUE / 2)

private fun axisLabel(minutes: Int) = when {
    minutes == 0 -> "0"
    minutes < 60 -> "$minutes min"
    else -> "${minutes / 60} h"
}

/** Valeur posée sur une barre : « 3 h 12 », « 45 min ». */
private fun shortTime(ms: Long): String {
    val m = ms / 60_000
    return if (m < 60) "$m min" else "${m / 60} h ${"%02d".format(m % 60)}"
}

@Composable
private fun TrackRow(rank: Int, t: TrackStat, onClick: () -> Unit) {
    val g = glassColors()
    Row(Modifier.fillMaxWidth().bubbleClickable(pressedScale = 0.97f, onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.ink.copy(alpha = 0.5f), modifier = Modifier.width(24.dp))
        AsyncImage(
            model = t.track.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(g.fill(0.08f)),
        )
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(t.track.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.track.artist, fontSize = 12.sp, color = g.ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlaysAndTime(t.plays, t.listenedMs)
    }
}

@Composable
private fun PlaysAndTime(n: Int, ms: Long) {
    val g = glassColors()
    Column(horizontalAlignment = Alignment.End) {
        Text(plays(n), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.ink)
        Text(listeningTime(ms), fontSize = 11.sp, color = g.ink.copy(alpha = 0.55f))
    }
}

@Composable
private fun AlbumTile(a: AlbumStat, onClick: () -> Unit) {
    val g = glassColors()
    Column(Modifier.bubbleClickable(pressedScale = 0.94f, onClick = onClick).width(124.dp)) {
        AsyncImage(
            model = a.album.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(124.dp).clip(RoundedCornerShape(10.dp)).background(g.fill(0.08f)).border(1.dp, g.line(0.08f), RoundedCornerShape(10.dp)),
        )
        Text(a.album.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(a.album.artist, fontSize = 12.sp, color = g.ink.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${plays(a.plays)} · ${listeningTime(a.listenedMs)}", fontSize = 11.sp, color = g.ink.copy(alpha = 0.55f), maxLines = 1)
    }
}

@Composable
private fun ArtistRow(rank: Int, a: ArtistStat, artwork: String?) {
    val g = glassColors()
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$rank", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.ink.copy(alpha = 0.5f), modifier = Modifier.width(24.dp))
        AsyncImage(
            model = artwork, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(46.dp).clip(CircleShape).background(g.fill(0.08f)).border(1.dp, g.border, CircleShape),
        )
        Text(a.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        PlaysAndTime(a.plays, a.listenedMs)
    }
}
