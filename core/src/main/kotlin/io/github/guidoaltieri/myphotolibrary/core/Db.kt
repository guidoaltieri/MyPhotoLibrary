package io.github.guidoaltieri.myphotolibrary.core

/** Riga di una lettura: colonna → valore (Long, Double, String, ByteArray o null). */
typealias Row = Map<String, Any?>

/**
 * Accesso minimo a SQLite, implementato da Android (androidx.sqlite) e dai test (JDBC).
 * Così tutto il motore della fototeca resta in Kotlin puro e si verifica senza dispositivo.
 */
interface Db {
    fun query(sql: String, args: List<Any?> = emptyList()): List<Row>
    fun exec(sql: String, args: List<Any?> = emptyList())
    /** INSERT: restituisce il rowid della riga inserita. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long
    /** UPDATE/DELETE: restituisce il numero di righe toccate. */
    fun update(sql: String, args: List<Any?> = emptyList()): Int
    fun <T> transaction(block: () -> T): T
}

fun Db.one(sql: String, args: List<Any?> = emptyList()): Row? = query(sql, args).firstOrNull()

fun Db.scalarLong(sql: String, args: List<Any?> = emptyList()): Long =
    (query(sql, args).firstOrNull()?.values?.firstOrNull() as? Number)?.toLong() ?: 0L

fun Row.long(k: String): Long? = (this[k] as? Number)?.toLong()
fun Row.int(k: String): Int? = (this[k] as? Number)?.toInt()
fun Row.double(k: String): Double? = (this[k] as? Number)?.toDouble()
fun Row.str(k: String): String? = this[k]?.let { if (it is ByteArray) String(it) else it.toString() }
fun Row.bool(k: String): Boolean = ((this[k] as? Number)?.toLong() ?: 0L) != 0L
fun Row.blob(k: String): ByteArray? = this[k] as? ByteArray

/**
 * «(1,2,3)» per una clausola IN: gli id sono numeri, quindi si scrivono nella query senza rischi,
 * e così non si supera il limite di 999 parametri di SQLite su Android 10.
 */
fun idList(ids: Collection<Long>): String = "(" + (if (ids.isEmpty()) "0" else ids.joinToString(",")) + ")"
