package com.tashu.player.playback

import androidx.media3.common.Player
import com.tashu.player.data.db.QueueDao
import com.tashu.player.data.db.QueueEntity
import com.tashu.player.domain.repository.LibraryRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Files d'attente multiples : chaque file (ex. "Sport", "Jazz du soir") conserve sa liste,
 * son index, sa position exacte, shuffle et repeat. Basculer = sauvegarder la file active,
 * puis recharger la cible dans le player à sa position mémorisée.
 * Toutes les méthodes s'exécutent sur le main thread (accès Player).
 */
class QueueManager(
    private val player: Player,
    private val dao: QueueDao,
    private val library: LibraryRepository,
    private val json: Json,
) {
    private val idsSerializer = ListSerializer(String.serializer())
    var activeQueueId: Long? = null
        private set

    suspend fun restoreActive(playWhenReady: Boolean = false): Boolean {
        val q = dao.active() ?: return false
        load(q, playWhenReady)
        return true
    }

    suspend fun saveCurrent() {
        if (player.mediaItemCount == 0) return
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val entity = QueueEntity(
            id = activeQueueId ?: 0,
            name = dao.byId(activeQueueId ?: -1)?.name ?: "File ${System.currentTimeMillis() % 10000}",
            trackIdsJson = json.encodeToString(idsSerializer, ids),
            currentIndex = player.currentMediaItemIndex,
            positionMs = player.currentPosition,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            isActive = true,
            updatedAt = System.currentTimeMillis(),
        )
        val id = dao.upsert(entity).takeIf { it > 0 } ?: entity.id
        activeQueueId = id
        dao.setActive(id)
    }

    /** Nouvelle file à partir d'une sélection ; l'ancienne est conservée intacte. */
    suspend fun createAndPlay(name: String, trackIds: List<String>, startIndex: Int) {
        saveCurrent()
        val id = dao.upsert(
            QueueEntity(
                name = name, trackIdsJson = json.encodeToString(idsSerializer, trackIds), currentIndex = startIndex,
                positionMs = 0, shuffle = false, repeatMode = Player.REPEAT_MODE_OFF, isActive = true,
                updatedAt = System.currentTimeMillis(),
            )
        )
        dao.setActive(id)
        load(dao.byId(id)!!, playWhenReady = true)
    }

    suspend fun switchTo(queueId: Long) {
        if (queueId == activeQueueId) return
        val wasPlaying = player.playWhenReady
        saveCurrent()
        val target = dao.byId(queueId) ?: return
        dao.setActive(queueId)
        load(target, wasPlaying)
    }

    private suspend fun load(q: QueueEntity, playWhenReady: Boolean) {
        val ids = json.decodeFromString(idsSerializer, q.trackIdsJson)
        val items = library.tracks(ids).map(MediaItemMapper::toMediaItem)
        activeQueueId = q.id
        if (items.isEmpty()) return
        player.setMediaItems(items, q.currentIndex.coerceIn(0, items.lastIndex), q.positionMs)
        player.shuffleModeEnabled = q.shuffle
        player.repeatMode = q.repeatMode
        player.prepare()
        player.playWhenReady = playWhenReady
    }
}
