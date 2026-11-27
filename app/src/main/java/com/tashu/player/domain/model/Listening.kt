package com.tashu.player.domain.model

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

enum class StatsPeriod(val label: String) {
    WEEK("7 jours"), MONTH("30 jours"), YEAR("Année"), ALL("Tout");

    /** Début de la période : glissante pour 7 et 30 jours, 1er janvier pour l'année. */
    fun since(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when (this) {
        WEEK -> now - 7 * DAY_MS
        MONTH -> now - 30 * DAY_MS
        YEAR -> LocalDate.now(zone).withDayOfYear(1).atStartOfDay(zone).toInstant().toEpochMilli()
        ALL -> 0
    }
}

const val DAY_MS = 86_400_000L

data class TrackStat(val track: Track, val plays: Int, val listenedMs: Long)
data class AlbumStat(val album: Album, val plays: Int, val listenedMs: Long)
data class ArtistStat(val name: String, val plays: Int, val listenedMs: Long)

data class ListeningTotal(val plays: Int, val listenedMs: Long, val tracks: Int, val albums: Int, val artists: Int)

data class ListeningStats(
    val total: ListeningTotal,
    val topTracks: List<TrackStat>,
    val topAlbums: List<AlbumStat>,
    val topArtists: List<ArtistStat>,
)

/** Temps d'écoute d'une semaine (lundi → dimanche). */
data class WeekListening(val weekStart: LocalDate, val listenedMs: Long)

/** Récapitulatif d'un mois pour Tashu Wrapped. [playsByWeekday] : 0 = lundi ; [playsByDay] : 0 = le 1er du mois. */
data class MonthRecap(
    val month: YearMonth,
    val total: ListeningTotal,
    val topTracks: List<TrackStat>,
    val topAlbums: List<AlbumStat>,
    val topArtists: List<ArtistStat>,
    val playsByWeekday: List<Int>,
    val playsByDay: List<Int>,
) {
    companion object {
        const val MIN_PLAYS = 20
        const val MIN_TRACKS = 5
        /** Assez d'écoutes pour un récapitulatif qui raconte quelque chose. */
        fun enough(plays: Int, tracks: Int) = plays >= MIN_PLAYS && tracks >= MIN_TRACKS
    }
}

/** Un mois de l'historique et ce qu'il contient, pour choisir le mois de Tashu Wrapped. */
data class WrappedMonth(val month: YearMonth, val plays: Int, val tracks: Int) {
    val enough: Boolean get() = MonthRecap.enough(plays, tracks)
}
