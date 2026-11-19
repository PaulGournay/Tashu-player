package com.tashu.player.drive.importer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tashu.player.R
import com.tashu.player.domain.repository.SettingsRepository
import com.tashu.player.drive.api.DriveApi
import com.tashu.player.drive.scan.ScanWorker
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Import d'une playlist YouTube Music vers Google Drive.
 *
 * 1. yt-dlp (embarqué) télécharge en MP3 avec exactement les options de la commande d'origine :
 *    meilleure piste audio, MP3 V0, métadonnées + pochette intégrées (recadrée en carré),
 *    numéro de piste = position dans la playlist, fichiers « NN - Titre.mp3 ».
 * 2. Les MP3 sont envoyés dans Drive : « Tashu Imports / <nom de la playlist> /». Les fichiers déjà
 *    présents (même nom) ne sont pas renvoyés, ce qui permet de relancer un import pour le compléter.
 * 3. « Tashu Imports » est ajouté aux dossiers de la bibliothèque et un scan est lancé.
 *
 * Tourne en service de premier plan (notification) : continue écran éteint ou app fermée.
 */
@HiltWorker
class PlaylistImportWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val drive: DriveApi,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure(error("Lien manquant"))
        setForeground(foregroundInfo("Préparation de yt-dlp…", 0, 0))
        report(STAGE_PREPARE, "Préparation de yt-dlp…")

        val work = File(applicationContext.cacheDir, "import/$id").apply { deleteRecursively(); mkdirs() }
        try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                updateYtDlpIfStale()
            }

            // ---------- 1. Téléchargement ----------
            report(STAGE_DOWNLOAD, "Lecture de la playlist…")
            val request = YoutubeDLRequest(url).apply {
                addOption("-f", "ba/best")
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("--embed-metadata")
                addOption("--embed-thumbnail")
                addOption("--convert-thumbnails", "jpg")
                addOption("--ppa", "ThumbnailsConvertor+ffmpeg_o:-vf crop=ih:ih")
                addOption("--parse-metadata", "playlist_index:%(track_number)s")
                addOption("-o", "${work.absolutePath}/%(playlist_title)s/%(playlist_index)s - %(title)s.%(ext)s")
                addOption("--ignore-errors")   // une vidéo indisponible n'arrête pas la playlist
                addOption("--no-mtime")
                addOption("--newline")
            }
            val processId = id.toString()
            coroutineContext[Job]?.invokeOnCompletion { cause ->
                if (cause != null) runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
            }
            var index = 0
            var total = 0
            var title = ""
            val premiumOnly = mutableSetOf<String>()
            val unavailable = mutableSetOf<String>()
            val outcome = runCatching {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId) { percent, _, line ->
                        ITEM.find(line)?.let { index = it.groupValues[1].toInt(); total = it.groupValues[2].toInt() }
                        DEST.find(line)?.let { title = File(it.groupValues[1]).nameWithoutExtension }
                        VIDEO_ERROR.find(line)?.let { m ->
                            if (m.groupValues[2].contains("Premium", true)) premiumOnly += m.groupValues[1] else unavailable += m.groupValues[1]
                        }
                        val label = if (total > 0) "Téléchargement $index sur $total" else "Téléchargement…"
                        report(STAGE_DOWNLOAD, label, index, total, title, percent.coerceIn(0f, 100f))
                    }
                }
            }
            if (isStopped) return Result.failure(error("Import annulé"))
            outcome.exceptionOrNull()?.let { Log.w(TAG, "yt-dlp a signalé des erreurs", it) }

            // --ignore-errors : on garde ce qui a été téléchargé, même si quelques morceaux ont échoué.
            val mp3s = work.walkTopDown().filter { it.isFile && it.extension.equals("mp3", true) }.sortedBy { it.name }.toList()
            // Les erreurs par vidéo se trouvent aussi dans le message de l'exception (sortie complète de yt-dlp).
            outcome.exceptionOrNull()?.message?.lineSequence()?.forEach { line ->
                VIDEO_ERROR.find(line)?.let { m ->
                    if (m.groupValues[2].contains("Premium", true)) premiumOnly += m.groupValues[1] else unavailable += m.groupValues[1]
                }
            }
            val skipped = skippedSummary(premiumOnly.size, unavailable.size)
            if (mp3s.isEmpty()) {
                val reason = when {
                    premiumOnly.isNotEmpty() && unavailable.isEmpty() ->
                        "Tous les morceaux de cette playlist sont réservés aux abonnés YouTube Music Premium : YouTube refuse de les fournir."
                    skipped != null -> "Aucun morceau téléchargé. $skipped"
                    else -> outcome.exceptionOrNull()?.message?.lineSequence()?.lastOrNull { it.contains("ERROR") }
                        ?: "Aucun morceau téléchargé. Vérifiez que le lien est une playlist publique."
                }
                return Result.failure(error(reason))
            }
            val playlistName = mp3s.first().parentFile?.takeIf { it != work }?.name ?: "Playlist importée"

            // ---------- 2. Envoi sur Drive ----------
            report(STAGE_UPLOAD, "Envoi sur Drive…", 0, mp3s.size, playlistName)
            val importRoot = drive.findOrCreateFolder(IMPORT_ROOT_NAME, "root")
            val target = drive.findOrCreateFolder(playlistName, importRoot.id)
            val already = drive.childNames(target.id)
            var sent = 0
            mp3s.forEachIndexed { i, file ->
                if (isStopped) return Result.failure(error("Import annulé"))
                report(STAGE_UPLOAD, "Envoi sur Drive ${i + 1} sur ${mp3s.size}", i + 1, mp3s.size, file.nameWithoutExtension)
                if (file.name !in already) {
                    drive.uploadFile(file, target.id, "audio/mpeg")
                    sent++
                }
                file.delete() // libère l'espace au fur et à mesure
            }

            // ---------- 3. Bibliothèque ----------
            val roots = settings.rootFolderIds.first()
            if (importRoot.id !in roots) settings.setRootFolders(roots + importRoot.id)
            ScanWorker.runNow(applicationContext)

            return Result.success(
                workDataOf(
                    KEY_FOLDER to "$IMPORT_ROOT_NAME / $playlistName", KEY_COUNT to mp3s.size, KEY_SENT to sent,
                    KEY_SKIPPED to skipped,
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Import échoué", e)
            return Result.failure(error(e.message ?: e.javaClass.simpleName))
        } finally {
            work.deleteRecursively()
        }
    }

    /** yt-dlp doit rester récent pour suivre les changements de YouTube : mise à jour au plus une fois par jour. */
    private fun updateYtDlpIfStale() {
        val prefs = applicationContext.getSharedPreferences("ytdlp", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("updated_at", 0) < 24 * 3600_000L) return
        report(STAGE_PREPARE, "Mise à jour de yt-dlp…")
        runCatching { YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel._STABLE) }
            .onSuccess { prefs.edit().putLong("updated_at", now).apply() }
            .onFailure { Log.w(TAG, "Mise à jour de yt-dlp impossible, on continue avec la version embarquée", it) }
    }

    private fun report(stage: String, message: String, index: Int = 0, total: Int = 0, track: String = "", percent: Float = 0f) {
        setProgressAsync(
            workDataOf(
                KEY_STAGE to stage, KEY_MESSAGE to message, KEY_INDEX to index, KEY_TOTAL to total,
                KEY_TRACK to track, KEY_PERCENT to percent,
            )
        )
        val now = System.currentTimeMillis()
        if (now - lastNotified > 1000) { // la notification n'a pas besoin de plus d'une mise à jour par seconde
            lastNotified = now
            setForegroundAsync(foregroundInfo(if (track.isNotEmpty()) "$message\n$track" else message, index, total))
        }
    }

    private fun foregroundInfo(text: String, index: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Import de playlists", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Import de playlist")
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(total, index, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Annuler", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun error(message: String): Data = workDataOf(KEY_ERROR to message)

    private fun skippedSummary(premium: Int, other: Int): String? {
        val parts = buildList {
            if (premium > 0) add("$premium réservé${if (premium > 1) "s" else ""} aux abonnés YouTube Music Premium")
            if (other > 0) add("$other indisponible${if (other > 1) "s" else ""} (supprimé, privé ou bloqué dans votre pays)")
        }
        return if (parts.isEmpty()) null else "Morceaux ignorés : " + parts.joinToString(", ") + "."
    }

    companion object {
        private const val TAG = "PlaylistImport"
        const val UNIQUE = "playlist-import"
        const val IMPORT_ROOT_NAME = "Tashu Imports"
        private const val CHANNEL = "playlist_import"
        private const val NOTIFICATION_ID = 4201

        const val KEY_URL = "url"
        const val KEY_STAGE = "stage"
        const val KEY_MESSAGE = "message"
        const val KEY_INDEX = "index"
        const val KEY_TOTAL = "total"
        const val KEY_TRACK = "track"
        const val KEY_PERCENT = "percent"
        const val KEY_ERROR = "error"
        const val KEY_FOLDER = "folder"
        const val KEY_COUNT = "count"
        const val KEY_SENT = "sent"
        const val KEY_SKIPPED = "skipped"

        const val STAGE_PREPARE = "prepare"
        const val STAGE_DOWNLOAD = "download"
        const val STAGE_UPLOAD = "upload"

        private val ITEM = Regex("""Downloading item (\d+) of (\d+)""")
        /** « ERROR: [youtube] <id>: <raison> » : un morceau précis n'a pas pu être récupéré. */
        private val VIDEO_ERROR = Regex("""ERROR: \[youtube] ([\w-]{11}): (.+)""")
        private val DEST = Regex("""\[(?:ExtractAudio|download)\] Destination: (.+)""")

        fun start(context: Context, url: String) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<PlaylistImportWorker>()
                    .setInputData(workDataOf(KEY_URL to url))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
    }
}
