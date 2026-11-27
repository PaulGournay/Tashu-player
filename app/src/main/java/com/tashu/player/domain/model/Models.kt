package com.tashu.player.domain.model

import kotlinx.serialization.Serializable

/** Piste audio indexée depuis Google Drive. `id` = fileId Drive (stable). */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val albumId: Long,
    val genre: String?,
    val composer: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val year: Int?,
    val durationMs: Long,
    val mimeType: String,
    val codec: AudioCodec,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    val sizeBytes: Long,
    val folderId: String,
    val artworkPath: String?,
    val replayGain: ReplayGainInfo,
    val hasLyrics: Boolean,
    val rating: Int,
    val playCount: Int,
    val lastPlayedAt: Long?,
)

data class ReplayGainInfo(
    val trackGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumGainDb: Float? = null,
    val albumPeak: Float? = null,
)

enum class AudioCodec(val label: String, val lossless: Boolean) {
    FLAC("FLAC", true), ALAC("ALAC", true), WAV("WAV", true),
    OPUS("Opus", false), AAC("AAC", false), MP3("MP3", false), VORBIS("Vorbis", false),
    UNKNOWN("?", false);

    companion object {
        fun fromName(name: String, mime: String?): AudioCodec {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when {
                ext == "flac" || mime == "audio/flac" || mime == "audio/x-flac" -> FLAC
                ext == "wav" || mime == "audio/wav" || mime == "audio/x-wav" -> WAV
                ext == "opus" -> OPUS
                ext == "ogg" || ext == "oga" || mime == "audio/ogg" -> VORBIS
                ext == "mp3" || mime == "audio/mpeg" -> MP3
                // .m4a peut être AAC ou ALAC : affiné au scan via le mime retourné par MediaMetadataRetriever.
                ext == "m4a" || ext == "mp4" || ext == "aac" -> AAC
                ext == "alac" -> ALAC
                else -> UNKNOWN
            }
        }
        val SUPPORTED_EXTENSIONS = setOf("flac", "wav", "opus", "ogg", "oga", "mp3", "m4a", "mp4", "aac", "alac")
    }
}

data class Album(
    val id: Long,
    val title: String,
    val artist: String,
    val year: Int?,
    val artworkPath: String?,
    val trackCount: Int,
    val dateAdded: Long = 0,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val durationMs: Long = 0,
)

/** Critères de tri des albums ; [defaultDescending] = sens proposé quand on choisit le critère. */
enum class AlbumSort(val label: String, val defaultDescending: Boolean) {
    TITLE("Titre", false),
    ARTIST("Artiste", false),
    YEAR("Année", true),
    DATE_ADDED("Date d'ajout", true),
    MOST_PLAYED("Nombre d'écoutes", true),
    LAST_PLAYED("Écouté récemment", true),
    TRACK_COUNT("Nombre de morceaux", true),
    DURATION("Durée", true),
}

data class AlbumSortOrder(val sort: AlbumSort = AlbumSort.ARTIST, val descending: Boolean = false)
data class Artist(val id: Long, val name: String, val albumCount: Int, val trackCount: Int)

/** Dossier Drive (Mode Fichiers + sélection des racines). */
data class DriveFolder(val id: String, val name: String, val parentId: String?)

// ---------- Smart playlists ----------

@Serializable
enum class SmartField(val column: String, val numeric: Boolean) {
    TITLE("title", false), ARTIST("artist", false), ALBUM("album", false), GENRE("genre", false),
    COMPOSER("composer", false), YEAR("year", true), RATING("rating", true), PLAY_COUNT("playCount", true),
    CODEC("codec", false), SAMPLE_RATE("sampleRateHz", true), BIT_DEPTH("bitDepth", true),
    DURATION_MS("durationMs", true),
    /** Valeur = nombre de jours. */
    DAYS_SINCE_PLAYED("lastPlayedAt", true),
    DAYS_SINCE_ADDED("dateAdded", true),
}

@Serializable
enum class SmartOperator { EQ, NEQ, CONTAINS, NOT_CONTAINS, GT, GTE, LT, LTE, IS_NULL }

@Serializable
data class SmartRule(val field: SmartField, val op: SmartOperator, val value: String = "")

