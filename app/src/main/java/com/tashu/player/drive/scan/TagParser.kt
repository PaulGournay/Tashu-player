package com.tashu.player.drive.scan

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Tags que `MediaMetadataRetriever` n'expose pas : ReplayGain, paroles (LYRICS / USLT),
 * et infos flux exactes FLAC (STREAMINFO : fréquence, bits, canaux).
 * Formats : FLAC (Vorbis comments), Ogg Vorbis / Opus (comment header), MP3 (ID3v2.3/2.4).
 */
data class ExtraTags(
    val comments: Map<String, String> = emptyMap(),
    val sampleRate: Int? = null,
    val bitDepth: Int? = null,
    val channels: Int? = null,
    val totalSamples: Long? = null,
) {
    private fun float(key: String) = comments[key]?.trim()?.removeSuffix("dB")?.removeSuffix("db")?.trim()?.toFloatOrNull()
    val rgTrackGain get() = float("REPLAYGAIN_TRACK_GAIN")
    val rgTrackPeak get() = float("REPLAYGAIN_TRACK_PEAK")
    val rgAlbumGain get() = float("REPLAYGAIN_ALBUM_GAIN")
    val rgAlbumPeak get() = float("REPLAYGAIN_ALBUM_PEAK")
    val lyrics get() = comments["LYRICS"] ?: comments["UNSYNCEDLYRICS"] ?: comments["USLT"]
}

object TagParser {

    fun parse(reader: DriveRangeReader): ExtraTags = runCatching {
        val magic = reader.readFully(0, 4)
        when {
            magic.ascii(0, 4) == "fLaC" -> parseFlac(reader)
            magic.ascii(0, 4) == "OggS" -> parseOgg(reader)
            magic.ascii(0, 3) == "ID3" -> parseId3(reader)
            else -> ExtraTags()
        }
    }.getOrDefault(ExtraTags())

    // ---------------- FLAC ----------------
    private fun parseFlac(r: DriveRangeReader): ExtraTags {
        var pos = 4L
        var comments = emptyMap<String, String>()
        var sampleRate: Int? = null; var bits: Int? = null; var ch: Int? = null; var total: Long? = null
        while (true) {
            val header = r.readFully(pos, 4)
            val last = header[0].toInt() and 0x80 != 0
            val type = header[0].toInt() and 0x7F
            val len = ((header[1].u() shl 16) or (header[2].u() shl 8) or header[3].u())
            pos += 4
            when (type) {
                0 -> { // STREAMINFO
                    val si = r.readFully(pos, 18)
                    sampleRate = (si[10].u() shl 12) or (si[11].u() shl 4) or (si[12].u() ushr 4)
                    ch = ((si[12].u() ushr 1) and 0x07) + 1
                    bits = (((si[12].u() and 0x01) shl 4) or (si[13].u() ushr 4)) + 1
                    total = ((si[13].u() and 0x0F).toLong() shl 32) or
                        (si[14].u().toLong() shl 24) or (si[15].u().toLong() shl 16) or
                        (si[16].u().toLong() shl 8) or si[17].u().toLong()
                }
                4 -> comments = parseVorbisComments(ByteBuffer.wrap(r.readFully(pos, len)))
            }
            pos += len
            if (last || pos >= r.size) break
        }
        return ExtraTags(comments, sampleRate, bits, ch, total)
    }

