package io.github.guidoaltieri.myphotolibrary.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import io.github.guidoaltieri.myphotolibrary.core.MediaMeta
import io.github.guidoaltieri.myphotolibrary.core.extension

/**
 * Lettura dei metadati di foto e video (porting di media.py): EXIF con ExifInterface, XMP
 * (Motion Photo, panoramiche, ritratti, parole chiave), video con MediaMetadataRetriever.
 */
class MetaReader(private val ctx: Context) {

    /** Metadati di un file. Non solleva eccezioni: null se il file è illeggibile. */
    fun read(uri: Uri, name: String, relpath: String, size: Long, mtime: Double, hintTaken: Double? = null,
             locationAllowed: Boolean = true): MediaMeta? {
        val meta = try {
            if (MediaInfo.isVideo(name)) readVideo(uri, name, relpath) else try {
                readImage(uri, name, relpath, size, locationAllowed)
            } catch (e: Exception) {
                if (!(MediaInfo.isRaw(name) || extension(name) in setOf("heic", "heif", "hif", "avif", "jxl"))) return null
                MediaMeta(kind = "photo")
            }
        } catch (e: Exception) {
            return null
        }
        if (meta.taken == null && hintTaken != null && hintTaken > 86400 * 365) meta.taken = hintTaken
        meta.finish(name, size, mtime)
        return meta
    }

    private fun originalUri(uri: Uri, locationAllowed: Boolean): Uri =
        if (locationAllowed && uri.authority == MediaStore.AUTHORITY) try { MediaStore.setRequireOriginal(uri) } catch (e: Exception) { uri } else uri

    private val EXIF_NAMES = linkedMapOf(
        ExifInterface.TAG_MAKE to "Produttore", ExifInterface.TAG_MODEL to "Modello", ExifInterface.TAG_ORIENTATION to "Orientamento",
        ExifInterface.TAG_SOFTWARE to "Software", ExifInterface.TAG_DATETIME to "Data modifica",
        ExifInterface.TAG_IMAGE_DESCRIPTION to "Descrizione", ExifInterface.TAG_ARTIST to "Autore", ExifInterface.TAG_COPYRIGHT to "Copyright",
        ExifInterface.TAG_EXPOSURE_TIME to "Tempo di esposizione", ExifInterface.TAG_F_NUMBER to "Apertura (ƒ)",
        ExifInterface.TAG_EXPOSURE_PROGRAM to "Programma di esposizione", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "ISO",
        ExifInterface.TAG_DATETIME_ORIGINAL to "Data scatto", ExifInterface.TAG_DATETIME_DIGITIZED to "Data digitalizzazione",
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "Fuso orario scatto", ExifInterface.TAG_SHUTTER_SPEED_VALUE to "Velocità otturatore (APEX)",
        ExifInterface.TAG_APERTURE_VALUE to "Apertura (APEX)", ExifInterface.TAG_BRIGHTNESS_VALUE to "Luminosità",
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE to "Compensazione esposizione", ExifInterface.TAG_MAX_APERTURE_VALUE to "Apertura massima",
        ExifInterface.TAG_METERING_MODE to "Modalità misurazione", ExifInterface.TAG_LIGHT_SOURCE to "Sorgente luce",
        ExifInterface.TAG_FLASH to "Flash", ExifInterface.TAG_FOCAL_LENGTH to "Lunghezza focale", ExifInterface.TAG_USER_COMMENT to "Commento",
        ExifInterface.TAG_COLOR_SPACE to "Spazio colore", ExifInterface.TAG_PIXEL_X_DIMENSION to "Larghezza (pixel)",
        ExifInterface.TAG_PIXEL_Y_DIMENSION to "Altezza (pixel)", ExifInterface.TAG_SENSING_METHOD to "Metodo di rilevamento",
        ExifInterface.TAG_EXPOSURE_MODE to "Modalità esposizione", ExifInterface.TAG_WHITE_BALANCE to "Bilanciamento del bianco",
        ExifInterface.TAG_DIGITAL_ZOOM_RATIO to "Zoom digitale", ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "Focale equivalente 35 mm",
        ExifInterface.TAG_SCENE_CAPTURE_TYPE to "Tipo di scena", ExifInterface.TAG_BODY_SERIAL_NUMBER to "Numero di serie corpo",
        ExifInterface.TAG_LENS_SPECIFICATION to "Specifiche obiettivo", ExifInterface.TAG_LENS_MAKE to "Produttore obiettivo",
        ExifInterface.TAG_LENS_MODEL to "Obiettivo", ExifInterface.TAG_IMAGE_UNIQUE_ID to "ID immagine",
        ExifInterface.TAG_GPS_ALTITUDE to "Altitudine GPS", ExifInterface.TAG_GPS_IMG_DIRECTION to "Direzione GPS",
        ExifInterface.TAG_GPS_SPEED to "Velocità GPS", ExifInterface.TAG_GPS_DATESTAMP to "Data GPS",
    )

