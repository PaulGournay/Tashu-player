package com.tashu.player.ui.nowplaying

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tashu.player.domain.lyrics.LrcParser
import com.tashu.player.domain.lyrics.LyricLine
import com.tashu.player.domain.lyrics.ParsedLyrics
import com.tashu.player.domain.model.NowPlayingStyle
import com.tashu.player.domain.repository.LibraryRepository
import com.tashu.player.domain.repository.LyricsOption
import com.tashu.player.domain.repository.LyricsRepository
import com.tashu.player.domain.repository.LyricsState
import com.tashu.player.domain.repository.SettingsRepository
import com.tashu.player.playback.PlayerConnection
import com.tashu.player.playback.dsp.SpectrumAnalyzer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Paroles prêtes à afficher : [parsed] si synchronisées, sinon [plain]. */
data class LyricsUi(val state: LyricsState, val parsed: ParsedLyrics? = null, val plain: String? = null)

data class LyricsSearchUi(
    val query: String,
    val loading: Boolean = false,
    val results: List<LyricsOption> = emptyList(),
    val error: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    val player: PlayerConnection,
    private val library: LibraryRepository,
    private val lyricsRepo: LyricsRepository,
    private val settings: SettingsRepository,
    val spectrum: SpectrumAnalyzer,
) : ViewModel() {

    val state = player.state

    val style: StateFlow<NowPlayingStyle> =
        settings.nowPlayingStyle.stateIn(viewModelScope, SharingStarted.Eagerly, NowPlayingStyle.CLASSIC)

    private val mediaId = player.state.map { it.mediaId }.distinctUntilChanged()

    val lyrics: StateFlow<LyricsUi> = mediaId
        .flatMapLatest { id -> if (id == null) flowOf(LyricsState.None()) else lyricsRepo.lyrics(id) }
        .combine(player.state.map { it.durationMs }.distinctUntilChanged()) { s, duration -> toUi(s, duration) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LyricsUi(LyricsState.Loading))

    val lyricsOnline: StateFlow<Boolean> = settings.lyricsOnline.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val lyricsFontScale: StateFlow<Float> = settings.lyricsFontScale.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    val lyricsFullscreenFontScale: StateFlow<Float> = settings.lyricsFullscreenFontScale.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)

    private val _search = MutableStateFlow<LyricsSearchUi?>(null)
    val search: StateFlow<LyricsSearchUi?> = _search
    private var searchJob: Job? = null

    /** Note (0-5) de la piste courante, lue dans Room. */
    val rating: StateFlow<Int> = mediaId
        .flatMapLatest { id -> flow { emit(id?.let { library.track(it)?.rating } ?: 0) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setStyle(s: NowPlayingStyle) = viewModelScope.launch { settings.setNowPlayingStyle(s) }
    fun rate(stars: Int) = state.value.mediaId?.let { id -> viewModelScope.launch { library.setRating(id, stars) } }

    fun shiftLyrics(deltaMs: Long) = withTrack { id ->
        val current = (lyrics.value.state as? LyricsState.Found)?.offsetMs ?: 0
        lyricsRepo.setOffset(id, if (deltaMs == 0L) 0 else current + deltaMs)
    }
    fun ignoreLyrics() = withTrack { lyricsRepo.ignore(it) }
    fun resetLyrics() = withTrack { lyricsRepo.resetToAuto(it) }
    fun setLyricsOnline(enabled: Boolean) = viewModelScope.launch { settings.setLyricsOnline(enabled) }
    fun setLyricsFontScale(fullscreen: Boolean, scale: Float) = viewModelScope.launch { settings.setLyricsFontScale(fullscreen, scale) }

    fun openSearch() = withTrack { id -> runSearch(lyricsRepo.defaultQuery(id)) }

    fun runSearch(query: String) {
        val id = state.value.mediaId ?: return
        searchJob?.cancel()
        _search.value = LyricsSearchUi(query, loading = true)
        searchJob = viewModelScope.launch {
            runCatching { lyricsRepo.options(id, query) }
                .onSuccess { _search.value = LyricsSearchUi(query, results = it) }
                .onFailure { _search.value = LyricsSearchUi(query, error = "Recherche impossible : ${it.message}") }
        }
    }

    fun closeSearch() { searchJob?.cancel(); _search.value = null }

    fun pick(option: LyricsOption) = withTrack { id ->
        option.candidate.text?.let { lyricsRepo.choose(id, it) }
        closeSearch()
    }

    private fun withTrack(block: suspend (String) -> Unit) = state.value.mediaId?.let { id -> viewModelScope.launch { block(id) } }

    private fun toUi(s: LyricsState, durationMs: Long): LyricsUi {
        if (s !is LyricsState.Found) return LyricsUi(s)
        val parsed = LrcParser.parse(s.raw, durationMs, s.offsetMs)
        if (!parsed.synced) return LyricsUi(s, plain = LrcParser.toPlain(s.raw))
        // Longue introduction : une ligne « ♪ » occupe l'attente, comme les points d'interlude de Namida.
        val first = parsed.lines.firstOrNull()?.timeMs ?: 0
        val lines = if (first > INTRO_MS) listOf(LyricLine(0, "")) + parsed.lines else parsed.lines
        return LyricsUi(s, parsed = parsed.copy(lines = lines))
    }

    private companion object {
        const val INTRO_MS = 3_000L
    }
}
