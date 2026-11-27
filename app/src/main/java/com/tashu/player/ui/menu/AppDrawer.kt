package com.tashu.player.ui.menu

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import com.tashu.player.drive.scan.ScanProgress
import com.tashu.player.domain.model.UiStyle
import com.tashu.player.domain.model.DeckMode
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.tashu.player.domain.model.BackgroundStyle
import com.tashu.player.ui.theme.GlassBackdrop
import com.tashu.player.ui.theme.glassColors
import com.tashu.player.ui.theme.isAppInDarkTheme
import com.tashu.player.ui.theme.bubbleClickable
import com.tashu.player.ui.theme.liquidGlass
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tashu.player.R
import com.tashu.player.domain.model.ThemeColorSource
import com.tashu.player.domain.model.ThemeMode
import com.tashu.player.domain.model.ThemeSettings
import com.tashu.player.domain.model.ThemeStyle

/** Couleurs proposées pour le thème à couleur fixe ; la première est la couleur par défaut de l'app. */
private val FIXED_COLORS = listOf(
    0xFF4F6D7A, 0xFF3F51B5, 0xFF0B7A75, 0xFF2E7D32, 0xFF9E9D24,
    0xFFE6A23C, 0xFFD2601A, 0xFFC62828, 0xFFAD1457, 0xFF6A1B9A,
).map { Color(it) }

/** Menu latéral : panneau flottant en verre « Clear », raccourcis de la bibliothèque et réglages du thème. */
@Composable
fun AppDrawer(
    backdrop: GlassBackdrop,
    theme: ThemeSettings,
    onThemeChange: ((ThemeSettings) -> ThemeSettings) -> Unit,
    onLibrary: () -> Unit,
    onManageFolders: () -> Unit,
    scan: ScanProgress,
    onRescan: () -> Unit,
) {
    ModalDrawerSheet(
        windowInsets = WindowInsets(0),
        drawerContainerColor = Color.Transparent,
        drawerTonalElevation = 0.dp,
        drawerShape = RoundedCornerShape(0.dp),
    ) {
        val g = glassColors()
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 10.dp)
                .fillMaxHeight()
                .liquidGlass(backdrop, corner = 34.dp, veil = 0.55f, lens = 30.dp, frost = 22.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Header()
            Spacer(Modifier.height(18.dp))
            DrawerItem(Icons.Rounded.LibraryMusic, "Bibliothèque", selected = true, onClick = onLibrary)
            DrawerItem(Icons.Rounded.FolderOpen, "Dossiers Drive", selected = false, onClick = onManageFolders)
            ScanCard(scan, onRescan)
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(g.line(0.10f)))
            ThemeSection(theme, onThemeChange)
            Credits()
        }
    }
}

@Composable
private fun Header() {
    val scheme = MaterialTheme.colorScheme
    val g = glassColors()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))),
        )
        Column(Modifier.padding(start = 14.dp)) {
            Text(stringResource(R.string.app_name), fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, color = g.ink)
            Text("Votre musique, depuis Google Drive", fontSize = 12.sp, color = g.ink.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val g = glassColors()
    Row(
        Modifier
            .bubbleClickable(pressedScale = 0.95f, onClick = onClick)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) g.fill(0.13f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.dp, Brush.linearGradient(listOf(g.line(0.45f), g.line(0.04f), g.line(0.25f))), RoundedCornerShape(16.dp)) else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = g.ink.copy(alpha = if (selected) 1f else 0.75f), modifier = Modifier.size(22.dp))
        Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = g.ink, modifier = Modifier.padding(start = 14.dp))
    }
}

/**
 * Rescan Drive : bouton au repos ; pendant l'indexation, barre de progression, compteurs
 * (dossiers, fichiers indexés / inchangés / en erreur) et nom du fichier en cours.
 */
