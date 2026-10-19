package io.github.guidoaltieri.myphotolibrary.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** Elemento della fototeca come compare nelle griglie (colonne di [Library.LIST_COLUMNS]). */
data class Item(
    val id: Long,
    val sourceId: Long,
    val path: String,
    val name: String,
    val relpath: String,
    val kind: String,
    val mtime: Double?,
    val taken: Double,
    val added: Double?,
    val width: Int,
    val height: Int,
    val duration: Double?,
    val flags: String,
    val favorite: Boolean,
    val hidden: Boolean,
    val deleted: Double?,
    val edits: String?,
    val liveVideo: String?,
    val motionOffset: Long?,
    val filesize: Long,
    val orientation: Int?,
    val lat: Double?,
    val lon: Double?,
    val place: String?,
) {
    val isVideo get() = kind == "video"
    val isLive get() = liveVideo != null || "live" in flags
    fun hasFlag(f: String) = flags.split(",").contains(f)
}

/** Tutti i dati di un elemento (scheda Informazioni). */
data class ItemDetail(
    val item: Item,
    val row: Row,
    val exif: Map<String, String>,
    val keywords: List<String>,
    val sourceName: String,
    val sourceKind: String,
    val albums: List<Pair<Long, String>>,
    val persons: List<Pair<Long, String>>,
) {
    fun s(k: String) = row.str(k)?.takeIf { it.isNotEmpty() }
    fun d(k: String) = row.double(k)
    fun i(k: String) = row.int(k)
}

data class Source(val id: Long, val kind: String, val provider: String, val name: String, val path: String,
                  val enabled: Boolean, val lastScan: Double?, val pendingGone: Int)

data class Album(val id: Long, val name: String, val kind: String, val query: String, val parentId: Long?,
                 val coverId: Long?, val count: Int, val cover: Item?)

data class Group(val key: String, val start: Double, val end: Double, val photos: Int, val videos: Int,
                 val ids: List<Long>, val places: List<String>, val best: List<Item>) {
    val count get() = ids.size
}

data class PlaceSummary(val place: String, val count: Int, val lat: Double, val lon: Double, val coverId: Long, val ids: List<Long>)

data class Person(val id: Long, val name: String, val favorite: Boolean, val count: Int, val face: Face?)

data class Face(val id: Long, val mediaId: Long, val x: Double, val y: Double, val w: Double, val h: Double,
                val personId: Long?, val name: String, val path: String = "", val edits: String? = null)

data class ImportInfo(val id: Long, val started: Double, val origin: String, val count: Int, val items: List<Item>)

data class MemoryState(val key: String, val favorite: Boolean, val hidden: Boolean, val title: String?)

class Library(val db: Db) {
    companion object {
        /** colonne lette dalla scansione */
        val MEDIA_COLUMNS = listOf("kind", "mime", "filesize", "mtime", "width", "height", "orientation", "taken", "taken_guess",
            "make", "model", "lens", "software", "iso", "exposure", "fnumber", "focal", "focal35", "flash",
            "lat", "lon", "alt", "duration", "fps", "codec", "flags", "caption", "title", "keywords", "rating",
            "motion_offset", "exif")
        /** campi modificati dall'utente (non sovrascritti dalla scansione) */
        val USER_COLUMNS = setOf("taken", "taken_guess", "lat", "lon", "alt", "caption", "title", "keywords", "rating")
        const val LIST_COLUMNS = "id, source_id, path, name, relpath, kind, mtime, taken, added, width, height, duration, flags, " +
            "favorite, hidden, deleted, edits, live_video, motion_offset, filesize, orientation, lat, lon, place"

        const val BASE = "deleted IS NULL AND hidden = 0 AND paired = 0"
        const val ENABLED = "source_id IN (SELECT id FROM sources WHERE enabled = 1)"

        data class View(val label: String, val icon: String, val cond: String, val order: String)

        /** viste della barra laterale: chiave → (etichetta, icona, condizione, ordine) */
        val VIEWS: LinkedHashMap<String, View> = linkedMapOf(
            "library" to View("Libreria", "library", BASE, "taken"),
            "favorites" to View("Preferiti", "heart", "$BASE AND favorite = 1", "taken"),
            "recents" to View("Aggiunti di recente", "recent", BASE, "added DESC, taken DESC"),
            "videos" to View("Video", "video", "$BASE AND kind = 'video'", "taken"),
            "selfies" to View("Selfie", "selfie", "$BASE AND instr(flags, 'selfie') > 0", "taken"),
            "live" to View("Live Photo", "live", "$BASE AND (live_video IS NOT NULL OR instr(flags, 'live') > 0)", "taken"),
            "portrait" to View("Ritratti", "portrait", "$BASE AND instr(flags, 'portrait') > 0", "taken"),
            "panorama" to View("Panoramiche", "panorama", "$BASE AND instr(flags, 'panorama') > 0", "taken"),
            "slomo" to View("Rallentatore", "slomo", "$BASE AND instr(flags, 'slomo') > 0", "taken"),
            "burst" to View("Raffiche", "burst", "$BASE AND instr(flags, 'burst') > 0", "taken"),
            "animated" to View("Animate", "animated", "$BASE AND instr(flags, 'animated') > 0", "taken"),
            "screenshots" to View("Istantanee schermo", "screenshot", "$BASE AND instr(flags, 'screenshot') > 0", "taken"),
            "screenrec" to View("Registrazioni schermo", "screenrec", "$BASE AND instr(flags, 'screenrec') > 0", "taken"),
            "raw" to View("RAW", "raw", "$BASE AND instr(flags, 'raw') > 0", "taken"),
            "hidden" to View("Nascosti", "hidden", "deleted IS NULL AND hidden = 1 AND paired = 0", "taken"),
            "deleted" to View("Eliminati di recente", "trash", "deleted IS NOT NULL AND paired = 0", "deleted DESC"),
            "edited" to View("Modificate", "edit", "$BASE AND edits IS NOT NULL", "taken"),
            "geotagged" to View("Con posizione", "places", "$BASE AND lat IS NOT NULL", "taken"),
        )
        val MEDIA_TYPES = listOf("videos", "selfies", "live", "portrait", "panorama", "slomo", "burst", "animated",
            "screenshots", "screenrec", "raw")

        const val DELETED_KEEP_DAYS = 30

        val json = Json { ignoreUnknownKeys = true }

        fun now() = System.currentTimeMillis() / 1000.0

        fun toItem(r: Row) = Item(
            id = r.long("id")!!, sourceId = r.long("source_id") ?: 0, path = r.str("path")!!,
            name = r.str("name") ?: basename(r.str("path")!!), relpath = r.str("relpath") ?: "",
            kind = r.str("kind") ?: "photo", mtime = r.double("mtime"), taken = r.double("taken") ?: 0.0,
            added = r.double("added"), width = r.int("width") ?: 0, height = r.int("height") ?: 0,
            duration = r.double("duration"), flags = r.str("flags") ?: "", favorite = r.bool("favorite"),
            hidden = r.bool("hidden"), deleted = r.double("deleted"), edits = r.str("edits"),
            liveVideo = r.str("live_video"), motionOffset = r.long("motion_offset"), filesize = r.long("filesize") ?: 0,
            orientation = r.int("orientation"), lat = r.double("lat"), lon = r.double("lon"), place = r.str("place"),
        )

        fun keywordsJson(k: Collection<String>): String =
            JsonArray(k.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().map { JsonPrimitive(it) }).toString()

        fun parseKeywords(s: String?): List<String> = try {
            if (s.isNullOrEmpty()) emptyList() else (json.parseToJsonElement(s) as JsonArray).map { it.jsonPrimitive.content }
        } catch (e: Exception) {
            emptyList()
        }

        fun exifJson(m: Map<String, String>): String = JsonObject(m.mapValues { JsonPrimitive(it.value) }).toString()

        fun parseExif(s: String?): Map<String, String> = try {
            if (s.isNullOrEmpty()) emptyMap() else (json.parseToJsonElement(s) as JsonObject).mapValues {
                (it.value as? JsonPrimitive)?.content ?: it.value.toString()
            }
        } catch (e: Exception) {
            emptyMap()
        }

        /** Colonne normalizzate per la ricerca (SQLite di Android non ha fold()). */
        fun folded(name: String?, relpath: String?, caption: String?, title: String?, keywords: String?, place: String?,
                   make: String?, model: String?, lens: String?, software: String?): List<Any?> = listOf(
            fold(name), fold(relpath), fold(caption), fold(title), fold(keywords), fold(place),
            fold(listOf(make.orEmpty(), model.orEmpty()).joinToString(" ").trim()), fold(lens), fold(software))

        const val FOLDED_SET = "f_name=?, f_path=?, f_caption=?, f_title=?, f_keywords=?, f_place=?, f_camera=?, f_lens=?, f_software=?"
    }

