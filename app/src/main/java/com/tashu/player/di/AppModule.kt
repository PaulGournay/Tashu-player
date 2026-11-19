package com.tashu.player.di

import android.content.Context
import androidx.room.Room
import com.tashu.player.data.db.MIGRATION_1_2
import com.tashu.player.data.db.MIGRATION_2_3
import com.tashu.player.data.db.MusicDatabase
import com.tashu.player.data.lyrics.LyricsRepositoryImpl
import com.tashu.player.data.repo.LibraryRepositoryImpl
import com.tashu.player.data.repo.SettingsRepositoryImpl
import com.tashu.player.domain.repository.LibraryRepository
import com.tashu.player.domain.repository.LyricsRepository
import com.tashu.player.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext context: Context): MusicDatabase =
        Room.databaseBuilder(context, MusicDatabase::class.java, "tashu.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds abstract fun library(impl: LibraryRepositoryImpl): LibraryRepository
    @Binds abstract fun settings(impl: SettingsRepositoryImpl): SettingsRepository
    @Binds abstract fun lyrics(impl: LyricsRepositoryImpl): LyricsRepository
}
