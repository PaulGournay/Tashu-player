package com.tashu.player.ui.coverflow

import android.content.Context
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import io.github.sceneview.node.Node
import io.github.sceneview.gesture.GestureDetector
import io.github.sceneview.collision.Vector3 as CollisionVector
import io.github.sceneview.collision.Box as CollisionBox
import dev.romainguy.kotlin.math.Float2
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.activity.compose.BackHandler
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.os.SystemClock
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.android.TextureHelper
import io.github.sceneview.Scene
import kotlin.math.sin
import io.github.sceneview.rememberView
import io.github.sceneview.SceneView
import com.google.android.filament.View
import io.github.sceneview.math.Position
import io.github.sceneview.math.Rotation
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Chemin du modèle dans `assets/` (gardé hors du dépôt public : voir .gitignore). */
internal const val STEREO_MODEL = "models/pm02.glb"

internal fun stereoModelAvailable(context: Context): Boolean =
    runCatching { context.assets.list("models")?.contains("pm02.glb") == true }.getOrDefault(false)

/** Ce qu'affiche l'écran de la chaîne. */
internal data class LcdInfo(val track: Int?, val trackCount: Int, val seconds: Long, val status: String)

/** Course du tiroir quand il est complètement sorti (mètres, vers l'avant). */
private const val TRAY_TRAVEL = 0.09f
/**
 * Fermé, le tiroir revient exactement à sa place d'origine : le boîtier est opaque (pas de problème de tri),
 * et tout décalage ouvrirait une fente visible entre la façade et le haut du tiroir.
 */
private const val TRAY_CLOSED_OFFSET = 0f
/** Hauteur d'où tombe le disque avant de se poser sur le plateau. */
private const val DISC_DROP = 0.10f

/**
 * Chaîne hi-fi 3D (Panasonic SA-PM02) rendue par Filament via SceneView, fond transparent.
 * Le modèle a été préparé dans Blender : objets séparés « Tray » (façade du tiroir, plateau et « Disc »)
 * et « Lcd » (vitre de l'afficheur, UV plein cadre).
 *
 * @param appear 0..1 : entrée de la chaîne par la droite.
 * @param trayOpen 0..1 : sortie du tiroir.
 * @param discDrop < 0 : disque caché ; 0..1 : il descend sur le plateau ; 1 : posé.
 * @param spinning le disque tourne (lecture en cours), visible à travers le capot vitré.
 */
/** Commandes de façade du modèle (noms des objets séparés dans Blender). */
internal enum class DeckControl(val nodeName: String) {
    POWER("BtnRectL"), BLANK("BtnRectR"),
    PLAY_PAUSE("BtnRound1"), SHUFFLE("BtnRound2"), REPEAT("BtnRound3"), CD_PLAY_PAUSE("BtnRound4"),
    BASS_TREBLE("BtnBottom1"), PREVIOUS("BtnBottom2"), NEXT("BtnBottom3"),
    EJECT("BtnEject"),
}

private const val PLAYER_BODY = "panasonic_sa-pm02_player_panasonic_sa-pm02-player_0"
private const val KNOB = "Knob"
/** Course des boutons enfoncés (mètres) et durée de l'appui. */
private const val PRESS_DEPTH = 0.0016f
private const val PRESS_MS = 220L
/** Débattement du bouton de volume. */
private const val KNOB_MIN = -135f
private const val KNOB_MAX = 135f

/**
 * Chaîne hi-fi 3D interactive. Toucher le lecteur : gros plan sur la façade. En gros plan, les boutons
 * s'enfoncent et commandent la lecture ; le bouton de volume se tourne en glissant le doigt dessus
 * (volume média du téléphone, « VOL nn » sur l'afficheur). Retour ou toucher hors du lecteur : vue d'ensemble.
 */