    init {
        Schema.create(db)
    }

    // --- sorgenti -----------------------------------------------------------------------
    private fun toSource(r: Row) = Source(r.long("id")!!, r.str("kind")!!, r.str("provider") ?: "", r.str("name")!!,
        r.str("path") ?: "", r.bool("enabled"), r.double("last_scan"), r.int("pending_gone") ?: 0)

    fun sources(): List<Source> = db.query("SELECT * FROM sources ORDER BY kind, name").map(::toSource)
    fun source(id: Long): Source? = db.one("SELECT * FROM sources WHERE id=?", listOf(id))?.let(::toSource)

    fun addSource(kind: String, name: String, path: String, provider: String = ""): Long =
        db.insert("INSERT INTO sources(kind, provider, name, fname, path) VALUES (?,?,?,?,?)", listOf(kind, provider, name, fold(name), path))

    fun removeSource(id: Long) = db.transaction {
        db.exec("DELETE FROM media WHERE source_id=?", listOf(id))
        db.exec("DELETE FROM sources WHERE id=?", listOf(id))
    }

    fun setSourceEnabled(id: Long, enabled: Boolean) = db.exec("UPDATE sources SET enabled=? WHERE id=?", listOf(if (enabled) 1 else 0, id))
    fun renameSource(id: Long, name: String) = db.exec("UPDATE sources SET name=?, fname=? WHERE id=?", listOf(name, fold(name), id))

    fun sourceCounts(): Map<Long, Int> = db.query("SELECT source_id, COUNT(*) n FROM media WHERE deleted IS NULL AND paired = 0 GROUP BY source_id")
        .associate { it.long("source_id")!! to it.int("n")!! }

    // --- interrogazioni -------------------------------------------------------------------
    data class ViewSql(val where: String, val params: List<Any?>, val order: String)

    /** (WHERE, parametri, ORDER BY) di una vista con filtro di ricerca facoltativo. */
    fun viewSql(view: String, c: Compiled? = null, extra: String = "", extraParams: List<Any?> = emptyList()): ViewSql {
        val params = mutableListOf<Any?>()
        val clauses = mutableListOf<String>()
        var order: String
        when {
            view.startsWith("album:") -> {
                val aid = view.substringAfter(":").toLong()
                val a = db.one("SELECT kind, query FROM albums WHERE id=?", listOf(aid))
                if (a != null && a.str("kind") == "smart") {
                    val sc = try { Search.compile(a.str("query")) } catch (e: QueryError) { Compiled("0", emptyList()) }
                    clauses += BASE
                    if (!sc.isEmpty) clauses += "(${sc.where})"
                    params.addAll(sc.params)
                    order = "taken"
                } else {
                    clauses += BASE
                    clauses += "id IN (SELECT media_id FROM album_items WHERE album_id = ?)"
                    params += aid
                    order = "(SELECT pos FROM album_items WHERE album_id = $aid AND media_id = media.id), taken"
                }
            }
            view.startsWith("person:") -> {
                clauses += BASE; clauses += "id IN (SELECT media_id FROM faces WHERE person_id = ?)"
                params += view.substringAfter(":").toLong(); order = "taken"
            }
            view.startsWith("source:") -> {
                clauses += "deleted IS NULL AND paired = 0"; clauses += "source_id = ?"
                params += view.substringAfter(":").toLong(); order = "taken"
            }
            view.startsWith("import:") -> {
                clauses += BASE; clauses += "import_id = ?"
                params += view.substringAfter(":").toLong(); order = "taken"
            }
            view.startsWith("ids:") -> {
                val ids = view.substring(4).split(",").filter { it.isNotEmpty() }.map { it.toLong() }
                val (q, p) = inClause(ids)
                clauses += "deleted IS NULL AND paired = 0"; clauses += "id IN $q"
                params.addAll(p); order = "taken"
            }
            else -> {
                val v = VIEWS[view] ?: VIEWS.getValue("library")
                clauses += v.cond; order = v.order
            }
        }
        if (!view.startsWith("source:")) clauses += ENABLED
        if (extra.isNotEmpty()) {
            clauses += "($extra)"; params.addAll(extraParams)
        }
        if (c != null && !c.isEmpty) {
            clauses += "(${c.where})"; params.addAll(c.params)
        }
        return ViewSql(" WHERE " + clauses.joinToString(" AND "), params, order)
    }

