package com.tashu.player.data.db

import androidx.room.DatabaseView
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tashu.player.drive.scan.DriveMusicScanner

@Entity(tableName = "artists", indices = [Index(value = ["name"], unique = true)])
data class ArtistEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "albums", indices = [Index(value = ["folderId"], unique = true)])
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val folderId: String,
    val artworkPath: String?,
)

/** Titre : tag Album commun à tout le dossier, sinon nom du dossier. Artiste : album-artist de plus de la moitié des pistes, sinon « Artistes divers ». */
const val ALBUM_VIEW_SQL = "SELECT a.id, a.folderId, a.artworkPath, " +
    "CASE WHEN s.albumTags = 1 AND s.album <> '${DriveMusicScanner.UNKNOWN_ALBUM}' THEN s.album ELSE f.name END AS title, " +
    "COALESCE((SELECT t.albumArtist FROM tracks t WHERE t.albumId = a.id GROUP BY t.albumArtist HAVING COUNT(*) * 2 > s.trackCount), 'Artistes divers') AS artistName, " +
    "s.year, s.trackCount, s.dateAdded, s.playCount, s.lastPlayedAt, s.durationMs " +
    "FROM albums a " +
    "JOIN (SELECT albumId, COUNT(*) AS trackCount, COUNT(DISTINCT album) AS albumTags, MIN(album) AS album, MAX(year) AS year, " +
    "MIN(dateAdded) AS dateAdded, SUM(playCount) AS playCount, MAX(lastPlayedAt) AS lastPlayedAt, SUM(durationMs) AS durationMs " +
    "FROM tracks GROUP BY albumId) s ON s.albumId = a.id " +
    "JOIN folders f ON f.id = a.folderId"

/** Champs d'agrégat (date d'ajout, écoutes, durée) utilisés pour trier les albums. */
@DatabaseView(viewName = "album_view", value = ALBUM_VIEW_SQL)
data class AlbumView(
    val id: Long,
    val folderId: String,
    val title: String,
    val artistName: String,
    val year: Int?,
    val artworkPath: String?,
    val trackCount: Int,
    val dateAdded: Long,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val durationMs: Long,
)

@Entity(tableName = "genres", indices = [Index(value = ["name"], unique = true)])
data class GenreEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

@Entity(tableName = "composers", indices = [Index(value = ["name"], unique = true)])
data class ComposerEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String)

/** Arborescence Drive indexée (Mode Fichiers). */
@Entity(tableName = "folders", indices = [Index("parentId")])
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val isRoot: Boolean,
    val coverFileId: String?,
    val scannedAt: Long,
)

@Entity(
    tableName = "tracks",
    indices = [Index("albumId"), Index("artistId"), Index("genreId"), Index("composerId"), Index("folderId"), Index("title")],
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["albumId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(entity = ArtistEntity::class, parentColumns = ["id"], childColumns = ["artistId"], onDelete = ForeignKey.SET_NULL),
    ],
)
data class TrackEntity(
    @PrimaryKey val id: String,            // fileId Drive
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: Long?,
    val artistId: Long?,
    val genre: String?,
    val genreId: Long?,
    val composer: String?,
    val composerId: Long?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val mimeType: String,
    val codec: String,                     // AudioCodec.name
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val sizeBytes: Long,
    val folderId: String,
    val fileName: String,
    val md5: String?,
    val modifiedTime: String,
    val rgTrackGain: Float?,
    val rgTrackPeak: Float?,
    val rgAlbumGain: Float?,
    val rgAlbumPeak: Float?,
    val embeddedLyrics: String?,
    val lrcFileId: String?,
    val artworkPath: String?,
    val rating: Int = 0,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val dateAdded: Long,
)

@Entity(tableName = "smart_playlists")
data class SmartPlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val definitionJson: String,            // SmartPlaylistDefinition sérialisée
)

/** Files d'attente multiples : chacune garde sa position exacte. */
@Entity(tableName = "queues")
data class QueueEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val trackIdsJson: String,
    val currentIndex: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeatMode: Int,
    val isActive: Boolean,
    val updatedAt: Long,
)
