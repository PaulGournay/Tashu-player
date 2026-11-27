package com.tashu.player.ui.stats

import java.time.DayOfWeek
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** « 12 h 05 min », « 3 h », « 45 min », « moins d'1 min ». */
internal fun listeningTime(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes == 0L -> "moins d'1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${"%02d".format(minutes % 60)} min"
    }
}

internal fun plays(n: Int): String = if (n == 1) "1 écoute" else "$n écoutes"

internal fun count(n: Int, one: String, many: String): String = if (n == 1) "1 $one" else "$n $many"

/** « novembre », ou « novembre 2025 » hors de l'année en cours. */
internal fun monthName(m: YearMonth, withYear: Boolean = m.year != YearMonth.now().year): String {
    val name = m.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.FRENCH)
    return if (withYear) "$name ${m.year}" else name
}

/** « de novembre », « d'avril », « d'août ». */
internal fun ofMonth(m: YearMonth): String {
    val name = monthName(m)
    return if (name.first() in "aeiouéâ") "d'$name" else "de $name"
}

/** 0 = lundi. */
internal fun weekdayName(i: Int): String = DayOfWeek.of(i + 1).getDisplayName(TextStyle.FULL, Locale.FRENCH)