    fun media(view: String = "library", c: Compiled? = null, extra: String = "", extraParams: List<Any?> = emptyList(),
              order: String? = null, limit: Int? = null): List<Item> {
        val v = viewSql(view, c, extra, extraParams)
        val lim = if (limit != null) " LIMIT $limit" else ""
        return db.query("SELECT $LIST_COLUMNS FROM media${v.where} ORDER BY ${order ?: v.order}, id$lim", v.params).map(::toItem)
    }

    fun count(view: String = "library", c: Compiled? = null): Pair<Int, Int> {
        val v = viewSql(view, c)
        val r = db.one("SELECT SUM(kind='photo') p, SUM(kind='video') v FROM media${v.where}", v.params)
        return (r?.int("p") ?: 0) to (r?.int("v") ?: 0)
    }

    /** Conteggi di tutte le viste della barra laterale in una sola lettura della tabella. */
    fun viewCounts(): Map<String, Int> {
        val keys = VIEWS.keys.toList()
        val sums = keys.mapIndexed { i, k -> "SUM(CASE WHEN ${VIEWS.getValue(k).cond} THEN 1 ELSE 0 END) c$i" }.joinToString(", ")
        val row = db.one("SELECT $sums FROM media WHERE $ENABLED") ?: return emptyMap()
        return keys.mapIndexed { i, k -> k to (row.int("c$i") ?: 0) }.toMap()
    }

    fun item(id: Long): Item? = db.one("SELECT $LIST_COLUMNS FROM media WHERE id=?", listOf(id))?.let(::toItem)

    fun itemByPath(path: String): Item? = db.one("SELECT $LIST_COLUMNS FROM media WHERE path=?", listOf(path))?.let(::toItem)

    fun detail(id: Long): ItemDetail? {
        val r = db.one("SELECT * FROM media WHERE id=?", listOf(id)) ?: return null
        val src = r.long("source_id")?.let { source(it) }
        return ItemDetail(
            item = toItem(r), row = r, exif = parseExif(r.str("exif")), keywords = parseKeywords(r.str("keywords")),
            sourceName = src?.name ?: "", sourceKind = src?.kind ?: "",
            albums = db.query("SELECT a.id, a.name FROM albums a JOIN album_items ai ON ai.album_id = a.id WHERE ai.media_id = ? ORDER BY a.fname", listOf(id))
                .map { it.long("id")!! to it.str("name")!! },
            persons = db.query("SELECT DISTINCT p.id, p.name FROM persons p JOIN faces f ON f.person_id = p.id WHERE f.media_id = ? AND p.name != '' ORDER BY p.fname", listOf(id))
                .map { it.long("id")!! to it.str("name")!! },
        )
    }

