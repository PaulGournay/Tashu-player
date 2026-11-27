package com.tashu.player.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Une écoute : un passage d'un morceau, du moment où il devient le morceau en cours jusqu'au suivant.
 * [listenedMs] = temps réellement joué (pauses, chargements et sauts exclus). Pas de clé étrangère :
 * l'historique survit aux rescans et aux morceaux retirés de Drive.
 */
@Entity(tableName = "play_history", indices = [Index("startedAt"), Index("albumId")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: String,
    val albumId: Long?,
    val artist: String,
    val startedAt: Long,
    val listenedMs: Long,
    /** Durée du morceau au moment de l'écoute (règle « 50 % »). */
    val durationMs: Long,
)

/** Une écoute compte après 30 s jouées, et la moitié du morceau ou 4 min (règle de Last.fm). */
private const val COUNTS = "(h.listenedMs >= 30000 AND (h.listenedMs * 2 >= h.durationMs OR h.listenedMs >= 240000))"
private const val RANGE = "h.startedAt >= :since AND h.startedAt < :until"

data class TrackPlays(val trackId: String, val plays: Int, val listenedMs: Long)
data class AlbumPlays(val albumId: Long, val plays: Int, val listenedMs: Long)
data class ArtistPlays(val artist: String, val plays: Int, val listenedMs: Long)
data class ListeningTotals(val plays: Int, val listenedMs: Long, val tracks: Int, val albums: Int, val artists: Int)
data class ListenSlice(val startedAt: Long, val listenedMs: Long, val counts: Boolean)
data class AlbumLastPlayed(val albumId: Long, val plays: Int, val lastPlayedAt: Long)
/** [month] au format « 2026-11 », dans le fuseau du téléphone. */
data class MonthPlays(val month: String, val plays: Int, val tracks: Int)

@Dao
interface PlayHistoryDao {
    /** Écritures bloquantes : faites en série sur le fil dédié de l'enregistreur de lecture. */
    @Insert
    fun insert(e: PlayEventEntity): Long

    @Query("UPDATE play_history SET listenedMs = :listenedMs WHERE id = :id")
    fun setListened(id: Long, listenedMs: Long)

    /** Morceaux encore dans la bibliothèque, classés par écoutes puis par temps. */
    @Query(
        "SELECT h.trackId, SUM($COUNTS) AS plays, SUM(h.listenedMs) AS listenedMs FROM play_history h " +
            "JOIN tracks t ON t.id = h.trackId WHERE $RANGE " +
            "GROUP BY h.trackId HAVING plays > 0 ORDER BY plays DESC, listenedMs DESC LIMIT :limit"
    )
    fun topTracks(since: Long, until: Long, limit: Int): Flow<List<TrackPlays>>

    @Query(
        "SELECT h.albumId, SUM($COUNTS) AS plays, SUM(h.listenedMs) AS listenedMs FROM play_history h " +
            "JOIN albums a ON a.id = h.albumId WHERE $RANGE " +
            "GROUP BY h.albumId HAVING plays > 0 ORDER BY plays DESC, listenedMs DESC LIMIT :limit"
    )
    fun topAlbums(since: Long, until: Long, limit: Int): Flow<List<AlbumPlays>>

    @Query(
        "SELECT h.artist, SUM($COUNTS) AS plays, SUM(h.listenedMs) AS listenedMs FROM play_history h " +
            "WHERE $RANGE GROUP BY h.artist HAVING plays > 0 ORDER BY plays DESC, listenedMs DESC LIMIT :limit"
    )
    fun topArtists(since: Long, until: Long, limit: Int): Flow<List<ArtistPlays>>

    /** Temps total (écoutes partielles comprises) ; morceaux, albums et artistes comptés sur les écoutes qui comptent. */
    @Query(
        "SELECT COALESCE(SUM($COUNTS), 0) AS plays, COALESCE(SUM(h.listenedMs), 0) AS listenedMs, " +
            "COUNT(DISTINCT CASE WHEN $COUNTS THEN h.trackId END) AS tracks, " +
            "COUNT(DISTINCT CASE WHEN $COUNTS THEN h.albumId END) AS albums, " +
            "COUNT(DISTINCT CASE WHEN $COUNTS THEN h.artist END) AS artists " +
            "FROM play_history h WHERE $RANGE"
    )
    fun totals(since: Long, until: Long): Flow<ListeningTotals>

    /** Écoutes brutes d'une fenêtre, regroupées par semaine ou par jour côté Kotlin (fuseau du téléphone). */
    @Query("SELECT h.startedAt, h.listenedMs, $COUNTS AS counts FROM play_history h WHERE $RANGE")
    fun slices(since: Long, until: Long): Flow<List<ListenSlice>>

    /** Écoutes et morceaux distincts par mois : choix des mois de Tashu Wrapped. */
    @Query(
        "SELECT strftime('%Y-%m', h.startedAt / 1000, 'unixepoch', 'localtime') AS month, SUM($COUNTS) AS plays, " +
            "COUNT(DISTINCT CASE WHEN $COUNTS THEN h.trackId END) AS tracks FROM play_history h GROUP BY month ORDER BY month"
    )
    fun months(): Flow<List<MonthPlays>>

    /** Albums de la bibliothèque déjà écoutés, avec leur dernière écoute qui compte (un simple saut n'y compte pas). */
    @Query(
        "SELECT h.albumId, SUM($COUNTS) AS plays, MAX(CASE WHEN $COUNTS THEN h.startedAt END) AS lastPlayedAt FROM play_history h " +
            "JOIN albums a ON a.id = h.albumId GROUP BY h.albumId HAVING plays > 0"
    )
    fun albumsLastPlayed(): Flow<List<AlbumLastPlayed>>

    @Query("SELECT MIN(startedAt) FROM play_history")
    fun firstListenAt(): Flow<Long?>
}
