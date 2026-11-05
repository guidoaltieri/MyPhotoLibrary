package io.github.guidoaltieri.myphotolibrary.core

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Ricerca: linguaggio di query → SQL (porting di archphotos/search.py).
 *
 * Testo libero come in Foto di Apple: «mare luglio 2023», «roma Marco», «selfie».
 * Le parole riconosciute diventano filtri: anni (2023), mesi (luglio/july), tipi di media
 * (video, selfie, panoramiche, screenshot…), preferiti; il resto si cerca in nome del file,
 * didascalia, titolo, parole chiave, luogo, persone, album e fotocamera.
 *
 *     camera:iphone  lens:"24-70"           il campo contiene il valore
 *     year:2023  year:2019..2021            numeri, intervalli, confronti (> < >= <= != =)
 *     date:2024-07  date:2024-07-01..2024-07-15
 *     iso:>=1600  f:<2.8  focal:>85  mp:>20  size:>10 (MB)  duration:>1:00
 *     is:favorite is:video is:selfie is:live …   has:location has:caption has:faces has:keywords
 *     place:roma  person:anna  album:vacanze  keyword:mare  caption:compleanno  file:IMG_12
 *     orientation:landscape|portrait|square    added:<30d
 *     -is:screenshot   roma OR milano
 *
 * I campi di testo si confrontano con colonne già normalizzate (f_*, minuscole e senza accenti):
 * SQLite di Android non permette funzioni personalizzate come fold().
 */
class QueryError(msg: String) : IllegalArgumentException(msg)

data class Compiled(val where: String, val params: List<Any?>) {
    val isEmpty get() = where.isEmpty()
}