    // --- raggruppamenti per Anni / Mesi / Giorni ----------------------------------------
    /**
     * Gruppi per 'year', 'month' o 'day' con conteggi e foto rappresentative: le rappresentative
     * privilegiano preferite e foto modificate, poi la distribuzione nel periodo.
     */
    fun groups(level: String, view: String = "library", c: Compiled? = null, perGroup: Int = 9): List<Group> {
        val fmt = mapOf("year" to "%Y", "month" to "%Y-%m", "day" to "%Y-%m-%d").getValue(level)
        val v = viewSql(view, c)
        val rows = db.query("SELECT strftime('$fmt', taken, 'unixepoch', 'localtime') AS g, $LIST_COLUMNS FROM media${v.where} ORDER BY taken, id", v.params)
        val groups = LinkedHashMap<String, MutableList<Item>>()
        for (r in rows) groups.getOrPut(r.str("g") ?: "") { mutableListOf() } += toItem(r)
        return groups.map { (key, items) ->
            val places = items.mapNotNull { it.place?.takeIf { p -> p.isNotEmpty() } }.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }.take(3).map { it.key }
            Group(key, items.first().taken, items.last().taken, items.count { !it.isVideo }, items.count { it.isVideo },
                items.map { it.id }, places, best(items, perGroup))
        }
    }

    // --- azioni sugli elementi ------------------------------------------------------------
    fun setFavorite(ids: Collection<Long>, on: Boolean) {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET favorite=? WHERE id IN $q", listOf<Any?>(if (on) 1 else 0) + p)
    }

    fun toggleFavorite(ids: Collection<Long>): Boolean {
        val (q, p) = inClause(ids)
        val on = !db.query("SELECT favorite FROM media WHERE id IN $q", p).all { it.bool("favorite") }
        setFavorite(ids, on)
        return on
    }

    fun setHidden(ids: Collection<Long>, on: Boolean) {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET hidden=? WHERE id IN $q", listOf<Any?>(if (on) 1 else 0) + p)
    }

    /** Sposta in «Eliminati di recente» (i file non vengono toccati). */
    fun delete(ids: Collection<Long>) {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET deleted=? WHERE id IN $q", listOf<Any?>(now()) + p)
    }

    fun restore(ids: Collection<Long>) {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET deleted=NULL WHERE id IN $q", p)
    }

    data class Purged(val id: Long, val path: String, val liveVideo: String?, val sourceKind: String)

    /**
     * Elimina definitivamente dalla fototeca. Restituisce gli elementi per eliminare i file;
     * restano nel database come «paired=2» finché la scansione non li vede sparire.
     */
    fun purge(ids: Collection<Long>): List<Purged> {
        if (ids.isEmpty()) return emptyList()
        val (q, p) = inClause(ids)
        val rows = db.query("SELECT m.id, m.path, m.live_video, s.kind AS source_kind FROM media m JOIN sources s ON s.id = m.source_id WHERE m.id IN $q", p)
            .map { Purged(it.long("id")!!, it.str("path")!!, it.str("live_video"), it.str("source_kind") ?: "") }
        db.transaction {
            db.exec("UPDATE media SET paired = 2 WHERE id IN $q", p)
            db.exec("DELETE FROM album_items WHERE media_id IN $q", p)
            db.exec("DELETE FROM faces WHERE media_id IN $q", p)
        }
        return rows
    }

    /** Il file è stato eliminato davvero: la riga può sparire. */
    fun forget(ids: Collection<Long>) {
        val (q, p) = inClause(ids)
        db.exec("DELETE FROM media WHERE id IN $q", p)
    }

    fun expiredDeleted(days: Int = DELETED_KEEP_DAYS): List<Long> =
        db.query("SELECT id FROM media WHERE deleted IS NOT NULL AND deleted < ? AND paired = 0", listOf(now() - days * 86400.0)).map { it.long("id")!! }

    private fun userMark(ids: Collection<Long>, vararg cols: String) {
        val (q, p) = inClause(ids)
        for (c in cols) db.exec("UPDATE media SET user = user || ? || ',' WHERE id IN $q AND instr(user, ?) = 0", listOf<Any?>(c) + p + listOf(",$c,"))
    }

    /** Ricalcola le colonne normalizzate per la ricerca. */
    fun refold(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        for (chunk in ids.chunked(500)) {
            val (q, p) = inClause(chunk)
            for (r in db.query("SELECT id, name, relpath, caption, title, keywords, place, make, model, lens, software FROM media WHERE id IN $q", p)) {
                db.exec("UPDATE media SET $FOLDED_SET WHERE id=?", folded(r.str("name"), r.str("relpath"), r.str("caption"),
                    r.str("title"), r.str("keywords"), r.str("place"), r.str("make"), r.str("model"), r.str("lens"), r.str("software")) + listOf(r.long("id")))
            }
        }
    }

    fun setCaption(id: Long, caption: String) = db.transaction {
        db.exec("UPDATE media SET caption=? WHERE id=?", listOf(caption, id)); userMark(listOf(id), "caption"); refold(listOf(id))
    }

    fun setTitle(id: Long, title: String) = db.transaction {
        db.exec("UPDATE media SET title=? WHERE id=?", listOf(title, id)); userMark(listOf(id), "title"); refold(listOf(id))
    }

    fun setRating(id: Long, rating: Int) = db.transaction {
        db.exec("UPDATE media SET rating=? WHERE id=?", listOf(rating, id)); userMark(listOf(id), "rating")
    }

    fun setKeywords(ids: Collection<Long>, keywords: List<String>) = db.transaction {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET keywords=? WHERE id IN $q", listOf<Any?>(keywordsJson(keywords)) + p)
        userMark(ids, "keywords"); refold(ids)
    }

    fun allKeywords(): List<String> {
        val out = sortedSetOf<String>(String.CASE_INSENSITIVE_ORDER)
        for (r in db.query("SELECT DISTINCT keywords FROM media WHERE keywords != '[]'")) out += parseKeywords(r.str("keywords"))
        return out.toList()
    }

    /** Modifica data e ora: valore assoluto o spostamento (in secondi) di tutte le foto. */
    fun setTaken(ids: Collection<Long>, ts: Double? = null, shift: Double? = null) = db.transaction {
        val (q, p) = inClause(ids)
        if (shift != null) db.exec("UPDATE media SET taken = taken + ?, taken_guess = 0 WHERE id IN $q", listOf<Any?>(shift) + p)
        else db.exec("UPDATE media SET taken = ?, taken_guess = 0 WHERE id IN $q", listOf<Any?>(ts) + p)
        userMark(ids, "taken", "taken_guess")
    }

    /** Date diverse per più elementi (suggerimenti accettati). */
    fun setTakenMany(values: List<Pair<Long, Double>>) = db.transaction {
        for ((id, ts) in values) db.exec("UPDATE media SET taken = ?, taken_guess = 0, suggest_ignored = 0 WHERE id = ?", listOf(ts, id))
        values.map { it.first }.chunked(500).forEach { userMark(it, "taken", "taken_guess") }
    }

    fun ignoreSuggestions(ids: Collection<Long>, on: Boolean = true) {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET suggest_ignored = ? WHERE id IN $q", listOf<Any?>(if (on) 1 else 0) + p)
    }

    data class Located(val id: Long, val lat: Double, val lon: Double, val place: String?)

    /** Posizioni diverse per più elementi (suggerimenti di luogo accettati). */
    fun setLocationMany(values: List<Located>) = db.transaction {
        for (v in values) db.exec("UPDATE media SET lat=?, lon=?, alt=NULL, place=?, place_suggest_ignored=0 WHERE id=?", listOf(v.lat, v.lon, v.place, v.id))
        val ids = values.map { it.id }
        ids.chunked(500).forEach { userMark(it, "lat", "lon", "alt") }
        refold(ids)
    }

    fun ignorePlaceSuggestions(ids: Collection<Long>, on: Boolean = true) {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET place_suggest_ignored = ? WHERE id IN $q", listOf<Any?>(if (on) 1 else 0) + p)
    }

    fun setLocation(ids: Collection<Long>, lat: Double?, lon: Double?, place: String? = null) = db.transaction {
        val (q, p) = inClause(ids)
        db.exec("UPDATE media SET lat=?, lon=?, alt=NULL, place=? WHERE id IN $q", listOf<Any?>(lat, lon, place) + p)
        userMark(ids, "lat", "lon", "alt"); refold(ids)
    }

    fun setEdits(id: Long, recipe: String?) {
        if (recipe != null) db.exec("UPDATE media SET edits=?, edited=? WHERE id=?", listOf(recipe, now(), id))
        else db.exec("UPDATE media SET edits=NULL, edited=NULL WHERE id=?", listOf(id))
    }

    fun setDhash(id: Long, h: Long) = db.exec("UPDATE media SET dhash=? WHERE id=?", listOf(h, id))

    fun missingHashes(limit: Int = 100000): List<Item> {
        val v = viewSql("library")
        return db.query("SELECT $LIST_COLUMNS FROM media${v.where} AND dhash IS NULL ORDER BY taken DESC LIMIT ?", v.params + limit).map(::toItem)
    }

    // --- album ---------------------------------------------------------------------------
    fun albums(parent: Long? = null, allLevels: Boolean = false): List<Album> {
        val rows = when {
            allLevels -> db.query("SELECT * FROM albums ORDER BY kind = 'folder' DESC, fname")
            parent == null -> db.query("SELECT * FROM albums WHERE parent_id IS NULL ORDER BY kind = 'folder' DESC, fname")
            else -> db.query("SELECT * FROM albums WHERE parent_id = ? ORDER BY kind = 'folder' DESC, fname", listOf(parent))
        }
        return rows.map { a ->
            val id = a.long("id")!!
            val kind = a.str("kind") ?: "album"
            var count: Int
            var cover: Item? = null
            if (kind == "folder") {
                count = db.scalarLong("SELECT COUNT(*) FROM albums WHERE parent_id=?", listOf(id)).toInt()
            } else {
                val v = viewSql("album:$id")
                count = db.scalarLong("SELECT COUNT(*) FROM media${v.where}", v.params).toInt()
                a.long("cover_id")?.let { cid -> cover = db.one("SELECT $LIST_COLUMNS FROM media WHERE id=? AND deleted IS NULL", listOf(cid))?.let(::toItem) }
                if (cover == null) cover = db.one("SELECT $LIST_COLUMNS FROM media${v.where} ORDER BY taken DESC LIMIT 1", v.params)?.let(::toItem)
            }
            Album(id, a.str("name")!!, kind, a.str("query") ?: "", a.long("parent_id"), a.long("cover_id"), count, cover)
        }
    }

    fun album(id: Long): Album? = db.one("SELECT * FROM albums WHERE id=?", listOf(id))?.let {
        Album(id, it.str("name")!!, it.str("kind") ?: "album", it.str("query") ?: "", it.long("parent_id"), it.long("cover_id"), 0, null)
    }

    fun createAlbum(name: String, kind: String = "album", query: String = "", parent: Long? = null): Long =
        db.insert("INSERT INTO albums(name, fname, kind, query, parent_id, created) VALUES (?,?,?,?,?,?)", listOf(name, fold(name), kind, query, parent, now()))

    fun renameAlbum(id: Long, name: String) = db.exec("UPDATE albums SET name=?, fname=? WHERE id=?", listOf(name, fold(name), id))
    fun updateSmartAlbum(id: Long, query: String) = db.exec("UPDATE albums SET query=? WHERE id=?", listOf(query, id))
    fun moveAlbum(id: Long, parent: Long?) = db.exec("UPDATE albums SET parent_id=? WHERE id=?", listOf(parent, id))

    fun deleteAlbum(id: Long): Unit = db.transaction {
        // ON DELETE CASCADE richiede foreign_keys attive: lo facciamo comunque a mano
        for (child in db.query("SELECT id FROM albums WHERE parent_id=?", listOf(id))) deleteAlbum(child.long("id")!!)
        db.exec("DELETE FROM album_items WHERE album_id=?", listOf(id))
        db.exec("DELETE FROM albums WHERE id=?", listOf(id))
    }

    fun addToAlbum(id: Long, ids: Collection<Long>): Int = db.transaction {
        val top = (db.one("SELECT COALESCE(MAX(pos), 0) m FROM album_items WHERE album_id=?", listOf(id))?.double("m") ?: 0.0)
        var n = 0
        ids.forEachIndexed { i, mid ->
            val exists = db.one("SELECT 1 x FROM album_items WHERE album_id=? AND media_id=?", listOf(id, mid)) != null
            if (!exists) {
                db.exec("INSERT INTO album_items(album_id, media_id, pos) VALUES (?,?,?)", listOf(id, mid, top + i + 1))
                n++
            }
        }
        n
    }

    fun removeFromAlbum(id: Long, ids: Collection<Long>) {
        val (q, p) = inClause(ids)
        db.exec("DELETE FROM album_items WHERE album_id=? AND media_id IN $q", listOf<Any?>(id) + p)
    }

    fun reorderAlbum(id: Long, ordered: List<Long>) = db.transaction {
        ordered.forEachIndexed { i, mid -> db.exec("UPDATE album_items SET pos=? WHERE album_id=? AND media_id=?", listOf(i, id, mid)) }
    }

    fun setAlbumCover(id: Long, mid: Long) = db.exec("UPDATE albums SET cover_id=? WHERE id=?", listOf(mid, id))

    // --- importazioni, duplicati ----------------------------------------------------------
    fun imports(): List<ImportInfo> = db.query("SELECT * FROM imports ORDER BY started DESC").mapNotNull { r ->
        val id = r.long("id")!!
        val count = db.scalarLong("SELECT COUNT(*) FROM media WHERE import_id=? AND deleted IS NULL", listOf(id)).toInt()
        if (count == 0) null else ImportInfo(id, r.double("started") ?: 0.0, r.str("origin") ?: "", count, media("import:$id", limit = 12))
    }

    fun newImport(origin: String): Long = db.insert("INSERT INTO imports(started, origin) VALUES (?, ?)", listOf(now(), origin))

    fun setImport(paths: Collection<String>, importId: Long) = db.transaction {
        for (p in paths) db.exec("UPDATE media SET import_id=? WHERE path=?", listOf(importId, p))
    }

    /** Gruppi di duplicati: stessa immagine (hash percettivo) o stesso file (dimensione + data). */
    fun duplicates(): List<List<Item>> {
        val v = viewSql("library")
        val rows = db.query("SELECT $LIST_COLUMNS, dhash FROM media${v.where} AND (dhash IS NOT NULL) ORDER BY dhash, taken", v.params)
        val byHash = LinkedHashMap<Pair<Long, String>, MutableList<Item>>()
        for (r in rows) {
            val h = r.long("dhash")!!
            if (h == 0L || h == -1L) continue   // immagini uniformi (nere/bianche): non sono duplicati
            val it = toItem(r)
            byHash.getOrPut(h to it.kind) { mutableListOf() } += it
        }
        // stesso hash percettivo: duplicati solo se anche proporzioni e data dello scatto coincidono
        val refined = mutableListOf<List<Item>>()
        for (g0 in byHash.values) {
            var g: List<Item> = g0
            while (g.isNotEmpty()) {
                val first = g[0]
                val same = listOf(first) + g.drop(1).filter { sameShot(first, it) }
                refined += same
                val ids = same.map { it.id }.toSet()
                g = g.drop(1).filter { it.id !in ids }
            }
        }
        val sameFile = LinkedHashMap<Triple<Long, Long, String>, MutableList<Item>>()
        for (r in db.query("SELECT $LIST_COLUMNS FROM media${v.where}", v.params)) {
            val it = toItem(r)
            sameFile.getOrPut(Triple(it.filesize, it.taken.toLong(), it.kind)) { mutableListOf() } += it
        }
        val seen = mutableSetOf<Long>()
        val out = mutableListOf<List<Item>>()
        for (g0 in refined + sameFile.values) {
            val g = g0.filter { it.id !in seen }
            if (g.size > 1) {
                seen += g.map { it.id }
                out += g.sortedByDescending { it.width.toLong() * it.height }
            }
        }
        return out.sortedByDescending { it[0].taken }
    }

    /**
     * Tiene la versione migliore (risoluzione e dimensione maggiori), sposta le altre negli
     * eliminati e ne riporta preferito, album e parole chiave sulla versione tenuta.
     */
    fun mergeDuplicates(groupIds: List<Long>): Int {
        val (q, p) = inClause(groupIds)
        val rows = db.query("SELECT * FROM media WHERE id IN $q", p)
        if (rows.size < 2) return 0
        val sorted = rows.sortedWith(compareBy({ -(it.long("width") ?: 0) * (it.long("height") ?: 0) }, { -(it.long("filesize") ?: 0) }))
        val keep = sorted[0]
        val keepId = keep.long("id")!!
        val others = sorted.drop(1)
        val oid = others.map { it.long("id")!! }
        if (others.any { it.bool("favorite") }) setFavorite(listOf(keepId), true)
        val (oq, op) = inClause(oid)
        for (a in db.query("SELECT DISTINCT album_id FROM album_items WHERE media_id IN $oq", op)) addToAlbum(a.long("album_id")!!, listOf(keepId))
        val kws = parseKeywords(keep.str("keywords")).toMutableSet()
        others.forEach { kws += parseKeywords(it.str("keywords")) }
        if (kws.isNotEmpty()) setKeywords(listOf(keepId), kws.toList())
        delete(oid)
        return oid.size
    }

    // --- luoghi -------------------------------------------------------------------------------
    fun geotagged(view: String = "library", c: Compiled? = null): List<Item> {
        val v = viewSql(view, c)
        return db.query("SELECT $LIST_COLUMNS FROM media${v.where} AND lat IS NOT NULL ORDER BY taken DESC", v.params).map(::toItem)
    }

    /**
     * Luoghi con il numero di elementi. Senza nome (nessuna rete o nomi disattivati) le foto
     * vengono raggruppate per zona di ~10 km e indicate con le coordinate.
     */
    fun placesSummary(): List<PlaceSummary> {
        val v = viewSql("library")
        return db.query("SELECT COALESCE(NULLIF(place, ''), printf('%.1f° %s %.1f° %s', abs(round(lat, 1)), " +
                "CASE WHEN lat >= 0 THEN 'N' ELSE 'S' END, abs(round(lon, 1)), CASE WHEN lon >= 0 THEN 'E' ELSE 'O' END)) " +
                "AS pname, COUNT(*) n, AVG(lat) alat, AVG(lon) alon, MAX(id) cover_id, GROUP_CONCAT(id) ids " +
                "FROM media${v.where} AND lat IS NOT NULL GROUP BY pname ORDER BY n DESC", v.params).map {
            PlaceSummary(it.str("pname")!!, it.int("n")!!, it.double("alat")!!, it.double("alon")!!, it.long("cover_id")!!,
                (it.str("ids") ?: "").split(",").filter { s -> s.isNotEmpty() }.map { s -> s.toLong() })
        }
    }

    // --- persone ------------------------------------------------------------------------------
    private fun toFace(r: Row) = Face(r.long("id")!!, r.long("media_id") ?: 0, r.double("x") ?: 0.0, r.double("y") ?: 0.0,
        r.double("w") ?: 0.0, r.double("h") ?: 0.0, r.long("person_id"), r.str("name") ?: "", r.str("path") ?: "", r.str("edits"))

    fun persons(includeUnnamed: Boolean = true, minFaces: Int = 3): List<Person> {
        val rows = db.query("SELECT p.*, COUNT(DISTINCT f.media_id) n FROM persons p JOIN faces f ON f.person_id = p.id " +
                "JOIN media m ON m.id = f.media_id WHERE m.deleted IS NULL AND m.hidden = 0 AND p.hidden = 0 " +
                "GROUP BY p.id ORDER BY p.favorite DESC, (p.name = '') ASC, n DESC")
        return rows.mapNotNull { r ->
            val name = r.str("name") ?: ""
            val n = r.int("n") ?: 0
            if (name.isEmpty() && (!includeUnnamed || n < minFaces)) return@mapNotNull null
            val id = r.long("id")!!
            var face = r.long("cover_face")?.let { cf ->
                db.one("SELECT f.*, m.path, m.edits FROM faces f JOIN media m ON m.id=f.media_id WHERE f.id=?", listOf(cf))?.let(::toFace)
            }
            if (face == null) face = db.one("SELECT f.*, m.path, m.edits FROM faces f JOIN media m ON m.id=f.media_id " +
                    "WHERE f.person_id=? AND m.deleted IS NULL ORDER BY f.score * f.w DESC LIMIT 1", listOf(id))?.let(::toFace)
            Person(id, name, r.bool("favorite"), n, face)
        }
    }

    fun person(id: Long): Person? = db.one("SELECT * FROM persons WHERE id=?", listOf(id))?.let {
        Person(id, it.str("name") ?: "", it.bool("favorite"), 0, null)
    }

    fun renamePerson(id: Long, name: String) {
        val other = db.one("SELECT id FROM persons WHERE name = ? AND id != ? AND name != ''", listOf(name, id))
        if (other != null) {   // stesso nome di una persona esistente: le uniamo, come fa Foto
            mergePersons(other.long("id")!!, listOf(id))
            return
        }
        db.exec("UPDATE persons SET name=?, fname=? WHERE id=?", listOf(name, fold(name), id))
    }

    fun mergePersons(into: Long, others: List<Long>) = db.transaction {
        val (q, p) = inClause(others)
        db.exec("UPDATE faces SET person_id=? WHERE person_id IN $q", listOf<Any?>(into) + p)
        db.exec("DELETE FROM persons WHERE id IN $q", p)
    }

    fun setPersonFavorite(id: Long, on: Boolean) = db.exec("UPDATE persons SET favorite=? WHERE id=?", listOf(if (on) 1 else 0, id))
    fun hidePerson(id: Long) = db.exec("UPDATE persons SET hidden=1 WHERE id=?", listOf(id))
    fun setPersonCover(id: Long, faceId: Long) = db.exec("UPDATE persons SET cover_face=? WHERE id=?", listOf(faceId, id))

    fun facesOf(mid: Long): List<Face> = db.query("SELECT f.id, f.media_id, f.x, f.y, f.w, f.h, f.person_id, COALESCE(p.name, '') AS name " +
            "FROM faces f LEFT JOIN persons p ON p.id = f.person_id WHERE f.media_id = ?", listOf(mid)).map(::toFace)

    /** Dà un nome a un volto: persona esistente con quel nome o nuova persona. */
    fun assignFace(faceId: Long, name: String): Long = db.transaction {
        val pid = db.one("SELECT id FROM persons WHERE name = ?", listOf(name))?.long("id")
            ?: db.insert("INSERT INTO persons(name, fname, created) VALUES (?, ?, ?)", listOf(name, fold(name), now()))
        db.exec("UPDATE faces SET person_id=?, manual=1 WHERE id=?", listOf(pid, faceId))
        pid
    }

    fun removeFromPerson(pid: Long, mediaIds: Collection<Long>) {
        val (q, p) = inClause(mediaIds)
        db.exec("UPDATE faces SET person_id=NULL, manual=1 WHERE person_id=? AND media_id IN $q", listOf<Any?>(pid) + p)
    }

    fun personNames(): List<String> = db.query("SELECT DISTINCT name FROM persons WHERE name != '' ORDER BY fname").map { it.str("name")!! }

    // --- ricordi ----------------------------------------------------------------------------
    fun memoryState(): Map<String, MemoryState> = db.query("SELECT * FROM memory_state").associate {
        it.str("key")!! to MemoryState(it.str("key")!!, it.bool("favorite"), it.bool("hidden"), it.str("title"))
    }

    fun setMemoryState(key: String, favorite: Boolean? = null, hidden: Boolean? = null, title: String? = null) = db.transaction {
        db.exec("INSERT OR IGNORE INTO memory_state(key) VALUES (?)", listOf(key))
        favorite?.let { db.exec("UPDATE memory_state SET favorite=? WHERE key=?", listOf(if (it) 1 else 0, key)) }
        hidden?.let { db.exec("UPDATE memory_state SET hidden=? WHERE key=?", listOf(if (it) 1 else 0, key)) }
        title?.let { db.exec("UPDATE memory_state SET title=? WHERE key=?", listOf(it, key)) }
    }

    // --- scansione ----------------------------------------------------------------------------
    /** Percorsi già noti di una sorgente: URI → (mtime, dimensione). */
    fun known(sourceId: Long): Map<String, Pair<Double?, Long?>> =
        db.query("SELECT path, mtime, filesize FROM media WHERE source_id=?", listOf(sourceId))
            .associate { it.str("path")!! to (it.double("mtime") to it.long("filesize")) }

    /**
     * Inserisce o aggiorna un file letto dalla scansione. I campi modificati dall'utente
     * (data, posizione, didascalia…) non vengono sovrascritti.
     */
    fun upsert(sourceId: Long, path: String, name: String, relpath: String, m: MediaMeta, token: String,
               importId: Long? = null): Long {
        val values: Map<String, Any?> = mapOf(
            "kind" to m.kind, "mime" to m.mime, "filesize" to m.filesize, "mtime" to m.mtime, "width" to m.width,
            "height" to m.height, "orientation" to m.orientation, "taken" to m.taken, "taken_guess" to if (m.takenGuess) 1 else 0,
            "make" to m.make, "model" to m.model, "lens" to m.lens, "software" to m.software, "iso" to m.iso,
            "exposure" to m.exposure, "fnumber" to m.fnumber, "focal" to m.focal, "focal35" to m.focal35, "flash" to m.flash,
            "lat" to m.lat, "lon" to m.lon, "alt" to m.alt, "duration" to m.duration, "fps" to m.fps, "codec" to m.codec,
            "flags" to m.flags.sorted().joinToString(","), "caption" to m.caption, "title" to m.title,
            "keywords" to keywordsJson(m.keywords), "rating" to m.rating, "motion_offset" to m.motionOffset,
            "exif" to exifJson(m.exif),
        )
        val ext = extension(name)
        val existing = db.one("SELECT id, user FROM media WHERE path=?", listOf(path))
        return if (existing == null) {
            val cols = listOf("source_id", "path", "name", "relpath", "ext") + MEDIA_COLUMNS + listOf("added", "import_id", "scan_seen")
            val args = listOf<Any?>(sourceId, path, name, relpath, ext) + MEDIA_COLUMNS.map { values[it] } + listOf(now(), importId, token)
            val id = db.insert("INSERT INTO media(${cols.joinToString(", ")}) VALUES (${cols.joinToString(",") { "?" }})", args)
            refold(listOf(id))
            id
        } else {
            val id = existing.long("id")!!
            val user = existing.str("user") ?: ","
            val sets = mutableListOf("source_id=?", "name=?", "relpath=?", "ext=?", "scan_seen=?", "dhash=NULL", "faces_done=0")
            val args = mutableListOf<Any?>(sourceId, name, relpath, ext, token)
            for (c in MEDIA_COLUMNS) {
                if (c in USER_COLUMNS && ",$c," in user) continue
                sets += "$c=?"; args += values[c]
            }
            db.exec("UPDATE media SET ${sets.joinToString(", ")} WHERE id=?", args + id)
            refold(listOf(id))
            id
        }
    }

    fun markSeen(paths: Collection<String>, token: String) = db.transaction {
        for (p in paths) db.exec("UPDATE media SET scan_seen=? WHERE path=?", listOf(token, p))
    }

    /** Aggiorna solo i dati che cambiano senza rileggere il file (es. nome o percorso spostato). */
    fun updateLocationInSource(path: String, name: String, relpath: String) =
        db.exec("UPDATE media SET name=?, relpath=?, f_name=?, f_path=? WHERE path=?", listOf(name, relpath, fold(name), fold(relpath), path))

    /**
     * Scansione completa: rimuove i file spariti, con le stesse protezioni di ArchPhotos
     * (cartella vuota ma fototeca piena; metà dei file spariti in una volta sola).
     * Restituisce (rimossi, messaggio o null).
     */
    fun finishScan(sourceId: Long, token: String, totalKnown: Int, remote: Boolean): Pair<Int, String?> = db.transaction {
        val src = source(sourceId)
        val gone = db.query("SELECT path FROM media WHERE source_id=? AND COALESCE(scan_seen, '') != ?", listOf(sourceId, token)).map { it.str("path")!! }
        val seenNow = db.scalarLong("SELECT COUNT(*) FROM media WHERE source_id=? AND scan_seen=?", listOf(sourceId, token))
        var msg: String? = null
        var toRemove = gone
        if (seenNow == 0L && totalKnown > 0) {
            msg = "${src?.name}: nessun file trovato, la fototeca non viene modificata"
            toRemove = emptyList()
        } else if (remote && gone.size > maxOf(50.0, totalKnown * 0.5) && src?.pendingGone != gone.size) {
            msg = "${src?.name}: risultano mancanti ${gone.size} file su $totalKnown; per sicurezza non vengono rimossi (ripeti la scansione per confermare)"
            db.exec("UPDATE sources SET pending_gone=? WHERE id=?", listOf(gone.size, sourceId))
            toRemove = emptyList()
        } else {
            db.exec("UPDATE sources SET pending_gone=0 WHERE id=?", listOf(sourceId))
        }
        for (p in toRemove) db.exec("DELETE FROM media WHERE path=?", listOf(p))
        pairLivePhotos(sourceId)
        db.exec("UPDATE sources SET last_scan=?, scan_token=NULL WHERE id=?", listOf(now(), sourceId))
        toRemove.size to msg
    }

    /**
     * Associa le Live Photo di iPhone (IMG_1234.HEIC + IMG_1234.MOV di pochi secondi):
     * il video diventa parte della foto e non compare da solo nella libreria.
     */
    fun pairLivePhotos(sourceId: Long): Int {
        val rows = db.query("SELECT id, path, relpath, name, kind, duration, live_video, paired FROM media WHERE source_id=? AND paired != 2", listOf(sourceId))
        val byStem = HashMap<Pair<String, String>, MutableMap<String, MutableList<Row>>>()
        for (r in rows) {
            val rel = r.str("relpath") ?: ""
            var st = stem(r.str("name") ?: basename(rel)).lowercase()
            if (st.endsWith("_3")) st = st.dropLast(2)   // IMG_1234_3.MOV (export di alcune app)
            byStem.getOrPut(dirname(rel) to st) { HashMap() }.getOrPut(r.str("kind") ?: "photo") { mutableListOf() } += r
        }
        var n = 0
        for (kinds in byStem.values) {
            val photos = kinds["photo"].orEmpty()
            val videos = kinds["video"].orEmpty()
            if (photos.isNotEmpty() && videos.size == 1 && (videos[0].double("duration") ?: 0.0) <= 6.5) {
                val v = videos[0]
                for (p in photos) {
                    if (p.str("live_video") != v.str("path")) {
                        db.exec("UPDATE media SET live_video=? WHERE id=?", listOf(v.str("path"), p.long("id")))
                        n++
                    }
                }
                if (!v.bool("paired")) db.exec("UPDATE media SET paired=1 WHERE id=?", listOf(v.long("id")))
            }
        }
        return n
    }

    // --- nomi dei luoghi -------------------------------------------------------------------
    fun placeKeysToResolve(): List<Pair<Double, Double>> =
        db.query("SELECT DISTINCT round(lat, 2) la, round(lon, 2) lo FROM media WHERE lat IS NOT NULL AND place IS NULL")
            .map { it.double("la")!! to it.double("lo")!! }

    fun knownPlaces(): Map<String, String> = db.query("SELECT key, name FROM places").associate { it.str("key")!! to (it.str("name") ?: "") }

    fun savePlace(key: String, name: String, city: String, region: String, country: String) =
        db.exec("INSERT OR REPLACE INTO places(key, name, city, region, country, fetched) VALUES (?,?,?,?,?,?)", listOf(key, name, city, region, country, now()))

    fun applyPlace(key: String, name: String) {
        val (la, lo) = key.split(",").map { it.toDouble() }
        db.exec("UPDATE media SET place=?, f_place=? WHERE lat IS NOT NULL AND place IS NULL AND round(lat, 2) = ? AND round(lon, 2) = ?",
            listOf(name, fold(name), la, lo))
    }

    fun clearPlaceNames() = db.exec("UPDATE media SET place=NULL, f_place='' WHERE instr(user, ',lat,') = 0")

    // --- volti ----------------------------------------------------------------------------
    fun faceTodo(): List<Item> {
        val v = viewSql("library")
        return db.query("SELECT $LIST_COLUMNS FROM media${v.where} AND kind = 'photo' AND faces_done = 0 ORDER BY taken DESC", v.params).map(::toItem)
    }

    fun faceEmbeddings(): List<Pair<Long, FloatArray>> = db.query("SELECT person_id, embedding FROM faces WHERE person_id IS NOT NULL AND embedding IS NOT NULL")
        .mapNotNull { r -> r.blob("embedding")?.let { r.long("person_id")!! to Faces.fromBytes(it) } }

    fun newPerson(): Long = db.insert("INSERT INTO persons(name, fname, created) VALUES ('', '', ?)", listOf(now()))

    fun addFace(mediaId: Long, x: Double, y: Double, w: Double, h: Double, score: Double, emb: FloatArray, personId: Long) =
        db.insert("INSERT INTO faces(media_id, x, y, w, h, score, embedding, person_id) VALUES (?,?,?,?,?,?,?,?)",
            listOf(mediaId, x, y, w, h, score, Faces.toBytes(emb), personId))

    fun setFacesDone(mediaId: Long) = db.exec("UPDATE media SET faces_done = 1 WHERE id = ?", listOf(mediaId))
}

