package com.tashu.player.playback.cache

import android.content.Context
import android.os.StatFs
import android.util.Log
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import com.tashu.player.domain.model.Track
import com.tashu.player.playback.datasource.GoogleDriveDataSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Téléchargement hors-ligne et prefetch.
 * - [download] : copie intégrale dans le cache hors-ligne (non évincé).
 * - [prefetch] : met en cache de streaming (LRU) la piste suivante pour un enchaînement sans réseau.
 * - Garde-fou disque : refuse si l'espace libre passerait sous [MIN_FREE_BYTES] ou si le quota est atteint.
 */
@Singleton
class OfflineManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @OfflineCache private val offlineCache: Cache,
    @StreamingFactory private val streaming: CacheDataSource.Factory,
    @DriveUpstream private val drive: DataSource.Factory,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSlots = Semaphore(2)
    private val jobs = ConcurrentHashMap<String, Job>()
    private var prefetchJob: Job? = null

    /** Quota du stockage hors-ligne (modifiable depuis les réglages). */
    @Volatile var maxOfflineBytes: Long = 20L * 1024 * 1024 * 1024

    private val _downloaded = MutableStateFlow(offlineCache.keys.toSet())
    val downloaded: StateFlow<Set<String>> = _downloaded
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress: StateFlow<Map<String, Float>> = _progress

    val usedBytes: Long get() = offlineCache.cacheSpace

    fun download(track: Track) {
        if (track.id in _downloaded.value || jobs.containsKey(track.id)) return
        if (!hasSpaceFor(track.sizeBytes)) { Log.w(TAG, "Espace insuffisant pour ${track.title}"); return }
        jobs[track.id] = scope.launch {
            downloadSlots.withPermit {
                val ds = CacheDataSource.Factory().setCache(offlineCache).setUpstreamDataSourceFactory(drive).createDataSource()
                val writer = CacheWriter(ds, spec(track.id), null) { requestLength, bytesCached, _ ->
                    if (requestLength > 0) _progress.update { it + (track.id to bytesCached.toFloat() / requestLength) }
                }
                runCatching { writer.cache() }
                    .onSuccess { _downloaded.update { it + track.id } }
                    .onFailure { Log.w(TAG, "Téléchargement échoué ${track.title}", it); offlineCache.removeResource(track.id) }
                _progress.update { it - track.id }
                jobs.remove(track.id)
            }
        }
    }

    /** Téléchargement automatique (ex : smart playlist "favoris") tant que le quota le permet. */
    fun autoDownload(tracks: List<Track>) = tracks.forEach { download(it) }

    fun remove(trackId: String) {
        jobs.remove(trackId)?.cancel()
        scope.launch { offlineCache.removeResource(trackId); _downloaded.update { it - trackId } }
    }

    fun prefetch(trackId: String) {
        if (trackId in _downloaded.value) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            runCatching { CacheWriter(streaming.createDataSource(), spec(trackId), null, null).cache() }
                .onFailure { Log.d(TAG, "Prefetch interrompu: ${it.message}") }
        }
    }

    private fun hasSpaceFor(bytes: Long): Boolean {
        val free = StatFs(context.filesDir.path).availableBytes
        return free - bytes > MIN_FREE_BYTES && offlineCache.cacheSpace + bytes <= maxOfflineBytes
    }

    private fun spec(fileId: String) =
        DataSpec.Builder().setUri(GoogleDriveDataSource.uriFor(fileId)).setKey(fileId).build()

    companion object {
        private const val TAG = "OfflineManager"
        private const val MIN_FREE_BYTES = 1024L * 1024 * 1024
    }
}
