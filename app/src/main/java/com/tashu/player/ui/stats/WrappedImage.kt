package com.tashu.player.ui.stats

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.palette.graphics.Palette
import com.tashu.player.R
import com.tashu.player.domain.model.MonthRecap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val W = 1080
private const val H = 1920

/**
 * Image à partager (1080 × 1920, PNG) dessinée hors écran : nuage tiré de la pochette de l'album du mois,
 * mois, pochette, chiffres clés et top 5. Écrite dans le cache, partagée par FileProvider (aucune permission).
 */
suspend fun renderWrappedImage(context: Context, recap: MonthRecap): File = withContext(Dispatchers.Default) {
    val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val album = recap.topAlbums.firstOrNull()?.album
    val art = album?.artworkPath?.let { BitmapFactory.decodeFile(it, BitmapFactory.Options().apply { inSampleSize = 2 }) }
    val colors = art?.let { a ->
        val p = Palette.from(a).maximumColorCount(24).generate()
        // Teintes franches seulement : un gris (texte blanc de la pochette, par exemple) salirait le fond.
        listOfNotNull(p.vibrantSwatch, p.darkVibrantSwatch, p.mutedSwatch, p.lightVibrantSwatch, p.dominantSwatch)
            .filter { it.hsl[1] >= 0.2f }.map { it.rgb }.distinct()
    }.orEmpty().ifEmpty { listOf(0xFF2B3A55.toInt(), 0xFF4F6D7A.toInt(), 0xFF3A2B55.toInt()) }

    // Fond : nappes de couleur tirées de la pochette (sans la pochette elle-même, dont le texte ferait des traînées).
    c.drawColor(0xFF0E0E12.toInt())
    val blobs = listOf(Triple(0.2f, 0.15f, 0.75f), Triple(0.85f, 0.35f, 0.65f), Triple(0.3f, 0.7f, 0.8f), Triple(0.8f, 0.92f, 0.7f))
    blobs.forEachIndexed { i, (x, y, r) ->
        val col = colors[i % colors.size]
        val radius = W * r
        c.drawCircle(W * x, H * y, radius, Paint().apply {
            shader = RadialGradient(W * x, H * y, radius, intArrayOf(withAlpha(col, 200), withAlpha(col, 90), withAlpha(col, 0)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        })
    }
    c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
        shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(0x55000000, 0x33000000, 0xB0000000.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
    })

    val bold = ResourcesCompat.getFont(context, R.font.inter_semibold)
    val regular = ResourcesCompat.getFont(context, R.font.inter_regular)
    fun paint(size: Float, semibold: Boolean, alpha: Float = 1f, align: Paint.Align = Paint.Align.CENTER) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = if (semibold) bold else regular
        textSize = size
        color = withAlpha(Color.WHITE, (alpha * 255).toInt())
        textAlign = align
    }
    fun text(s: String, x: Float, y: Float, p: TextPaint, max: Float = W - 160f) =
        c.drawText(TextUtils.ellipsize(s, p, max, TextUtils.TruncateAt.END).toString(), x, y, p)

    text("TASHU WRAPPED", W / 2f, 150f, paint(36f, true, 0.8f).apply { letterSpacing = 0.3f })
    text(monthName(recap.month, withYear = true).replaceFirstChar { it.uppercase() }, W / 2f, 262f, paint(92f, true).apply { letterSpacing = -0.02f })

    // Pochette de l'album du mois, coins arrondis et ombre portée.
    val box = RectF(250f, 340f, 830f, 920f)
    c.drawRoundRect(box, 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF; setShadowLayer(60f, 0f, 24f, 0x99000000.toInt()) })
    if (art != null) {
        c.save()
        c.clipPath(Path().apply { addRoundRect(box, 40f, 40f, Path.Direction.CW) })
        val side = minOf(art.width, art.height)
        c.drawBitmap(art, Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2), box, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
    }
    if (album != null) {
        text(album.title, W / 2f, 1010f, paint(52f, true))
        text("Album du mois · ${album.artist}", W / 2f, 1068f, paint(36f, false, 0.75f))
    }

    // Chiffres clés : deux colonnes, deux lignes.
    val t = recap.total
    val cells = listOf(
        listeningTime(t.listenedMs) to "d'écoute",
        "${t.plays}" to "écoutes",
        recap.topArtists.first().name to "artiste du mois",
        "${t.tracks}" to if (t.tracks == 1) "morceau" else "morceaux différents",
    )
    cells.forEachIndexed { i, (value, label) ->
        val x = if (i % 2 == 0) W * 0.29f else W * 0.71f
        val y = 1200f + (i / 2) * 170f
        text(value, x, y, paint(if (value.length > 12) 46f else 60f, true), max = W * 0.4f)
        text(label, x, y + 50f, paint(32f, false, 0.7f), max = W * 0.4f)
    }

    text("TOP MORCEAUX", 110f, 1530f, paint(30f, true, 0.7f, Paint.Align.LEFT).apply { letterSpacing = 0.2f })
    recap.topTracks.take(5).forEachIndexed { i, s ->
        val y = 1595f + i * 54f
        c.drawText("${i + 1}", 110f, y, paint(38f, true, 0.6f, Paint.Align.LEFT))
        text("${s.track.title} — ${s.track.artist}", 160f, y, paint(38f, false, 1f, Paint.Align.LEFT), max = W - 270f)
    }
    text("Tashu · ton mois en musique", W / 2f, 1885f, paint(28f, false, 0.6f))

    val file = File(context.cacheDir, "partage/tashu-wrapped-${recap.month}.png")
    file.parentFile!!.mkdirs()
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    file
}

/** Feuille de partage Android avec l'image (URI content://, lecture accordée au destinataire). */
fun shareWrappedImage(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(null, uri)
    context.startActivity(Intent.createChooser(send, "Partager mon mois"))
}

private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
