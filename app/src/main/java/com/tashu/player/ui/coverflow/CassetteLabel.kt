package com.tashu.player.ui.coverflow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.core.content.res.ResourcesCompat
import com.tashu.player.R
import com.tashu.player.domain.model.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Étiquette du modèle 3D : 92,2 × 43,3 mm (UV plein cadre), percée pour la fenêtre (u 0,183–0,823, v 0,371–0,724). */
internal const val LABEL_RATIO = 1024f / 480f
private const val HOLE_TOP = 0.371f

/** Boîtier de la cassette (mm) et place de l'étiquette sur sa face, mesurés sur le modèle. */
internal const val CASSETTE_RATIO = 101.6f / 65.8f
private const val LABEL_LEFT = 0.0433f
private const val LABEL_RIGHT = 0.9508f
private const val LABEL_TOP = 0.0927f
private const val LABEL_BOTTOM = 0.7508f

/** Pochette décodée à au moins [minSide] px de côté (sous-échantillonnée si elle est bien plus grande). */
internal fun decodeArtwork(path: String?, minSide: Int): Bitmap? {
    if (path == null) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= minSide) sample *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private object LabelFonts {
    @Volatile private var semibold: Typeface? = null
    @Volatile private var regular: Typeface? = null
    fun semibold(context: Context) = semibold ?: ResourcesCompat.getFont(context, R.font.inter_semibold)!!.also { semibold = it }
    fun regular(context: Context) = regular ?: ResourcesCompat.getFont(context, R.font.inter_regular)!!.also { regular = it }
}

/**
 * Étiquette « pleine hauteur » : la pochette entière (carrée) sur toute la hauteur à gauche, le reste en pochette floue
 * assombrie, titre (gras) et artiste en blanc en haut à droite, au-dessus de la fenêtre. Même dessin pour la cassette 3D
 * et pour les cassettes du carrousel.
 */
internal fun cassetteLabel(context: Context, artworkPath: String?, title: String, artist: String, width: Int): Bitmap {
    val w = width
    val h = (width / LABEL_RATIO).roundToInt()
    val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    c.drawColor(android.graphics.Color.rgb(46, 46, 52))
    val art = decodeArtwork(artworkPath, h)
    if (art != null) {
        val side = minOf(art.width, art.height)
        val sx = (art.width - side) / 2
        val sy = (art.height - side) / 2
        val bandH = (side / LABEL_RATIO).roundToInt()
        val band = Bitmap.createBitmap(art, sx, sy + (side - bandH) / 2, side, bandH)
        val blurred = blur(band)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(blurred, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), filter)
        c.drawColor(android.graphics.Color.argb(80, 0, 0, 0))
        c.drawBitmap(art, Rect(sx, sy, sx + side, sy + side), RectF(0f, 0f, h.toFloat(), h.toFloat()), filter)
        if (band !== art) band.recycle()
        blurred.recycle()
        art.recycle()
    }
    val x0 = h + h * 0.05f
    val maxW = (w - x0 - h * 0.05f).roundToInt()
    val shadow = android.graphics.Color.argb(150, 0, 0, 0)
    fun titleLayout(size: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = LabelFonts.semibold(context)
            textSize = size
            setShadowLayer(h * 0.012f, 0f, h * 0.004f, shadow)
        }
        return StaticLayout.Builder.obtain(title, 0, title.length, paint, maxW)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 0.92f)
            .setIncludePad(false)
            .build()
    }
    // Un titre sur deux lignes passe en plus petit pour tenir au-dessus de la fenêtre.
    val titleLayout = titleLayout(h * 0.088f).let { if (it.lineCount > 1) titleLayout(h * 0.068f) else it }
    val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(235, 255, 255, 255)
        typeface = LabelFonts.regular(context)
        textSize = h * 0.062f
        setShadowLayer(h * 0.01f, 0f, h * 0.003f, shadow)
    }
    val artistLine = TextUtils.ellipsize(artist, artistPaint, maxW.toFloat(), TextUtils.TruncateAt.END).toString()
    // Bloc titre + artiste calé juste au-dessus de la fenêtre : le haut de l'étiquette est caché par la vitre du walkman.
    val gap = h * 0.014f
    val artistH = artistPaint.fontMetrics.let { it.descent - it.ascent }
    val blockH = titleLayout.height + gap + artistH
    val top = (h * (HOLE_TOP - 0.025f) - blockH).coerceAtLeast(h * 0.03f)
    c.save()
    c.translate(x0, top)
    titleLayout.draw(c)
    c.restore()
    c.drawText(artistLine, x0, top + titleLayout.height + gap - artistPaint.fontMetrics.ascent, artistPaint)
    return out
}

