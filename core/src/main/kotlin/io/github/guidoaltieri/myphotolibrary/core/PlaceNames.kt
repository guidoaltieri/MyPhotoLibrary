package io.github.guidoaltieri.myphotolibrary.core

import java.io.InputStream
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * Luoghi nei nomi di cartelle e file: suggerisce una posizione alle foto senza GPS
 * (porting di placenames.py). Funziona senza rete con l'elenco GeoNames incluso
 * (gazetteer.tsv.gz, licenza CC BY 4.0) più regioni, zone note e paesi.
 */
data class Place(val name: String, val lat: Double, val lon: Double, val cc: String, val level: String,
                 val matched: String = "", val score: Double = 0.0, val evidence: String = "")

object PlaceNames {
    /** livello di precisione, dal più preciso */
    val LEVELS = listOf("città", "zona", "regione", "paese")

    private val PARTICLES = setOf("di", "da", "del", "della", "delle", "dei", "degli", "dello", "e", "ed", "a", "al", "alla", "in",
        "con", "per", "su", "tra", "fra", "il", "lo", "la", "le", "i", "gli", "un", "una", "and", "the", "of",
        "at", "to", "with", "en", "de", "du", "des", "et", "y", "el", "los", "las")
    private val OPENERS = PARTICLES - setOf("la", "le", "los", "las", "el", "il", "lo")

    /** abitanti minimi per accettare un nome di una sola parola: [cartella, nome del file] */
    private val NEAR = mapOf("SM" to 0, "VA" to 0, "CH" to 15000, "FR" to 15000, "AT" to 15000, "SI" to 15000, "HR" to 15000,
        "MT" to 15000, "ES" to 15000, "PT" to 15000, "DE" to 15000, "GR" to 15000, "MC" to 0)
    private val MIN_POP = mapOf(
        false to NEAR + mapOf("IT" to 0, "*" to 200000),
        true to NEAR.mapValues { maxOf(it.value, 50000) } + mapOf("IT" to 5000, "*" to 200000),
    )

    private const val PLAIN = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZàèéìòùÀÈÉÌÒÙ '.-"
    private val COMBINING = Regex("\\p{Mn}+")

