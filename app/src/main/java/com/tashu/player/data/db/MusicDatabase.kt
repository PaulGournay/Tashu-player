package com.tashu.player.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TrackEntity::class, AlbumEntity::class, ArtistEntity::class, GenreEntity::class,
        ComposerEntity::class, FolderEntity::class, SmartPlaylistEntity::class, QueueEntity::class,
        LyricsEntity::class,
    ],
    views = [AlbumView::class],
    version = 3,
    exportSchema = true,
)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun catalogDao(): CatalogDao
    abstract fun folderDao(): FolderDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun queueDao(): QueueDao
    abstract fun lyricsDao(): LyricsDao
}

/** Albums par dossier Drive (titre/artiste calculés par album_view) et table des paroles. SQL figé sur schemas/2.json. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `albums_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `folderId` TEXT NOT NULL, `artworkPath` TEXT)")
        db.execSQL(
            "INSERT INTO albums_new (folderId, artworkPath) SELECT t.folderId, " +
                "(SELECT a.artworkPath FROM tracks t2 JOIN albums a ON a.id = t2.albumId WHERE t2.folderId = t.folderId AND a.artworkPath IS NOT NULL LIMIT 1) " +
                "FROM tracks t GROUP BY t.folderId"
        )
        db.execSQL("UPDATE tracks SET albumId = (SELECT n.id FROM albums_new n WHERE n.folderId = tracks.folderId)")
        db.execSQL("DROP TABLE albums")
        db.execSQL("ALTER TABLE albums_new RENAME TO albums")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_albums_folderId` ON `albums` (`folderId`)")
        db.execSQL(
            "CREATE VIEW `album_view` AS SELECT a.id, a.folderId, a.artworkPath, " +
                "CASE WHEN s.albumTags = 1 AND s.album <> 'Album inconnu' THEN s.album ELSE f.name END AS title, " +
                "COALESCE((SELECT t.albumArtist FROM tracks t WHERE t.albumId = a.id GROUP BY t.albumArtist HAVING COUNT(*) * 2 > s.trackCount), 'Artistes divers') AS artistName, " +
                "s.year, s.trackCount " +
                "FROM albums a " +
                "JOIN (SELECT albumId, COUNT(*) AS trackCount, COUNT(DISTINCT album) AS albumTags, MIN(album) AS album, MAX(year) AS year FROM tracks GROUP BY albumId) s ON s.albumId = a.id " +
                "JOIN folders f ON f.id = a.folderId"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `lyrics` (`trackId` TEXT NOT NULL, `manualText` TEXT, `ignored` INTEGER NOT NULL, `onlineText` TEXT, " +
                "`onlineInstrumental` INTEGER NOT NULL, `onlineFetchedAt` INTEGER, `offsetMs` INTEGER NOT NULL, PRIMARY KEY(`trackId`), " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
    }
}

/** album_view : ajout de dateAdded, playCount, lastPlayedAt, durationMs (tri des albums). */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("DROP VIEW IF EXISTS `album_view`")
        db.execSQL("CREATE VIEW `album_view` AS $ALBUM_VIEW_SQL")
    }
}
