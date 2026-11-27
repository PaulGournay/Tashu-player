package com.tashu.player.ui.stats

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tashu.player.domain.model.BackgroundStyle
import com.tashu.player.domain.model.MonthRecap
import com.tashu.player.domain.model.ThemeColorSource
import com.tashu.player.domain.model.UiStyle
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.theme.AppBackground
import com.tashu.player.ui.theme.LocalThemeSettings
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.bubbleClickable
import com.tashu.player.ui.theme.glassColors
import com.tashu.player.ui.theme.rememberArtworkSeed
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

private enum class WrappedPage { INTRO, TIME, ARTIST, TRACKS, ALBUM, RHYTHM, VARIETY, SHARE }

/**
 * Tashu Wrapped : le mois en cartes plein écran, façon « stories ». Toucher à droite ou glisser = carte suivante,
 * toucher à gauche = précédente. Le nuage suit la pochette de la carte (album du mois, artiste du mois…).
 */
@Composable
fun WrappedScreen(vm: MainViewModel, onClose: () -> Unit) {
    val wrapped: WrappedViewModel = hiltViewModel()
    val recap by wrapped.recap.collectAsStateWithLifecycle()
    val covers by vm.artistCovers.collectAsStateWithLifecycle()
    val r = recap
    val theme = LocalThemeSettings.current
    // Le Wrapped garde toujours son nuage vivant tiré des pochettes, quel que soit le fond choisi pour l'app.
    CompositionLocalProvider(
        LocalThemeSettings provides theme.copy(
            background = BackgroundStyle.CLOUD, colorSource = ThemeColorSource.ARTWORK, uiStyle = UiStyle.LIQUID_GLASS, cloudAnimated = true,
        ),
    ) {
        if (r == null) {
            TashuTheme { Box(Modifier.fillMaxSize().background(glassColors().base)) }
        } else {
            WrappedStory(r, covers[r.topArtists.first().name], onClose)
        }
    }
}

@Composable
private fun WrappedStory(r: MonthRecap, artistCover: String?, onClose: () -> Unit) {
    val pages = WrappedPage.entries
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var image by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(r) { image = renderWrappedImage(context, r) }

    val albumArt = r.topAlbums.firstOrNull()?.album?.artworkPath
    val bgArt = when (pages[pager.currentPage]) {
        WrappedPage.ARTIST -> artistCover ?: albumArt
        WrappedPage.TRACKS -> r.topTracks.first().track.artworkPath
        else -> albumArt
    }
    val seed by rememberArtworkSeed(bgArt)

    TashuTheme(seed = seed) {
        val g = glassColors()
        Box(
            Modifier
                .fillMaxSize()
                .background(g.base)
                .pointerInput(Unit) {
                    detectTapGestures { o ->
                        val target = if (o.x < size.width / 3f) pager.currentPage - 1 else pager.currentPage + 1
                        if (target in pages.indices) scope.launch { pager.animateScrollToPage(target) }
                    }
                },
        ) {
            AppBackground(bgArt)
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                val offset = (pager.currentPage - i) + pager.currentPageOffsetFraction
                val active = pager.settledPage == i
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Les cartes pivotent légèrement en passant, comme les faces d'un cube.
                            val f = 1f - abs(offset).coerceIn(0f, 1f)
                            alpha = 0.3f + 0.7f * f
                            rotationY = offset * 28f
                            cameraDistance = 14f * density
                            scaleX = 0.9f + 0.1f * f
                            scaleY = 0.9f + 0.1f * f
                        }
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(start = 28.dp, end = 28.dp, top = 64.dp, bottom = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val p = rememberReveal(active)
                    when (pages[i]) {
                        WrappedPage.INTRO -> IntroCard(r, p)
                        WrappedPage.TIME -> TimeCard(r, p)
                        WrappedPage.ARTIST -> ArtistCard(r, artistCover, p)
                        WrappedPage.TRACKS -> TracksCard(r, p)
                        WrappedPage.ALBUM -> AlbumCard(r, p)
                        WrappedPage.RHYTHM -> RhythmCard(r, p)
                        WrappedPage.VARIETY -> VarietyCard(r, p)
                        WrappedPage.SHARE -> ShareCard(image, p, onShare = { image?.let { shareWrappedImage(context, it) } }) {
                            scope.launch { pager.animateScrollToPage(0) }
                        }
                    }
                }
            }
            // Avancement : un segment par carte.
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 64.dp, top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                pages.indices.forEach { i ->
                    val c by animateColorAsState(if (i <= pager.currentPage) g.ink else g.ink.copy(alpha = 0.25f), tween(300), label = "seg")
                    Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(c))
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 2.dp, end = 12.dp)
                    .bubbleClickable(onClick = onClose)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(g.fill(0.14f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Close, contentDescription = "Fermer", tint = g.ink) }
        }
    }
}

