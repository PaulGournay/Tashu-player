package com.tashu.player.ui.menu

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Menu latéral : bannière Tashu, raccourcis de la bibliothèque et réglages du thème. */
@Composable
fun AppDrawer(
    theme: ThemeSettings,
    onThemeChange: ((ThemeSettings) -> ThemeSettings) -> Unit,
    onLibrary: () -> Unit,
    onImport: () -> Unit,
    onManageFolders: () -> Unit,
    onRescan: () -> Unit,
) {
    // Pas de marge système sur le tiroir : la bannière passe sous la barre d'état.
    ModalDrawerSheet(windowInsets = WindowInsets(0)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            Banner()
            Spacer(Modifier.height(12.dp))
            NavigationDrawerItem(
                label = { Text("Bibliothèque") }, selected = true, onClick = onLibrary,
                icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text("Dossiers Drive") }, selected = false, onClick = onManageFolders,
                icon = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text("Importer une playlist") }, selected = false, onClick = onImport,
                icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text("Rescanner Drive") }, selected = false, onClick = onRescan,
                icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 12.dp))
            ThemeSection(theme, onThemeChange)
        }
    }
}

@Composable
private fun Banner() {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(scheme.primary, scheme.tertiary)))
            .statusBarsPadding()
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                colorFilter = ColorFilter.tint(scheme.primary),
                modifier = Modifier.size(64.dp).clip(CircleShape).background(scheme.onPrimary),
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = scheme.onPrimary)
                Text("Votre musique, depuis Google Drive", style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary.copy(alpha = 0.85f))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ThemeSection(theme: ThemeSettings, onChange: ((ThemeSettings) -> ThemeSettings) -> Unit) {
    Column(Modifier.padding(horizontal = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Thème", style = MaterialTheme.typography.titleMedium)

        Label("Mode")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { i, mode ->
                SegmentedButton(
                    selected = theme.mode == mode,
                    onClick = { onChange { it.copy(mode = mode) } },
                    shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                ) { Text(mode.label) }
            }
        }

        Label("Couleur")
        val sources = ThemeColorSource.entries.filter { it != ThemeColorSource.WALLPAPER || Build.VERSION.SDK_INT >= 31 }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { source ->
                FilterChip(
                    selected = theme.colorSource == source,
                    onClick = { onChange { it.copy(colorSource = source) } },
                    label = { Text(source.label) },
                )
            }
        }
        if (theme.colorSource == ThemeColorSource.FIXED) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FIXED_COLORS.forEach { color -> Swatch(color, selected = theme.fixedColor == color.toArgb()) { onChange { it.copy(fixedColor = color.toArgb()) } } }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Noir pur", style = MaterialTheme.typography.bodyLarge)
                Text("Fond noir en mode sombre (écrans OLED)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = theme.amoled, onCheckedChange = { v -> onChange { it.copy(amoled = v) } })
        }

        Label("Style des couleurs")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeStyle.entries.forEach { style ->
                FilterChip(
                    selected = theme.style == style,
                    onClick = { onChange { it.copy(style = style) } },
                    label = { Text(style.label) },
                )
            }
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(50))
            .background(color)
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Couleur du thème" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
    }
}
