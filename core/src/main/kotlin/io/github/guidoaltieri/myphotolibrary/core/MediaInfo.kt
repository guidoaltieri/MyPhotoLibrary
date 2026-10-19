package io.github.guidoaltieri.myphotolibrary.core

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Riconoscimento dei file multimediali e interpretazione dei metadati (indipendente da Android):
 * estensioni, date nei nomi dei file, date EXIF, XMP, tipo di scatto (selfie, panoramica…).
 */
object MediaInfo {
    val PHOTO_EXT = setOf("jpg", "jpeg", "jpe", "png", "heic", "heif", "hif", "avif", "webp", "tif", "tiff",
        "gif", "bmp", "jxl")
    val RAW_EXT = setOf("dng", "cr2", "cr3", "crw", "nef", "nrw", "arw", "srf", "sr2", "orf", "rw2", "raf",
        "pef", "srw", "x3f", "3fr", "erf", "kdc", "mrw", "raw", "rwl", "iiq")
    val VIDEO_EXT = setOf("mov", "mp4", "m4v", "avi", "mkv", "webm", "3gp", "3g2", "mts", "m2ts", "wmv",
        "mpg", "mpeg", "ogv")
    val MEDIA_EXT = PHOTO_EXT + RAW_EXT + VIDEO_EXT