@Composable
private fun ScanCard(scan: ScanProgress, onRescan: () -> Unit) {
    if (!scan.running) {
        DrawerItem(Icons.Rounded.Refresh, "Rescanner Drive", selected = false, onClick = onRescan)
        return
    }
    val done = scan.filesIndexed + scan.filesSkipped
    val shape = RoundedCornerShape(16.dp)
    val g = glassColors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(g.fill(0.08f))
            .border(1.dp, g.line(0.10f), shape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = g.ink, modifier = Modifier.size(20.dp))
            Text("Indexation de Drive…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = g.ink, modifier = Modifier.padding(start = 12.dp))
        }
        val fraction = if (scan.filesFound > 0) done.toFloat() / scan.filesFound else 0f
        if (scan.filesFound > 0) {
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = Color(0xFF34C759), trackColor = g.line(0.12f), drawStopIndicator = {},
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = Color(0xFF34C759), trackColor = g.line(0.12f))
        }
        Text(
            if (scan.filesFound > 0) "$done sur ${scan.filesFound} fichiers · ${(fraction * 100).toInt()} %"
            else "Exploration des dossiers…",
            fontSize = 13.sp, color = g.ink.copy(alpha = 0.85f),
        )
        Text(
            buildList {
                add("${scan.foldersVisited} dossier${if (scan.foldersVisited > 1) "s" else ""}")
                add("${scan.filesIndexed} nouveau${if (scan.filesIndexed > 1) "x" else ""}")
                add("${scan.filesSkipped} inchangé${if (scan.filesSkipped > 1) "s" else ""}")
                if (scan.errors > 0) add("${scan.errors} erreur${if (scan.errors > 1) "s" else ""}")
            }.joinToString(" · "),
            fontSize = 12.sp, color = g.ink.copy(alpha = 0.55f),
        )
        scan.current?.let {
            Text(it, fontSize = 12.sp, color = g.ink.copy(alpha = 0.45f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Pastille de choix en verre, partagée par toutes les options du thème. */
@Composable
private fun GlassOption(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val g = glassColors()
    Text(
        label,
        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        color = if (selected) g.onInk else g.ink.copy(alpha = 0.9f),
        textAlign = TextAlign.Center,
        modifier = modifier
            .then(if (enabled) Modifier.bubbleClickable(pressedScale = 0.92f, onClick = onClick) else Modifier.alpha(0.4f))
            .clip(CircleShape)
            .background(if (selected) g.ink.copy(alpha = 0.92f) else g.fill(0.08f))
            .border(1.dp, if (selected) Color.Transparent else g.border, CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSection(theme: ThemeSettings, onChange: ((ThemeSettings) -> ThemeSettings) -> Unit) {
    val g = glassColors()
    val minimal = theme.uiStyle == UiStyle.MINIMAL
    Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Apparence", fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = g.ink)

        Label("Style")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            UiStyle.entries.forEach { st ->
                GlassOption(st.label, theme.uiStyle == st, Modifier.weight(1f)) { onChange { it.copy(uiStyle = st) } }
            }
        }
        Label("Fond")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BackgroundStyle.entries.forEach { bg ->
                GlassOption(bg.label, !minimal && theme.background == bg, enabled = !minimal) { onChange { it.copy(background = bg) } }
            }
        }
        Hint(
            if (minimal) "Le style Minimaliste garde son fond uni."
            else when (theme.background) {
                BackgroundStyle.CLOUD -> "Nappes de couleur qui dérivent doucement."
                BackgroundStyle.BLURRED_ARTWORK -> "La pochette en cours, très floutée."
                BackgroundStyle.PLAIN -> "Un aplat qui suit le mode clair ou sombre."
            },
        )
        Label("Paysage : toucher la pochette")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            DeckMode.entries.forEach { m ->
                GlassOption(m.label, theme.deckMode == m, Modifier.weight(1f)) { onChange { it.copy(deckMode = m) } }
            }
        }
        Hint(
            when (theme.deckMode) {
                DeckMode.CD -> "Le disque sort de la pochette et part dans la platine 3D."
                DeckMode.WALKMAN -> "Des cassettes à la place des pochettes ; la cassette touchée entre dans le walkman."
                DeckMode.FLIP -> "La pochette se retourne et affiche la liste des titres, comme sur iOS 3."
            },
        )

        Box(Modifier.padding(vertical = 8.dp).fillMaxWidth().height(1.dp).background(g.line(0.10f)))
        Text("Thème", fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = g.ink)

        Label("Mode")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEach { mode ->
                GlassOption(mode.label, theme.mode == mode, Modifier.weight(1f)) { onChange { it.copy(mode = mode) } }
            }
        }

        Label("Couleur")
        val sources = ThemeColorSource.entries.filter { it != ThemeColorSource.WALLPAPER || Build.VERSION.SDK_INT >= 31 }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            sources.forEach { source -> GlassOption(source.label, theme.colorSource == source) { onChange { it.copy(colorSource = source) } } }
        }
        if (theme.colorSource == ThemeColorSource.FIXED) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FIXED_COLORS.forEach { color -> Swatch(color, selected = theme.fixedColor == color.toArgb()) { onChange { it.copy(fixedColor = color.toArgb()) } } }
            }
        }

        val dark = isAppInDarkTheme()
        ToggleRow(
            title = "Noir pur",
            subtitle = if (dark) "Fond noir et nuage discret (écrans OLED)" else "Sans effet en mode clair",
            checked = theme.amoled, enabled = dark,
        ) { v -> onChange { it.copy(amoled = v) } }

        Label("Style des couleurs")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThemeStyle.entries.forEach { style -> GlassOption(style.label, theme.style == style) { onChange { it.copy(style = style) } } }
        }
    }
}

/** Crédits des modèles 3D (licence CC BY 4.0 : attribution obligatoire). */
@Composable
private fun Credits() {
    val ink = glassColors().ink
    Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label("Crédits")
        Text("« Old Walkman » par Charles48900, CC BY 4.0", fontSize = 11.sp, color = ink.copy(alpha = 0.5f))
        Text("« Cassette tapes » par FEDDYFAZBARE, CC BY 4.0", fontSize = 11.sp, color = ink.copy(alpha = 0.5f))
        Text("Modèles adaptés (pièces séparées, textures réduites).", fontSize = 11.sp, color = ink.copy(alpha = 0.4f))
        Text("Police Inter, © The Inter Project Authors, SIL Open Font License 1.1", fontSize = 11.sp, color = ink.copy(alpha = 0.4f))
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val g = glassColors()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 15.sp, color = g.ink.copy(alpha = if (enabled) 1f else 0.45f))
            Text(subtitle, fontSize = 12.sp, color = g.ink.copy(alpha = 0.55f))
        }
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF34C759), checkedBorderColor = Color.Transparent,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = g.line(0.16f), uncheckedBorderColor = Color.Transparent,
                disabledCheckedThumbColor = Color.White, disabledCheckedTrackColor = g.line(0.16f), disabledCheckedBorderColor = Color.Transparent,
                disabledUncheckedThumbColor = Color.White, disabledUncheckedTrackColor = g.line(0.10f), disabledUncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = glassColors().ink.copy(alpha = 0.5f), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, fontSize = 12.sp, color = glassColors().ink.copy(alpha = 0.55f))
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val g = glassColors()
    Box(
        Modifier
            .bubbleClickable(onClick = onClick)
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 2.dp else 1.dp, if (selected) g.ink else g.line(0.2f), CircleShape)
            .semantics { contentDescription = "Couleur du thème" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
