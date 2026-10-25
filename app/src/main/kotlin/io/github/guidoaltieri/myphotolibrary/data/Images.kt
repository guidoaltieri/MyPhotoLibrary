package io.github.guidoaltieri.myphotolibrary.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Size
import io.github.guidoaltieri.myphotolibrary.core.Edit
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import io.github.guidoaltieri.myphotolibrary.core.RawPreview
import io.github.guidoaltieri.myphotolibrary.core.Recipe
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** Apertura delle immagini (miniature, anteprime, piena risoluzione) e applicazione delle modifiche. */
object Images {

    /** Miniatura veloce: cache di sistema (MediaStore o fornitore del documento), poi decodifica ridotta. */
    fun thumbnail(ctx: Context, item: Item, size: Int): Bitmap? {
        val uri = Uri.parse(item.path)
        try {
            if (uri.authority == MediaStore.AUTHORITY) return ctx.contentResolver.loadThumbnail(uri, Size(size, size), null)
            if (DocumentsContract.isDocumentUri(ctx, uri)) {
                DocumentsContract.getDocumentThumbnail(ctx.contentResolver, uri, android.graphics.Point(size, size), null)?.let { return it }
            }
        } catch (e: Exception) {
            // nessuna miniatura di sistema: si decodifica il file
        }
        return if (item.isVideo) videoFrame(ctx, uri, item.duration, size) else decode(ctx, uri, item.name, size)
    }

    fun videoFrame(ctx: Context, uri: Uri, duration: Double?, size: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            val at = ((duration ?: 0.0) / 3).coerceIn(0.0, 1.0)
            r.getScaledFrameAtTime((at * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) {}
        }
    }

