package com.tashu.player.domain.repository

import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.AlbumSort
import com.tashu.player.domain.model.AlbumSortOrder
import com.tashu.player.domain.model.Artist
import com.tashu.player.domain.model.AudioSettings
import com.tashu.player.domain.model.DriveFolder
import com.tashu.player.domain.model.NowPlayingStyle
import com.tashu.player.domain.model.SmartPlaylistDefinition
import com.tashu.player.domain.model.ThemeSettings
import com.tashu.player.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun albums(): Flow<List<Album>>
    fun album(id: Long): Flow<Album?>
    fun artists(): Flow<List<Artist>>
    fun tracks(): Flow<List<Track>>
    fun albumTracks(albumId: Long): Flow<List<Track>>
    fun search(query: String): Flow<List<Track>>
    fun smartPlaylist(definition: SmartPlaylistDefinition): Flow<List<Track>>
    fun rootFolders(): Flow<List<DriveFolder>>
    fun subFolders(parentId: String): Flow<List<DriveFolder>>
    fun folderTracks(folderId: String): Flow<List<Track>>
    suspend fun track(id: String): Track?
    suspend fun tracks(ids: List<String>): List<Track>
    suspend fun setRating(trackId: String, rating: Int)
    suspend fun markPlayed(trackId: String)
}

interface SettingsRepository {
    val rootFolderIds: Flow<Set<String>>
    val audioSettings: Flow<AudioSettings>
    val nowPlayingStyle: Flow<NowPlayingStyle>
    /** Recherche automatique des paroles sur LRCLIB (envoie titre, artiste et album). */
    val lyricsOnline: Flow<Boolean>
    /** Taille du texte des paroles (pincer pour zoomer) : vue intégrée et plein écran. */
    val lyricsFontScale: Flow<Float>
    val lyricsFullscreenFontScale: Flow<Float>
    val themeSettings: Flow<ThemeSettings>
    suspend fun setRootFolders(ids: Set<String>)
    suspend fun updateAudio(transform: (AudioSettings) -> AudioSettings)
    suspend fun setNowPlayingStyle(style: NowPlayingStyle)
    suspend fun setLyricsOnline(enabled: Boolean)
    suspend fun setLyricsFontScale(fullscreen: Boolean, scale: Float)
    suspend fun updateTheme(transform: (ThemeSettings) -> ThemeSettings)
    val albumSortOrder: Flow<AlbumSortOrder>
    /** Albums par ligne dans la grille : 1, 2, 4 ou 8. */
    val albumGridColumns: Flow<Int>
    suspend fun setAlbumGridColumns(columns: Int)
    suspend fun setAlbumSortOrder(order: AlbumSortOrder)
}