/** Flou doux et bon marché : réduction à 64 px de large, trois passes de flou boîte, puis agrandissement filtré au dessin. */
private fun blur(src: Bitmap): Bitmap {
    val bw = 64
    val bh = (bw / LABEL_RATIO).roundToInt()
    val small = Bitmap.createScaledBitmap(Bitmap.createScaledBitmap(src, bw * 4, bh * 4, true), bw, bh, true)
    val px = IntArray(bw * bh)
    small.getPixels(px, 0, bw, 0, 0, bw, bh)
    val tmp = IntArray(px.size)
    repeat(3) {
        boxPass(px, tmp, bw, bh, 3, horizontal = true)
        boxPass(tmp, px, bw, bh, 3, horizontal = false)
    }
    val out = Bitmap.createBitmap(px, bw, bh, Bitmap.Config.ARGB_8888)
    small.recycle()
    return out
}

private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
    val n = if (horizontal) w else h
    val lines = if (horizontal) h else w
    val div = 2 * r + 1
    for (line in 0 until lines) {
        fun at(i: Int) = src[if (horizontal) line * w + i.coerceIn(0, n - 1) else i.coerceIn(0, n - 1) * w + line]
        var sr = 0; var sg = 0; var sb = 0
        for (i in -r..r) { val p = at(i); sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF }
        for (i in 0 until n) {
            dst[if (horizontal) line * w + i else i * w + line] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val add = at(i + r + 1)
            val rem = at(i - r)
            sr += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
            sg += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
            sb += (add and 0xFF) - (rem and 0xFF)
        }
    }
}

/** Étiquettes des cassettes du carrousel, gardées en mémoire (≈ 24 Mo au plus). */
private val labelCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** Largeur (px) de l'étiquette d'une cassette du carrousel haute de [heightPx] px (la même en 2D et en 3D). */
internal fun carouselLabelWidth(heightPx: Float): Int =
    (heightPx * (LABEL_RIGHT - LABEL_LEFT)).roundToInt().let { ((it + 63) / 64 * 64).coerceIn(128, 1024) }

private fun carouselLabelKey(album: Album, width: Int) = "${album.artworkPath}|${album.title}|${album.artist}|$width"

/** Étiquette d'une cassette du carrousel : depuis le cache, sinon dessinée hors du fil principal puis gardée. */
internal suspend fun carouselLabel(context: Context, album: Album, width: Int): Bitmap {
    val key = carouselLabelKey(album, width)
    return labelCache.get(key) ?: withContext(Dispatchers.Default) {
        cassetteLabel(context, album.artworkPath, album.title, album.artist, width).also { labelCache.put(key, it) }
    }
}

/**
 * Cassette du carrousel, debout (quart de tour vers la gauche : le haut de l'étiquette à gauche, comme dans le walkman).
 * Dessinée dans un carré : hauteur = côté, largeur = côté / [CASSETTE_RATIO].
 */
@Composable
internal fun CassetteCard(album: Album, modifier: Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier) {
        val labelW = carouselLabelWidth(with(LocalDensity.current) { maxHeight.toPx() })
        val key = carouselLabelKey(album, labelW)
        val label by produceState(labelCache.get(key), key) {
            if (value == null) value = carouselLabel(context, album, labelW)
        }
        val image = label?.asImageBitmap()
        ComposeCanvas(Modifier.matchParentSize()) {
            // Repère « cassette couchée » (longueur L, hauteur S), tourné d'un quart de tour vers la gauche.
            val big = size.height
            val small = big / CASSETTE_RATIO
            val origin = Offset((size.width - big) / 2f, (size.height - small) / 2f)
            rotate(-90f, pivot = center) {
                drawCassette(origin, big, small, image)
            }
        }
    }
}