object Search {
    val MONTHS_EN = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september",
        "october", "november", "december")

    const val PERSONS_SQL = "(SELECT GROUP_CONCAT(p.fname, ' ') FROM faces f JOIN persons p ON p.id = f.person_id WHERE f.media_id = media.id)"
    const val ALBUMS_SQL = "(SELECT GROUP_CONCAT(a.fname, ' ') FROM album_items ai JOIN albums a ON a.id = ai.album_id WHERE ai.media_id = media.id)"
    const val CAMERA_SQL = "COALESCE(f_camera,'')"

    data class Field(val expr: String, val kind: String, val label: String)

    /** nome → (espressione sql, tipo, etichetta). Le espressioni di testo sono già normalizzate. */
    val FIELDS: LinkedHashMap<String, Field> = linkedMapOf(
        "camera" to Field(CAMERA_SQL, "text", "Fotocamera"),
        "make" to Field(CAMERA_SQL, "text", "Produttore"),
        "model" to Field(CAMERA_SQL, "text", "Modello"),
        "lens" to Field("COALESCE(f_lens,'')", "text", "Obiettivo"),
        "place" to Field("COALESCE(f_place,'')", "text", "Luogo"),
        "person" to Field("COALESCE($PERSONS_SQL,'')", "text", "Persona"),
        "album" to Field("COALESCE($ALBUMS_SQL,'')", "text", "Album"),
        "keyword" to Field("COALESCE(f_keywords,'')", "text", "Parola chiave"),
        "caption" to Field("COALESCE(f_caption,'')", "text", "Didascalia"),
        "title" to Field("COALESCE(f_title,'')", "text", "Titolo"),
        "file" to Field("COALESCE(f_name,'')", "text", "Nome file"),
        "path" to Field("COALESCE(f_path,'')", "text", "Percorso"),
        "ext" to Field("COALESCE(ext,'')", "text", "Estensione"),
        "software" to Field("COALESCE(f_software,'')", "text", "Software"),
        "source" to Field("COALESCE((SELECT fname FROM sources s WHERE s.id = media.source_id),'')", "text", "Sorgente"),
        "year" to Field("CAST(strftime('%Y', taken, 'unixepoch', 'localtime') AS INTEGER)", "int", "Anno"),
        "month" to Field("CAST(strftime('%m', taken, 'unixepoch', 'localtime') AS INTEGER)", "int", "Mese (1-12)"),
        "day" to Field("CAST(strftime('%d', taken, 'unixepoch', 'localtime') AS INTEGER)", "int", "Giorno del mese"),
        "hour" to Field("CAST(strftime('%H', taken, 'unixepoch', 'localtime') AS INTEGER)", "int", "Ora"),
        "date" to Field("taken", "date", "Data dello scatto"),
        "added" to Field("added", "date", "Data di aggiunta"),
        "iso" to Field("iso", "int", "ISO"),
        "f" to Field("fnumber", "float", "Apertura (ƒ)"),
        "focal" to Field("COALESCE(focal35, focal)", "float", "Lunghezza focale (mm)"),
        "exposure" to Field("exposure", "float", "Esposizione (s)"),
        "width" to Field("width", "int", "Larghezza (px)"),
        "height" to Field("height", "int", "Altezza (px)"),
        "mp" to Field("(width * height / 1000000.0)", "float", "Megapixel"),
        "size" to Field("filesize", "size", "Dimensione (MB)"),
        "duration" to Field("duration", "time", "Durata"),
        "rating" to Field("rating", "int", "Valutazione (0-5)"),
        "is" to Field("", "flag", "Tipo"),
        "has" to Field("", "has", "Contiene"),
        "orientation" to Field("", "orient", "Orientamento"),
    )

    val ALIASES = mapOf(
        "fotocamera" to "camera", "macchina" to "camera", "produttore" to "make", "modello" to "model",
        "obiettivo" to "lens", "luogo" to "place", "posto" to "place", "città" to "place", "citta" to "place",
        "persona" to "person", "chi" to "person", "people" to "person", "parola" to "keyword", "tag" to "keyword",
        "parolachiave" to "keyword", "didascalia" to "caption", "descrizione" to "caption", "titolo" to "title",
        "nome" to "file", "filename" to "file", "name" to "file", "percorso" to "path", "estensione" to "ext",
        "sorgente" to "source", "anno" to "year", "mese" to "month", "giorno" to "day", "ora" to "hour", "data" to "date",
        "aggiunta" to "added", "aggiunto" to "added", "apertura" to "f", "aperture" to "f", "focale" to "focal",
        "esposizione" to "exposure", "larghezza" to "width", "altezza" to "height", "megapixel" to "mp",
        "dimensione" to "size", "durata" to "duration", "valutazione" to "rating", "stelle" to "rating",
        "tipo" to "is", "type" to "is", "è" to "is", "e" to "is", "ha" to "has", "con" to "has", "orientamento" to "orientation",
    )

    /** valori di is: → condizione SQL */
    val FLAG_SQL: Map<String, String> = buildMap {
        put("favorite", "favorite = 1"); put("video", "kind = 'video'"); put("photo", "kind = 'photo'")
        put("edited", "edits IS NOT NULL"); put("hidden", "hidden = 1"); put("geotagged", "lat IS NOT NULL")
        put("live", "(live_video IS NOT NULL OR instr(flags, 'live') > 0)")
        for (f in listOf("selfie", "panorama", "portrait", "screenshot", "raw", "animated", "slomo", "burst", "screenrec"))
            put(f, "instr(flags, '$f') > 0")
    }
    val FLAG_ALIASES = mapOf(
        "preferito" to "favorite", "preferita" to "favorite", "preferiti" to "favorite", "preferite" to "favorite",
        "fav" to "favorite", "favorites" to "favorite", "videos" to "video", "filmato" to "video", "filmati" to "video",
        "foto" to "photo", "photos" to "photo", "modificata" to "edited", "modificate" to "edited", "modificato" to "edited",
        "nascosta" to "hidden", "nascoste" to "hidden", "selfies" to "selfie", "panoramica" to "panorama",
        "panoramiche" to "panorama", "panoramas" to "panorama", "ritratto" to "portrait", "ritratti" to "portrait",
        "screenshots" to "screenshot", "schermata" to "screenshot", "schermate" to "screenshot", "livephoto" to "live",
        "animata" to "animated", "animate" to "animated", "gif" to "animated", "rallentatore" to "slomo", "slowmo" to "slomo",
        "raffica" to "burst", "registrazione" to "screenrec", "geolocalizzata" to "geotagged", "posizione" to "geotagged",
    )
    val HAS_SQL = mapOf(
        "location" to "lat IS NOT NULL", "caption" to "COALESCE(caption,'') != ''", "title" to "COALESCE(title,'') != ''",
        "keywords" to "COALESCE(keywords,'[]') != '[]'", "faces" to "EXISTS (SELECT 1 FROM faces f WHERE f.media_id = media.id)",
        "people" to "EXISTS (SELECT 1 FROM faces f WHERE f.media_id = media.id AND f.person_id IS NOT NULL)",
        "edits" to "edits IS NOT NULL",
    )
    val HAS_ALIASES = mapOf("posizione" to "location", "luogo" to "location", "gps" to "location", "didascalia" to "caption",
        "titolo" to "title", "parole" to "keywords", "tag" to "keywords", "volti" to "faces", "persone" to "people",
        "modifiche" to "edits")
    val ORIENT_SQL = mapOf("landscape" to "width > height", "portrait" to "height > width", "square" to "width = height")
    val ORIENT_ALIASES = mapOf("orizzontale" to "landscape", "verticale" to "portrait", "quadrata" to "square", "quadrato" to "square")

    /** parole del testo libero che diventano filtri */
    private val FREE_WORDS: Map<String, String> = buildMap {
        for ((w, v) in FLAG_ALIASES) FLAG_SQL[v]?.let { put(w, it) }
        for (w in listOf("video", "selfie", "panorama", "portrait", "screenshot", "raw", "live")) put(w, FLAG_SQL.getValue(w))
        remove("foto"); remove("posizione"); remove("registrazione")
    }

    val FREE_TEXT_SQL = "(COALESCE(f_name,'') || ' ' || COALESCE(f_caption,'') || ' ' || COALESCE(f_title,'') || ' ' " +
        "|| COALESCE(f_keywords,'') || ' ' || COALESCE(f_place,'') || ' ' || $CAMERA_SQL || ' ' " +
        "|| COALESCE(f_lens,'') || ' ' || COALESCE($PERSONS_SQL,'') || ' ' || COALESCE($ALBUMS_SQL,''))"

    val TEXT_OPS = listOf("contains" to "contiene", "not_contains" to "non contiene", "is" to "è", "is_not" to "non è",
        "starts" to "inizia con", "ends" to "finisce con")
    val NUM_OPS = listOf("eq" to "=", "ne" to "≠", "gt" to ">", "lt" to "<", "ge" to "≥", "le" to "≤", "between" to "tra")

    fun resolveField(name: String): String {
        val n = name.lowercase()
        val r = ALIASES[n] ?: n
        if (r !in FIELDS) throw QueryError("Campo sconosciuto: $name")
        return r
    }

    fun monthNumber(value: String): Int? {
        val v = fold(value)
        for (names in listOf(Fmt.MONTHS, MONTHS_EN)) {
            names.forEachIndexed { i, m ->
                val fm = fold(m)
                if (fm == v || (v.length >= 3 && fm.startsWith(v) && v != "mar" && v != "ma")) return i + 1
            }
        }
        return null
    }

    private fun startOf(d: LocalDate) = Fmt.ts(d.atStartOfDay())

    /** '2024', '2024-07', '2024-07-12', '12/07/2024', '30d' → intervallo [inizio, fine) in secondi. */
    fun parseDate(value: String, now: Double = System.currentTimeMillis() / 1000.0): Pair<Double, Double> {
        val v = value.trim()
        if (Regex("\\d+d").matches(v)) {
            val t = now - v.dropLast(1).toLong() * 86400
            return t to t
        }
        try {
            when {
                Regex("\\d{4}").matches(v) -> {
                    val y = v.toInt()
                    return startOf(LocalDate.of(y, 1, 1)) to startOf(LocalDate.of(y + 1, 1, 1))
                }
                Regex("\\d{4}-\\d{1,2}").matches(v) -> {
                    val (y, m) = v.split("-").map { it.toInt() }
                    val a = LocalDate.of(y, m, 1)
                    return startOf(a) to startOf(a.plusMonths(1))
                }
                Regex("\\d{4}-\\d{1,2}-\\d{1,2}").matches(v) -> {
                    val (y, m, d) = v.split("-").map { it.toInt() }
                    val a = LocalDate.of(y, m, d)
                    return startOf(a) to startOf(a.plusDays(1))
                }
                Regex("\\d{1,2}/\\d{1,2}/\\d{4}").matches(v) -> {
                    val (d, m, y) = v.split("/").map { it.toInt() }
                    val a = LocalDate.of(y, m, d)
                    return startOf(a) to startOf(a.plusDays(1))
                }
            }
        } catch (e: java.time.DateTimeException) {
            // sotto: errore
        }
        throw QueryError("Data non valida: '$value' (usa AAAA, AAAA-MM o AAAA-MM-GG)")
    }

    private fun parseScalar(kind: String, value: String): Any {
        val v = value.trim()
        try {
            return when (kind) {
                "int" -> {
                    val m = monthNumber(v)
                    if (m != null && !v.all { it.isDigit() }) m.toLong() else v.toDouble().toLong()
                }
                "float" -> v.replace(",", ".").trimStart('ƒ', 'f', '/').toDouble()
                "size" -> (v.replace(",", ".").toDouble() * 1024 * 1024).toLong()
                "time" -> {
                    var secs = 0.0
                    for (p in v.split(":")) secs = secs * 60 + p.toDouble()
                    secs
                }
                else -> v
            }
        } catch (e: NumberFormatException) {
            throw QueryError("Valore non valido: '$value'")
        }
    }

    /** Condizione SQL per un singolo criterio (usata anche dalla ricerca avanzata). */
    fun condition(fieldName: String, op: String, value: String, value2: String? = null): Pair<String, List<Any?>> {
        val field = resolveField(fieldName)
        val (expr, kind, _) = FIELDS.getValue(field)
        if (kind == "flag" || kind == "has" || kind == "orient") {
            val (table, aliases) = when (kind) {
                "flag" -> FLAG_SQL to FLAG_ALIASES
                "has" -> HAS_SQL to HAS_ALIASES
                else -> ORIENT_SQL to ORIENT_ALIASES
            }
            var v = fold(value)
            v = aliases[v] ?: v
            val sql = table[v] ?: throw QueryError("Valore sconosciuto per $field: $value (possibili: ${table.keys.sorted().joinToString(", ")})")
            return if (op in setOf("is_not", "ne", "not_contains")) "NOT ($sql)" to emptyList() else "($sql)" to emptyList()
        }
        if (kind == "text") {
            val v = fold(value)
            return when (op) {
                "contains" -> "$expr LIKE ? ESCAPE '\\'" to listOf("%${likeEscape(v)}%")
                "not_contains" -> "$expr NOT LIKE ? ESCAPE '\\'" to listOf("%${likeEscape(v)}%")
                "is" -> "$expr = ?" to listOf(v)
                "is_not" -> "$expr != ?" to listOf(v)
                "starts" -> "$expr LIKE ? ESCAPE '\\'" to listOf("${likeEscape(v)}%")
                "ends" -> "$expr LIKE ? ESCAPE '\\'" to listOf("%${likeEscape(v)}")
                else -> throw QueryError("Operatore non valido per testo: $op")
            }
        }
        if (kind == "date") {
            val (a0, a1) = parseDate(value)
            val relative = Regex("\\d+d").matches(value.trim())
            if (op == "between") {
                val (b0, b1) = parseDate(value2 ?: throw QueryError("Serve un secondo valore per «tra»"))
                return "$expr >= ? AND $expr < ?" to listOf(minOf(a0, b0), maxOf(a1, b1))
            }
            if (relative) {   // added:<30d = negli ultimi 30 giorni
                val sym = mapOf("lt" to ">", "le" to ">=", "gt" to "<", "ge" to "<=", "eq" to ">=", "ne" to "<")[op]
                return "$expr $sym ?" to listOf(a0)
            }
            return when (op) {
                "eq" -> "($expr >= ? AND $expr < ?)" to listOf(a0, a1)
                "ne" -> "NOT ($expr >= ? AND $expr < ?)" to listOf(a0, a1)
                "gt" -> "$expr >= ?" to listOf(a1)
                "ge" -> "$expr >= ?" to listOf(a0)
                "lt" -> "$expr < ?" to listOf(a0)
                "le" -> "$expr < ?" to listOf(a1)
                else -> throw QueryError("Operatore non valido per date: $op")
            }
        }
        val a = parseScalar(kind, value)
        val sym = mapOf("eq" to "=", "ne" to "!=", "gt" to ">", "lt" to "<", "ge" to ">=", "le" to "<=")
        if (kind == "time" && op == "eq") return "CAST($expr AS INTEGER) = ?" to listOf((a as Double).toLong())
        if (kind == "float" && op == "eq") return "ABS($expr - ?) < 0.05" to listOf(a)
        sym[op]?.let { return "$expr $it ?" to listOf(a) }
        if (op == "between") {
            val b = parseScalar(kind, value2 ?: throw QueryError("Serve un secondo valore per «tra»"))
            val (lo, hi) = if ((a as Number).toDouble() <= (b as Number).toDouble()) a to b else b to a
            return "$expr BETWEEN ? AND ?" to listOf(lo, hi)
        }
        throw QueryError("Operatore non valido per numeri: $op")
    }

    private val TOKEN = Regex("\\s*(-)?(?:([A-Za-z_àèéìòù]+):(>=|<=|!=|=|\\^|>|<)?)?(?:\"([^\"]*)\"|(\\S+))")

    private fun freeTerm(raw: String): Pair<String, List<Any?>> {
        val w = fold(raw)
        FREE_WORDS[w]?.let { return "($it)" to emptyList() }
        if (Regex("(19|20)\\d{2}").matches(w)) return condition("year", "eq", w)
        val m = if (w.length >= 4 && !w.all { it.isDigit() }) monthNumber(w) else null
        if (m != null) return "(${FIELDS.getValue("month").expr} = ? OR $FREE_TEXT_SQL LIKE ? ESCAPE '\\')" to listOf(m.toLong(), "%${likeEscape(w)}%")
        return "$FREE_TEXT_SQL LIKE ? ESCAPE '\\'" to listOf("%${likeEscape(w)}%")
    }

    private fun termToSql(field: String?, raw: String, quoted: Boolean): Pair<String, List<Any?>> {
        if (field == null) {
            return if (!quoted) freeTerm(raw) else "$FREE_TEXT_SQL LIKE ? ESCAPE '\\'" to listOf("%${likeEscape(fold(raw))}%")
        }
        val kind = FIELDS.getValue(resolveField(field)).kind
        if (kind == "flag" || kind == "has" || kind == "orient") return condition(field, "is", raw)
        if (kind == "text") {
            if (quoted) {
                if (raw.endsWith("$") && raw.length > 1) return condition(field, "ends", raw.dropLast(1))
                return condition(field, "contains", raw)
            }
            return when {
                raw.startsWith("!=") -> condition(field, "is_not", raw.substring(2))
                raw.startsWith("=") -> condition(field, "is", raw.substring(1))
                raw.startsWith("^") -> condition(field, "starts", raw.substring(1))
                raw.endsWith("$") && raw.length > 1 -> condition(field, "ends", raw.dropLast(1))
                else -> condition(field, "contains", raw)
            }
        }
        Regex("(.+?)\\.\\.(.+)").matchEntire(raw)?.let { return condition(field, "between", it.groupValues[1], it.groupValues[2]) }
        for ((prefix, op) in listOf(">=" to "ge", "<=" to "le", "!=" to "ne", ">" to "gt", "<" to "lt", "=" to "eq")) {
            if (raw.startsWith(prefix)) return condition(field, op, raw.substring(prefix.length))
        }
        return condition(field, "eq", raw)
    }

    /** Converte una query testuale in (clausola WHERE, parametri). */
    fun compile(query: String?): Compiled {
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return Compiled("", emptyList())
        val groups = mutableListOf(mutableListOf<Pair<String, List<Any?>>>())
        var pos = 0
        val matcher = TOKEN.toPattern().matcher(q)
        while (pos < q.length) {
            matcher.region(pos, q.length)
            if (!matcher.lookingAt() || matcher.end() == pos) break
            pos = matcher.end()
            val neg = matcher.group(1)
            var field: String? = matcher.group(2)
            val prefix = matcher.group(3)
            var quoted: String? = matcher.group(4)
            val bare = matcher.group(5)
            var raw = quoted ?: bare ?: continue
            if (prefix != null) {
                raw = prefix + raw
                quoted = null
            }
            if (field == null && quoted == null && raw in setOf("OR", "|", "o", "oppure")) {
                groups += mutableListOf<Pair<String, List<Any?>>>()
                continue
            }
            if (field != null && field.lowercase() !in FIELDS && field.lowercase() !in ALIASES) {
                raw = "$field:$raw"   // non è un campo: «xxx:yyy» è testo libero
                field = null
            }
            if (raw.isEmpty() && quoted == null) continue
            var (sql, params) = termToSql(field, raw, quoted != null)
            if (neg != null) sql = "NOT ($sql)"
            groups.last() += sql to params
        }
        val ors = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        for (g in groups) {
            if (g.isEmpty()) continue
            ors += "(" + g.joinToString(" AND ") { it.first } + ")"
            g.forEach { params.addAll(it.second) }
        }
        return Compiled(ors.joinToString(" OR "), params)
    }

    // --- regole (ricerca avanzata / album smart) → query testuale -------------------------
    data class Rule(val field: String, val op: String, val value: String, val value2: String = "")

    private fun quote(v: String) = if (v.isEmpty() || Regex("[\\s\"]").containsMatchIn(v)) "\"$v\"" else v

    fun ruleToQuery(r: Rule): String {
        val kind = FIELDS.getValue(r.field).kind
        if (kind == "flag" || kind == "has" || kind == "orient") return "${if (r.op == "is_not" || r.op == "ne") "-" else ""}${r.field}:${r.value}"
        if (kind == "text") return when (r.op) {
            "contains" -> "${r.field}:${quote(r.value)}"
            "not_contains" -> "-${r.field}:${quote(r.value)}"
            "is" -> "${r.field}:=${quote(r.value)}"
            "is_not" -> "${r.field}:!=${quote(r.value)}"
            "starts" -> "${r.field}:^${quote(r.value)}"
            else -> if (Regex("[\\s\"]").containsMatchIn(r.value)) "${r.field}:\"${r.value}$\"" else "${r.field}:${r.value}$"
        }
        if (r.op == "between") return "${r.field}:${r.value}..${r.value2}"
        val sym = mapOf("eq" to "", "ne" to "!=", "gt" to ">", "lt" to "<", "ge" to ">=", "le" to "<=")
        return "${r.field}:${sym[r.op]}${r.value}"
    }

    fun rulesToQuery(rules: List<Rule>, matchAll: Boolean = true): String =
        rules.filter { it.value.isNotEmpty() }.joinToString(if (matchAll) " " else " OR ") { ruleToQuery(it) }

    fun fieldOps(field: String): List<Pair<String, String>> {
        val kind = FIELDS.getValue(field).kind
        if (kind == "flag" || kind == "has" || kind == "orient") return listOf("is" to "è", "is_not" to "non è")
        return if (kind == "text") TEXT_OPS else NUM_OPS
    }

    fun fieldChoices(field: String): List<String> = when (FIELDS.getValue(field).kind) {
        "flag" -> FLAG_SQL.keys.sorted()
        "has" -> HAS_SQL.keys.sorted()
        "orient" -> ORIENT_SQL.keys.sorted()
        else -> emptyList()
    }
}
