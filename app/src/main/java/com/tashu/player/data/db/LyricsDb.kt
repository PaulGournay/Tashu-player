package com.tashu.player.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Paroles propres à une piste, hors fichiers Drive (lecture seule) :
 * choix manuel, résultat LRCLIB mis en cache, décalage réglé par l'utilisateur.
 */
@Entity(
    tableName = "lyrics",
    foreignKeys = [ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE)],
)
data class LyricsEntity(
    @PrimaryKey val trackId: String,
    /** Paroles choisies ou collées à la main ; prioritaires sur toutes les autres sources. */
    val manualText: String? = null,
    /** L'utilisateur a demandé « pas de paroles » pour ce morceau. */
    val ignored: Boolean = false,
    /** Résultat LRCLIB retenu automatiquement (LRC ou texte simple). */
    val onlineText: String? = null,
    /** LRCLIB indique un morceau instrumental. */
    val onlineInstrumental: Boolean = false,
    val onlineFetchedAt: Long? = null,
    /** Décalage ajouté aux temps (ms) ; positif = paroles plus tôt, comme `[offset:]`. */
    val offsetMs: Long = 0,
)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    suspend fun get(trackId: String): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE trackId = :trackId")
    fun observe(trackId: String): Flow<LyricsEntity?>

    @Upsert
    suspend fun upsert(e: LyricsEntity)
}