private val ShellTop = Color(0xFF3E3E3E)
private val ShellBottom = Color(0xFF2A2A2A)
private val WindowFrame = Color(0xFF3A3A3A)
private val WindowInside = Color(0xFF101010)

/** Face de la cassette 3D (boîtier gris, fenêtre à cadre gris, moyeux argentés), vue couchée dans [len] × [ht]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCassette(
    o: Offset, len: Float, ht: Float, label: androidx.compose.ui.graphics.ImageBitmap?,
) {
    fun x(f: Float) = o.x + f * len
    fun y(f: Float) = o.y + f * ht
    drawRoundRect(
        Brush.verticalGradient(listOf(ShellTop, ShellBottom), startY = o.y, endY = o.y + ht),
        topLeft = o, size = Size(len, ht), cornerRadius = CornerRadius(len * 0.025f),
    )
    // Bas trapézoïdal (ouverture de la bande) et vis.
    val trap = Path().apply {
        moveTo(x(0.16f), y(1f)); lineTo(x(0.21f), y(0.80f)); lineTo(x(0.79f), y(0.80f)); lineTo(x(0.84f), y(1f)); close()
    }
    drawPath(trap, Color.Black.copy(alpha = 0.18f))
    drawPath(trap, Color.White.copy(alpha = 0.10f), style = Stroke(len * 0.003f))
    for (v in listOf(Offset(0.025f, 0.045f), Offset(0.975f, 0.045f), Offset(0.025f, 0.955f), Offset(0.975f, 0.955f), Offset(0.5f, 0.87f))) {
        drawCircle(Color(0xFF6A6A6A), radius = len * 0.011f, center = Offset(x(v.x), y(v.y)))
        drawCircle(Color(0xFF1E1E1E), radius = len * 0.005f, center = Offset(x(v.x), y(v.y)))
    }
    for (hx in listOf(0.30f, 0.37f, 0.63f, 0.70f)) {
        drawCircle(Color(0xFF151515), radius = len * 0.012f, center = Offset(x(hx), y(0.92f)))
    }
    val lx = x(LABEL_LEFT)
    val ly = y(LABEL_TOP)
    val lw = (LABEL_RIGHT - LABEL_LEFT) * len
    val lh = (LABEL_BOTTOM - LABEL_TOP) * ht
    if (label != null) {
        drawImage(
            label, srcOffset = IntOffset.Zero, srcSize = IntSize(label.width, label.height),
            dstOffset = IntOffset(lx.roundToInt(), ly.roundToInt()), dstSize = IntSize(lw.roundToInt(), lh.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    } else {
        drawRect(Color(0xFF2E2E34), topLeft = Offset(lx, ly), size = Size(lw, lh))
    }
    // Fenêtre (trou de l'étiquette) : cadre gris, deux moyeux argentés, petite fenêtre centrale.
    val wx = lx + 0.183f * lw
    val wy = ly + 0.371f * lh
    val ww = (0.823f - 0.183f) * lw
    val wh = (0.724f - 0.371f) * lh
    drawRect(WindowFrame, topLeft = Offset(wx, wy), size = Size(ww, wh))
    val cy = wy + wh / 2f
    val r = 0.048f * len
    drawRect(WindowInside, topLeft = Offset(x(0.405f), cy - wh * 0.30f), size = Size(len * 0.19f, wh * 0.60f))
    drawRect(Color(0xFF5A5A5A), topLeft = Offset(x(0.405f), cy - wh * 0.30f), size = Size(len * 0.19f, wh * 0.60f), style = Stroke(len * 0.004f))
    for (hx in listOf(0.287f, 0.713f)) {
        val ctr = Offset(x(hx), cy)
        drawCircle(Color(0xFFB4B4B4), radius = r, center = ctr)
        drawCircle(Color(0xFF7A7A7A), radius = r, center = ctr, style = Stroke(r * 0.12f))
        drawCircle(Color.Black, radius = r * 0.68f, center = ctr)
        for (k in 0 until 6) {
            val a = k * PI.toFloat() / 3f
            drawCircle(Color(0xFFB4B4B4), radius = r * 0.13f, center = ctr + Offset(cos(a), sin(a)) * (r * 0.62f))
        }
    }
}
