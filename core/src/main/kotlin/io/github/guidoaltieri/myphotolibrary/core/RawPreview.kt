package io.github.guidoaltieri.myphotolibrary.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Anteprime JPEG incorporate nei file RAW, lette senza decodificare il RAW e senza leggere tutto
 * il file (porting di rawpreview.py):
 * - TIFF e varianti (CR2, NEF, ARW, DNG, PEF, SRW, ORF, RW2): IFD, SubIFD e JPEGInterchangeFormat;
 * - CR3 (contenitore ISO): riquadri THMB e PRVW;
 * - RAF (Fujifilm): posizione dell'anteprima nell'intestazione.
 */
object RawPreview {
    /** Lettura a caso nel file: [n] byte dalla posizione [pos] (meno se il file finisce prima). */
    fun interface Source {
        fun read(pos: Long, n: Int): ByteArray
    }

    data class Preview(val width: Int, val height: Int, val offset: Long, val length: Int)

    private const val MAX_IFDS = 64              // file danneggiati: non si gira all'infinito
    private const val CR3_SCAN = 4 shl 20        // i riquadri delle anteprime CR3 stanno all'inizio del file
    private val JPEG_SOF = setOf(0xC0, 0xC1, 0xC2)

    private fun order(head: ByteArray): ByteOrder? {
        if (head.size < 4) return null
        val m = String(head, 0, 4, Charsets.ISO_8859_1)
        return when (m) {
            "II*\u0000", "IIRO", "IIRS", "IIU\u0000" -> ByteOrder.LITTLE_ENDIAN
            "MM\u0000*" -> ByteOrder.BIG_ENDIAN
            else -> null
        }
    }

    private fun buf(b: ByteArray, o: ByteOrder) = ByteBuffer.wrap(b).order(o)

