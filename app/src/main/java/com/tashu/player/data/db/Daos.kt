package com.tashu.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

data class ArtistWithCount(val id: Long, val name: String, val albumCount: Int, val trackCount: Int)
data class TrackFingerprint(val id: String, val md5: String?, val modifiedTime: String)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE albumId = :albumId ORDER BY discNumber, trackNumber, title")
    fun observeAlbum(albumId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE folderId = :folderId ORDER BY fileName COLLATE NOCASE")
    fun observeFolder(folderId: String): Flow<List<TrackEntity>>

    @Query(
        "SELECT * FROM tracks WHERE title LIKE '%' || :q || '%' OR artist LIKE '%' || :q || '%' " +
            "OR album LIKE '%' || :q || '%' ORDER BY title COLLATE NOCASE LIMIT 200"
    )
    fun search(q: String): Flow<List<TrackEntity>>

    /** Smart playlists : requête SQL construite dynamiquement (colonnes whitelistées). */
    @RawQuery(observedEntities = [TrackEntity::class])
    fun observeRaw(query: SupportSQLiteQuery): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT id, md5, modifiedTime FROM tracks")
    suspend fun fingerprints(): List<TrackFingerprint>

    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("UPDATE tracks SET rating = :rating WHERE id = :id")
    suspend fun setRating(id: String, rating: Int)

    @Query("UPDATE tracks SET playCount = playCount + 1, lastPlayedAt = :now WHERE id = :id")
    suspend fun markPlayed(id: String, now: Long)

    /** Propage la pochette d'album aux pistes qui n'en ont pas. */
    @Query("UPDATE tracks SET artworkPath = (SELECT artworkPath FROM albums WHERE albums.id = tracks.albumId) WHERE artworkPath IS NULL")
    suspend fun backfillArtwork()
}

@Dao
interface CatalogDao {
    @Query("SELECT id FROM artists WHERE name = :name")
    suspend fun artistId(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtist(a: ArtistEntity): Long

    @Query("SELECT id FROM albums WHERE folderId = :folderId")
    suspend fun albumIdForFolder(folderId: String): Long?

    @Query("SELECT artworkPath FROM albums WHERE id = :id")
    suspend fun albumArtwork(id: Long): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbum(a: AlbumEntity): Long

    @Query("UPDATE albums SET artworkPath = :path WHERE id = :id AND artworkPath IS NULL")
    suspend fun setAlbumArtworkIfMissing(id: Long, path: String)

    @Query("UPDATE albums SET artworkPath = :path WHERE id = :id")
    suspend fun setAlbumArtwork(id: Long, path: String)

    @Query("SELECT id FROM genres WHERE name = :name")
    suspend fun genreId(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGenre(g: GenreEntity): Long

    @Query("SELECT id FROM composers WHERE name = :name")
    suspend fun composerId(name: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertComposer(c: ComposerEntity): Long

    @Query("SELECT * FROM album_view ORDER BY artistName COLLATE NOCASE, year")
    fun observeAlbums(): Flow<List<AlbumView>>

    @Query("SELECT * FROM album_view WHERE id = :id")
    fun observeAlbum(id: Long): Flow<AlbumView?>

    @Query(
        "SELECT ar.id, ar.name, COUNT(DISTINCT t.albumId) AS albumCount, COUNT(t.id) AS trackCount " +
            "FROM artists ar JOIN tracks t ON t.artistId = ar.id GROUP BY ar.id ORDER BY ar.name COLLATE NOCASE"
    )
    fun observeArtists(): Flow<List<ArtistWithCount>>

    /** Nettoyage des entrées orphelines après un scan. */
    @Transaction
    suspend fun pruneOrphans() {
        pruneAlbums(); pruneArtists(); pruneGenres(); pruneComposers()
    }
    @Query("DELETE FROM albums WHERE id NOT IN (SELECT DISTINCT albumId FROM tracks WHERE albumId IS NOT NULL)")
    suspend fun pruneAlbums()
    @Query("DELETE FROM artists WHERE id NOT IN (SELECT DISTINCT artistId FROM tracks WHERE artistId IS NOT NULL)")
    suspend fun pruneArtists()
    @Query("DELETE FROM genres WHERE id NOT IN (SELECT DISTINCT genreId FROM tracks WHERE genreId IS NOT NULL)")
    suspend fun pruneGenres()
    @Query("DELETE FROM composers WHERE id NOT IN (SELECT DISTINCT composerId FROM tracks WHERE composerId IS NOT NULL)")
    suspend fun pruneComposers()
}

@Dao
interface FolderDao {
    @Upsert
    suspend fun upsert(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders WHERE parentId = :parentId ORDER BY name COLLATE NOCASE")
    fun observeChildren(parentId: String): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE isRoot = 1 ORDER BY name COLLATE NOCASE")
    fun observeRoots(): Flow<List<FolderEntity>>

    @Query("UPDATE folders SET parentId = :parentId, isRoot = 0 WHERE id = :id")
    suspend fun attachToParent(id: String, parentId: String)

    @Query("DELETE FROM folders WHERE scannedAt < :scanStart")
    suspend fun deleteStale(scanStart: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM smart_playlists ORDER BY name")
    fun observeSmart(): Flow<List<SmartPlaylistEntity>>

    @Upsert
    suspend fun upsertSmart(p: SmartPlaylistEntity): Long

    @Query("DELETE FROM smart_playlists WHERE id = :id")
    suspend fun deleteSmart(id: Long)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queues ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<QueueEntity>>

    @Query("SELECT * FROM queues WHERE isActive = 1 LIMIT 1")
    suspend fun active(): QueueEntity?

    @Query("SELECT * FROM queues WHERE id = :id")
    suspend fun byId(id: Long): QueueEntity?

    @Upsert
    suspend fun upsert(q: QueueEntity): Long

    @Query("UPDATE queues SET isActive = (id = :id)")
    suspend fun setActive(id: Long)

    @Query("DELETE FROM queues WHERE id = :id")
    suspend fun delete(id: Long)
}
