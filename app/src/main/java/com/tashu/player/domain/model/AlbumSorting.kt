package com.tashu.player.domain.model

import java.text.Collator
import java.util.Locale

/**
 * Tri des albums en mémoire.
 * Texte : ordre alphabétique français, sans tenir compte des accents ni de la casse.
 * Valeurs absentes (année inconnue, jamais écouté) : toujours en fin de liste, quel que soit le sens.
 * Égalités départagées par artiste, puis titre.
 */
fun List<Album>.sortedFor(order: AlbumSortOrder): List<Album> {
    val collator = Collator.getInstance(Locale.FRENCH).apply { strength = Collator.PRIMARY }
    val byText = Comparator<String> { a, b -> collator.compare(a, b) }
    val tieBreak = compareBy(byText) { a: Album -> a.artist }.thenBy(byText) { it.title }

    fun <T : Comparable<T>> keyed(key: (Album) -> T?): Comparator<Album> {
        val values = Comparator<Album> { a, b ->
            val x = key(a); val y = key(b)
            when {
                x == null && y == null -> 0
                x == null -> 1
                y == null -> -1
                order.descending -> y.compareTo(x)
                else -> x.compareTo(y)
            }
        }
        return values.then(tieBreak)
    }

    fun text(key: (Album) -> String): Comparator<Album> {
        val primary = compareBy(byText, key)
        return (if (order.descending) primary.reversed() else primary).then(tieBreak)
    }

    val comparator = when (order.sort) {
        AlbumSort.TITLE -> text { it.title }
        AlbumSort.ARTIST -> {
            // Par artiste, les albums d'un même artiste restent dans l'ordre chronologique.
            val artist = compareBy(byText) { a: Album -> a.artist }
            (if (order.descending) artist.reversed() else artist)
                .thenBy(nullsLast()) { it.year }
                .thenBy(byText) { it.title }
        }
        AlbumSort.YEAR -> keyed { it.year }
        AlbumSort.DATE_ADDED -> keyed { it.dateAdded.takeIf { d -> d > 0 } }
        AlbumSort.MOST_PLAYED -> keyed { it.playCount }
        AlbumSort.LAST_PLAYED -> keyed { it.lastPlayedAt }
        AlbumSort.TRACK_COUNT -> keyed { it.trackCount }
        AlbumSort.DURATION -> keyed { it.durationMs }
    }
    return sortedWith(comparator)
}