    private fun readImage(uri: Uri, name: String, relpath: String, size: Long, locationAllowed: Boolean): MediaMeta {
        val m = MediaMeta(kind = "photo")
        val src = originalUri(uri, locationAllowed)
        var exif: ExifInterface? = null
        try {
            ctx.contentResolver.openFileDescriptor(src, "r")?.use { pfd -> exif = ExifInterface(pfd.fileDescriptor) }
        } catch (e: SecurityException) {
            ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> exif = ExifInterface(pfd.fileDescriptor) }
        } catch (e: Exception) {
            // formati senza EXIF leggibile (BMP, alcuni RAW): restano i dati del file
        }
        val ex = exif
        // dimensioni: ExifInterface le legge dall'intestazione (JPEG, HEIF, PNG, WebP); altrimenti si decodificano i limiti
        var w = ex?.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) ?: 0
        var h = ex?.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0) ?: 0
        if (w <= 0 || h <= 0) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            try {
                ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Exception) {
            }
            w = maxOf(0, opts.outWidth); h = maxOf(0, opts.outHeight)
        }
        var xmp = ""
        if (ex != null) {
            val orient = ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
            if (orient in 5..8) {
                val t = w; w = h; h = t
            }
            m.orientation = orient
            val model = ex.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
            m.model = model
            m.make = MediaInfo.cleanMake(ex.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty(), model)
            m.software = ex.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim().orEmpty()
            m.lens = ex.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim().orEmpty()
            m.iso = ex.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }
            m.exposure = ex.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }
            m.fnumber = ex.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }
            m.focal = ex.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }
            m.focal35 = ex.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0.0).takeIf { it > 0 }
            ex.getAttribute(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { m.flash = it and 1 }
            m.modifyDate = MediaInfo.parseExifDate(ex.getAttribute(ExifInterface.TAG_DATETIME))
            ex.latLong?.let { (lat, lon) ->
                if (MediaInfo.validCoords(lat, lon)) {
                    m.lat = lat; m.lon = lon
                    m.alt = ex.getAltitude(Double.NaN).takeIf { !it.isNaN() }
                }
            }
            m.caption = MediaInfo.cleanCaption(ex.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
            try {
                ex.getAttributeBytes(ExifInterface.TAG_XMP)?.let { xmp = String(it, Charsets.UTF_8) }
            } catch (e: Exception) {
            }
            // tutti i dati EXIF leggibili per la scheda informazioni
            val readable = LinkedHashMap<String, String>()
            for ((tag, label) in EXIF_NAMES) {
                val v = ex.getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() && it.length < 200 } ?: continue
                readable[label] = when (tag) {
                    ExifInterface.TAG_EXPOSURE_PROGRAM -> MediaInfo.EXPOSURE_PROGRAMS[v.toIntOrNull()] ?: v
                    ExifInterface.TAG_METERING_MODE -> MediaInfo.METERING[v.toIntOrNull()] ?: v
                    ExifInterface.TAG_WHITE_BALANCE -> MediaInfo.WHITE_BALANCE[v.toIntOrNull()] ?: v
                    ExifInterface.TAG_FLASH -> if ((v.toIntOrNull() ?: 0) and 1 == 1) "Scattato" else "Non scattato"
                    ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH ->
                        ex.getAttributeDouble(tag, 0.0).let { d -> if (d != 0.0) "%.4g".format(java.util.Locale.ROOT, d) else v }
                    else -> v
                }
            }
            m.exif = readable
        }
        // l'intestazione si legge solo se serve: XMP non esposto da ExifInterface o numero di fotogrammi
        val ext = extension(name)
        val needsFrames = ext in setOf("gif", "webp", "png")
        // (nei JPEG ExifInterface legge già il blocco XMP: se manca, il file non ce l'ha)
        val jpeg = ext in setOf("jpg", "jpeg", "jpe")
        val head = if ((xmp.isEmpty() && !(jpeg && ex != null)) || needsFrames) readHead(uri, 256 * 1024) else ByteArray(0)
        if (xmp.isEmpty()) xmp = xmpFrom(head)
        // data di scatto: ora «sull'orologio», poi XMP e GPS (come ArchPhotos); la data di modifica è solo una stima
        m.taken = MediaInfo.imageDate(ex?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL), ex?.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED),
            xmp, ex?.gpsDateTime?.takeIf { it > 0 }?.let { it / 1000.0 })
        m.width = w; m.height = h
        if (xmp.isNotEmpty()) {
            MediaInfo.xmpValues(xmp, "dc:description").firstOrNull()?.let { m.caption = it }
            m.title = MediaInfo.xmpValues(xmp, "dc:title").firstOrNull() ?: ""
            m.keywords = (MediaInfo.xmpValues(xmp, "dc:subject") + MediaInfo.xmpValues(xmp, "lr:hierarchicalSubject")).toSortedSet().toList()
            m.rating = MediaInfo.xmpValues(xmp, "xmp:Rating").firstOrNull()?.toDoubleOrNull()?.toInt() ?: 0
        }
        val comment = ex?.getAttribute(ExifInterface.TAG_USER_COMMENT)?.trim().orEmpty()
        m.flags = MediaInfo.photoFlags(name, m.lens.orEmpty(), comment, xmp, w, h, if (needsFrames) frames(head, name) else 1, ext.uppercase(), relpath)
        if (Regex("burst", RegexOption.IGNORE_CASE).containsMatchIn(name)) m.flags += "burst"
        if ("live" in m.flags) m.motionOffset = MediaInfo.motionOffset(xmp, size)
        return m
    }

    private fun readHead(uri: Uri, n: Int): ByteArray = try {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r <= 0) break
                off += r
            }
            buf.copyOf(off)
        } ?: ByteArray(0)
    } catch (e: Exception) {
        ByteArray(0)
    }

    /** XMP incorporato nell'intestazione del file (JPEG APP1, HEIC, PNG iTXt…). */
    private fun xmpFrom(head: ByteArray): String {
        val s = String(head, Charsets.ISO_8859_1)
        val a = s.indexOf("<x:xmpmeta")
        if (a < 0) return ""
        val b = s.indexOf("</x:xmpmeta>", a)
        if (b < 0) return ""
        return String(head, a, b + 12 - a, Charsets.UTF_8)
    }

    /** Più fotogrammi (GIF, WebP o PNG animati)? Basta l'intestazione. */
    private fun frames(head: ByteArray, name: String): Int {
        val s = String(head, Charsets.ISO_8859_1)
        return when (extension(name)) {
            "gif" -> if (Regex("!ù\u0004").findAll(s).take(2).count() > 1) 2 else 1
            "webp" -> if ("ANIM" in s.take(4096)) 2 else 1
            "png" -> if ("acTL" in s.take(8192)) 2 else 1
            else -> 1
        }
    }

    private fun readVideo(uri: Uri, name: String, relpath: String): MediaMeta {
        val m = MediaMeta(kind = "video")
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            fun k(key: Int) = try { r.extractMetadata(key) } catch (e: Exception) { null }
            m.duration = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000.0)
            var w = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (kotlin.math.abs(rot) % 180 == 90) {
                val t = w; w = h; h = t
            }
            m.width = w; m.height = h
            val capture = k(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull()
            val frames = k(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
            val fps = capture ?: if (frames != null && (m.duration ?: 0.0) > 0) frames / m.duration!! else null
            m.fps = fps?.let { Math.round(it * 100) / 100.0 }
            m.taken = MediaInfo.parseIsoDate(k(MediaMetadataRetriever.METADATA_KEY_DATE))
            MediaInfo.iso6709(k(MediaMetadataRetriever.METADATA_KEY_LOCATION))?.let { (lat, lon, alt) ->
                m.lat = lat; m.lon = lon; m.alt = alt
            }
            m.title = k(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            val readable = LinkedHashMap<String, String>()
            k(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { readable["Contenitore"] = it }
            k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let { readable["Bitrate"] = it }
            m.fps?.let { readable["Fotogrammi al secondo"] = it.toString() }
            if (rot != 0) readable["Rotazione"] = "$rot°"
            k(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.let { readable["Audio"] = if (it == "yes") "Sì" else "No" }
            k(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.let { readable["Trasferimento"] = it }
            m.exif = readable
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
        try {
            val ext = MediaExtractor()
            ext.setDataSource(ctx, uri, null)
            for (i in 0 until ext.trackCount) {
                val f = ext.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && m.codec == null) {
                    m.codec = mime.removePrefix("video/")
                    m.exif = m.exif + ("Codec video" to mime)
                }
                if (mime.startsWith("audio/")) {
                    val extra = mutableMapOf("Codec audio" to mime)
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) extra["Canali audio"] = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT).toString()
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) extra["Frequenza audio"] = f.getInteger(MediaFormat.KEY_SAMPLE_RATE).toString()
                    m.exif = m.exif + extra
                }
            }
            ext.release()
        } catch (e: Exception) {
        }
        m.flags = MediaInfo.videoFlags(name, m.fps, relpath)
        return m
    }
}
