package io.github.guidoaltieri.myphotolibrary.core

import java.time.LocalDate
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ricordi: raccolte create automaticamente, come in Foto (porting di memories.py).
 *
 * - «Un giorno come oggi»: le foto di questi giorni negli anni passati
 * - Viaggi: giorni consecutivi lontano da casa (casa = luogo più fotografato)
 * - Giornate speciali: un giorno con molte foto
 * - Il meglio dell'anno, dell'estate, del mese
 * - Persone nel tempo (volti con nome) e luoghi ricorrenti
 *
 * Ogni ricordo ha una chiave stabile: preferiti e ricordi nascosti si conservano tra un
 * calcolo e l'altro (tabella memory_state).
 */
data class Memory(
    val kind: String, val key: String, val title: String, val subtitle: String,
    val ids: List<Long>, val allIds: List<Long>, val cover: Item?, val start: Double, val end: Double,
    val favorite: Boolean, val count: Int,
)

object Memories {
    const val MAX_ITEMS = 60

    fun km(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val lat1 = Math.toRadians(a.first); val lon1 = Math.toRadians(a.second)
        val lat2 = Math.toRadians(b.first); val lon2 = Math.toRadians(b.second)
        val h = sin((lat2 - lat1) / 2).let { it * it } + cos(lat1) * cos(lat2) * sin((lon2 - lon1) / 2).let { it * it }
        return 6371 * 2 * asin(sqrt(h))
    }

    private fun day(ts: Double): LocalDate = Fmt.date(ts)
    private fun r1(v: Double) = round(v * 10) / 10

    private fun home(items: List<Item>): Pair<Double, Double>? =
        items.filter { it.lat != null }.groupingBy { r1(it.lat!!) to r1(it.lon!!) }.eachCount().maxByOrNull { it.value }?.key

    private fun memory(kind: String, key: String, title: String, subtitle: String, items: List<Item>, state: Map<String, MemoryState>): Memory {
        val bestItems = best(items.filter { "screenshot" !in it.flags }.ifEmpty { items }, MAX_ITEMS)
        val st = state[key]
        val cover = bestItems.firstOrNull { it.favorite } ?: bestItems.getOrNull(bestItems.size / 2)
        return Memory(kind, key, st?.title?.takeIf { it.isNotEmpty() } ?: title, subtitle, bestItems.map { it.id }, items.map { it.id },
            cover, items.first().taken, items.last().taken, st?.favorite == true, items.size)
    }