    /**
     * Decodifica con il lato lungo al massimo [maxSide] (0 = piena risoluzione). ImageDecoder
     * applica l'orientamento EXIF. Per i RAW si usa prima l'anteprima JPEG incorporata (si legge
     * solo quella, non i 20-30 MB del file), come in ArchPhotos.
     */
    fun decode(ctx: Context, uri: Uri, name: String, maxSide: Int): Bitmap? {
        if (MediaInfo.isRaw(name)) rawPreview(ctx, uri, maxSide)?.let { return it }
        try {
            val src = ImageDecoder.createSource(ctx.contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width; val h = info.size.height
                if (maxSide > 0 && max(w, h) > maxSide) {
                    val k = maxSide.toDouble() / max(w, h)
                    decoder.setTargetSize(max(1, (w * k).roundToInt()), max(1, (h * k).roundToInt()))
                }
            }
        } catch (e: Exception) {
            // formati non gestiti da ImageDecoder
        }
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            if (maxSide > 0) while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        } catch (e: Exception) {
            null
        }
    }

    /** Anteprima incorporata in un RAW (la più piccola che basta per [maxSide]), ruotata come il RAW. */
    private fun rawPreview(ctx: Context, uri: Uri, maxSide: Int): Bitmap? = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                val src = RawPreview.Source { pos, n ->
                    val buf = ByteBuffer.allocate(n)
                    var p = pos
                    while (buf.hasRemaining()) {
                        val r = ch.read(buf, p)
                        if (r <= 0) break
                        p += r
                    }
                    buf.array().copyOf(buf.position())
                }
                val bytes = RawPreview.read(src, maxSide) ?: return@use null
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                if (maxSide > 0) while (max(opts.outWidth, opts.outHeight) / (sample * 2) >= maxSide) sample *= 2
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: return@use null
                orient(b, RawPreview.orientation(src))
            }
        }
    } catch (e: Exception) {
        null
    }

    /** Applica un orientamento EXIF (1-8) a una bitmap. */
    private fun orient(b: Bitmap, o: Int): Bitmap {
        if (o <= 1 || o > 8) return b
        val m = Matrix()
        when (o) {
            2 -> m.postScale(-1f, 1f)
            3 -> m.postRotate(180f)
            4 -> m.postScale(1f, -1f)
            5 -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            6 -> m.postRotate(90f)
            7 -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            8 -> m.postRotate(270f)
        }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** Immagine di un elemento (foto: decodifica; video: fotogramma) con lato lungo ≤ [maxSide]. */
    fun open(ctx: Context, item: Item, maxSide: Int): Bitmap? {
        val uri = Uri.parse(item.path)
        return if (item.isVideo) videoFrame(ctx, uri, item.duration, if (maxSide > 0) maxSide else 4096) else decode(ctx, uri, item.name, maxSide)
    }

    // --- modifiche ---------------------------------------------------------------------------------

    /** Rotazione, riflessione, raddrizzamento e ritaglio (come edit.geometry). */
    fun geometry(src: Bitmap, r: Recipe, withCrop: Boolean = true): Bitmap {
        var img = src
        val rot = ((r.rotate % 360) + 360) % 360
        if (rot != 0 || r.flipH || r.flipV) {
            val m = Matrix()
            m.postRotate(rot.toFloat())
            if (r.flipH || r.flipV) m.postScale(if (r.flipH) -1f else 1f, if (r.flipV) -1f else 1f)
            img = Bitmap.createBitmap(img, 0, 0, img.width, img.height, m, true)
        }
        if (kotlin.math.abs(r.straighten) >= 0.05) {
            val w = img.width; val h = img.height
            val k = Edit.inscribedScale(w, h, r.straighten)
            val cw = max(2, (w * k).roundToInt()); val ch = max(2, (h * k).roundToInt())
            val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.translate(cw / 2f, ch / 2f)
            c.rotate(r.straighten.toFloat())
            c.translate(-w / 2f, -h / 2f)
            c.drawBitmap(img, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            img = out
        }
        if (withCrop) Edit.cropBox(img.width, img.height, r.crop)?.let { b ->
            img = Bitmap.createBitmap(img, b[0], b[1], b[2] - b[0], b[3] - b[1])
        }
        return img
    }

    /** Regolazioni di luce e colore (Edit.tone) su una Bitmap. */
    fun tone(src: Bitmap, adj: Map<String, Double>): Bitmap {
        if (adj.values.none { kotlin.math.abs(it) > 1e-3 }) return src
        return Bitmap.createBitmap(Edit.tone(pixels(src), src.width, src.height, adj), src.width, src.height, Bitmap.Config.ARGB_8888)
    }

    /** Applica la ricetta completa (anteprime, miniature, esportazione). */
    fun render(src: Bitmap, recipe: Recipe): Bitmap = tone(geometry(src, recipe), Edit.combinedAdjust(recipe))

    /**
     * Anteprima dell'editor. In Ritaglia si vede l'immagine intera (raddrizzata, ruotata e con le
     * regolazioni): il riquadro di ritaglio viene disegnato sopra dall'interfaccia.
     */
    fun preview(src: Bitmap, recipe: Recipe, cropMode: Boolean): Bitmap =
        if (cropMode) tone(geometry(src, recipe, withCrop = false), Edit.combinedAdjust(recipe)) else render(src, recipe)

    fun render(src: Bitmap, edits: String?): Bitmap = if (edits.isNullOrEmpty()) src else render(src, Edit.load(edits))

    /** La stessa immagine in ARGB_8888 software (richiesto da getPixels, OpenCV e dalle regolazioni). */
    fun argb(b: Bitmap): Bitmap = if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)

    fun pixels(b: Bitmap): IntArray {
        val px = IntArray(b.width * b.height)
        argb(b).getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return px
    }

    /** Hash percettivo (difference hash, 64 bit) per trovare i duplicati. */
    fun dhash(b: Bitmap): Long {
        val px = pixels(Bitmap.createScaledBitmap(argb(b), 9, 8, true))
        val gray = IntArray(72) { val p = px[it]; (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000 }
        return Edit.dhash(gray)
    }

    // --- Live Photo e Motion Photo ----------------------------------------------------------------

    /** Video di una Live Photo: il file abbinato o quello incorporato nella Motion Photo (estratto una volta). */
    fun liveVideo(ctx: Context, item: Item): Uri? {
        item.liveVideo?.let { return Uri.parse(it) }
        val off = item.motionOffset ?: return null
        val dir = File(ctx.cacheDir, "motion").apply { mkdirs() }
        val dest = File(dir, "${item.id}_${item.filesize}.mp4")
        if (dest.exists()) return Uri.fromFile(dest)
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(item.path))?.use { input ->
                var skipped = 0L
                while (skipped < off) {
                    val s = input.skip(off - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val data = input.readBytes()
                if (!String(data, 0, minOf(64, data.size), Charsets.ISO_8859_1).contains("ftyp")) return null
                val tmp = File(dir, dest.name + ".tmp")
                tmp.writeBytes(data)
                tmp.renameTo(dest)
                Uri.fromFile(dest)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Ritaglio quadrato del volto per le copertine delle persone. */
    fun faceCrop(ctx: Context, item: Item, x: Double, y: Double, w: Double, h: Double, size: Int): Bitmap? {
        val img = open(ctx, item, 1024) ?: return null
        val cx = (x + w / 2) * img.width; val cy = (y + h / 2) * img.height
        val side = max(w * img.width, h * img.height) * 1.8
        val l = max(0.0, cx - side / 2).toInt(); val t = max(0.0, cy - side / 2).toInt()
        val r = minOf(img.width.toDouble(), cx + side / 2).toInt(); val b = minOf(img.height.toDouble(), cy + side / 2).toInt()
        if (r - l < 2 || b - t < 2) return null
        val crop = Bitmap.createBitmap(img, l, t, r - l, b - t)
        return Bitmap.createScaledBitmap(crop, size, size * crop.height / crop.width, true)
    }
}
