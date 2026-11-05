package io.github.guidoaltieri.myphotolibrary.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Test delle novità di ArchPhotos portate nel motore (ritaglio, date di scatto, suggerimenti, RAW). */
class UpdatesTest {
    private fun ts(y: Int, m: Int, d: Int, h: Int = 12, mi: Int = 0) = Fmt.ts(LocalDateTime.of(y, m, d, h, mi))

    private fun near(a: List<Double>, b: List<Double>) = a.zip(b).all { (x, y) -> kotlin.math.abs(x - y) < 1e-9 }

    @Test
    fun cropGeometryHelpers() {
        val c = listOf(0.1, 0.2, 0.3, 0.4)
        assertTrue(near(Edit.rotateCrop(Edit.rotateCrop(c, 90), -90), c))
        assertTrue(near(Edit.flipCrop(Edit.flipCrop(c, true), true), c))
        assertTrue(near(Edit.flipCrop(c, false), listOf(0.1, 0.4, 0.3, 0.4)))
        assertEquals(300 to 400, Edit.orientedSize(400, 300, Recipe(rotate = 90)))
        assertEquals(4 / 3.0, Edit.aspectRatio(0.0, 400, 300, Recipe())!!, 1e-9)
        assertEquals(1.0, Edit.aspectRatio(1.0, 400, 300, Recipe()))
    }

    @Test
    fun captureDateRules() {
        // ora «sull'orologio»: il fuso scritto nel valore non sposta la foto
        assertEquals(ts(2023, 7, 4, 10), MediaInfo.parseExifDate("2023:07:04 10:00:00-04:00"))
        assertEquals(ts(2020, 5, 1, 10), MediaInfo.imageDate("2020:05:01 10:00:00", null, "", null))
        assertEquals(ts(2016, 12, 24, 20), MediaInfo.imageDate(null, "2016:12:24 20:00:00", "", null))
        assertEquals(ts(2018, 8, 15, 11, 22) + 33, MediaInfo.imageDate(null, null,
            """<x:xmpmeta photoshop:DateCreated="2018-08-15T11:22:33"/>""", null))
        assertEquals(1234.0, MediaInfo.imageDate(null, null, "", 1234.0))
        // solo la data di modifica: vince il nome del file, ed è comunque una stima
        val a = MediaMeta(modifyDate = ts(2024, 2, 2, 9)).apply { finish("IMG_20190101_120000.jpg", 1, 5.0) }
        assertEquals(ts(2019, 1, 1, 12), a.taken)
        assertTrue(a.takenGuess)
        val b = MediaMeta(modifyDate = ts(2024, 2, 2, 9)).apply { finish("f.jpg", 1, 5.0) }
        assertEquals(ts(2024, 2, 2, 9), b.taken)
        assertTrue(b.takenGuess)
    }

    private fun item(id: Long, taken: Double, place: String? = null, lat: Double? = null) = Item(id, 1, "p$id", "a.jpg", "x/a.jpg", "photo",
        null, taken, null, 0, 0, null, "", false, false, null, null, null, null, 0, null, lat, lat, place)

    @Test
    fun suggestionTexts() {
        val t = ts(2024, 3, 4, 10)
        val it = item(1, t)
        val g = Dates.SuggestionGroup("2019/Vacanze", "Foto/2019/Vacanze", 1,
            listOf(Dates.SuggestedItem(it, t - 86400 * 365 * 5.0, "anno", false), Dates.SuggestedItem(it, t - 86400 * 365 * 5.0, "anno", false)))
        assertEquals("2019/Vacanze" to "2 foto · data attuale: 2024 → suggerita: 2019", Suggestions.dateCard(g))
        assertEquals("2 foto in 1 cartella", Suggestions.datesHeader(listOf(g)))
        val p = PlaceNames.PlaceGroup("Parigi", 48.8, 2.3, "regione", "nome del file", "x/Parigi_01.jpg", 1, listOf(it))
        assertEquals("Parigi" to "1 foto · dal nome del file · Parigi_01.jpg · posizione approssimativa (centro della regione)",
            Suggestions.placeCard(p))
        assertEquals("1 foto · dalla cartella", Suggestions.placeCard(p.copy(evidence = "cartella", level = "città")).second)
    }

    @Test
    fun infoEditDescriptions() {
        val roma = item(1, 1_000_000.0, "Roma", 41.9)
        val none = item(2, 2_000_000.0)
        assertEquals("Attuale: Roma", InfoEdit.placeDescription(listOf(roma)))
        assertEquals("Attuale: Roma (solo per alcuni elementi)", InfoEdit.placeDescription(listOf(roma, none)))
        assertEquals("Attuale: 2 luoghi diversi", InfoEdit.placeDescription(listOf(roma, roma.copy(place = "Milano"))))
        assertEquals("Attuale: nessuno", InfoEdit.placeDescription(listOf(none)))
        assertTrue(InfoEdit.dateDescription(listOf(roma, none)).startsWith("2 elementi, dal "))
    }

