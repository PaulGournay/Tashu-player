package com.tashu.player.playback.cache

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.tashu.player.drive.auth.GoogleAuthManager
import com.tashu.player.playback.datasource.GoogleDriveDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier @Retention(AnnotationRetention.BINARY) annotation class StreamCache
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class OfflineCache
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DriveUpstream
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class StreamingFactory
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class PlaybackFactory

/**
 * Pile de DataSource de lecture (de l'extérieur vers Drive) :
 *
 *   [OfflineCache]  (lecture seule, fichiers téléchargés manuellement/auto, jamais évincés)
 *        └── [StreamCache] (LRU 1 Gio : lecture en cours + prefetch de la piste suivante)
 *               └── GoogleDriveDataSource (Bearer + Range) └── OkHttp
 *
 * Clé de cache = fileId Drive (`MediaItem.customCacheKey`), indépendante du token.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {
    private const val STREAM_CACHE_BYTES = 1024L * 1024 * 1024

    @Provides @Singleton
    fun databaseProvider(@ApplicationContext c: Context) = StandaloneDatabaseProvider(c)

    @Provides @Singleton @StreamCache
    fun streamCache(@ApplicationContext c: Context, db: StandaloneDatabaseProvider): Cache =
        SimpleCache(File(c.cacheDir, "stream"), LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES), db)

    @Provides @Singleton @OfflineCache
    fun offlineCache(@ApplicationContext c: Context, db: StandaloneDatabaseProvider): Cache =
        SimpleCache(File(c.filesDir, "offline"), NoOpCacheEvictor(), db)

    @Provides @Singleton @DriveUpstream
    fun driveUpstream(okHttp: OkHttpClient, auth: GoogleAuthManager): DataSource.Factory =
        GoogleDriveDataSource.Factory(OkHttpDataSource.Factory(okHttp).setUserAgent("Tashu/1.0"), auth)

    @Provides @Singleton @StreamingFactory
    fun streaming(@StreamCache cache: Cache, @DriveUpstream upstream: DataSource.Factory): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    @Provides @Singleton @PlaybackFactory
    fun playback(@OfflineCache offline: Cache, @StreamingFactory streaming: CacheDataSource.Factory): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(offline)
            .setUpstreamDataSourceFactory(streaming)
            .setCacheWriteDataSinkFactory(null) // le cache hors-ligne n'est écrit que par OfflineManager
}
