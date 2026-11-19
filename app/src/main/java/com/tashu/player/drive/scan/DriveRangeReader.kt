package com.tashu.player.drive.scan

import android.media.MediaDataSource
import com.tashu.player.drive.api.DriveApi
import kotlinx.coroutines.runBlocking
import java.io.IOException

/** Dépassement du budget d'octets : on refuse de télécharger tout le fichier pendant le scan. */
class ByteBudgetExceeded : IOException("Budget de lecture dépassé")

/**
 * Accès aléatoire à un fichier Drive via HTTP Range Requests, avec cache de blocs de 64 Kio.
 * Seuls les blocs effectivement lus sont téléchargés (en-têtes ID3/FLAC/Ogg, atome moov MP4…),
 * jamais le fichier entier : un [byteBudget] coupe court si un extracteur part lire tout le flux.
 *
 * Sert à la fois au [TagParser] (lecture directe) et à `MediaMetadataRetriever`
 * (via [asMediaDataSource]).
 */
class DriveRangeReader(
    private val api: DriveApi,
    private val fileId: String,
    val size: Long,
    private val byteBudget: Long = 4L * 1024 * 1024,
) {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }
    var bytesFetched = 0L
        private set

    @Synchronized
    fun read(position: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        var pos = position
        var written = 0
        val toRead = minOf(length.toLong(), size - position).toInt()
        while (written < toRead) {
            val blockIndex = pos / BLOCK
            val block = block(blockIndex)
            val inBlock = (pos - blockIndex * BLOCK).toInt()
            val n = minOf(block.size - inBlock, toRead - written)
            if (n <= 0) break
            System.arraycopy(block, inBlock, dst, offset + written, n)
            written += n
            pos += n
        }
        return written
    }

    fun readFully(position: Long, length: Int): ByteArray {
        val buf = ByteArray(length)
        val n = read(position, buf, 0, length)
        if (n < length) throw IOException("EOF à ${position + n}")
        return buf
    }

    private fun block(index: Long): ByteArray = blocks.getOrPut(index) {
        val start = index * BLOCK
        val end = minOf(start + BLOCK, size) - 1
        if (bytesFetched + (end - start + 1) > byteBudget) throw ByteBudgetExceeded()
        val data = runBlocking { api.readRange(fileId, start, end) }
        bytesFetched += data.size
        data
    }

    fun asMediaDataSource(): MediaDataSource = object : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            if (size == 0) 0 else read(position, buffer, offset, size)
        override fun getSize(): Long = this@DriveRangeReader.size
        override fun close() = Unit
    }

    companion object {
        const val BLOCK = 64L * 1024
        private const val MAX_BLOCKS = 64
    }
}