    @Test
    fun manyIdsAndYears() {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "F", "content://tree/F")
        val ids = (0 until 1500).map { i ->
            lib.upsert(sid, "content://f/$i", "IMG_$i.jpg", "F/IMG_$i.jpg",
                MediaMeta(taken = ts(2020 + i % 3, 1, 1)).apply { finish("IMG_$i.jpg", i.toLong(), 1.0) }, "t")
        }
        lib.setFavorite(ids, true)                       // oltre il limite di 999 parametri di SQLite
        assertEquals(1500, lib.count("favorites").first)
        assertEquals(ids.reversed().take(5), lib.items(ids.reversed().take(5)).map { it.id })
        assertEquals(listOf("2022", "2021", "2020"), lib.years())
        val aid = lib.createAlbum("A")
        assertEquals(1500, lib.addToAlbum(aid, ids))
        assertEquals(0, lib.addToAlbum(aid, ids.take(10)))   // già presenti
        assertEquals(1, lib.media("library", Search.compile("file:img_1499")).size)
    }

    @Test
    fun incompleteScanNeverRemovesPhotos() {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "Cloud", "content://tree/Cloud")
        for (i in 0 until 10) lib.upsert(sid, "content://c/$i", "a$i.jpg", "Viaggi/a$i.jpg",
            MediaMeta(taken = ts(2023, 1, 1)).apply { finish("a$i.jpg", i.toLong(), 1.0) }, "t1")
        // nuova scansione: la sottocartella «Viaggi» non ha risposto, nessun file visto
        val (removed, _) = lib.finishScan(sid, "t2", 10, remote = true, complete = false)
        assertEquals(0, removed)
        assertEquals(10, lib.count("library").first)
        // scansione completa con 3 file davvero spariti (meno della metà): vengono tolti
        lib.markSeen((0 until 7).map { "content://c/$it" }, "t3")
        assertEquals(3, lib.finishScan(sid, "t3", 10, remote = true).first)
    }

    @Test
    fun scanKeepsPhotosCopiedFromICloudMeanwhile() {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "Cloud", "content://tree/Cloud")
        val meta = { n: String -> MediaMeta(taken = ts(2025, 11, 1)).apply { finish(n, 1, 1.0) } }
        for (i in 0 until 10) lib.upsert(sid, "content://c/$i", "a$i.jpg", "2025/a$i.jpg", meta("a$i.jpg"), "100.000000")
        // scansione iniziata a t=200 che vede i 10 file; nel frattempo la copia di iCloud ne aggiunge uno
        lib.markSeen((0 until 10).map { "content://c/$it" }, "200.000000")
        lib.upsert(sid, "content://c/new", "new.jpg", "2025/new.jpg", meta("new.jpg"), "icloud-250")
        // e una foto di una copia precedente alla scansione, davvero sparita, si toglie
        lib.upsert(sid, "content://c/old", "old.jpg", "2025/old.jpg", meta("old.jpg"), "icloud-150")
        assertEquals(1, lib.finishScan(sid, "200.000000", 12, remote = true).first)
        assertEquals(11, lib.count("library").first)
    }

    private fun jpeg(w: Int, h: Int, pad: Int): ByteArray {
        // JPEG minimale: SOI, SOF0 con le dimensioni, poi byte di riempimento
        val sof = byteArrayOf(0xFF.toByte(), 0xC0.toByte(), 0, 17, 8, (h shr 8).toByte(), h.toByte(), (w shr 8).toByte(), w.toByte(), 3) + ByteArray(9)
        return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + sof + ByteArray(pad)
    }

    @Test
    fun rawEmbeddedPreviews() {
        // RAW in stile TIFF: IFD0 con orientamento e anteprima 640 px, IFD1 con la miniatura 160 px
        val big = jpeg(640, 480, 3000); val small = jpeg(160, 120, 500)
        val ifd0 = 8; val ifd1 = ifd0 + 2 + 3 * 12 + 4; val data = ifd1 + 2 + 2 * 12 + 4
        val bigOff = data; val smallOff = data + big.size
        val out = ByteArrayOutputStream()
        val bb = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("II*\u0000".toByteArray(Charsets.ISO_8859_1)).putInt(ifd0)
        fun entry(tag: Int, typ: Int, v: Int) {
            bb.putShort(tag.toShort()).putShort(typ.toShort()).putInt(1)
            if (typ == 3) bb.putShort(v.toShort()).putShort(0) else bb.putInt(v)
        }
        bb.putShort(3); entry(0x0112, 3, 6); entry(0x0201, 4, bigOff); entry(0x0202, 4, big.size); bb.putInt(ifd1)
        bb.putShort(2); entry(0x0201, 4, smallOff); entry(0x0202, 4, small.size); bb.putInt(0)
        out.write(bb.array()); out.write(big); out.write(small); out.write(ByteArray(200_000))
        val file = out.toByteArray()
        val src = RawPreview.Source { pos, n -> file.copyOfRange(pos.toInt().coerceAtMost(file.size), (pos + n).toInt().coerceAtMost(file.size)) }
        val found = RawPreview.previews(src)
        assertEquals(listOf(160, 640), found.map { it.width })
        assertEquals(6, RawPreview.orientation(src))
        assertEquals(small.size, RawPreview.read(src, 100)!!.size)
        assertEquals(big.size, RawPreview.read(src, 400)!!.size)
        assertEquals(big.size, RawPreview.read(src, 5000)!!.size)   // nessuna basta: la più grande
        assertNull(RawPreview.read(RawPreview.Source { _, _ -> ByteArray(16) }))
    }
}