    /** (posizione, lunghezza) delle anteprime JPEG trovate nella struttura del file. */
    fun candidates(src: Source): List<Pair<Long, Int>> = try {
        val head = src.read(0, 16)
        val bo = order(head)
        when {
            bo != null -> tiff(src, bo)
            head.size >= 12 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp" && String(head, 8, 4, Charsets.ISO_8859_1).contains("crx") -> cr3(src)
            String(head, Charsets.ISO_8859_1).startsWith("FUJIFILMCCD-RAW") -> raf(src)
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** Orientamento EXIF del RAW (IFD0): le anteprime incorporate di solito non sono ruotate. */
    fun orientation(src: Source): Int = try {
        val head = src.read(0, 8)
        val bo = order(head) ?: return 1
        val ifd = buf(head, bo).getInt(4).toLong() and 0xFFFFFFFFL
        val n = minOf(buf(src.read(ifd, 2), bo).short.toInt() and 0xFFFF, 1000)
        val raw = buf(src.read(ifd + 2, 12 * n), bo)
        (0 until n).firstNotNullOfOrNull { i ->
            val tag = raw.getShort(12 * i).toInt() and 0xFFFF
            val typ = raw.getShort(12 * i + 2).toInt() and 0xFFFF
            if (tag == 0x0112 && typ == 3) (raw.getShort(12 * i + 8).toInt() and 0xFFFF).takeIf { it in 1..8 } ?: 1 else null
        } ?: 1
    } catch (e: Exception) {
        1
    }

    private class Entry(val typ: Int, val count: Long, val data: ByteArray)

    private fun tiff(src: Source, bo: ByteOrder): List<Pair<Long, Int>> {
        val first = buf(src.read(4, 4), bo).int.toLong() and 0xFFFFFFFFL
        val out = mutableListOf<Pair<Long, Int>>()
        val todo = ArrayDeque(listOf(first))
        val seen = HashSet<Long>()
        while (todo.isNotEmpty() && seen.size < MAX_IFDS) {
            val off = todo.removeFirst()
            if (off == 0L || !seen.add(off)) continue
            val n = buf(src.read(off, 2), bo).short.toInt() and 0xFFFF
            if (n > 1000) continue
            val raw = src.read(off + 2, 12 * n + 4)
            if (raw.size < 12 * n + 4) continue
            val b = buf(raw, bo)
            val tags = HashMap<Int, Entry>()
            for (i in 0 until n) {
                val tag = b.getShort(12 * i).toInt() and 0xFFFF
                tags[tag] = Entry(b.getShort(12 * i + 2).toInt() and 0xFFFF, b.getInt(12 * i + 4).toLong() and 0xFFFFFFFFL,
                    raw.copyOfRange(12 * i + 8, 12 * i + 12))
            }
            todo.addLast(b.getInt(12 * n).toLong() and 0xFFFFFFFFL)                     // IFD successivo
            values(src, bo, tags[0x014A]).forEach { todo.addLast(it) }                   // SubIFD
            if (0x0201 in tags && 0x0202 in tags) {                                      // JPEGInterchangeFormat
                val o = values(src, bo, tags[0x0201]).firstOrNull(); val l = values(src, bo, tags[0x0202]).firstOrNull()
                if (o != null && l != null) out += o to l.toInt()
            }
            val comp = values(src, bo, tags[0x0103])
            if (comp.isNotEmpty() && comp[0] in setOf(6L, 7L) && 0x0111 in tags && 0x0117 in tags) {   // JPEG in una sola striscia
                val offs = values(src, bo, tags[0x0111]); val lens = values(src, bo, tags[0x0117])
                if (offs.size == 1 && lens.size == 1) out += offs[0] to lens[0].toInt()
            }
            tags[0x002E]?.let { e ->                                                     // RW2: JpgFromRaw
                if (e.count > 4) out += (buf(e.data, bo).int.toLong() and 0xFFFFFFFFL) to e.count.toInt()
            }
        }
        return out.filter { it.first > 0 && it.second > 100 }
    }

    /** Valori interi di una voce IFD (SHORT o LONG), anche quando stanno fuori dalla voce. */
    private fun values(src: Source, bo: ByteOrder, e: Entry?): List<Long> {
        if (e == null) return emptyList()
        val size = when (e.typ) { 3 -> 2; 4, 13 -> 4; else -> return emptyList() }
        if (e.count > 4096) return emptyList()
        val n = e.count.toInt()
        val data = if (size * n > 4) src.read(buf(e.data, bo).int.toLong() and 0xFFFFFFFFL, size * n) else e.data
        if (data.size < size * n) return emptyList()
        val b = buf(data, bo)
        return List(n) { i -> if (size == 2) (b.getShort(2 * i).toLong() and 0xFFFF) else (b.getInt(4 * i).toLong() and 0xFFFFFFFFL) }
    }

    /** CR3: le anteprime stanno nei riquadri THMB (160 px) e PRVW (1620 px) vicino all'inizio. */
    private fun cr3(src: Source): List<Pair<Long, Int>> {
        val data = src.read(0, CR3_SCAN)
        val s = String(data, Charsets.ISO_8859_1)
        val out = mutableListOf<Pair<Long, Int>>()
        for (name in listOf("THMB", "PRVW")) {
            val i = s.indexOf(name)
            if (i < 4) continue
            val boxEnd = i - 4 + (ByteBuffer.wrap(data, i - 4, 4).int.toLong() and 0xFFFFFFFFL)
            val soi = s.indexOf("ÿØÿ", i).takeIf { it in i until i + 64 } ?: continue
            if (boxEnd > soi) out += soi.toLong() to (boxEnd - soi).toInt()
        }
        return out
    }

    private fun raf(src: Source): List<Pair<Long, Int>> {
        val b = ByteBuffer.wrap(src.read(84, 8))
        val off = b.int.toLong() and 0xFFFFFFFFL
        val len = b.int
        return if (off > 0 && len > 0) listOf(off to len) else emptyList()
    }

    /** Dimensioni di un JPEG dai primi byte; null se non è un JPEG decodificabile (es. lossless dei RAW). */
    fun jpegSize(head: ByteArray): Pair<Int, Int>? {
        fun u(i: Int) = head[i].toInt() and 0xFF
        if (head.size < 4 || u(0) != 0xFF || u(1) != 0xD8) return null
        var i = 2
        while (i + 9 < head.size) {
            if (u(i) != 0xFF) return null
            val marker = u(i + 1)
            if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) {
                i += 2; continue
            }
            val seg = (u(i + 2) shl 8) or u(i + 3)
            if (marker in 0xC0..0xCF && marker !in setOf(0xC4, 0xC8, 0xCC)) {
                if (marker !in JPEG_SOF) return null
                val h = (u(i + 5) shl 8) or u(i + 6)
                val w = (u(i + 7) shl 8) or u(i + 8)
                return if (w > 0 && h > 0) w to h else null
            }
            i += 2 + seg
        }
        return null
    }

    /** Anteprime decodificabili, dalla più piccola. */
    fun previews(src: Source): List<Preview> = candidates(src).toSet().mapNotNull { (off, len) ->
        jpegSize(src.read(off, minOf(len, 65536)))?.let { (w, h) -> Preview(w, h, off, len) }
    }.sortedBy { it.width.toLong() * it.height }

    /** L'anteprima più piccola con il lato lungo di almeno [minLong] (o la più grande, se nessuna basta). */
    fun choose(previews: List<Preview>, minLong: Int = 0): Preview? =
        previews.firstOrNull { maxOf(it.width, it.height) >= minLong } ?: previews.lastOrNull()

    /** I byte JPEG dell'anteprima scelta, o null se il file non ne ha. */
    fun read(src: Source, minLong: Int = 0): ByteArray? = choose(previews(src), minLong)?.let { src.read(it.offset, it.length) }
}
