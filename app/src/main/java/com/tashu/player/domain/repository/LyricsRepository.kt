package com.tashu.player.domain.repository

import com.tashu.player.domain.lyrics.LyricsCandidate
import kotlinx.coroutines.flow.Flow

enum class LyricsSource(val label: String) {
    MANUAL("Choisies à la main"),
    EMBEDDED("Tag du fichier"),
    DRIVE_LRC("Fichier .lrc sur Drive"),
    ONLINE("LRCLIB"),
}

sealed interface LyricsState {
    data object Loading : LyricsState
    data class Found(val raw: String, val source: LyricsSource, val offsetMs: Long) : LyricsState
    data object Instrumental : LyricsState
    /** [ignored] : l'utilisateur a choisi « pas de paroles » ; [error] : la recherche en ligne a échoué. */
    data class None(val ignored: Boolean = false, val error: String? = null) : LyricsState
}

/** Paroles proposées dans la recherche manuelle : sources locales de la piste, puis LRCLIB. */
data class LyricsOption(val source: LyricsSource, val candidate: LyricsCandidate)

interface LyricsRepository {
    /**
     * Paroles de la piste, par ordre de priorité (comme Namida) : choix manuel, tag synchronisé,
     * `.lrc` frère sur Drive, tag en texte simple, puis LRCLIB (mis en cache).
     */
    fun lyrics(trackId: String): Flow<LyricsState>
    suspend fun options(trackId: String, query: String?): List<LyricsOption>
    suspend fun choose(trackId: String, text: String)
    suspend fun ignore(trackId: String)
    /** Oublie le choix manuel et le cache LRCLIB, puis relance la recherche automatique. */
    suspend fun resetToAuto(trackId: String)
    suspend fun setOffset(trackId: String, offsetMs: Long)
    /** Requête de recherche manuelle proposée par défaut (« titre artiste »). */
    suspend fun defaultQuery(trackId: String): String
}