@Composable
internal fun Stereo3D(
    appear: Float,
    trayOpen: Float,
    discDrop: Float,
    spinning: Boolean,
    artworkPath: String?,
    lcd: LcdInfo,
    controlsEnabled: Boolean,
    onControl: (DeckControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hostView = LocalView.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    // Qualité de rendu : MSAA ×4 + FXAA, occlusion ambiante (creux, jointures), léger bloom (écran rétroéclairé).
    val view = rememberView(engine) {
        SceneView.createView(engine).apply {
            antiAliasing = View.AntiAliasing.FXAA
            multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
            ambientOcclusionOptions = View.AmbientOcclusionOptions().apply {
                enabled = true
                radius = 0.02f
                intensity = 1.2f
                quality = View.QualityLevel.HIGH
            }
            bloomOptions = View.BloomOptions().apply { enabled = true; strength = 0.06f; threshold = true }
            dithering = View.Dithering.TEMPORAL
        }
    }
    val cameraNode = rememberCameraNode(engine) {
        position = Position(0f, 0.12f, 0.46f)
        lookAt(Position(0f, 0.075f, 0f))
    }
    val model = remember(modelLoader) {
        ModelNode(modelInstance = modelLoader.createModelInstance(STEREO_MODEL), autoAnimate = false).apply {
            // Seuls le corps du lecteur et ses commandes reçoivent les touchers (zones = boîtes englobantes).
            isTouchable = false
            collisionShape = null
            val touchable = DeckControl.entries.map { it.nodeName }.toSet() + KNOB + PLAYER_BODY
            renderableNodes.filter { it.name in touchable }.forEach { node ->
                val box = node.axisAlignedBoundingBox
                node.collisionShape = CollisionBox(
                    CollisionVector(box.halfExtent[0] * 2f, box.halfExtent[1] * 2f, box.halfExtent[2] * 2f),
                    CollisionVector(box.center[0], box.center[1], box.center[2]),
                )
                node.isTouchable = true
            }
        }
    }
    val tray = remember(model) { model.nodes.firstOrNull { it.name == "Tray" } }
    val disc = remember(model) { model.renderableNodes.firstOrNull { it.name == "Disc" } }
    val screen = remember(model) { model.renderableNodes.firstOrNull { it.name == "Lcd" } }
    val knob = remember(model) { model.renderableNodes.firstOrNull { it.name == KNOB } }
    val buttons = remember(model) {
        DeckControl.entries.mapNotNull { c -> model.renderableNodes.firstOrNull { it.name == c.nodeName }?.let { c to it } }.toMap()
    }
    val buttonBase = remember(buttons) { buttons.mapValues { it.value.position } }
    val trayBase = remember(tray) { tray?.position }
    val discBase = remember(disc) { disc?.position }

    // ---- Interaction ----
    var focused by remember { mutableStateOf(false) }
    val focus = remember { Animatable(0f) }
    // En gros plan, la boîte du boîtier passerait devant les boutons : seules les commandes restent touchables.
    val body = remember(model) { model.renderableNodes.firstOrNull { it.name == PLAYER_BODY } }
    LaunchedEffect(focused) {
        body?.isTouchable = !focused
        focus.animateTo(if (focused) 1f else 0f, tween(650, easing = FastOutSlowInEasing))
    }
    BackHandler(enabled = focused) { focused = false }
    val pressedAt = remember { mutableMapOf<DeckControl, Long>() }
    var knobAngle by remember {
        mutableStateOf(KNOB_MIN + (KNOB_MAX - KNOB_MIN) * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume)
    }
    var draggingKnob by remember { mutableStateOf(false) }
    var volumeShownUntil by remember { mutableStateOf(0L) }
    var volumeLevel by remember { mutableStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val enabled by rememberUpdatedState(controlsEnabled)
    val control by rememberUpdatedState(onControl)

    fun press(c: DeckControl) {
        pressedAt[c] = SystemClock.uptimeMillis()
        hostView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        control(c)
    }

    fun turnKnob(deltaDegrees: Float) {
        knobAngle = (knobAngle + deltaDegrees).coerceIn(KNOB_MIN, KNOB_MAX)
        val level = ((knobAngle - KNOB_MIN) / (KNOB_MAX - KNOB_MIN) * maxVolume).roundToInt()
        if (level != volumeLevel) {
            volumeLevel = level
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
            hostView.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        volumeShownUntil = SystemClock.uptimeMillis() + 1500
    }

    val gestures = remember {
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent, node: Node?) {
                draggingKnob = focused && node?.name == KNOB
            }

            override fun onSingleTapConfirmed(e: MotionEvent, node: Node?) {
                val name = node?.name
                when {
                    name == null -> focused = false // toucher hors du lecteur : vue d'ensemble
                    !focused -> focused = true // premier toucher : gros plan sur la façade
                    !enabled -> Unit
                    else -> DeckControl.entries.firstOrNull { it.nodeName == name }?.let(::press)
                }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, node: Node?, distance: Float2) {
                // Glisser vers le haut ou la droite = monter le volume.
                if (draggingKnob) turnKnob((distance.y - distance.x) * 0.6f)
            }
        }
    }

    // Étiquette du disque = pochette de l'album.
    val discTexture = remember { TextureSlot(engine) }
    LaunchedEffect(artworkPath, disc) {
        val label = withContext(Dispatchers.Default) { discLabel(artworkPath) }
        disc?.materialInstance?.let { discTexture.set(it, label, "baseColorMap") }
    }
    // Afficheur : redessiné quand son contenu change ; « VOL nn » pendant qu'on tourne le bouton.
    val lcdTexture = remember { TextureSlot(engine) }
    val showVolume = volumeShownUntil != 0L
    val shown = if (showVolume) lcd.copy(status = "VOL %02d".format(volumeLevel)) else lcd
    LaunchedEffect(shown, screen) {
        val bmp = withContext(Dispatchers.Default) { lcdBitmap(shown) }
        screen?.materialInstance?.let { lcdTexture.set(it, bmp, "baseColorMap", "emissiveMap") }
    }
    LaunchedEffect(volumeShownUntil) {
        val wait = volumeShownUntil - SystemClock.uptimeMillis()
        if (volumeShownUntil != 0L) { delay(wait.coerceAtLeast(0L) + 50); volumeShownUntil = 0L }
    }
    DisposableEffect(engine) { onDispose { discTexture.release(); lcdTexture.release() } }

    val a by rememberUpdatedState(appear)
    val open by rememberUpdatedState(trayOpen)
    val drop by rememberUpdatedState(discDrop)
    val spin by rememberUpdatedState(spinning)
    var angle by remember { mutableStateOf(0f) }
    var speed by remember { mutableStateOf(0f) }

    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        view = view,
        cameraNode = cameraNode,
        childNodes = listOf(model),
        isOpaque = false,
        cameraManipulator = null,
        onGestureListener = gestures,
        onFrame = { frameTimeNanos ->
            // Caméra : vue d'ensemble qui se balance doucement, ou gros plan sur la façade.
            val t = frameTimeNanos / 1_000_000_000.0
            val f = focus.value
            val sway = 1f - f * 0.85f
            val far = Position((sin(t * 0.23) * 0.018 * sway).toFloat(), (0.12 + sin(t * 0.17) * 0.004 * sway).toFloat(), 0.46f)
            val near = Position(0.012f, 0.088f, 0.235f)
            cameraNode.position = Position(lerp(far.x, near.x, f), lerp(far.y, near.y, f), lerp(far.z, near.z, f))
            cameraNode.lookAt(Position(lerp(0f, 0.008f, f), lerp(0.075f, 0.078f, f), lerp(0f, 0.09f, f)))

            val hidden = 1f - a
            model.position = Position(x = hidden * 0.45f)
            model.rotation = Rotation(y = hidden * -40f)
            if (tray != null && trayBase != null) {
                tray.position = Position(trayBase.x, trayBase.y, trayBase.z + TRAY_CLOSED_OFFSET + TRAY_TRAVEL * open)
            }
            if (disc != null && discBase != null) {
                disc.isVisible = drop >= 0f
                val dt = drop.coerceIn(0f, 1f)
                val fall = 1f - (1f - dt) * (1f - dt) // décélération en fin de chute
                disc.position = Position(discBase.x, discBase.y + DISC_DROP * (1f - fall), discBase.z)
                // Démarrage et arrêt progressifs du disque (inertie), comme une vraie platine.
                speed += ((if (spin) 7f else 0f) - speed) * 0.04f
                angle = (angle + speed) % 360f
                disc.rotation = Rotation(y = angle)
            }
            // Boutons : enfoncés puis relâchés (aller-retour en PRESS_MS).
            val now = SystemClock.uptimeMillis()
            buttons.forEach { (c, node) ->
                val base = buttonBase[c] ?: return@forEach
                val elapsed = now - (pressedAt[c] ?: 0L)
                val depth = if (elapsed in 0 until PRESS_MS) {
                    val u = elapsed / PRESS_MS.toFloat()
                    PRESS_DEPTH * (if (u < 0.35f) u / 0.35f else (1f - u) / 0.65f)
                } else 0f
                node.position = Position(base.x, base.y, base.z - depth)
            }
            knob?.rotation = Rotation(z = -knobAngle)
        },
    )
}