    // ---------------- Ogg (Vorbis / Opus) ----------------
    /** Recompose le 2ᵉ paquet logique (comment header) à travers les pages Ogg. */
    private fun parseOgg(r: DriveRangeReader): ExtraTags {
        var pos = 0L
        var packetIndex = 0
        val packet = java.io.ByteArrayOutputStream()
        var sampleRate: Int? = null; var ch: Int? = null
        while (pos < r.size && packetIndex < 2) {
            val head = r.readFully(pos, 27)
            if (head.ascii(0, 4) != "OggS") break
            val nSeg = head[26].u()
            val segTable = r.readFully(pos + 27, nSeg)
            var dataPos = pos + 27 + nSeg
            for (seg in segTable) {
                val segLen = seg.u()
                packet.write(r.readFully(dataPos, segLen))
                dataPos += segLen
                if (segLen < 255) { // fin de paquet
                    val p = packet.toByteArray(); packet.reset()
                    if (packetIndex == 0) {
                        if (p.ascii(0, 8) == "OpusHead") { ch = p[9].u(); sampleRate = 48_000 }
                        else if (p.size > 16 && p.ascii(1, 6) == "vorbis") {
                            ch = p[11].u()
                            sampleRate = ByteBuffer.wrap(p, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        }
                    } else if (packetIndex == 1) {
                        val skip = when {
                            p.ascii(0, 8) == "OpusTags" -> 8
                            p.ascii(1, 6) == "vorbis" -> 7
                            else -> return ExtraTags(sampleRate = sampleRate, channels = ch)
                        }
                        val buf = ByteBuffer.wrap(p, skip, p.size - skip).slice()
                        return ExtraTags(parseVorbisComments(buf), sampleRate, null, ch)
                    }
                    packetIndex++
                }
            }
            pos = dataPos
        }
        return ExtraTags(sampleRate = sampleRate, channels = ch)
    }

    private fun parseVorbisComments(buf: ByteBuffer): Map<String, String> {
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val vendorLen = buf.int
        buf.position(buf.position() + vendorLen)
        val count = buf.int
        val out = HashMap<String, String>()
        repeat(count) {
            if (buf.remaining() < 4) return out
            val len = buf.int
            if (len < 0 || len > buf.remaining()) return out
            val bytes = ByteArray(len); buf.get(bytes)
            val s = String(bytes, Charsets.UTF_8)
            val eq = s.indexOf('=')
            if (eq > 0) out[s.substring(0, eq).uppercase()] = s.substring(eq + 1)
        }
        return out
    }

    // ---------------- ID3v2 ----------------
    private fun parseId3(r: DriveRangeReader): ExtraTags {
        val h = r.readFully(0, 10)
        val major = h[3].u()
        val tagSize = syncsafe(h, 6)
        val tag = r.readFully(10, tagSize.coerceAtMost(1_048_576))
        val out = HashMap<String, String>()
        var p = if (h[5].toInt() and 0x40 != 0) { // extended header
            if (major == 4) syncsafe(tag, 0) else ByteBuffer.wrap(tag, 0, 4).int + 4
        } else 0
        while (p + 10 <= tag.size) {
            val id = tag.ascii(p, 4)
            if (id[0] == '\u0000') break
            val size = if (major == 4) syncsafe(tag, p + 4) else ByteBuffer.wrap(tag, p + 4, 4).int
            val start = p + 10
            if (size <= 0 || start + size > tag.size) break
            val frame = tag.copyOfRange(start, start + size)
            when (id) {
                "TXXX" -> {
                    val enc = frame[0].toInt()
                    val (desc, valueStart) = readTerminated(frame, 1, enc)
                    out[desc.uppercase()] = decode(frame, valueStart, frame.size, enc).trimEnd('\u0000')
                }
                "USLT" -> {
                    val enc = frame[0].toInt()
                    val (_, textStart) = readTerminated(frame, 4, enc) // 1 enc + 3 langue
                    out["USLT"] = decode(frame, textStart, frame.size, enc).trimEnd('\u0000')
                }
            }
            p = start + size
        }
        return ExtraTags(out)
    }

    private fun readTerminated(b: ByteArray, from: Int, enc: Int): Pair<String, Int> {
        val wide = enc == 1 || enc == 2
        var i = from
        while (i < b.size) {
            if (!wide && b[i].toInt() == 0) return decode(b, from, i, enc) to i + 1
            if (wide && i + 1 < b.size && b[i].toInt() == 0 && b[i + 1].toInt() == 0 && (i - from) % 2 == 0)
                return decode(b, from, i, enc) to i + 2
            i++
        }
        return decode(b, from, b.size, enc) to b.size
    }

    private fun decode(b: ByteArray, from: Int, to: Int, enc: Int): String {
        if (to <= from) return ""
        val cs: Charset = when (enc) {
            1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1
        }
        return String(b, from, to - from, cs)
    }

    private fun syncsafe(b: ByteArray, o: Int) =
        (b[o].u() shl 21) or (b[o + 1].u() shl 14) or (b[o + 2].u() shl 7) or b[o + 3].u()

    private fun Byte.u() = toInt() and 0xFF
    private fun ByteArray.ascii(from: Int, len: Int) =
        if (from + len > size) "" else String(this, from, len, Charsets.ISO_8859_1)
}
