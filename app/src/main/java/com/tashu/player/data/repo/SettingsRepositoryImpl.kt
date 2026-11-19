package com.tashu.player.data.repo

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tashu.player.domain.model.AlbumSort
import com.tashu.player.domain.model.AlbumSortOrder
import com.tashu.player.domain.model.AudioSettings
import com.tashu.player.domain.model.NowPlayingStyle
import com.tashu.player.domain.model.ThemeSettings
import com.tashu.player.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore("settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) : SettingsRepository {
    private val ROOTS = stringSetPreferencesKey("root_folders")
    private val AUDIO = stringPreferencesKey("audio_settings")
    private val NP_STYLE = stringPreferencesKey("now_playing_style")
    private val LYRICS_ONLINE = booleanPreferencesKey("lyrics_online")
    private val LYRICS_SCALE = floatPreferencesKey("lyrics_font_scale")
    private val LYRICS_FULL_SCALE = floatPreferencesKey("lyrics_fullscreen_font_scale")
    private val THEME = stringPreferencesKey("theme_settings")
    private val ALBUM_SORT = stringPreferencesKey("album_sort")
    private val ALBUM_SORT_DESC = booleanPreferencesKey("album_sort_desc")
    private val ALBUM_COLUMNS = intPreferencesKey("album_grid_columns")

    override val albumGridColumns: Flow<Int> = context.dataStore.data.map { it[ALBUM_COLUMNS] ?: 2 }.distinctUntilChanged()
    override suspend fun setAlbumGridColumns(columns: Int) { context.dataStore.edit { it[ALBUM_COLUMNS] = columns } }

    override val albumSortOrder: Flow<AlbumSortOrder> = context.dataStore.data.map { p ->
        AlbumSortOrder(
            sort = p[ALBUM_SORT]?.let { runCatching { AlbumSort.valueOf(it) }.getOrNull() } ?: AlbumSort.ARTIST,
            descending = p[ALBUM_SORT_DESC] ?: false,
        )
    }.distinctUntilChanged()

    override suspend fun setAlbumSortOrder(order: AlbumSortOrder) {
        context.dataStore.edit { it[ALBUM_SORT] = order.sort.name; it[ALBUM_SORT_DESC] = order.descending }
    }

    override val rootFolderIds: Flow<Set<String>> = context.dataStore.data.map { it[ROOTS] ?: emptySet() }.distinctUntilChanged()

    override val audioSettings: Flow<AudioSettings> = context.dataStore.data.map { p ->
        p[AUDIO]?.let { runCatching { json.decodeFromString(AudioSettings.serializer(), it) }.getOrNull() } ?: AudioSettings()
    }.distinctUntilChanged()

    override val nowPlayingStyle: Flow<NowPlayingStyle> = context.dataStore.data.map { p ->
        p[NP_STYLE]?.let { runCatching { NowPlayingStyle.valueOf(it) }.getOrNull() } ?: NowPlayingStyle.CLASSIC
    }.distinctUntilChanged()

    override val lyricsOnline: Flow<Boolean> = context.dataStore.data.map { it[LYRICS_ONLINE] ?: true }.distinctUntilChanged()
    override val lyricsFontScale: Flow<Float> = context.dataStore.data.map { it[LYRICS_SCALE] ?: 1f }.distinctUntilChanged()
    override val lyricsFullscreenFontScale: Flow<Float> = context.dataStore.data.map { it[LYRICS_FULL_SCALE] ?: 1f }.distinctUntilChanged()

    override val themeSettings: Flow<ThemeSettings> = context.dataStore.data.map { p ->
        p[THEME]?.let { runCatching { json.decodeFromString(ThemeSettings.serializer(), it) }.getOrNull() } ?: ThemeSettings()
    }.distinctUntilChanged()

    override suspend fun setRootFolders(ids: Set<String>) { context.dataStore.edit { it[ROOTS] = ids } }

    override suspend fun updateAudio(transform: (AudioSettings) -> AudioSettings) {
        context.dataStore.edit { p ->
            val current = p[AUDIO]?.let { runCatching { json.decodeFromString(AudioSettings.serializer(), it) }.getOrNull() } ?: AudioSettings()
            p[AUDIO] = json.encodeToString(AudioSettings.serializer(), transform(current))
        }
    }

    override suspend fun setNowPlayingStyle(style: NowPlayingStyle) { context.dataStore.edit { it[NP_STYLE] = style.name } }

    override suspend fun setLyricsOnline(enabled: Boolean) { context.dataStore.edit { it[LYRICS_ONLINE] = enabled } }

    override suspend fun setLyricsFontScale(fullscreen: Boolean, scale: Float) {
        context.dataStore.edit { it[if (fullscreen) LYRICS_FULL_SCALE else LYRICS_SCALE] = scale }
    }

    override suspend fun updateTheme(transform: (ThemeSettings) -> ThemeSettings) {
        context.dataStore.edit { p ->
            val current = p[THEME]?.let { runCatching { json.decodeFromString(ThemeSettings.serializer(), it) }.getOrNull() } ?: ThemeSettings()
            p[THEME] = json.encodeToString(ThemeSettings.serializer(), transform(current))
        }
    }
}
