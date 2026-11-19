package com.tashu.player.domain.lyrics

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Résultat d'une recherche de paroles en ligne. */
data class LyricsCandidate(
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long?,
    val instrumental: Boolean,
    val syncedLyrics: String?,
    val plainLyrics: String?,
) {
    val synced get() = !syncedLyrics.isNullOrBlank()
    val text get() = syncedLyrics?.takeIf { it.isNotBlank() } ?: plainLyrics?.takeIf { it.isNotBlank() }
}

/**
 * Choix du bon résultat, d'après Namida : titre et artiste découpés en mots, mots parasites retirés,
 * comparaison tolérante aux fautes ; score = 0,7 × titre + 0,3 × artiste, seuil 0,7 ;
 * à score égal, la durée la plus proche gagne (ignorée pour les versions accélérées ou ralenties).
 */
object LyricsMatcher {
    const val MIN_SCORE = 0.7
    private const val MAX_DURATION_GAP_MS = 5_000L
    private val NOISE = setOf(
        "official", "video", "audio", "lyrics", "lyric", "hd", "hq", "4k", "mv", "remastered", "remaster",
        "version", "explicit", "clean", "radio", "edit", "mono", "stereo", "the", "a", "feat", "ft", "featuring",
    )
    private val SPEED_VARIANT = Regex("""sped\s*up|speed\s*up|slowed|nightcore|daycore|reverb""", RegexOption.IGNORE_CASE)
    private val INSTRUMENTAL_TITLE = Regex("""instrumental|off\s*vocal|karaoke""", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex("""\s*(?:,|;|/|&|\+|\bfeat\.?|\bft\.?|\bfeaturing\b|\bx\b|\bet\b|\band\b)\s*""", RegexOption.IGNORE_CASE)

    data class Ranked(val candidate: LyricsCandidate, val score: Double, val durationGapMs: Long?)

    /** Candidats pertinents, du meilleur au moins bon (score ≥ 0,7). */
    fun rank(title: String, artist: String, durationMs: Long, candidates: List<LyricsCandidate>): List<Ranked> {
        val ignoreDuration = SPEED_VARIANT.containsMatchIn(title) || SPEED_VARIANT.containsMatchIn(artist)
        return candidates.mapIndexed { i, c ->
            val gap = if (ignoreDuration || durationMs <= 0 || c.durationMs == null) null else abs(c.durationMs - durationMs)
            i to Ranked(c, score(title, artist, c.title, c.artist), gap)
        }
            .filter { it.second.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Pair<Int, Ranked>> { it.second.score }.thenBy { it.second.durationGapMs ?: Long.MAX_VALUE }.thenBy { it.first })
            .map { it.second }
    }

    /**
     * Le résultat à afficher automatiquement : parmi les candidats de bonne durée (±5 s),
     * les paroles synchronisées d'abord ; sinon le meilleur score.
     */
    fun best(title: String, artist: String, durationMs: Long, candidates: List<LyricsCandidate>): Ranked? {
        val ranked = rank(title, artist, durationMs, candidates).filter { it.candidate.text != null || it.candidate.instrumental }
        val rightLength = ranked.filter { it.durationGapMs == null || it.durationGapMs <= MAX_DURATION_GAP_MS }
        val pool = rightLength.ifEmpty { ranked }
        return pool.firstOrNull { it.candidate.synced } ?: pool.firstOrNull()
    }

    /** Vrai si LRCLIB dit « instrumental » pour un résultat très sûr, et que le titre n'annonce pas déjà une version instrumentale. */
    fun isInstrumental(title: String, best: Ranked?): Boolean =
        best != null && best.candidate.instrumental && best.score >= 0.9 && !INSTRUMENTAL_TITLE.containsMatchIn(title)

    /** Artiste nettoyé pour la requête : coupé au premier « ( » ou « [ ». */
    fun cleanArtist(artist: String): String = artist.substringBefore('(').substringBefore('[').trim()

    fun firstArtist(artist: String): String = cleanArtist(artist).split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist

    fun score(qTitle: String, qArtist: String, cTitle: String, cArtist: String): Double {
        val qt = tokens(qTitle)
        val ct = tokens(cTitle)
        val titleScore = if (qt.joinToString("") == ct.joinToString("") && qt.isNotEmpty()) 1.0 else coverage(qt, ct)
        val qa = tokens(qArtist)
        val ca = tokens(cArtist)
        val artistScore = if (qa.isEmpty()) 1.0 else max(coverage(qa, ca), coverage(ca, qa))
        return 0.7 * titleScore + 0.3 * artistScore
    }

    fun tokens(s: String): List<String> {
        val ascii = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        val words = ascii.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() }
        return words.filterNot { it in NOISE }.ifEmpty { words }
    }

    private fun coverage(a: List<String>, b: List<String>): Double {
        if (a.isEmpty()) return if (b.isEmpty()) 1.0 else 0.0
        return a.count { x -> b.any { y -> similar(x, y) } }.toDouble() / a.size
    }

    private fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        val n = min(a.length, b.length)
        val allowed = when {
            n < 4 -> 0
            n <= 6 -> 1
            else -> 2
        }
        return allowed > 0 && abs(a.length - b.length) <= allowed && levenshtein(a, b) <= allowed
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
