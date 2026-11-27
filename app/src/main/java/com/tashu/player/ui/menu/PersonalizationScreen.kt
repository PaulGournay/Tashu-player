package com.tashu.player.ui.menu

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tashu.player.domain.model.BackgroundStyle
import com.tashu.player.domain.model.DeckMode
import com.tashu.player.domain.model.ThemeColorSource
import com.tashu.player.domain.model.ThemeMode
import com.tashu.player.domain.model.ThemeSettings
import com.tashu.player.domain.model.ThemeStyle
import com.tashu.player.domain.model.UiStyle
import com.tashu.player.ui.MainViewModel
import com.tashu.player.ui.theme.AppBackground
import com.tashu.player.ui.theme.GlassBackdrop
import com.tashu.player.ui.theme.GlassCircle
import com.tashu.player.ui.theme.TashuTheme
import com.tashu.player.ui.theme.bubbleClickable
import com.tashu.player.ui.theme.glassColors
import com.tashu.player.ui.theme.glassSource
import com.tashu.player.ui.theme.isAppInDarkTheme
import com.tashu.player.ui.theme.liquidGlass
import com.tashu.player.ui.theme.rememberArtworkSeed
import com.tashu.player.ui.theme.rememberGlassBackdrop
import com.tashu.player.ui.theme.scrollEdgeBlur
import kotlin.math.roundToInt

/** Couleurs proposées pour le thème à couleur fixe ; la première est la couleur par défaut de l'app. */
private val FIXED_COLORS = listOf(
    0xFF4F6D7A, 0xFF3F51B5, 0xFF0B7A75, 0xFF2E7D32, 0xFF9E9D24,
    0xFFE6A23C, 0xFFD2601A, 0xFFC62828, 0xFFAD1457, 0xFF6A1B9A,
).map { Color(it) }

private const val SPEED_MIN = 0.25f
private const val SPEED_MAX = 3f
private const val SPEED_STEP = 0.25f

/** Personnalisation : tous les réglages d'apparence, par sections en verre, sur le fond choisi (aperçu en direct). */
@Composable
fun PersonalizationScreen(vm: MainViewModel, onBack: () -> Unit) {
    val theme by vm.themeSettings.collectAsStateWithLifecycle()
    val artwork = vm.player.state.collectAsStateWithLifecycle().value.metadata.artworkUri?.path
    val seed by rememberArtworkSeed(artwork)
    val backdrop = rememberGlassBackdrop()
    val onChange: ((ThemeSettings) -> ThemeSettings) -> Unit = { vm.updateTheme(it) }

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
                Section(backdrop, "Fond et nuage") { BackgroundSettings(theme, onChange) }
                Section(backdrop, "Thème") { ThemeSettingsSection(theme, onChange) }
                Section(backdrop, "Style") { StyleSettings(theme, onChange) }
                Section(backdrop, "Paysage") { DeckSettings(theme, onChange) }
            }
            Box(Modifier.fillMaxWidth().height(status + 84.dp).scrollEdgeBlur(backdrop))
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassCircle(backdrop, Icons.AutoMirrored.Rounded.ArrowBack, "Retour", onClick = onBack)
                Text(
                    "Personnalisation", fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp,
                    color = g.ink, modifier = Modifier.padding(start = 14.dp),
                )
            }
        }
    }
}