    val MIME = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jpe" to "image/jpeg", "png" to "image/png",
        "heic" to "image/heic", "heif" to "image/heif", "hif" to "image/heif", "avif" to "image/avif",
        "webp" to "image/webp", "tif" to "image/tiff", "tiff" to "image/tiff", "gif" to "image/gif",
        "bmp" to "image/bmp", "jxl" to "image/jxl", "mov" to "video/quicktime", "mp4" to "video/mp4",
        "m4v" to "video/x-m4v", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "3gp" to "video/3gpp", "mts" to "video/mp2t", "m2ts" to "video/mp2t", "wmv" to "video/x-ms-wmv",
        "mpg" to "video/mpeg", "mpeg" to "video/mpeg", "ogv" to "video/ogg", "dng" to "image/x-adobe-dng")

    /** flag (colonna "flags" del database, separati da virgole) */
    val FLAGS = listOf("live", "selfie", "panorama", "portrait", "screenshot", "raw", "animated", "slomo", "burst", "screenrec")

    fun isMedia(name: String) = extension(name) in MEDIA_EXT
    fun isVideo(name: String) = extension(name) in VIDEO_EXT
    fun isRaw(name: String) = extension(name) in RAW_EXT
    fun mimeType(name: String): String = extension(name).let { MIME[it] ?: if (it in RAW_EXT) "image/x-raw" else "application/octet-stream" }

    // --- date ----------------------------------------------------------------------------
    private val EXIF_DATE = Regex("(\\d{4})[:\\-](\\d{2})[:\\-](\\d{2})[ T](\\d{2}):(\\d{2}):(\\d{2})")
    private val NAME_DATES = listOf(
        // IMG_20230712_153012, PXL_20230712_153012345, VID_20230712_153012, Screenshot_20230712-153012
        Regex("(?<!\\d)(20\\d{2}|19\\d{2})(\\d{2})(\\d{2})[_\\-T ]?(\\d{2})(\\d{2})(\\d{2})"),
        // 2023-07-12 15.30.12 (Dropbox/Takeout), Schermata 2023-07-12 alle 15.30.12
        Regex("(?<!\\d)(20\\d{2}|19\\d{2})-(\\d{2})-(\\d{2})[ _\\-T]+(?:alle |at )?(\\d{2})[.\\-:](\\d{2})[.\\-:](\\d{2})"),
        // WhatsApp: IMG-20230712-WA0001 (solo giorno)
        Regex("(?<!\\d)(20\\d{2})(\\d{2})(\\d{2})-WA\\d+"),
    )

    fun ts(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0, s: Int = 0, offset: String? = null): Double? {
        val t = try {
            LocalDateTime.of(y, mo, d, h, mi, s)
        } catch (e: Exception) {
            return null
        }
        if (t.year < 1970 || t.year > 2100) return null
        if (offset != null) {
            val m = Regex("([+-])(\\d{2}):?(\\d{2})").find(offset.trim())
            if (m != null) {
                val sign = if (m.groupValues[1] == "+") 1 else -1
                val off = ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * m.groupValues[3].toInt())
                return OffsetDateTime.of(t, off).toInstant().toEpochMilli() / 1000.0
            }
        }
        return Fmt.ts(t)
    }

    fun parseExifDate(value: String?, offset: String? = null): Double? {
        if (value.isNullOrBlank()) return null
        val m = EXIF_DATE.find(value) ?: return null
        val g = m.groupValues.drop(1).map { it.toInt() }
        return ts(g[0], g[1], g[2], g[3], g[4], g[5], offset)
    }

    fun dateFromName(name: String): Double? {
        val base = basename(name)
        for (rx in NAME_DATES) {
            val m = rx.find(base) ?: continue
            val g = m.groupValues.drop(1).map { it.toInt() }
            return if (g.size >= 6) ts(g[0], g[1], g[2], g[3], g[4], g[5]) else ts(g[0], g[1], g[2])
        }
        return null
    }

    /** Data ISO dei video («2023-07-12T15:30:12.000000Z», «20230712T153012.000Z»). */
    fun parseIsoDate(value: String?): Double? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        try {
            return OffsetDateTime.parse(v.replace("Z", "+00:00")).toInstant().toEpochMilli() / 1000.0
        } catch (_: Exception) {
        }
        val m = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})").find(v)
        if (m != null) {
            val g = m.groupValues.drop(1).map { it.toInt() }
            val t = ts(g[0], g[1], g[2], g[3], g[4], g[5], "+00:00")
            if (t != null && t > 86400 * 365) return t
            return null
        }
        return parseExifDate(v)?.takeIf { it > 86400 * 365 }
    }

    // --- XMP ------------------------------------------------------------------------------
    /** Valori di un campo XMP (rdf:Bag/Seq/Alt o attributo). */
    fun xmpValues(xmp: String, tag: String): List<String> {
        if (xmp.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        val t = Regex.escape(tag)
        Regex("<$t[^>]*>(.*?)</$t>", RegexOption.DOT_MATCHES_ALL).find(xmp)?.let { m ->
            val inner = m.groupValues[1]
            Regex("<rdf:li[^>]*>(.*?)</rdf:li>", RegexOption.DOT_MATCHES_ALL).findAll(inner).forEach {
                out += it.groupValues[1].replace(Regex("<[^>]+>"), "").trim()
            }
            if (out.isEmpty()) {
                val plain = inner.replace(Regex("<[^>]+>"), "").trim()
                if (plain.isNotEmpty()) out += plain
            }
        }
        Regex("$t=\"([^\"]*)\"").find(xmp)?.let { if (it.groupValues[1].isNotBlank()) out += it.groupValues[1].trim() }
        return out.filter { it.isNotEmpty() }.map { unescapeXml(it) }
    }

    private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#39;", "'").replace("&amp;", "&")

    /** Posizione del video incorporato in una Motion Photo Android (dall'inizio del file). */
    fun motionOffset(xmp: String, size: Long): Long? {
        Regex("MicroVideoOffset=\"(\\d+)\"").find(xmp)?.let { return size - it.groupValues[1].toLong() }
        val rx = Regex("Item:Semantic=\"MotionPhoto\"[^>]*Item:Length=\"(\\d+)\"|Item:Length=\"(\\d+)\"[^>]*Item:Semantic=\"MotionPhoto\"")
        for (m in rx.findAll(xmp)) {
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toLongOrNull() ?: 0
            if (n > 0) return size - n
        }
        return null
    }

    /** Produttore senza ripetizioni: «Canon» + «Canon EOS R6» → «» (il modello basta). */
    fun cleanMake(make: String, model: String): String {
        val first = make.lowercase().split(" ").firstOrNull().orEmpty()
        return if (make.isNotEmpty() && first.isNotEmpty() && model.lowercase().startsWith(first)) "" else make
    }

    fun cleanCaption(c: String?): String {
        val v = c?.trim().orEmpty()
        return if (v.lowercase() in setOf("", "default", "olympus digital camera", "sony dsc", "image")) "" else v
    }

    private val SCREENSHOT_NAME = Regex("screenshot|screen shot|schermata|bildschirmfoto|capture d.écran|captura de pantalla")
    private val SCREENREC_NAME = Regex("screen.?record|registrazione schermo|screenrecorder")

    /** Tipo di scatto di una foto. */
    fun photoFlags(name: String, lens: String, comment: String, xmp: String, width: Int, height: Int,
                   frames: Int = 1, format: String = "", relPath: String = ""): MutableSet<String> {
        val flags = mutableSetOf<String>()
        val lname = name.lowercase()
        if (isRaw(name)) flags += "raw"
        if (frames > 1 && format.uppercase() in setOf("GIF", "WEBP", "PNG")) flags += "animated"
        val l = lens.lowercase()
        if ("front" in l || "frontale" in l) flags += "selfie"
        if ((SCREENSHOT_NAME.containsMatchIn(lname) || comment.lowercase() == "screenshot" ||
                    relPath.lowercase().contains("/screenshots/") || relPath.lowercase().startsWith("screenshots/")) && lens.isEmpty())
            flags += "screenshot"
        if ("GPano:ProjectionType" in xmp || "UsePanoramaViewer" in xmp ||
            (width > 0 && height > 0 && maxOf(width, height).toDouble() / maxOf(1, minOf(width, height)) >= 2.0 &&
                    minOf(width, height) >= 600 && "screenshot" !in flags))
            flags += "panorama"
        if (Regex("GDepth:|PortraitMode|DepthMap|apdi:|GCamera:SpecialTypeID=\"[^\"]*portrait").containsMatchIn(xmp)) flags += "portrait"
        if (Regex("MotionPhoto|MicroVideo").containsMatchIn(xmp)) flags += "live"
        if (Regex("BurstUUID|apple-fi:BurstUUID").containsMatchIn(xmp)) flags += "burst"
        return flags
    }

    fun videoFlags(name: String, fps: Double?, relPath: String = ""): MutableSet<String> {
        val flags = mutableSetOf<String>()
        if ((fps ?: 0.0) >= 100) flags += "slomo"
        if (SCREENREC_NAME.containsMatchIn(name.lowercase()) || relPath.lowercase().contains("screen recordings/") ||
            relPath.lowercase().contains("screenrecord")) flags += "screenrec"
        return flags
    }

    /** Coordinate ISO 6709 dei video: «+45.4642+009.1900+120.0/». */
    fun iso6709(s: String?): Triple<Double, Double, Double?>? {
        val m = Regex("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)?").find(s ?: return null) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (lat == 0.0 && lon == 0.0) return null
        return Triple(lat, lon, m.groupValues[3].toDoubleOrNull())
    }

    fun validCoords(lat: Double?, lon: Double?): Boolean =
        lat != null && lon != null && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180 && !(lat == 0.0 && lon == 0.0)

    val EXPOSURE_PROGRAMS = mapOf(0 to "Non definito", 1 to "Manuale", 2 to "Normale", 3 to "Priorità di apertura",
        4 to "Priorità di tempi", 5 to "Creativo", 6 to "Azione", 7 to "Ritratto", 8 to "Paesaggio")
    val METERING = mapOf(0 to "Sconosciuta", 1 to "Media", 2 to "Media ponderata al centro", 3 to "Spot",
        4 to "Multi-spot", 5 to "Matrice", 6 to "Parziale")
    val WHITE_BALANCE = mapOf(0 to "Automatico", 1 to "Manuale")
}