private fun lerp(x: Float, y: Float, t: Float) = x + (y - x) * t

/** Une texture Filament remplaçable, branchée sur un ou plusieurs paramètres d'un matériau glTF. */
internal class TextureSlot(private val engine: Engine) {
    private var texture: Texture? = null
    private val sampler = TextureSampler(
        TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR, TextureSampler.MagFilter.LINEAR, TextureSampler.WrapMode.CLAMP_TO_EDGE,
    )

    fun set(material: MaterialInstance, bitmap: Bitmap, vararg parameters: String) {
        val tex = Texture.Builder()
            .width(bitmap.width).height(bitmap.height)
            .levels(0xff)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(Texture.InternalFormat.SRGB8_A8)
            .build(engine)
        TextureHelper.setBitmap(engine, tex, 0, bitmap)
        tex.generateMipmaps(engine)
        parameters.forEach { material.setParameter(it, tex, sampler) }
        texture?.let { engine.destroyTexture(it) }
        texture = tex
    }

    fun release() {
        texture?.let { runCatching { engine.destroyTexture(it) } }
        texture = null
    }
}

/** Face imprimée du CD : pochette en étiquette, reflets irisés, moyeu argenté et trou central. */
private fun discLabel(artworkPath: String?): Bitmap {
    val size = 1024
    val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val r = size / 2f
    c.drawColor(android.graphics.Color.rgb(205, 205, 212))
    val art = artworkPath?.let { path ->
        runCatching {
            val opts = BitmapFactory.Options()
            BitmapFactory.decodeFile(File(path).absolutePath, opts)
        }.getOrNull()
    }
    if (art != null) {
        val side = minOf(art.width, art.height)
        val src = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
        c.drawBitmap(art, src, RectF(0f, 0f, size.toFloat(), size.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        art.recycle()
    }
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = SweepGradient(
        r, r,
        intArrayOf(0x00FFFFFF, 0x40FF6EC7, 0x00FFFFFF, 0x4046E6FF, 0x00FFFFFF, 0x40FFF176, 0x00FFFFFF), null,
    )
    c.drawCircle(r, r, r, p)
    p.shader = RadialGradient(r, r, r * 0.33f, intArrayOf(0xFFF2F2F2.toInt(), 0xFF9A9A9A.toInt(), 0xFFE0E0E0.toInt()), null, Shader.TileMode.CLAMP)
    c.drawCircle(r, r, r * 0.33f, p)
    p.shader = null
    p.color = 0xFF050505.toInt()
    c.drawCircle(r, r, r * 0.075f, p)
    return out
}

/** Contenu de l'afficheur : fond lilas rétroéclairé, piste et temps en gros, état et nombre de pistes. */
private fun lcdBitmap(info: LcdInfo): Bitmap {
    val w = 640
    val h = 128
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(android.graphics.Color.rgb(104, 104, 158))
    val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(236, 236, 255)
        textSize = 74f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.rgb(222, 222, 250)
        textSize = 24f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    // Seule la partie droite (~58 %) de la vitre est visible : la gauche est sous le cadre de la façade.
    val x0 = w * 0.42f + 14f
    val track = info.track?.let { "%02d".format(it) } ?: "--"
    val time = "%d:%02d".format(info.seconds / 60, info.seconds % 60)
    c.drawText(info.status, x0, 38f, small)
    if (info.trackCount > 0) c.drawText("${info.trackCount} TR", w - 24f - small.measureText("${info.trackCount} TR"), 38f, small)
    val mid = Paint(big).apply { textSize = 58f }
    c.drawText(track, x0, 106f, mid)
    c.drawText(time, w - 28f - mid.measureText(time), 106f, mid)
    return out
}