@Serializable
enum class SmartSort(val sql: String) {
    TITLE("title COLLATE NOCASE ASC"), ARTIST("artist COLLATE NOCASE ASC, album, discNumber, trackNumber"),
    ALBUM("album COLLATE NOCASE ASC, discNumber, trackNumber"), RATING("rating DESC"),
    MOST_PLAYED("playCount DESC"), RECENTLY_ADDED("dateAdded DESC"), RECENTLY_PLAYED("lastPlayedAt DESC"),
    RANDOM("RANDOM()"),
}

@Serializable
data class SmartPlaylistDefinition(
    val rules: List<SmartRule>,
    val matchAll: Boolean = true,
    val sort: SmartSort = SmartSort.ARTIST,
    val limit: Int? = null,
)

// ---------- Réglages audio ----------

enum class ReplayGainMode { OFF, TRACK, ALBUM, SMART /* album si lecture d'album continue, sinon piste */ }

@Serializable
data class EqBandSetting(val frequencyHz: Float, val gainDb: Float, val q: Float = 1.41f, val type: PeqFilterType = PeqFilterType.PEAKING)

@Serializable
enum class PeqFilterType { PEAKING, LOW_SHELF, HIGH_SHELF }

@Serializable
data class AudioSettings(
    val geqEnabled: Boolean = false,
    /** 10 bandes GEQ (DynamicsProcessing), gains en dB. */
    val geqGainsDb: List<Float> = List(10) { 0f },
    val peqEnabled: Boolean = false,
    val peqBands: List<EqBandSetting> = DEFAULT_PEQ,
    val preampDb: Float = 0f,
    val replayGainMode: ReplayGainMode = ReplayGainMode.SMART,
    val replayGainPreampDb: Float = 0f,
    /** Gain appliqué aux pistes sans tags ReplayGain. */
    val replayGainFallbackDb: Float = -6f,
    val crossfadeMs: Int = 0,
    val preferUsbDac: Boolean = true,
) {
    companion object {
        val GEQ_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        val DEFAULT_PEQ = GEQ_FREQUENCIES.map { EqBandSetting(it, 0f) }
    }
}

enum class NowPlayingStyle { CLASSIC, IMMERSIVE, MINIMAL }

// ---------- Thème ----------

enum class DeckMode(val label: String) { CD("Platine CD"), WALKMAN("Walkman"), FLIP("Liste") }

enum class UiStyle(val label: String) { LIQUID_GLASS("Liquid Glass"), MINIMAL("Minimaliste") }

enum class BackgroundStyle(val label: String) { CLOUD("Nuage vivant"), BLURRED_ARTWORK("Pochette floue"), PLAIN("Uni") }

enum class ThemeMode(val label: String) { SYSTEM("Système"), LIGHT("Clair"), DARK("Sombre") }

enum class ThemeColorSource(val label: String) { ARTWORK("Pochette"), WALLPAPER("Fond d'écran"), FIXED("Couleur fixe") }

enum class ThemeStyle(val label: String) {
    TONAL_SPOT("Tonal"), VIBRANT("Vif"), EXPRESSIVE("Expressif"), NEUTRAL("Neutre"),
    FIDELITY("Fidèle"), RAINBOW("Arc-en-ciel"), MONOCHROME("Monochrome"),
}

@Serializable
data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val colorSource: ThemeColorSource = ThemeColorSource.ARTWORK,
    /** Couleur ARGB utilisée quand [colorSource] = FIXED. */
    val fixedColor: Int = 0xFF4F6D7A.toInt(),
    /** Fond noir pur en mode sombre. */
    val amoled: Boolean = true,
    val style: ThemeStyle = ThemeStyle.TONAL_SPOT,
    /** Liquid Glass (verre, nuage animé, réfraction) ou interface minimaliste (aplats sombres). */
    val uiStyle: UiStyle = UiStyle.LIQUID_GLASS,
    /** Paysage : ce que fait la pochette centrale quand on la touche (platine CD, walkman ou liste façon iOS 3). */
    val deckMode: DeckMode = DeckMode.CD,
    /** Fond de la bibliothèque, des albums et du lecteur (ignoré en style minimaliste). */
    val background: BackgroundStyle = BackgroundStyle.CLOUD,
)
