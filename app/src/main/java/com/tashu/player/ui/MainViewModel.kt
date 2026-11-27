package com.tashu.player.ui

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tashu.player.domain.model.Album
import kotlinx.coroutines.flow.debounce
import com.tashu.player.domain.model.Artist
import com.tashu.player.domain.model.AlbumSort
import com.tashu.player.domain.model.AlbumSortOrder
import com.tashu.player.domain.model.sortedFor
import com.tashu.player.domain.model.AudioCodec
import com.tashu.player.domain.model.DriveFolder
import com.tashu.player.domain.model.SmartField
import com.tashu.player.domain.model.SmartOperator
import com.tashu.player.domain.model.SmartPlaylistDefinition
import com.tashu.player.domain.model.SmartRule
import com.tashu.player.domain.model.SmartSort
import com.tashu.player.domain.model.ThemeSettings
import com.tashu.player.domain.model.Track
import com.tashu.player.domain.repository.LibraryRepository
import com.tashu.player.domain.repository.SettingsRepository
import com.tashu.player.domain.repository.StatsRepository
import com.tashu.player.drive.api.DriveApi
import com.tashu.player.drive.api.DriveFile
import com.tashu.player.drive.api.DriveParents
import com.tashu.player.drive.auth.GoogleAuthManager
import com.tashu.player.drive.scan.DriveMusicScanner
import com.tashu.player.drive.scan.ScanWorker
import com.tashu.player.playback.PlayerConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val auth: GoogleAuthManager,
    val player: PlayerConnection,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    stats: StatsRepository,
    scanner: DriveMusicScanner,
) : ViewModel() {
    val authState = auth.state
    val scanProgress = scanner.progress
    val roots: StateFlow<Set<String>?> = settings.rootFolderIds.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val albumSortOrder: StateFlow<AlbumSortOrder> = settings.albumSortOrder.stateIn(viewModelScope, SharingStarted.Eagerly, AlbumSortOrder())
    val albums: StateFlow<List<Album>> = combine(library.albums(), settings.albumSortOrder) { list, order -> list.sortedFor(order) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Choisir un critère applique son sens par défaut ; re-choisir le même critère inverse le sens. */
    fun selectAlbumSort(sort: AlbumSort) = viewModelScope.launch {
        val current = albumSortOrder.value
        settings.setAlbumSortOrder(
            if (current.sort == sort) current.copy(descending = !current.descending)
            else AlbumSortOrder(sort, sort.defaultDescending)
        )
    }

    /** Instant (uptimeMillis) où le lecteur a été réduit : le mini-lecteur y répond par un rebond. */
    val miniPlayerLandedAt = MutableStateFlow(0L)
    fun onPlayerCollapsed() { miniPlayerLandedAt.value = android.os.SystemClock.uptimeMillis() }

    val albumGridColumns: StateFlow<Int> = settings.albumGridColumns.stateIn(viewModelScope, SharingStarted.Eagerly, 2)

    /** Cycle 1 → 2 → 3 → … → 8 → 1… */
    fun cycleAlbumGridColumns() = viewModelScope.launch {
        settings.setAlbumGridColumns(
            albumGridColumns.value % 8 + 1
        )
    }

    fun toggleAlbumSortDirection() = viewModelScope.launch {
        settings.setAlbumSortOrder(albumSortOrder.value.let { it.copy(descending = !it.descending) })
    }
    val tracks: StateFlow<List<Track>> = library.tracks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val artists: StateFlow<List<Artist>> = library.artists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Accueil, « Pas écouté depuis longtemps ». */
    val rediscover: StateFlow<List<Album>> = stats.rediscover(15).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Recherche (morceaux, albums, artistes ; sans accents ni majuscules) ----
    val searchQuery = MutableStateFlow("")
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    val searchResults: StateFlow<SearchResults> = combine(
        searchQuery.debounce(150), library.tracks(), library.albums(), library.artists(),
    ) { q, allTracks, allAlbums, allArtists ->
        val needle = q.normalizedForSearch()
        if (needle.isBlank()) return@combine SearchResults()
        fun hit(vararg fields: String?) = fields.any { it != null && it.normalizedForSearch().contains(needle) }
        val coverByArtist = artistCovers(allAlbums, allTracks)
        SearchResults(
            query = q,
            artists = allArtists.filter { hit(it.name) }.map { it to coverByArtist[it.name] },
            albums = allAlbums.filter { hit(it.title, it.artist) },
            tracks = allTracks.filter { hit(it.title, it.artist, it.album) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /** Pochette représentative de chaque artiste (accueil, recherche). */
    val artistCovers: StateFlow<Map<String, String?>> = combine(library.albums(), library.tracks()) { al, tr -> artistCovers(al, tr) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun signIn(activity: Activity) = viewModelScope.launch {
        runCatching { auth.signIn(activity) }.onFailure { _error.value = it.message ?: "Connexion impossible" }
    }

    fun album(id: Long): Flow<Album?> = library.album(id)
    fun albumTracks(id: Long): Flow<List<Track>> = library.albumTracks(id)
    fun folderTracks(id: String): Flow<List<Track>> = library.folderTracks(id)
    val rootFolders: StateFlow<List<DriveFolder>> = library.rootFolders().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun subFolders(id: String): Flow<List<DriveFolder>> = library.subFolders(id)
    fun smart(def: SmartPlaylistDefinition): Flow<List<Track>> = library.smartPlaylist(def)

    fun play(tracks: List<Track>, index: Int, queueName: String? = null) = player.play(tracks, index, queueName)
    /** Cœur = note 5 étoiles ; le retirer remet la note à 0. */
    fun toggleFavorite(track: Track) = viewModelScope.launch { library.setRating(track.id, if (track.rating >= 5) 0 else 5) }

    fun rescan(force: Boolean = false) = ScanWorker.runNow(context, force)

    val themeSettings: StateFlow<ThemeSettings> = settings.themeSettings.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettings())
    fun updateTheme(transform: (ThemeSettings) -> ThemeSettings) = viewModelScope.launch { settings.updateTheme(transform) }

    fun saveRoots(ids: Set<String>) = viewModelScope.launch {
        settings.setRootFolders(ids)
        ScanWorker.runNow(context, force = false)
    }

    companion object {
        /** Exemples de listes dynamiques prêtes à l'emploi. */
        val SMART_PRESETS: List<Pair<String, SmartPlaylistDefinition>> = listOf(
            "Notés 4 étoiles et plus" to SmartPlaylistDefinition(listOf(SmartRule(SmartField.RATING, SmartOperator.GTE, "4")), sort = SmartSort.RATING),
            "Pas écoutés depuis 30 jours" to SmartPlaylistDefinition(listOf(SmartRule(SmartField.DAYS_SINCE_PLAYED, SmartOperator.GT, "30")), sort = SmartSort.RANDOM, limit = 200),
            "Hi-Res (plus de 48 kHz)" to SmartPlaylistDefinition(listOf(SmartRule(SmartField.SAMPLE_RATE, SmartOperator.GT, "48000")), sort = SmartSort.ARTIST),
            "FLAC jazz" to SmartPlaylistDefinition(
                listOf(SmartRule(SmartField.CODEC, SmartOperator.EQ, AudioCodec.FLAC.name), SmartRule(SmartField.GENRE, SmartOperator.CONTAINS, "jazz")),
            ),
            "Ajoutés récemment" to SmartPlaylistDefinition(listOf(SmartRule(SmartField.DAYS_SINCE_ADDED, SmartOperator.LT, "14")), sort = SmartSort.RECENTLY_ADDED),
        )
    }
}

/** Navigation dans Drive pour choisir les dossiers racine de la bibliothèque. */
@HiltViewModel
class FolderPickerViewModel @Inject constructor(
    private val api: DriveApi,
    private val settings: SettingsRepository,
) : ViewModel() {
    data class UiState(
        val path: List<DriveFile> = emptyList(),   // fil d'Ariane ; vide = racine virtuelle
        val folders: List<DriveFile> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )

    data class MusicFolder(val folder: DriveFile, val path: String, val albums: Int, val tracks: Int)
    data class Suggestions(val loading: Boolean = true, val folders: List<MusicFolder> = emptyList(), val error: String? = null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state
    val selected = MutableStateFlow<Map<String, String>>(emptyMap()) // id → nom
    private val _suggestions = MutableStateFlow(Suggestions())
    val suggestions: StateFlow<Suggestions> = _suggestions

    init {
        viewModelScope.launch { settings.rootFolderIds.collect { ids -> if (selected.value.isEmpty()) selected.value = ids.associateWith { it } } }
        open(null)
        findMusicFolders()
    }

    private fun findMusicFolders() = viewModelScope.launch {
        runCatching {
            coroutineScope {
                val audio = async { api.audioParents() }
                val folders = async { api.allFolders() }
                val rootId = async { api.file("root").id }
                suggestMusicFolders(audio.await(), folders.await(), rootId.await())
            }
        }.onSuccess { found ->
            _suggestions.value = Suggestions(loading = false, folders = found)
            if (selected.value.isEmpty() && settings.rootFolderIds.first().isEmpty()) {
                selected.value = found.associate { it.folder.id to it.folder.name }
            }
        }.onFailure { _suggestions.value = Suggestions(loading = false, error = "Recherche des dossiers de musique impossible : ${it.message}") }
    }

    fun open(folder: DriveFile?) = viewModelScope.launch {
        val path = when {
            folder == null -> emptyList()
            _state.value.path.any { it.id == folder.id } -> _state.value.path.takeWhile { it.id != folder.id } + folder
            else -> _state.value.path + folder
        }
        _state.value = _state.value.copy(path = path, loading = true, error = null)
        runCatching {
            if (folder == null) {
                // Racine virtuelle : "Mon Drive" + dossiers partagés avec moi.
                listOf(DriveFile("root", "Mon Drive", DriveApi.FOLDER_MIME)) + api.sharedFolders()
            } else api.listChildren(folder.id, foldersOnly = true)
        }.onSuccess { _state.value = _state.value.copy(folders = it, loading = false) }
            .onFailure { _state.value = _state.value.copy(loading = false, error = "Impossible de lister ce dossier : ${it.message}") }
    }

    fun up() = _state.value.path.dropLast(1).lastOrNull().let { open(it) }

    fun toggle(f: DriveFile) {
        selected.value = if (f.id in selected.value) selected.value - f.id else selected.value + (f.id to f.name)
    }

    /** Coche tous les dossiers affichés, ou les décoche s'ils l'étaient déjà tous. */
    fun toggleAllShown() {
        val shown = _state.value.folders
        selected.value = if (shown.all { it.id in selected.value }) selected.value - shown.map { it.id }.toSet()
        else selected.value + shown.associate { it.id to it.name }
    }
}

/** Par branche de premier niveau (enfant de Mon Drive ou dossier partagé), l'ancêtre commun le plus proche des dossiers audio. */
private fun suggestMusicFolders(audio: List<DriveParents>, folders: List<DriveFile>, rootId: String): List<FolderPickerViewModel.MusicFolder> {
    val byId = folders.associateBy { it.id }
    val tracksPerFolder = audio.flatMap { it.parents }.filter { it == rootId || it in byId }.groupingBy { it }.eachCount()
    val chains = tracksPerFolder.keys.associateWith { id ->
        generateSequence(id) { byId[it]?.parents?.firstOrNull()?.takeIf { p -> p == rootId || p in byId } }.toList().asReversed()
    }
    val myDriveHasAudio = rootId in tracksPerFolder
    val branches = chains.entries.groupBy { (_, chain) ->
        if (chain.first() == rootId && !myDriveHasAudio) chain[1] else chain.first()
    }
    return branches.values.map { members ->
        val common = members.map { it.value }.reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }
        val names = common.map { if (it == rootId) "Mon Drive" else byId.getValue(it).name }
        val folder = if (common.last() == rootId) DriveFile("root", "Mon Drive", DriveApi.FOLDER_MIME) else byId.getValue(common.last())
        FolderPickerViewModel.MusicFolder(folder, names.joinToString(" / "), members.size, members.sumOf { tracksPerFolder.getValue(it.key) })
    }.sortedBy { it.path.lowercase() }
}


data class SearchResults(
    val query: String = "",
    val artists: List<Pair<Artist, String?>> = emptyList(),
    val albums: List<Album> = emptyList(),
    val tracks: List<Track> = emptyList(),
) {
    val isEmpty get() = artists.isEmpty() && albums.isEmpty() && tracks.isEmpty()
}

/** Minuscules sans accents : « Élodie » et « elodie » se trouvent l'une l'autre. */
internal fun String.normalizedForSearch(): String =
    java.text.Normalizer.normalize(lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()

/**
 * Pochette par artiste : son album le plus récent, sinon celle d'un morceau où il apparaît
 * (artistes présents seulement en featuring ou sur des compilations).
 */
private fun artistCovers(albums: List<Album>, tracks: List<Track>): Map<String, String?> {
    val m = HashMap<String, String?>()
    albums.sortedByDescending { it.year ?: 0 }.forEach { if (it.artworkPath != null) m.putIfAbsent(it.artist, it.artworkPath) }
    tracks.forEach { t -> if (t.artworkPath != null) m.putIfAbsent(t.artist, t.artworkPath) }
    return m
}