    private fun mostCommonPlace(items: List<Item>): String? =
        items.mapNotNull { it.place?.takeIf { p -> p.isNotEmpty() } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun generate(lib: Library, today: LocalDate = LocalDate.now(Fmt.zone)): List<Memory> {
        val state = lib.memoryState()
        val items = lib.db.query("SELECT ${Library.LIST_COLUMNS} FROM media WHERE ${Library.BASE} AND ${Library.ENABLED} AND taken_guess = 0 ORDER BY taken")
            .map(Library::toItem)
        if (items.isEmpty()) return emptyList()
        val out = mutableListOf<Memory>()
        val byDay = LinkedHashMap<LocalDate, MutableList<Item>>()
        for (i in items) byDay.getOrPut(day(i.taken)) { mutableListOf() } += i

        // --- un giorno come oggi (±3 giorni negli anni passati)
        val otd = mutableListOf<Item>()
        for ((d, its) in byDay) {
            if (d.year < today.year) {
                val same = try { LocalDate.of(d.year, today.monthValue, today.dayOfMonth) } catch (e: Exception) { LocalDate.of(d.year, today.monthValue, 28) }
                if (kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(same, d)) <= 3) otd += its
            }
        }
        if (otd.size >= 6) {
            otd.sortBy { it.taken }
            val years = otd.map { day(it.taken).year }.toSortedSet()
            val sub = if (years.size > 1) "${years.first()}–${years.last()}" else years.first().toString()
            out += memory("onthisday", "otd-%02d-%02d".format(today.monthValue, today.dayOfMonth), "Un giorno come oggi", sub, otd, state)
        }

        // --- viaggi: sequenze di giorni (buchi ≤ 1 giorno) lontano da casa
        val home = home(items)
        val trips = mutableListOf<List<Item>>()
        if (home != null) {
            val farDays = byDay.filter { (_, its) -> its.any { it.lat != null && km(home, it.lat to it.lon!!) > 100 } }.keys.sorted()
            var run = mutableListOf<LocalDate>()
            for (d in farDays + listOf<LocalDate?>(null)) {
                if (d != null && run.isNotEmpty() && java.time.temporal.ChronoUnit.DAYS.between(run.last(), d) <= 2) {
                    run += d; continue
                }
                if (run.isNotEmpty()) {
                    val its = run.flatMap { byDay.getValue(it) }
                    if (its.size >= 15) trips += its
                }
                run = if (d != null) mutableListOf(d) else mutableListOf()
            }
        }
        for (its in trips) {
            val name = mostCommonPlace(its) ?: ""
            val title = if (name.isNotEmpty()) "Viaggio a ${name.substringBefore(",")}" else "Viaggio"
            out += memory("trip", "trip-${day(its.first().taken)}", title, Fmt.dateRange(its.first().taken, its.last().taken), its, state)
        }

        // --- giornate speciali
        val tripDays = trips.flatten().map { day(it.taken) }.toSet()
        for ((d, its) in byDay) {
            if (its.size >= 30 && d !in tripDays) {
                val sub = mostCommonPlace(its)?.substringBefore(",") ?: Fmt.WEEKDAYS[d.dayOfWeek.value - 1].capitalizeFirst()
                out += memory("day", "day-$d", Fmt.day(its.first().taken), sub, its, state)
            }
        }

        // --- il meglio dell'anno / dell'estate / del mese
        val byYear = LinkedHashMap<Int, MutableList<Item>>()
        for (i in items) byYear.getOrPut(day(i.taken).year) { mutableListOf() } += i
        for ((y, its) in byYear) {
            if (its.size >= 50 && (y < today.year || today.monthValue == 12))
                out += memory("year", "year-$y", "Il meglio del $y", Fmt.items(its.count { !it.isVideo }.toLong(), its.count { it.isVideo }.toLong()), its, state)
            val summer = its.filter { day(it.taken).monthValue in 6..8 }
            if (summer.size >= 40 && (y < today.year || today.monthValue > 8))
                out += memory("season", "summer-$y", "Estate $y", "Giugno – agosto", summer, state)
        }
        val byMonth = LinkedHashMap<Pair<Int, Int>, MutableList<Item>>()
        for (i in items) day(i.taken).let { d -> byMonth.getOrPut(d.year to d.monthValue) { mutableListOf() } += i }
        val cut = (today.year - 2) * 12 + today.monthValue
        for ((ym, its) in byMonth) {
            val (y, m) = ym
            if (its.size >= 40 && y * 12 + m >= cut && !(y == today.year && m == today.monthValue))
                out += memory("month", "month-$y-%02d".format(m), "${Fmt.monthName(m).capitalizeFirst()} $y",
                    Fmt.items(its.count { !it.isVideo }.toLong(), its.count { it.isVideo }.toLong()), its, state)
        }

        // --- persone nel tempo
        for (p in lib.db.query("SELECT p.id, p.name, COUNT(DISTINCT f.media_id) n FROM persons p JOIN faces f ON f.person_id = p.id " +
                "WHERE p.name != '' AND p.hidden = 0 GROUP BY p.id HAVING n >= 20")) {
            val ids = lib.db.query("SELECT media_id FROM faces WHERE person_id=?", listOf(p.long("id"))).map { it.long("media_id") }.toSet()
            val its = items.filter { it.id in ids }
            if (its.size >= 20)
                out += memory("person", "person-${p.long("id")}", "${p.str("name")} nel tempo", Fmt.dateRange(its.first().taken, its.last().taken), its, state)
        }

        // --- luoghi ricorrenti (almeno tre visite)
        val byPlace = LinkedHashMap<String, MutableList<Item>>()
        for (i in items) i.place?.takeIf { it.isNotEmpty() }?.let { byPlace.getOrPut(it) { mutableListOf() } += i }
        for ((place, its) in byPlace) {
            val visits = its.map { day(it.taken) }.toSet().size
            val first = its.first()
            if (its.size >= 30 && visits >= 3 && (home == null || first.lat == null || km(home, first.lat to first.lon!!) > 30))
                out += memory("place", "place-$place", place.substringBefore(","), Fmt.dateRange(first.taken, its.last().taken), its, state)
        }

        val visible = out.filter { state[it.key]?.hidden != true }
        val rank = mapOf("onthisday" to 0, "trip" to 1, "day" to 2, "month" to 3, "season" to 4, "year" to 5, "person" to 6, "place" to 7)
        val recent = Library.now() - 120 * 86400
        return visible.sortedWith(compareBy<Memory>({ !it.favorite }, { it.kind != "onthisday" }, { it.end <= recent },
            { if (it.kind in setOf("trip", "day", "month")) -it.end else 0.0 }, { rank[it.kind] }, { -it.end }))
    }
}
