package io.github.guidoaltieri.myphotolibrary.core

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Porting dei test del motore di ArchPhotos (tests/test_core.py). */
class CoreTest {
    private fun ts(y: Int, m: Int, d: Int, h: Int = 12, mi: Int = 0) = Fmt.ts(LocalDateTime.of(y, m, d, h, mi))

    private fun meta(taken: Double, lat: Double? = null, lon: Double? = null, model: String = "iPhone 15 Pro",
                     lens: String = "iPhone 15 Pro back camera 6.86mm f/1.78", size: Long = 1000, kind: String = "photo",
                     duration: Double? = null, w: Int = 640, h: Int = 480) =
        MediaMeta(kind = kind, taken = taken, lat = lat, lon = lon, make = "Apple", model = model, lens = lens, iso = 64,
            fnumber = 1.8, exposure = 1 / 250.0, width = w, height = h, duration = duration).also { it.finish("x.jpg", size, taken) }

    private var token = "t1"

    private fun add(lib: Library, sid: Long, rel: String, m: MediaMeta) =
        lib.upsert(sid, "content://test/$rel", basename(rel), rel, m, token)

    /** Come _library_with_photos: 12 foto a Roma nel 2022, un duplicato esatto e una Live Photo. */
    private fun libraryWithPhotos(): Pair<Library, Long> {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "Foto", "content://tree/Foto")
        val base = LocalDateTime.of(2022, 7, 1, 10, 0)
        for (i in 0 until 12) {
            val t = Fmt.ts(base.plusDays((i / 4).toLong()).plusHours(i.toLong()))
            add(lib, sid, "2022/IMG_%04d.JPG".format(i), meta(t, 41.9 + i * 0.001, 12.5, size = 1000L + i))
        }
        val t0 = Fmt.ts(base)
        add(lib, sid, "copia/IMG_0000.JPG", meta(t0, 41.9, 12.5, size = 1000L))
        add(lib, sid, "live/IMG_9000.JPG", meta(ts(2023, 1, 1), size = 5000))
        add(lib, sid, "live/IMG_9000.MOV", meta(ts(2023, 1, 1), kind = "video", duration = 2.0, size = 9000))
        lib.finishScan(sid, token, 0, false)
        return lib to sid
    }

    @Test
    fun scanAndViews() {
        val (lib, sid) = libraryWithPhotos()
        val items = lib.media("library")
        assertEquals(14, items.size)                       // il video della Live Photo non compare da solo
        val live = items.filter { it.liveVideo != null }
        assertEquals(1, live.size)
        assertTrue(live[0].liveVideo!!.endsWith("IMG_9000.MOV"))
        assertEquals(1 to 0, lib.count("live"))
        assertEquals(listOf("2022", "2023"), lib.groups("year").map { it.key })
        assertEquals(5, lib.groups("day")[0].count)        // 4 + il duplicato
        val ids = items.take(3).map { it.id }
        lib.setFavorite(ids.take(2), true)
        assertEquals(2, lib.count("favorites").first)
        lib.setHidden(listOf(ids[2]), true)
        assertEquals(1, lib.count("hidden").first)
        assertEquals(13, lib.count("library").first)
        lib.delete(listOf(ids[0]))
        assertEquals(1, lib.count("deleted").first)
        lib.restore(listOf(ids[0]))
        assertEquals(0, lib.count("deleted").first)
        // una riscansione non perde le modifiche dell'utente
        lib.setCaption(ids[0], "Al mare")
        token = "t2"
        val path = lib.item(ids[0])!!.path
        lib.upsert(sid, path, basename(path), lib.item(ids[0])!!.relpath, meta(lib.item(ids[0])!!.taken).apply { caption = "" }, token)
        assertEquals("Al mare", lib.detail(ids[0])!!.s("caption"))
        val counts = lib.viewCounts()
        assertEquals(13, counts["library"])
        assertEquals(1, counts["hidden"])
    }

    @Test
    fun search() {
        val (lib, _) = libraryWithPhotos()
        fun n(q: String) = lib.media("library", Search.compile(q)).size
        assertEquals(13, n("2022"))
        assertEquals(13, n("luglio"))
        assertEquals(1, n("year:2023"))
        assertEquals(14, n("iso:<100 camera:iphone"))
        assertEquals(1, n("-year:2022"))
        assertEquals(1, n("is:live"))
        assertEquals(13, n("has:location"))
        assertEquals(9, n("date:2022-07-01..2022-07-02"))
        assertEquals(1, n("file:IMG_0003"))
        lib.setKeywords(listOf(lib.media("library")[0].id), listOf("spiaggia", "estate"))
        assertEquals(1, n("spiaggia"))
        assertEquals(1, n("2021 OR 2023"))
        assertEquals(14, n("f:1.8"))
        assertEquals(14, n("mp:<1"))
        lib.setLocation(listOf(lib.media("library")[1].id), 37.9, 14.0, "Cefalù, Italia")
        assertEquals(1, n("cefalu"))
        assertEquals(1, n("place:CEFALÙ"))
        assertEquals(1, n("luogo:\"cefalu, italia\""))
        assertTrue(Search.rulesToQuery(listOf(Search.Rule("year", "between", "2019", "2021"), Search.Rule("is", "is_not", "screenshot")))
            == "year:2019..2021 -is:screenshot")
    }

    @Test
    fun albumsAndSmart() {
        val (lib, _) = libraryWithPhotos()
        val ids = lib.media("library").map { it.id }
        val aid = lib.createAlbum("Vacanze")
        assertEquals(5, lib.addToAlbum(aid, ids.take(5)))
        assertEquals(5, lib.media("album:$aid").size)
        val smart = lib.createAlbum("Del 2023", kind = "smart", query = "year:2023")
        assertEquals(1, lib.media("album:$smart").size)
        assertEquals(mapOf("Vacanze" to 5, "Del 2023" to 1), lib.albums().associate { it.name to it.count })
        assertEquals(5, lib.media("library", Search.compile("album:vacanze")).size)
        val folder = lib.createAlbum("Viaggi", kind = "folder")
        lib.moveAlbum(aid, folder)
        assertEquals(listOf("Viaggi", "Del 2023"), lib.albums().map { it.name })
        lib.deleteAlbum(folder)
        assertEquals(listOf("Del 2023"), lib.albums(allLevels = true).map { it.name })
    }

    @Test
    fun duplicates() {
        val (lib, _) = libraryWithPhotos()
        lib.media("library").forEachIndexed { i, it -> lib.setDhash(it.id, 1000L + (if (it.relpath.endsWith("IMG_0000.JPG")) 0 else i)) }
        val groups = lib.duplicates()
        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
        assertEquals(1, lib.mergeDuplicates(groups[0].map { it.id }))
        assertEquals(1, lib.count("deleted").first)
    }

    @Test
    fun memories() {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "Mem", "content://tree/Mem")
        for (i in 0 until 8) add(lib, sid, "otd_$i.jpg", meta(ts(2023, 7, 3, 10 + i), size = i.toLong()))
        for (i in 0 until 20) add(lib, sid, "trip_$i.jpg", meta(ts(2024, 5, 10 + i / 7, 9 + i % 7), 40.85, 14.27, size = 100L + i))
        for (i in 0 until 30) add(lib, sid, "home_$i.jpg", meta(ts(2024, 1, 1 + i % 20, 9), 45.46, 9.19, size = 200L + i))
        lib.db.exec("UPDATE media SET place='Napoli, Italia' WHERE lat < 41")
        val mem = Memories.generate(lib, LocalDate.of(2025, 7, 3))
        assertEquals("onthisday", mem[0].kind)
        val trip = mem.first { it.kind == "trip" }
        assertEquals("Viaggio a Napoli", trip.title)
        assertEquals(20, trip.count)
        lib.setMemoryState(trip.key, favorite = true, title = "Napoli!")
        val again = Memories.generate(lib, LocalDate.of(2025, 7, 3))
        assertEquals("Napoli!", again[0].title)
        lib.setMemoryState(trip.key, hidden = true)
        assertTrue(Memories.generate(lib, LocalDate.of(2025, 7, 3)).none { it.kind == "trip" })
    }

    @Test
    fun dateSuggestions() {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "Arch", "content://tree/Arch")
        add(lib, sid, "2024/05/a.jpg", meta(ts(2026, 2, 3, 14, 5), size = 1))             // anno e mese sbagliati
        add(lib, sid, "2024/05 - Maggio/b.jpg", meta(ts(2024, 5, 9, 9), size = 2))         // coerente
        add(lib, sid, "2023/Vacanze/c.jpg", meta(ts(2026, 8, 31, 12), size = 3))          // solo anno
        add(lib, sid, "2024/05/IMG_20240517_101500.jpg", meta(ts(2026, 1, 1), size = 4))  // dal nome del file
        add(lib, sid, "Varie/d.jpg", meta(ts(2026, 1, 1), size = 5))                      // nessuna data nella cartella
        val groups = Dates.suggestions(lib)
        val byName = groups.flatMap { it.items }.associateBy { it.item.name }
        assertEquals(setOf("a.jpg", "c.jpg", "IMG_20240517_101500.jpg"), byName.keys)
        val a = Fmt.dt(byName.getValue("a.jpg").suggested)
        assertEquals(listOf(2024, 5, 3, 14, 5), listOf(a.year, a.monthValue, a.dayOfMonth, a.hour, a.minute))
        val c = Fmt.dt(byName.getValue("c.jpg").suggested)
        assertEquals(listOf(2023, 8, 31), listOf(c.year, c.monthValue, c.dayOfMonth))
        val f = Fmt.dt(byName.getValue("IMG_20240517_101500.jpg").suggested)
        assertEquals(listOf(2024, 5, 17, 10), listOf(f.year, f.monthValue, f.dayOfMonth, f.hour))
        lib.setTakenMany(listOf(byName.getValue("a.jpg").item.id to byName.getValue("a.jpg").suggested))
        lib.ignoreSuggestions(listOf(byName.getValue("c.jpg").item.id))
        assertEquals(setOf("IMG_20240517_101500.jpg"), Dates.suggestions(lib).flatMap { it.items }.map { it.item.name }.toSet())
        // una nuova scansione non annulla la data corretta
        token = "t2"
        lib.upsert(sid, "content://test/2024/05/a.jpg", "a.jpg", "2024/05/a.jpg", meta(ts(2026, 2, 3, 14, 5), size = 1), token)
        assertEquals(2024, Fmt.dt(lib.item(byName.getValue("a.jpg").item.id)!!.taken).year)
        assertEquals(Dates.Component(2024, 5), Dates.parseComponent("Maggio 2024"))
        assertEquals(Dates.Component(m = 5), Dates.parseComponent("05 - Maggio"))
        assertEquals(Dates.Component(2024, 5, 12), Dates.parseComponent("2024-05-12 Vacanze"))
    }

    private class FakeTarget(val dirs: Map<String, List<String>>, val files: Map<String, Map<String, Long>>) : ImportTarget {
        override fun listDirs(rel: String) = dirs[rel] ?: emptyList()
        override fun listFiles(rel: String) = files[rel] ?: emptyMap()
    }

    @Test
    fun plannerExistingFolders() {
        val target = FakeTarget(mapOf("" to listOf("2024", "Anno 2023"), "2024" to listOf("05 - Maggio")),
            mapOf("2024/05 - Maggio" to mapOf("x.jpg" to 5L)))
        val d = { y: Int, m: Int, dd: Int -> LocalDateTime.of(y, m, dd, 0, 0) }
        val p = Planner(target, "{anno}/{mese}", true)
        assertEquals("2024/05 - Maggio", p.folderFor(d(2024, 5, 3)))
        assertEquals("2024/06", p.folderFor(d(2024, 6, 3)))
        assertEquals("2022/01", p.folderFor(d(2022, 1, 1)))
        assertEquals("2024/05 - Maggio/x.jpg" to true, p.destination("x.jpg", 5, d(2024, 5, 1)))
        assertEquals("2024/05 - Maggio/x (1).jpg" to false, p.destination("x.jpg", 9, d(2024, 5, 1)))
        assertEquals("2024/05", Planner(target, "{anno}/{mese}", false).folderFor(d(2024, 5, 3)))
        assertEquals("2024/05 - Maggio/12", Planner(target, "{anno}/{mese} - {nome_mese}/{giorno}").folderFor(d(2024, 5, 12)))
        assertNotNull(Layouts.validate("{anno}/{boh}"))
        assertNotNull(Layouts.validate("../x"))
        assertNull(Layouts.validate("{anno}-{mese}"))
    }

    @Test
    fun placeGuessFromNames() {
        fun g(path: String) = PlaceNames.guess(path)?.let { it.name to it.level }
        assertEquals("Parigi, Francia" to "città", g("F/2023/Vacanze Parigi/IMG_1.jpg"))
        assertEquals("San Gimignano" to "città", g("F/Toscana 2022/San Gimignano/a.jpg"))
        assertEquals("Roma" to "città", g("F/2021/Varie/Roma_Colosseo_014.jpg"))
        assertEquals("Valencia, Spagna" to "città", g("F/Spagna/Valencia/a.jpg"))
        assertEquals("Cortina d'Ampezzo" to "città", g("F/2018/Gita a Cortina d'Ampezzo/a.jpg"))
        assertEquals("Sardegna" to "regione", g("F/Sardegna 2017/b.jpg"))
        assertEquals("Lago di Garda" to "zona", g("F/2015/Weekend Lago di Garda/d.jpg"))
        assertEquals("Barcellona, Spagna" to "città", g("F/2020/Barcellona/x.jpg"))
        for (none in listOf("F/2020/Matrimonio Marco e Giulia/DSC001.jpg", "F/2019/Natale a casa/IMG_3.jpg",
                "F/2024/05/IMG_20240512_101010.jpg", "F/Camera Roll/PXL_20230101.jpg", "F/2020/Compleanno Nonna Rosa/x.jpg"))
            assertNull(PlaceNames.guess(none), none)
    }

    @Test
    fun placeSuggestions() {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "Arch", "content://tree/Arch")
        add(lib, sid, "2023/Vacanze Parigi/a.jpg", meta(ts(2023, 8, 1), size = 1))
        add(lib, sid, "2023/Vacanze Parigi/b.jpg", meta(ts(2023, 8, 2), size = 2))
        add(lib, sid, "2023/Vacanze Parigi/c.jpg", meta(ts(2023, 8, 2), 48.86, 2.35, size = 3))   // ha già il GPS
        add(lib, sid, "Varie/Napoli_042.jpg", meta(ts(2022, 1, 1), size = 4))
        add(lib, sid, "Varie/d.jpg", meta(ts(2022, 1, 1), size = 5))
        val byPlace = PlaceNames.suggestions(lib).associateBy { it.place }
        assertEquals(setOf("Parigi, Francia", "Napoli"), byPlace.keys)
        assertEquals(setOf("a.jpg", "b.jpg"), byPlace.getValue("Parigi, Francia").items.map { it.name }.toSet())
        assertEquals("nome del file", byPlace.getValue("Napoli").evidence)
        val par = byPlace.getValue("Parigi, Francia")
        lib.setLocationMany(par.items.map { Library.Located(it.id, par.lat, par.lon, par.place) })
        lib.ignorePlaceSuggestions(byPlace.getValue("Napoli").items.map { it.id })
        assertTrue(PlaceNames.suggestions(lib).isEmpty())
        val a = lib.item(par.items[0].id)!!
        assertTrue(kotlin.math.abs(a.lat!! - 48.85) < 0.1)
        assertEquals("Parigi, Francia", a.place)
    }

    @Test
    fun placeSearch() {
        fun names(q: String) = PlaceNames.search(q, 5).map { it.name }
        assertEquals("Parigi, Francia", names("parig")[0])
        assertEquals("Milano", names("milan")[0])
        assertEquals("Roma", names("rom")[0])
        assertEquals(listOf("San Gimignano"), names("san gim"))
        assertEquals(emptyList(), names("x"))
    }

    @Test
    fun editRecipes() {
        val recipe = Recipe(rotate = 90, crop = listOf(0.1, 0.1, 0.5, 0.5), straighten = 3.0,
            adjust = mapOf("exposure" to 0.3, "vignette" to 0.5), filter = "noir")
        assertNull(Edit.clean(Recipe(adjust = mapOf("exposure" to 0.0))))
        assertTrue("Noir" in Edit.describe(recipe))
        val json = recipe.toJson()
        assertEquals(recipe, Edit.load(json))
        // compatibile con le ricette di ArchPhotos
        val py = Edit.load("""{"adjust": {"exposure": 0.2}, "filter": "vivid", "filter_amount": 0.5, "flip_h": true}""")
        assertTrue(py.flipH)
        assertEquals(0.5, py.filterAmount)
        assertEquals(0.35 * 0.5, Edit.combinedAdjust(py)["saturation"]!!, 1e-9)
        // regolazioni sui pixel: un'immagine grigia diventa più chiara con l'esposizione
        val w = 64; val h = 48
        val px = IntArray(w * h) { (0xFF shl 24) or (0x60 shl 16) or (0x60 shl 8) or 0x60 }
        val out = Edit.tone(px, w, h, mapOf("exposure" to 0.5, "sharpness" to 0.3, "noise" to 0.2, "vignette" to 0.4, "definition" to 0.2))
        assertTrue(((out[w * h / 2 + w / 2] shr 16) and 0xFF) > 0x60)
        assertTrue(Edit.autoEnhance(px, w, h).containsKey("exposure"))
        assertTrue(Edit.inscribedScale(400, 300, 3.0) < 1.0)
    }

    @Test
    fun metadataHelpers() {
        assertNotNull(MediaInfo.dateFromName("PXL_20230712_153012345.jpg"))
        assertNotNull(MediaInfo.dateFromName("IMG-20230712-WA0001.jpg"))
        assertNotNull(MediaInfo.dateFromName("Schermata 2024-01-02 alle 10.11.12.png"))
        val t = Fmt.dt(MediaInfo.parseExifDate("2023:07:12 15:30:00")!!)
        assertEquals(LocalDateTime.of(2023, 7, 12, 15, 30), t)
        val flags = MediaInfo.photoFlags("IMG_1.JPG", "iPhone 15 Pro front camera 2.69mm f/1.9", "", "", 480, 640)
        assertTrue("selfie" in flags)
        assertTrue("screenshot" in MediaInfo.photoFlags("Screenshot_20240101.png", "", "", "", 1080, 2400))
        assertTrue("panorama" in MediaInfo.photoFlags("PANO.jpg", "", "", "", 8000, 2000))
        val xmp = """<x:xmpmeta><rdf:Description GCamera:MicroVideo="1" GCamera:MicroVideoOffset="1000"><dc:subject><rdf:Bag><rdf:li>mare</rdf:li><rdf:li>sole</rdf:li></rdf:Bag></dc:subject></rdf:Description></x:xmpmeta>"""
        assertEquals(listOf("mare", "sole"), MediaInfo.xmpValues(xmp, "dc:subject"))
        assertEquals(4000L, MediaInfo.motionOffset(xmp, 5000))
        assertEquals(Triple(45.4642, 9.19, 120.0), MediaInfo.iso6709("+45.4642+009.1900+120.0/"))
        assertEquals("1/250 s", Fmt.exposure(1 / 250.0))
        assertEquals("ƒ/1.8", Fmt.aperture(1.8))
        assertEquals("1.204 foto, 37 video", Fmt.items(1204, 37))
        assertEquals("3–9 luglio 2024", Fmt.dateRange(ts(2024, 7, 3), ts(2024, 7, 9)))
    }

    @Test
    fun personsAndFaces() {
        val (lib, _) = libraryWithPhotos()
        val ids = lib.media("library").map { it.id }
        val clusters = Faces.Clusters(emptyList())
        val v1 = Faces.normalize(FloatArray(128) { if (it == 0) 1f else 0.01f })
        val v2 = Faces.normalize(FloatArray(128) { if (it == 1) 1f else 0.01f })
        var pid1 = 0L
        for (i in 0 until 4) {
            val v = if (i < 3) v1 else v2
            val pid = clusters.match(v) ?: lib.newPerson()
            clusters.add(pid, v)
            if (i == 0) pid1 = pid
            lib.addFace(ids[i], 0.1, 0.1, 0.2, 0.2, 0.9, v, pid)
            lib.setFacesDone(ids[i])
        }
        assertEquals(1, lib.persons().size)    // la seconda persona ha un volto solo
        lib.renamePerson(pid1, "Anna")
        assertEquals(3, lib.media("library", Search.compile("person:anna")).size)
        assertEquals(3, lib.media("person:$pid1").size)
        val emb = lib.faceEmbeddings()
        assertEquals(4, emb.size)
        assertEquals(v1.toList(), emb[0].second.toList())
    }
}