/** Avancement 0 → 1 de l'apparition d'une carte, relancé à chaque fois qu'elle devient la carte affichée. */
@Composable
private fun rememberReveal(active: Boolean): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            a.snapTo(0f)
            a.animateTo(1f, tween(1800, easing = LinearEasing))
        }
    }
    return a.value
}

/** Portion [from, to] de l'avancement, adoucie. */
private fun stage(p: Float, from: Float, to: Float): Float = FastOutSlowInEasing.transform(((p - from) / (to - from)).coerceIn(0f, 1f))

/** Apparition : fondu, montée et léger grossissement. */
private fun Modifier.rise(t: Float): Modifier = graphicsLayer {
    alpha = t
    translationY = (1f - t) * 36.dp.toPx()
    scaleX = 0.94f + 0.06f * t
    scaleY = 0.94f + 0.06f * t
}

@Composable
private fun Kicker(text: String, t: Float) {
    Text(
        text.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
        color = glassColors().ink.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.rise(t),
    )
}

@Composable
private fun Big(text: String, t: Float, size: Int = 44) {
    Text(
        text, fontSize = size.sp, lineHeight = (size * 1.1f).sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
        color = glassColors().ink, textAlign = TextAlign.Center, modifier = Modifier.rise(t),
    )
}

@Composable
private fun Small(text: String, t: Float) {
    Text(text, fontSize = 16.sp, lineHeight = 22.sp, color = glassColors().ink.copy(alpha = 0.8f), textAlign = TextAlign.Center, modifier = Modifier.rise(t))
}

