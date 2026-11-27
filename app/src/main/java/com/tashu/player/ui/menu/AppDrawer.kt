package com.tashu.player.ui.menu

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.style.TextOverflow
import com.tashu.player.drive.scan.ScanProgress
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tashu.player.ui.theme.GlassBackdrop
import com.tashu.player.ui.theme.glassColors
import com.tashu.player.ui.theme.bubbleClickable
import com.tashu.player.ui.theme.liquidGlass
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.tashu.player.R

/** Menu latéral : panneau flottant en verre « Clear », raccourcis de la bibliothèque et accès à la personnalisation. */
@Composable
fun AppDrawer(
    backdrop: GlassBackdrop,
    onLibrary: () -> Unit,
    onManageFolders: () -> Unit,
    scan: ScanProgress,
    onRescan: () -> Unit,
    onPersonalize: () -> Unit,
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
            DrawerItem(Icons.Rounded.Palette, "Personnalisation", selected = false, onClick = onPersonalize)
            Box(Modifier.padding(vertical = 14.dp).fillMaxWidth().height(1.dp).background(g.line(0.10f)))
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