private fun sameShot(a: Item, b: Item): Boolean {
    val ra = (a.width.takeIf { it > 0 } ?: 1).toDouble() / (a.height.takeIf { it > 0 } ?: 1)
    val rb = (b.width.takeIf { it > 0 } ?: 1).toDouble() / (b.height.takeIf { it > 0 } ?: 1)
    if (kotlin.math.abs(ra - rb) > 0.02 * maxOf(ra, rb)) return false
    return a.filesize == b.filesize || kotlin.math.abs(a.taken - b.taken) < 2 * 86400
}

/** Fino a [n] elementi rappresentativi, in ordine cronologico. */
fun best(items: List<Item>, n: Int): List<Item> {
    if (items.size <= n) return items
    val chosen = items.filter { it.favorite }.take(n / 2).toMutableList()
    var taken = chosen.map { it.id }.toSet()
    chosen += items.filter { it.edits != null && it.id !in taken }.take(maxOf(0, n / 3 - chosen.size))
    taken = chosen.map { it.id }.toSet()
    val rest = items.filter { it.id !in taken && "screenshot" !in it.flags }.ifEmpty { items.filter { it.id !in taken } }
    val need = n - chosen.size
    if (need > 0 && rest.isNotEmpty()) {
        val step = rest.size.toDouble() / need
        for (k in 0 until need) chosen += rest[(k * step).toInt()]
    }
    return chosen.sortedWith(compareBy({ it.taken }, { it.id })).take(n)
}
