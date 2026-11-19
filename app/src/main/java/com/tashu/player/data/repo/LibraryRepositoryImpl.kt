package com.tashu.player.data.repo

import com.tashu.player.data.db.AlbumView
import com.tashu.player.data.db.MusicDatabase
import com.tashu.player.data.db.SmartPlaylistQueryBuilder
import com.tashu.player.data.db.TrackEntity
import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.Artist
import com.tashu.player.domain.model.AudioCodec
import com.tashu.player.domain.model.DriveFolder
import com.tashu.player.domain.model.ReplayGainInfo
import com.tashu.player.domain.model.SmartPlaylistDefinition
import com.tashu.player.domain.model.Track
import com.tashu.player.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val db: MusicDatabase,
) : LibraryRepository {
    private val tracks = db.trackDao()
    private val catalog = db.catalogDao()

    override fun albums(): Flow<List<Album>> = catalog.observeAlbums().map { l -> l.map { it.toDomain() } }
    override fun album(id: Long): Flow<Album?> = catalog.observeAlbum(id).map { it?.toDomain() }
    override fun artists(): Flow<List<Artist>> = catalog.observeArtists().map { l ->
        l.map { Artist(it.id, it.name, it.albumCount, it.trackCount) }
    }
    override fun tracks(): Flow<List<Track>> = tracks.observeAll().map { it.toDomain() }
    override fun albumTracks(albumId: Long) = tracks.observeAlbum(albumId).map { it.toDomain() }
    override fun search(query: String) = tracks.search(query).map { it.toDomain() }
    override fun smartPlaylist(definition: SmartPlaylistDefinition) =
        tracks.observeRaw(SmartPlaylistQueryBuilder.build(definition)).map { it.toDomain() }
    override fun rootFolders() = db.folderDao().observeRoots().map { l -> l.map { DriveFolder(it.id, it.name, null) } }
    override fun subFolders(parentId: String) = db.folderDao().observeChildren(parentId).map { l ->
        l.map { DriveFolder(it.id, it.name, it.parentId) }
    }
    override fun folderTracks(folderId: String) = tracks.observeFolder(folderId).map { it.toDomain() }
    override suspend fun track(id: String) = tracks.byId(id)?.toDomain()
    override suspend fun tracks(ids: List<String>): List<Track> {
        val byId = ids.chunked(900).flatMap { tracks.byIds(it) }.associateBy { it.id }
        return ids.mapNotNull { byId[it]?.toDomain() } // conserve l'ordre de la file
    }
    override suspend fun setRating(trackId: String, rating: Int) = tracks.setRating(trackId, rating.coerceIn(0, 5))
    override suspend fun markPlayed(trackId: String) = tracks.markPlayed(trackId, System.currentTimeMillis())
}

private fun List<TrackEntity>.toDomain() = map { it.toDomain() }

private fun AlbumView.toDomain() = Album(id, title, artistName, year, artworkPath, trackCount, dateAdded, playCount, lastPlayedAt, durationMs)

fun TrackEntity.toDomain() = Track(
    id = id, title = title, artist = artist, albumArtist = albumArtist, album = album, albumId = albumId ?: -1,
    genre = genre, composer = composer, trackNumber = trackNumber, discNumber = discNumber, year = year,
    durationMs = durationMs, mimeType = mimeType, codec = runCatching { AudioCodec.valueOf(codec) }.getOrDefault(AudioCodec.UNKNOWN),
    bitrateKbps = bitrateKbps, sampleRateHz = sampleRateHz, bitDepth = bitDepth, channels = channels,
    sizeBytes = sizeBytes, folderId = folderId, artworkPath = artworkPath,
    replayGain = ReplayGainInfo(rgTrackGain, rgTrackPeak, rgAlbumGain, rgAlbumPeak),
    hasLyrics = lrcFileId != null || embeddedLyrics != null,
    rating = rating, playCount = playCount, lastPlayedAt = lastPlayedAt,
)
