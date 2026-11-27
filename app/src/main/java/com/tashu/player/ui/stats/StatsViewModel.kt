package com.tashu.player.ui.stats

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tashu.player.domain.model.ListeningStats
import com.tashu.player.domain.model.MonthRecap
import com.tashu.player.domain.model.StatsPeriod
import com.tashu.player.domain.model.WeekListening
import com.tashu.player.domain.model.WrappedMonth
import com.tashu.player.domain.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(repo: StatsRepository) : ViewModel() {
    val period = MutableStateFlow(StatsPeriod.MONTH)
    val stats: StateFlow<ListeningStats?> = period.flatMapLatest { repo.stats(it, TOP) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val weeks: StateFlow<List<WeekListening>> = repo.weeklyListening(WEEKS)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** Tous les mois de l'historique (null = pas encore lu). */
    val months: StateFlow<List<WrappedMonth>?> = repo.wrappedMonths()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Mois choisi à la main pour Tashu Wrapped ; sinon le mois en cours s'il suffit, ou le dernier qui suffit. */
    val pickedMonth = MutableStateFlow<YearMonth?>(null)
    val wrappedMonth: StateFlow<YearMonth?> = combine(months, pickedMonth) { all, picked ->
        val enough = all.orEmpty().filter { it.enough }.map { it.month }
        picked?.takeIf { it in enough } ?: enough.lastOrNull()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private companion object {
        const val TOP = 10
        const val WEEKS = 12
    }
}

@HiltViewModel
class WrappedViewModel @Inject constructor(repo: StatsRepository, handle: SavedStateHandle) : ViewModel() {
    val month: YearMonth = YearMonth.parse(checkNotNull(handle.get<String>("month")))
    val recap: StateFlow<MonthRecap?> = repo.monthRecap(month).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
