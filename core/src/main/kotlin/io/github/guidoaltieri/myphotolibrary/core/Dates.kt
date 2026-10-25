package io.github.guidoaltieri.myphotolibrary.core

import java.time.YearMonth

/**
 * Date nei nomi delle cartelle e suggerimenti di correzione (porting di dates.py).
 *
 * Se la data di una foto non corrisponde alla cartella in cui si trova (es. cartella 2024/05
 * ma data 2026, tipico di file copiati o scaricati di nuovo) si propone la data coerente con la
 * cartella, mantenendo giorno e ora quando possibile.
 */
object Dates {
    data class FolderDate(val y: Int, val m: Int? = null, val d: Int? = null, val folder: String = "")
    data class Component(val y: Int? = null, val m: Int? = null, val d: Int? = null) {
        val isEmpty get() = y == null && m == null && d == null
    }

    data class Suggestion(val ts: Double, val level: String, val folder: String, val fromName: Boolean)

    private val MONTH_WORDS: Map<String, Int> = buildMap {
        for (i in 0 until 12) {
            val it_ = Fmt.MONTHS[i]; val en = Search.MONTHS_EN[i]
            for (w in setOf(it_, en, it_.take(3), en.take(3))) put(w, i + 1)
        }
        put("sett", 9)
    }
    private const val YEAR = "(19[5-9]\\d|20\\d\\d)"

    private fun monthWord(text: String): Int? {
        for (m in Regex("[a-zà-ù]+").findAll(text.lowercase())) {
            val w = m.value
            if (w in MONTH_WORDS && (w.length > 3 || text.trim().length <= 4 || Regex("\\d").containsMatchIn(text))) return MONTH_WORDS[w]
        }
        return null
    }