@Composable
internal fun Section(backdrop: GlassBackdrop, title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(backdrop, corner = 28.dp, veil = 0.55f, lens = 24.dp, frost = 22.dp)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp, color = glassColors().ink)
        content()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundSettings(theme: ThemeSettings, onChange: ((ThemeSettings) -> ThemeSettings) -> Unit) {
    val minimal = theme.uiStyle == UiStyle.MINIMAL
    val cloud = !minimal && theme.background == BackgroundStyle.CLOUD
    val moving = cloud && theme.cloudAnimated
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
    ToggleRow(
        title = "Nuage animé",
        subtitle = when {
            minimal -> "Le style Minimaliste garde son fond uni."
            !cloud -> "Seulement avec le fond Nuage vivant."
            theme.cloudAnimated -> "Les nappes dérivent et respirent."
            else -> "Image fixe, sans animation (économise la batterie)."
        },
        checked = theme.cloudAnimated, enabled = cloud,
    ) { v -> onChange { it.copy(cloudAnimated = v) } }

    val off = when {
        minimal -> "Le style Minimaliste garde son fond uni."
        !cloud -> "Seulement avec le Nuage vivant animé."
        !moving -> "Nuage immobile : activez Nuage animé."
        else -> null
    }
    SliderRow("Vitesse", theme.cloudSpeed, enabled = moving, hint = off ?: "Lent à gauche, rapide à droite ; 1× = vitesse d'origine.") { v ->
        onChange { it.copy(cloudSpeed = v) }
    }
    ToggleRow(
        title = "Réagit à la musique",
        subtitle = off ?: "Le nuage pulse sur la musique jouée par Tashu.",
        checked = theme.cloudReactsToMusic, enabled = moving,
    ) { v -> onChange { it.copy(cloudReactsToMusic = v) } }
    val reacting = moving && theme.cloudReactsToMusic
    SliderRow(
        "Intensité", theme.cloudMusicIntensity, enabled = reacting,
        hint = off ?: if (!reacting) "Activez Réagit à la musique." else "Discret à gauche, très marqué à droite ; 1× = réglage d'origine.",
    ) { v -> onChange { it.copy(cloudMusicIntensity = v) } }
}

@Composable
private fun SliderRow(label: String, value: Float, enabled: Boolean, hint: String, onChange: (Float) -> Unit) {
    val g = glassColors()
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, color = g.ink.copy(alpha = if (enabled) 1f else 0.45f), modifier = Modifier.weight(1f))
            Text(
                "%.2f".format(value).trimEnd('0').trimEnd('.', ',') + "×",
                fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = g.ink.copy(alpha = if (enabled) 0.9f else 0.45f),
            )
        }
        Slider(
            value = value,
            onValueChange = { v ->
                val snapped = (v / SPEED_STEP).roundToInt() * SPEED_STEP
                if (snapped != value) onChange(snapped)
            },
            valueRange = SPEED_MIN..SPEED_MAX,
            steps = ((SPEED_MAX - SPEED_MIN) / SPEED_STEP).roundToInt() - 1,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = Color.White, activeTrackColor = Color(0xFF34C759), inactiveTrackColor = g.line(0.16f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                disabledThumbColor = g.line(0.35f), disabledActiveTrackColor = g.line(0.22f), disabledInactiveTrackColor = g.line(0.10f),
                disabledActiveTickColor = Color.Transparent, disabledInactiveTickColor = Color.Transparent,
            ),
        )
        Text(hint, fontSize = 12.sp, color = g.ink.copy(alpha = 0.55f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettingsSection(theme: ThemeSettings, onChange: ((ThemeSettings) -> ThemeSettings) -> Unit) {
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

    Label("Style des couleurs")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ThemeStyle.entries.forEach { style -> GlassOption(style.label, theme.style == style) { onChange { it.copy(style = style) } } }
    }

    val dark = isAppInDarkTheme()
    ToggleRow(
        title = "Noir pur",
        subtitle = if (dark) "Fond noir et nuage discret (écrans OLED)" else "Sans effet en mode clair",
        checked = theme.amoled, enabled = dark,
    ) { v -> onChange { it.copy(amoled = v) } }
}

@Composable
private fun StyleSettings(theme: ThemeSettings, onChange: ((ThemeSettings) -> ThemeSettings) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        UiStyle.entries.forEach { st ->
            GlassOption(st.label, theme.uiStyle == st, Modifier.weight(1f)) { onChange { it.copy(uiStyle = st) } }
        }
    }
}

@Composable
private fun DeckSettings(theme: ThemeSettings, onChange: ((ThemeSettings) -> ThemeSettings) -> Unit) {
    Label("Toucher la pochette")
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
}

/** Pastille de choix en verre, partagée par toutes les options. */
@Composable
internal fun GlassOption(label: String, selected: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
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
internal fun Label(text: String) {
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
