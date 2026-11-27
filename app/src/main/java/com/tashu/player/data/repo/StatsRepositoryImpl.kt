package com.tashu.player.data.repo

import com.tashu.player.data.db.ListeningTotals
import com.tashu.player.data.db.MusicDatabase
import com.tashu.player.domain.model.Album
import com.tashu.player.domain.model.AlbumStat
import com.tashu.player.domain.model.ArtistStat
import com.tashu.player.domain.model.DAY_MS
import com.tashu.player.domain.model.ListeningStats
import com.tashu.player.domain.model.ListeningTotal
import com.tashu.player.domain.model.MonthRecap
import com.tashu.player.domain.model.StatsPeriod
import com.tashu.player.domain.model.TrackStat
import com.tashu.player.domain.model.WeekListening
import com.tashu.player.domain.model.WrappedMonth
import com.tashu.player.domain.repository.StatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepositoryImpl @Inject constructor(
    private val db: MusicDatabase,
) : StatsRepository {
    private val history = db.playHistoryDao()

    override fun stats(period: StatsPeriod, limit: Int): Flow<ListeningStats> =
        stats(period.since(System.currentTimeMillis()), Long.MAX_VALUE, limit)

    private fun stats(since: Long, until: Long, limit: Int): Flow<ListeningStats> = combine(
        history.totals(since, until),
        history.topTracks(since, until, limit).map { rows ->
            // Un morceau retiré par un rescan entre les deux lectures disparaît simplement du classement.
            val byId = db.trackDao().byIds(rows.map { it.trackId }).associateBy { it.id }
            rows.mapNotNull { r -> byId[r.trackId]?.let { TrackStat(it.toDomain(), r.plays, r.listenedMs) } }
        },
        combine(history.topAlbums(since, until, limit), db.catalogDao().observeAlbums()) { rows, albums ->
            val byId = albums.associateBy { it.id }
            rows.mapNotNull { r -> byId[r.albumId]?.let { AlbumStat(it.toDomain(), r.plays, r.listenedMs) } }
        },
        history.topArtists(since, until, limit),
    ) { total, tracks, albums, artists ->
        ListeningStats(total.toDomain(), tracks, albums, artists.map { ArtistStat(it.artist, it.plays, it.listenedMs) })
    }

    override fun weeklyListening(weeks: Int): Flow<List<WeekListening>> {
        val zone = ZoneId.systemDefault()
        val firstMonday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(weeks - 1L)
        return history.slices(firstMonday.startMs(zone), Long.MAX_VALUE).map { slices ->
            val ms = LongArray(weeks)
            slices.forEach { s -> ms[ChronoUnit.WEEKS.between(firstMonday, s.startedAt.localDate(zone)).toInt()] += s.listenedMs }
            List(weeks) { WeekListening(firstMonday.plusWeeks(it.toLong()), ms[it]) }
        }
    }

    override fun wrappedMonths(): Flow<List<WrappedMonth>> =
        history.months().map { rows -> rows.map { WrappedMonth(YearMonth.parse(it.month), it.plays, it.tracks) } }

    override fun monthRecap(month: YearMonth): Flow<MonthRecap> {
        val zone = ZoneId.systemDefault()
        val since = month.atDay(1).startMs(zone)
        val until = month.plusMonths(1).atDay(1).startMs(zone)
        return combine(stats(since, until, 5), history.slices(since, until)) { st, slices ->
            val weekdays = IntArray(7)
            val days = IntArray(month.lengthOfMonth())
            slices.filter { it.counts }.forEach { s ->
                val d = s.startedAt.localDate(zone)
                weekdays[d.dayOfWeek.value - 1]++
                days[d.dayOfMonth - 1]++
            }
            MonthRecap(month, st.total, st.topTracks, st.topAlbums, st.topArtists, weekdays.toList(), days.toList())
        }
    }

    override fun rediscover(limit: Int): Flow<List<Album>> = combine(
        history.albumsLastPlayed(), history.firstListenAt(), db.catalogDao().observeAlbums(),
    ) { rows, first, albums ->
        val now = System.currentTimeMillis()
        // « Longtemps » = 60 jours, ou la moitié de l'historique s'il est plus court ; moins de 14 jours ne veut rien dire.
        val gap = minOf(REDISCOVER_DAYS * DAY_MS, (now - (first ?: now)) / 2)
        if (gap < MIN_GAP_DAYS * DAY_MS) return@combine emptyList()
        val byId = albums.associateBy { it.id }
        rows.filter { it.lastPlayedAt < now - gap }
            .sortedWith(compareByDescending<com.tashu.player.data.db.AlbumLastPlayed> { it.plays }.thenBy { it.lastPlayedAt })
            .mapNotNull { byId[it.albumId]?.toDomain() }
            .take(limit)
    }

    private companion object {
        const val REDISCOVER_DAYS = 60L
        const val MIN_GAP_DAYS = 14L
    }
}

private fun ListeningTotals.toDomain() = ListeningTotal(plays, listenedMs, tracks, albums, artists)
private fun LocalDate.startMs(zone: ZoneId) = atStartOfDay(zone).toInstant().toEpochMilli()
private fun Long.localDate(zone: ZoneId) = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