    /** Data contenuta nel nome di UNA cartella. */
    fun parseComponent(name: String): Component {
        val s = name.trim()
        val low = s.lowercase()
        Regex("^$YEAR[-_. ]?(0[1-9]|1[0-2])[-_. ]?(0[1-9]|[12]\\d|3[01])(?!\\d)").find(s)?.let {
            return Component(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("^$YEAR[-_. ](0?[1-9]|1[0-2])(?!\\d)").find(s)?.let { return Component(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
        Regex("^(0[1-9]|[12]\\d|3[01])[-_. ](0[1-9]|1[0-2])[-_. ]$YEAR(?!\\d)").find(s)?.let {   // 12-05-2024
            return Component(it.groupValues[3].toInt(), it.groupValues[2].toInt(), it.groupValues[1].toInt())
        }
        Regex("^(0?[1-9]|1[0-2])[-_. ]$YEAR(?!\\d)").find(s)?.let { return Component(it.groupValues[2].toInt(), it.groupValues[1].toInt()) }  // 05-2024
        val year = Regex("(?<!\\d)$YEAR(?!\\d)").find(s)
        val month = monthWord(low)
        if (year != null && month != null) return Component(year.groupValues[1].toInt(), month)     // «Maggio 2024»
        if (Regex(YEAR).matches(s) || (year != null && year.range.first == 0 && !Regex("^\\d{5,}").containsMatchIn(s)))
            return Component(year!!.groupValues[1].toInt())                                          // «2024», «2024 Vacanze»
        Regex("^(0?[1-9]|1[0-2])(?:\\s*[-_. ]\\s*\\D.*)?$").find(s)?.let { return Component(m = it.groupValues[1].toInt()) }   // «05», «05 - Maggio»
        if (month != null && low.replace(Regex("[^a-zà-ù]"), "").length <= 10) return Component(m = month)   // «Maggio»
        return Component()
    }

    /**
     * Data indicata dalle cartelle che contengono il file (al massimo 4 livelli sopra).
     * [relPath] parte dalla cartella radice della sorgente («Foto/2024/05/a.jpg»).
     */
    fun parseFolderDate(relPath: String): FolderDate? {
        val parts = dirname(relPath).split("/").filter { it.isNotEmpty() }.takeLast(4)
        var y: Int? = null; var mo: Int? = null; var day: Int? = null
        var folderParts = emptyList<String>()
        parts.forEachIndexed { i, p ->
            val c = parseComponent(p)
            if (c.y != null) {
                y = c.y; mo = c.m; day = c.d
                folderParts = parts.subList(0, i + 1)
            } else if (y != null && mo == null && c.m != null) {
                mo = c.m
                folderParts = parts.subList(0, i + 1)
            } else if (y != null && mo != null && day == null) {
                Regex("^(0?[1-9]|[12]\\d|3[01])(?!\\d)").find(p)?.let {   // «2024/05/12»
                    day = it.groupValues[1].toInt()
                    folderParts = parts.subList(0, i + 1)
                }
            }
        }
        val yy = y ?: return null
        var m = mo
        var d = day
        if (m != null) {
            if (d != null && d!! > YearMonth.of(yy, m!!).lengthOfMonth()) d = null
        } else d = null
        return FolderDate(yy, m, d, folderParts.joinToString("/"))
    }

    private fun clampDay(y: Int, m: Int, d: Int) = minOf(d, YearMonth.of(y, m).lengthOfMonth())

    /** Data suggerita per una foto la cui data non corrisponde alla cartella, oppure null. */
    fun suggest(relPath: String, taken: Double?): Suggestion? {
        val fd = parseFolderDate(relPath) ?: return null
        if (taken == null || taken == 0.0) return null
        val t = Fmt.dt(taken)
        val (y, m, d) = Triple(fd.y, fd.m, fd.d)
        val level = when {
            d != null -> if (t.year == y && t.monthValue == m && t.dayOfMonth == d) return null else "giorno"
            m != null -> if (t.year == y && t.monthValue == m) return null else "mese"
            else -> if (t.year == y) return null else "anno"
        }
        // la data nel nome del file, se coerente con la cartella, è la più precisa
        MediaInfo.dateFromName(relPath)?.let { fn ->
            val f = Fmt.dt(fn)
            if (f.year == y && (m == null || f.monthValue == m) && (d == null || f.dayOfMonth == d)) return Suggestion(fn, level, fd.folder, true)
        }
        val nm = m ?: t.monthValue
        val nd = d ?: clampDay(y, nm, t.dayOfMonth)
        val new = t.withDayOfMonth(1).withYear(y).withMonth(nm).withDayOfMonth(nd)
        return Suggestion(Fmt.ts(new), level, fd.folder, false)
    }

    data class SuggestedItem(val item: Item, val suggested: Double, val level: String, val fromName: Boolean)
    data class SuggestionGroup(val folder: String, val dir: String, val sourceId: Long, val items: List<SuggestedItem>)

    /** Suggerimenti per tutta la fototeca, raggruppati per cartella. */
    fun suggestions(lib: Library): List<SuggestionGroup> {
        val roots = lib.sources().associate { it.id to (if (it.kind == "device") null else it.name) }
        val rows = lib.db.query("SELECT ${Library.LIST_COLUMNS}, taken_guess FROM media WHERE ${Library.BASE} AND ${Library.ENABLED} " +
                "AND COALESCE(suggest_ignored, 0) = 0 ORDER BY relpath").map(Library::toItem)
        val groups = LinkedHashMap<Pair<Long, String>, Pair<String, MutableList<SuggestedItem>>>()
        val cache = HashMap<String, FolderDate?>()
        for (it in rows) {
            val rel = rootedPath(roots[it.sourceId], it.relpath)
            val dir = dirname(rel)
            if (!cache.containsKey(dir)) cache[dir] = parseFolderDate(rel)
            if (cache[dir] == null) continue
            val s = suggest(rel, it.taken) ?: continue
            groups.getOrPut(it.sourceId to dir) { s.folder to mutableListOf() }.second += SuggestedItem(it, s.ts, s.level, s.fromName)
        }
        return groups.map { (k, v) -> SuggestionGroup(v.first, k.second, k.first, v.second) }.sortedBy { it.dir }
    }

    /** Percorso con la cartella radice della sorgente (che conta per data e luogo). */
    fun rootedPath(sourceName: String?, relpath: String): String =
        if (sourceName.isNullOrEmpty() || relpath.startsWith("$sourceName/")) relpath else "$sourceName/$relpath"

}
