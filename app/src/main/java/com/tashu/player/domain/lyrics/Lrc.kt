package com.tashu.player.domain.lyrics

/** Mot d'une ligne en LRC enrichi (`<mm:ss.xx>mot`) : sert au surlignage karaoké. */
data class LyricWord(val startMs: Long, val text: String)

/** `timeMs == null` : ligne non synchronisée (paroles en texte simple, ou ligne sans horodatage). */
data class LyricLine(val timeMs: Long?, val text: String, val words: List<LyricWord> = emptyList())

data class ParsedLyrics(val lines: List<LyricLine>, val synced: Boolean) {
    /** Index de la ligne en cours à [positionMs], -1 avant la première ligne. Recherche dichotomique. */
    fun indexAt(positionMs: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            // Tolérance de 5 ms : un seek sur le début d'une ligne tombe bien sur cette ligne.
            if ((lines[mid].timeMs ?: Long.MIN_VALUE) <= positionMs + 5) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}

/**
 * Parseur LRC, d'après celui de Namida (dart_lrc) :
 * horodatages `[m:ss]`, `[m:ss.f]` à `[m:ss.ffffff]`, plusieurs par ligne ; balises `[ti:]`, `[ar:]`,
 * `[offset:±ms]` (positif = paroles plus tôt), `[length:m:ss]` ; mots `<m:ss.xx>` du LRC enrichi.
 * Si `[length:]` diffère de la durée réelle (version accélérée ou ralentie), les temps sont étirés.
 */
object LrcParser {
    private val LEADING_TIME = Regex("""^\s*\[(\d+):(\d{1,2})(?:[.:](\d{1,6}))?]""")
    private val TAG = Regex("""^\s*\[([a-zA-Z#]+):(.*)]\s*$""")
    private val WORD = Regex("""<(\d+):(\d{1,2})(?:[.:](\d{1,6}))?>""")
    private val INLINE_TIME = Regex("""\[\d+:\d{1,2}(?:[.:]\d{1,6})?]""")

    fun parse(raw: String, trackDurationMs: Long = 0, extraOffsetMs: Long = 0): ParsedLyrics {
        val tags = HashMap<String, String>()
        // (ordre dans le fichier, ligne) ; une ligne sans horodatage reprend le temps de la ligne synchronisée qui la précède.
        val entries = ArrayList<Pair<Int, LyricLine>>()
        val untimed = ArrayList<String>()
        var lastTime: Long? = null
        var hasTimed = false

        raw.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEachIndexed { index, line ->
            var rest = line
            val stamps = ArrayList<Long>()
            while (true) {
                val m = LEADING_TIME.find(rest) ?: break
                stamps += toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                rest = rest.substring(m.range.last + 1)
            }
            if (stamps.isEmpty()) {
                val tag = TAG.matchEntire(line)
                if (tag != null) tags.putIfAbsent(tag.groupValues[1].lowercase(), tag.groupValues[2].trim())
                else if (line.isNotBlank()) {
                    untimed += line.trim()
                    entries += index to LyricLine(lastTime ?: 0L, line.trim())
                }
                return@forEachIndexed
            }
            hasTimed = true
            val words = parseWords(rest)
            val text = if (words.isNotEmpty()) words.joinToString("") { it.text }.trim() else rest.trim()
            stamps.forEach { entries += index to LyricLine(it, text, words) }
            lastTime = stamps.first()
        }

        if (!hasTimed) return ParsedLyrics(untimed.map { LyricLine(null, it) }, synced = false)

        val offset = (tags["offset"]?.trim()?.removePrefix("+")?.toLongOrNull() ?: 0L) + extraOffsetMs
        val declaredLength = tags["length"]?.let(::parseLength)
        val stretch = if (declaredLength != null && declaredLength > 0 && trackDurationMs > 0 &&
            kotlin.math.abs(declaredLength - trackDurationMs) > 1_000
        ) trackDurationMs.toDouble() / declaredLength else 1.0

        fun adjust(t: Long) = ((t * stretch).toLong() - offset).coerceAtLeast(0)

        val lines = entries.sortedWith(compareBy({ it.second.timeMs }, { it.first })).map { (_, l) ->
            l.copy(timeMs = adjust(l.timeMs!!), words = l.words.map { it.copy(startMs = adjust(it.startMs)) })
        }
        return ParsedLyrics(lines, synced = true)
    }

    /** Retire les balises et horodatages d'un texte pour l'afficher en paroles simples. */
    fun toPlain(raw: String): String = raw.lineSequence()
        .filterNot { TAG.matches(it) && LEADING_TIME.find(it) == null }
        .map { it.replace(INLINE_TIME, "").replace(WORD, "").trim() }
        .joinToString("\n")
        .trim()

    fun isSynced(raw: String?): Boolean = raw != null && raw.lineSequence().any { LEADING_TIME.containsMatchIn(it) }

    private fun parseWords(text: String): List<LyricWord> {
        val matches = WORD.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexedNotNull { i, m ->
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
            val word = text.substring(m.range.last + 1, end)
            if (word.isEmpty()) null else LyricWord(toMs(m.groupValues[1], m.groupValues[2], m.groupValues[3]), word)
        }
    }

    private fun parseLength(v: String): Long? {
        val parts = v.trim().split(':')
        return when (parts.size) {
            1 -> parts[0].toDoubleOrNull()?.let { (it * 1000).toLong() }
            2 -> parts[0].toLongOrNull()?.let { m -> parts[1].toDoubleOrNull()?.let { s -> m * 60_000 + (s * 1000).toLong() } }
            else -> null
        }
    }

    private fun toMs(min: String, sec: String, frac: String): Long {
        val fracMs = if (frac.isEmpty()) 0L else (frac.padEnd(6, '0').take(6).toLong() / 1000)
        return (min.toLong() * 60 + sec.toLong()) * 1000 + fracMs
    }
}