@Composable
private fun CardColumn(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

@Composable
private fun IntroCard(r: MonthRecap, p: Float) {
    val g = glassColors()
    val covers = r.topAlbums.take(3).map { it.album.artworkPath }
    val float by rememberInfiniteTransition(label = "float").animateFloat(-1f, 1f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "f")
    CardColumn {
        Box(Modifier.height(220.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // L'album du mois au centre et devant, les deux suivants de part et d'autre.
            covers.indices.reversed().forEach { i ->
                val art = covers[i]
                val t = stage(p, 0.05f * i, 0.45f + 0.05f * i)
                val side = when (i) { 0 -> 0f; 1 -> -1f; else -> 1f }
                AsyncImage(
                    model = art, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = t
                            translationX = side * 70.dp.toPx() * t
                            translationY = (1f - t) * 80.dp.toPx() + float * 6.dp.toPx() * (if (i % 2 == 0) 1 else -1)
                            rotationZ = side * 9f * t
                            val s = if (side == 0f) 1f else 0.86f
                            scaleX = s; scaleY = s
                        }
                        .size(170.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(g.fill(0.1f))
                        .border(1.dp, g.line(0.2f), RoundedCornerShape(16.dp)),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Kicker("Tashu Wrapped", stage(p, 0.25f, 0.55f))
        Big(monthName(r.month, withYear = false).replaceFirstChar { it.uppercase() }, stage(p, 0.35f, 0.7f), size = 64)
        Small("${r.month.year} · ton mois en musique", stage(p, 0.45f, 0.8f))
        Spacer(Modifier.height(30.dp))
        val blink by rememberInfiniteTransition(label = "hint").animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "h")
        Text("Touchez pour continuer", fontSize = 13.sp, color = g.ink.copy(alpha = blink * stage(p, 0.7f, 1f)))
    }
}

@Composable
private fun TimeCard(r: MonthRecap, p: Float) {
    val minutes = r.total.listenedMs / 60_000
    val shown = (minutes * stage(p, 0.15f, 0.8f)).toLong() * 60_000
    // Mois en cours : moyenne sur les jours déjà passés.
    val days = if (r.month == YearMonth.now()) LocalDate.now().dayOfMonth else r.month.lengthOfMonth()
    CardColumn {
        Kicker("Ce mois-ci, tu as écouté", stage(p, 0f, 0.3f))
        Big(listeningTime(shown), stage(p, 0.1f, 0.4f), size = 56)
        Small("de musique, en ${plays(r.total.plays)}", stage(p, 0.4f, 0.7f))
        Spacer(Modifier.height(10.dp))
        Small("Environ ${listeningTime(r.total.listenedMs / days)} par jour", stage(p, 0.6f, 0.95f))
    }
}

@Composable
private fun ArtistCard(r: MonthRecap, cover: String?, p: Float) {
    val g = glassColors()
    val top = r.topArtists.first()
    val pulse by rememberInfiniteTransition(label = "rings").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "r")
    CardColumn {
        Kicker("Ton artiste du mois", stage(p, 0f, 0.3f))
        Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
            // Ondes qui s'éloignent de la photo, comme un haut-parleur.
            repeat(2) { k ->
                val w = (pulse + k * 0.5f) % 1f
                Box(
                    Modifier
                        .size(200.dp)
                        .graphicsLayer { scaleX = 1f + 0.3f * w; scaleY = 1f + 0.3f * w; alpha = (1f - w) * stage(p, 0.3f, 0.6f) }
                        .border(2.dp, g.ink.copy(alpha = 0.5f), CircleShape),
                )
            }
            val t = stage(p, 0.05f, 0.5f)
            AsyncImage(
                model = cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier
                    .graphicsLayer { scaleX = 0.4f + 0.6f * t; scaleY = 0.4f + 0.6f * t; alpha = t; rotationZ = (1f - t) * -30f }
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(g.fill(0.12f)),
            )
        }
        Big(top.name, stage(p, 0.35f, 0.65f), size = 40)
        Small("${plays(top.plays)} · ${listeningTime(top.listenedMs)}", stage(p, 0.5f, 0.8f))
        val others = r.topArtists.drop(1).map { it.name }
        if (others.isNotEmpty()) Small("Puis ${others.joinToString(", ")}", stage(p, 0.65f, 0.95f))
    }
}

@Composable
private fun TracksCard(r: MonthRecap, p: Float) {
    val g = glassColors()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Kicker("Tes 5 morceaux du mois", stage(p, 0f, 0.25f)) }
        Spacer(Modifier.height(4.dp))
        r.topTracks.take(5).forEachIndexed { i, s ->
            val t = stage(p, 0.1f + i * 0.12f, 0.45f + i * 0.12f)
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = t; translationX = (1f - t) * 120.dp.toPx() }
                    .clip(RoundedCornerShape(18.dp))
                    .background(g.fill(if (i == 0) 0.18f else 0.09f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${i + 1}", fontSize = if (i == 0) 30.sp else 22.sp, fontWeight = FontWeight.Bold, color = g.ink, modifier = Modifier.width(40.dp), textAlign = TextAlign.Center)
                AsyncImage(
                    model = s.track.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(if (i == 0) 64.dp else 52.dp).clip(RoundedCornerShape(10.dp)).background(g.fill(0.1f)),
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(s.track.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = g.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.track.artist, fontSize = 13.sp, color = g.ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(plays(s.plays), fontSize = 12.sp, color = g.ink.copy(alpha = 0.75f))
            }
        }
    }
}

@Composable
private fun AlbumCard(r: MonthRecap, p: Float) {
    val g = glassColors()
    val top = r.topAlbums.firstOrNull()
    val sway by rememberInfiniteTransition(label = "sway").animateFloat(-1f, 1f, infiniteRepeatable(tween(4000), RepeatMode.Reverse), label = "s")
    CardColumn {
        Kicker("Ton album du mois", stage(p, 0f, 0.3f))
        if (top == null) {
            Small("Tes morceaux du mois n'appartiennent à aucun album de la bibliothèque.", stage(p, 0.2f, 0.5f))
        } else {
            val t = stage(p, 0.05f, 0.6f)
            AsyncImage(
                model = top.album.artworkPath, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier
                    .graphicsLayer {
                        // La pochette se retourne vers nous, puis oscille doucement.
                        rotationY = (1f - t) * 80f + sway * 6f * t
                        rotationX = sway * -3f * t
                        cameraDistance = 12f * density
                        alpha = t
                    }
                    .size(260.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(g.fill(0.1f)),
            )
            Spacer(Modifier.height(6.dp))
            Big(top.album.title, stage(p, 0.4f, 0.7f), size = 32)
            Small(top.album.artist, stage(p, 0.5f, 0.8f))
            Small("${plays(top.plays)} · ${listeningTime(top.listenedMs)}", stage(p, 0.6f, 0.9f))
        }
    }
}

@Composable
private fun RhythmCard(r: MonthRecap, p: Float) {
    val g = glassColors()
    val primary = MaterialTheme.colorScheme.primary
    val fav = r.playsByWeekday.indices.maxBy { r.playsByWeekday[it] }
    val maxDay = r.playsByWeekday.max().coerceAtLeast(1)
    val bestDay = r.playsByDay.indices.maxBy { r.playsByDay[it] }
    CardColumn {
        Kicker("Ton jour préféré", stage(p, 0f, 0.3f))
        Big("le ${weekdayName(fav)}", stage(p, 0.1f, 0.4f), size = 48)
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            r.playsByWeekday.forEachIndexed { i, n ->
                val t = stage(p, 0.25f + i * 0.05f, 0.65f + i * 0.05f)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$n", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = g.ink.copy(alpha = if (i == fav) 1f else 0.7f), modifier = Modifier.graphicsLayer { alpha = t })
                    Box(
                        Modifier
                            .padding(top = 4.dp)
                            .fillMaxWidth()
                            .height(4.dp + 120.dp * (n.toFloat() / maxDay) * t)
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                            .background(if (i == fav) primary else g.ink.copy(alpha = 0.35f)),
                    )
                    Text(
                        java.time.DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.FRENCH).take(3),
                        fontSize = 12.sp, color = g.ink.copy(alpha = 0.75f), modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Small(
            "Ton meilleur jour : le ${bestDay + 1} ${monthName(r.month, withYear = false)}, ${plays(r.playsByDay[bestDay])}",
            stage(p, 0.7f, 1f),
        )
    }
}

@Composable
private fun VarietyCard(r: MonthRecap, p: Float) {
    val t = r.total
    CardColumn {
        Kicker("Ta palette du mois", stage(p, 0f, 0.3f))
        listOf(
            Triple(t.tracks, "morceau différent", "morceaux différents"),
            Triple(t.albums, "album", "albums"),
            Triple(t.artists, "artiste", "artistes"),
        ).forEachIndexed { i, (n, one, many) ->
            val s = stage(p, 0.1f + i * 0.2f, 0.5f + i * 0.2f)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Big("${(n * s).toInt()}", s, size = 64)
                Small(if (n == 1) one else many, s)
            }
        }
    }
}

@Composable
private fun ShareCard(image: File?, p: Float, onShare: () -> Unit, onReplay: () -> Unit) {
    val g = glassColors()
    CardColumn {
        Kicker("Ton mois en une image", stage(p, 0f, 0.3f))
        Box(
            Modifier
                .rise(stage(p, 0.05f, 0.5f))
                .height(400.dp)
                .aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(20.dp))
                .background(g.fill(0.1f))
                .border(1.dp, g.line(0.2f), RoundedCornerShape(20.dp)),
        ) {
            if (image != null) {
                val bmp = remember(image) { android.graphics.BitmapFactory.decodeFile(image.path).asImageBitmap() }
                Image(bmp, contentDescription = "Image à partager", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.rise(stage(p, 0.4f, 0.8f))) {
            Row(
                Modifier.bubbleClickable(onClick = onShare).clip(CircleShape).background(g.ink).padding(horizontal = 22.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.IosShare, contentDescription = null, tint = g.onInk, modifier = Modifier.size(18.dp))
                Text("Partager", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.onInk, modifier = Modifier.padding(start = 8.dp))
            }
            Row(
                Modifier.bubbleClickable(onClick = onReplay).clip(CircleShape).background(g.fill(0.14f)).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, tint = g.ink, modifier = Modifier.size(18.dp))
                Text("Revoir", fontSize = 15.sp, color = g.ink, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