/** Metadati letti da un file, pronti per il database (colonne di [Library.MEDIA_COLUMNS]). */
data class MediaMeta(
    var kind: String = "photo",
    var mime: String? = null,
    var filesize: Long? = null,
    var mtime: Double? = null,
    var width: Int? = null,
    var height: Int? = null,
    var orientation: Int? = null,
    var taken: Double? = null,
    var takenGuess: Boolean = false,
    var make: String? = null,
    var model: String? = null,
    var lens: String? = null,
    var software: String? = null,
    var iso: Int? = null,
    var exposure: Double? = null,
    var fnumber: Double? = null,
    var focal: Double? = null,
    var focal35: Double? = null,
    var flash: Int? = null,
    var lat: Double? = null,
    var lon: Double? = null,
    var alt: Double? = null,
    var duration: Double? = null,
    var fps: Double? = null,
    var codec: String? = null,
    var flags: MutableSet<String> = mutableSetOf(),
    var caption: String? = null,
    var title: String? = null,
    var keywords: List<String> = emptyList(),
    var rating: Int = 0,
    var motionOffset: Long? = null,
    var exif: Map<String, String> = emptyMap(),
) {
    /** Data assente: dal nome del file, poi la data di modifica (come in ArchPhotos). */
    fun finish(name: String, size: Long, mtime: Double) {
        filesize = size
        this.mtime = mtime
        if (mime == null) mime = MediaInfo.mimeType(name)
        if (MediaInfo.isRaw(name)) flags += "raw"
        if (taken == null || taken == 0.0) {
            taken = MediaInfo.dateFromName(name) ?: mtime
            takenGuess = true
        }
        if (!MediaInfo.validCoords(lat, lon)) {
            lat = null; lon = null
        }
    }
}
