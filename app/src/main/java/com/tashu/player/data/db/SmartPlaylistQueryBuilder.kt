package com.tashu.player.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import com.tashu.player.domain.model.SmartField
import com.tashu.player.domain.model.SmartOperator
import com.tashu.player.domain.model.SmartPlaylistDefinition

/**
 * Transforme une [SmartPlaylistDefinition] en requête SQL paramétrée.
 * Les noms de colonnes viennent exclusivement de l'enum [SmartField] (whitelist) ;
 * toutes les valeurs utilisateur passent en paramètres liés → pas d'injection SQL.
 *
 * Exemples :
 *  - "Notés > 4 étoiles"           : RATING GT 4
 *  - "Non écoutés depuis 30 jours" : DAYS_SINCE_PLAYED GT 30  (inclut les jamais écoutés)
 *  - "FLAC Jazz uniquement"        : CODEC EQ FLAC + GENRE CONTAINS jazz
 */
object SmartPlaylistQueryBuilder {
    private const val DAY_MS = 86_400_000L

    fun build(def: SmartPlaylistDefinition, now: Long = System.currentTimeMillis()): SimpleSQLiteQuery {
        val clauses = mutableListOf<String>()
        val args = mutableListOf<Any?>()

        for (rule in def.rules) {
            val col = rule.field.column
            if (rule.field == SmartField.DAYS_SINCE_PLAYED || rule.field == SmartField.DAYS_SINCE_ADDED) {
                // "depuis plus de N jours" ⇔ timestamp < now - N jours
                val threshold = now - (rule.value.toLongOrNull() ?: 0L) * DAY_MS
                val clause = when (rule.op) {
                    SmartOperator.GT, SmartOperator.GTE -> "($col IS NULL OR $col < ?)"
                    SmartOperator.LT, SmartOperator.LTE -> "($col IS NOT NULL AND $col >= ?)"
                    SmartOperator.IS_NULL -> "$col IS NULL"
                    else -> error("Opérateur ${rule.op} non supporté pour ${rule.field}")
                }
                clauses += clause
                if (rule.op != SmartOperator.IS_NULL) args += threshold
                continue
            }
            val value: Any = if (rule.field.numeric) (rule.value.toDoubleOrNull() ?: 0.0) else rule.value
            clauses += when (rule.op) {
                SmartOperator.EQ -> if (rule.field.numeric) "$col = ?" else "$col = ? COLLATE NOCASE"
                SmartOperator.NEQ -> if (rule.field.numeric) "$col != ?" else "$col != ? COLLATE NOCASE"
                SmartOperator.CONTAINS -> "$col LIKE '%' || ? || '%'"
                SmartOperator.NOT_CONTAINS -> "($col IS NULL OR $col NOT LIKE '%' || ? || '%')"
                SmartOperator.GT -> "$col > ?"
                SmartOperator.GTE -> "$col >= ?"
                SmartOperator.LT -> "$col < ?"
                SmartOperator.LTE -> "$col <= ?"
                SmartOperator.IS_NULL -> "$col IS NULL"
            }
            if (rule.op != SmartOperator.IS_NULL) args += value
        }

        val where = if (clauses.isEmpty()) "" else
            "WHERE " + clauses.joinToString(if (def.matchAll) " AND " else " OR ")
        val limit = def.limit?.let { " LIMIT ${it.coerceIn(1, 10_000)}" } ?: ""
        return SimpleSQLiteQuery("SELECT * FROM tracks $where ORDER BY ${def.sort.sql}$limit", args.toTypedArray())
    }
}