    /** Normalizzazione dei nomi uguale a quella usata per generare il dizionario. */
    fun foldName(s: String): String {
        val n = COMBINING.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD), "")
        return n.replace("'", " ").replace("’", " ").replace(Regex("[\\s\\-]+"), " ").trim()
    }

    /** Parole di un nome di cartella o file, senza numeri, date ed estensione. */
    fun tokens(text: String): List<String> {
        var t = foldName(text)
        t = t.replace(Regex("(?<=[a-z])(?=\\d)|(?<=\\d)(?=[a-z])"), " ")   // «roma2023» → «roma 2023»
        return t.split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
    }

    /** «Barċellona» → «Barcellona»: toglie i segni diacritici fuori dall'alfabeto italiano. */
    private fun plain(name: String) = name.map { ch -> if (ch in PLAIN) ch else Normalizer.normalize(ch.toString(), Normalizer.Form.NFKD)[0] }.joinToString("")

    private fun isPlain(name: String) = name.all { it in PLAIN }

    private fun titleCase(s: String): String {
        val sb = StringBuilder()
        var prevLetter = false
        for (c in s) {
            sb.append(if (!prevLetter) c.uppercaseChar() else c)
            prevLetter = c.isLetter()
        }
        return sb.toString()
    }

    private fun isLowerPy(s: String) = s.any { it.isLetter() } && s.filter { it.isLetter() }.all { it.isLowerCase() }

    data class Entry(val disp: String, val lat: Double, val lon: Double, val pop: Int, val cc: String)

    @Volatile private var names: Map<String, List<Entry>>? = null

    /** Nomi di località → candidati (caricato al primo uso, ~100.000 nomi). */
    fun gazetteer(): Map<String, List<Entry>> {
        names?.let { return it }
        synchronized(this) {
            names?.let { return it }
            val map = HashMap<String, MutableList<Entry>>(200_000)
            val stream: InputStream? = PlaceNames::class.java.getResourceAsStream("/gazetteer.tsv.gz")
            stream?.use { s ->
                GZIPInputStream(s).bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    if (line.startsWith("#")) return@forEachLine
                    val f = line.split("\t")
                    if (f.size < 6) return@forEachLine
                    val key = f[0]
                    var disp = f[1]
                    if (isLowerPy(disp) || !isPlain(disp)) {
                        // accenti stranieri (spesso nomi in altre lingue): si usa la grafia cercata
                        disp = if (" " !in key || isLowerPy(disp)) titleCase(key) else plain(disp)
                    }
                    map.getOrPut(key) { mutableListOf() } += Entry(disp, f[2].toDouble(), f[3].toDouble(), f[4].toInt(), f[5])
                }
            }
            names = map
            return map
        }
    }

    private fun place(disp: String, lat: Double, lon: Double, cc: String, level: String, matched: String, score: Double): Place {
        val country = PlaceData.COUNTRIES[cc]?.name ?: ""
        val name = if (cc == "IT" || cc == "" || level == "paese" || country.isEmpty() || disp == country) disp else "$disp, $country"
        return Place(name, lat, lon, cc, level, matched, score)
    }

    private val COUNTRY_NAMES: Map<String, String> = PlaceData.COUNTRIES.flatMap { (cc, c) -> c.aliases.map { it to cc } }.toMap()

    /** Località citate in una sequenza di parole: le più lunghe prima (4 → 1 parole). */
    private fun candidates(words: List<String>, strict: Boolean): List<Place> {
        val gaz = gazetteer()
        val found = mutableListOf<Place>()
        val used = BooleanArray(words.size)
        for (n in minOf(4, words.size) downTo 1) {
            for (i in 0..words.size - n) {
                if ((i until i + n).any { used[it] }) continue
                val chunk = words.subList(i, i + n)
                if (n > 1 && (chunk[0] in OPENERS || chunk.last() in PARTICLES)) continue
                val key = chunk.joinToString(" ")
                val hits = mutableListOf<Place>()
                val area = PlaceData.AREAS[key]
                if (area != null) {
                    hits += place(area.name, area.lat, area.lon, area.cc, area.level, key, 1e7)
                } else if (key in COUNTRY_NAMES) {
                    val cc = COUNTRY_NAMES.getValue(key)
                    val c = PlaceData.COUNTRIES.getValue(cc)
                    hits += place(c.name, c.lat, c.lon, cc, "paese", key, 1e7)
                } else if (key in gaz) {
                    if (n == 1 && (key.length < 4 || key in PlaceData.COMMON || key in PARTICLES)) continue
                    for (e in gaz.getValue(key)) {
                        // una sola parola: le città lontane devono essere grandi, nel nome di un file anche quelle vicine
                        val min = MIN_POP.getValue(strict)
                        if (n == 1 && e.pop < (min[e.cc] ?: min.getValue("*"))) continue
                        hits += place(e.disp, e.lat, e.lon, e.cc, "città", key, e.pop.toDouble() * (if (e.cc == "IT") 3 else 1))
                    }
                }
                if (hits.isNotEmpty()) {
                    found += hits
                    for (k in i until i + n) used[k] = true
                }
            }
        }
        return found
    }

    /**
     * Località suggerita dal nome del file o delle cartelle che lo contengono, oppure null.
     * [relPath] parte dalla cartella radice della sorgente.
     */
    fun guess(relPath: String): Place? {
        val parts = dirname(relPath).split("/").filter { it.isNotEmpty() }.takeLast(4)
        // fonti dalla più vicina alla foto: nome del file, poi le cartelle risalendo
        val sources = mutableListOf(Triple("nome del file", tokens(stem(relPath)), true))
        for (p in parts.reversed()) sources += Triple("cartella «$p»", tokens(p), false)
        data class Found(val dist: Int, val evidence: String, val c: Place)
        val found = mutableListOf<Found>()
        sources.forEachIndexed { dist, (evidence, words, strict) -> candidates(words, strict).forEach { found += Found(dist, evidence, it) } }
        if (found.isEmpty()) return null
        // il paese (o la regione) citato in un'altra cartella scioglie le omonimie: «Spagna/Valencia»
        val context = found.filter { it.c.level != "città" && it.c.cc.isNotEmpty() }.map { it.c.cc }.toSet()
        val best = found.minWithOrNull(compareBy<Found>({ LEVELS.indexOf(it.c.level) },
            { !(context.isEmpty() || it.c.cc in context || it.c.level != "città") }, { it.dist }, { -it.c.score }))!!
        return best.c.copy(evidence = best.evidence)
    }

    data class PlaceGroup(val place: String, val lat: Double, val lon: Double, val level: String, val evidence: String,
                          val dir: String, val sourceId: Long, val items: List<Item>)

    /** Foto senza posizione con un luogo nel nome, raggruppate per cartella e luogo. */
    fun suggestions(lib: Library): List<PlaceGroup> {
        val roots = lib.sources().associate { it.id to (if (it.kind == "device") null else it.name) }
        val rows = lib.db.query("SELECT ${Library.LIST_COLUMNS} FROM media WHERE ${Library.BASE} AND ${Library.ENABLED} AND lat IS NULL " +
                "AND COALESCE(place_suggest_ignored, 0) = 0 ORDER BY relpath").map(Library::toItem)
        data class Key(val sid: Long, val dir: String, val name: String, val fromFile: Boolean)
        val groups = LinkedHashMap<Key, Pair<Place, MutableList<Item>>>()
        for (it in rows) {
            val rel = Dates.rootedPath(roots[it.sourceId], it.relpath)
            val g = guess(rel) ?: continue
            val dir = dirname(rel)
            groups.getOrPut(Key(it.sourceId, dir, g.name, g.evidence == "nome del file")) { g to mutableListOf() }.second += it
        }
        return groups.map { (k, v) -> PlaceGroup(v.first.name, v.first.lat, v.first.lon, v.first.level, v.first.evidence, k.dir, k.sid, v.second) }
            .sortedWith(compareBy({ it.dir }, { it.place }))
    }

    /** Località che iniziano con [query] (senza rete): zone e paesi, poi città dalla più grande. */
    fun search(query: String, limit: Int = 8): List<Place> {
        val q = foldName(query)
        if (q.length < 2) return emptyList()
        data class Hit(val weight: Double, val exact: Boolean, val curated: Boolean, val p: Place)
        val found = mutableListOf<Hit>()
        for ((key, a) in PlaceData.AREAS) if (key.startsWith(q)) found += Hit(3e6, key == q, true, place(a.name, a.lat, a.lon, a.cc, a.level, key, 0.0))
        for ((alias, cc) in COUNTRY_NAMES) if (alias.startsWith(q)) {
            val c = PlaceData.COUNTRIES.getValue(cc)
            found += Hit(3e6, alias == q, true, place(c.name, c.lat, c.lon, cc, "paese", alias, 0.0))
        }
        for ((key, entries) in gazetteer()) if (key.startsWith(q)) for (e in entries)
            found += Hit(e.pop.toDouble() * (if (e.cc == "IT") 3 else 1), key == q, false, place(e.disp, e.lat, e.lon, e.cc, "città", key, e.pop.toDouble()))
        // la stessa località ha molti nomi (Parigi, Parigge…): si raggruppa per posizione e si
        // mostra un nome solo, preferendo quelli scritti a mano e quelli italiani
        val groups = LinkedHashMap<Pair<Double, Double>, MutableList<Hit>>()
        for (h in found) groups.getOrPut(Math.round(h.p.lat * 10) / 10.0 to Math.round(h.p.lon * 10) / 10.0) { mutableListOf() } += h
        val ranked = groups.values.map { entries ->
            val bestHit = entries.minWithOrNull(compareBy<Hit>({ !it.curated }, { it.p.name.substringBefore(",") !in PlaceData.ITALIAN_NAMES },
                { !isPlain(it.p.name.substringBefore(",")) }, { it.p.name.substringBefore(",").length }, { it.p.name.substringBefore(",") }))!!
            val weight = entries.maxOf { it.weight } * (if (entries.any { it.exact }) 20 else 1)
            weight to bestHit.p
        }.sortedByDescending { it.first }
        return ranked.take(limit).map { it.second }
    }
}
