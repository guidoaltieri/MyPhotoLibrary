package io.github.guidoaltieri.myphotolibrary.data

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import io.github.guidoaltieri.myphotolibrary.core.Edit
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import io.github.guidoaltieri.myphotolibrary.core.extension
import io.github.guidoaltieri.myphotolibrary.core.stem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Esportazione (con o senza modifiche), condivisione, copia, «Usa come» e «Apri con» (come export.py). */
class Exporter(private val ctx: Context) {

    data class Options(
        val edited: Boolean = true,          // false: copia identica dell'originale
        val format: String = "jpeg",         // jpeg | png | webp | keep
        val quality: Int = 92,
        val maxSize: Int = 0,                // lato lungo massimo (0 = originale)
        val stripLocation: Boolean = false,
    )

    private val exportDir get() = File(ctx.cacheDir, "export").apply { mkdirs() }

    /** Prepara un file temporaneo con l'elemento esportato secondo le opzioni. */
    suspend fun render(item: Item, o: Options): File = withContext(Dispatchers.IO) {
        val recipe = Edit.load(item.edits)
        val src = Uri.parse(item.path)
        if (item.isVideo) {
            val dst = unique(File(exportDir, item.name))
            if (o.edited && (recipe.trim != null || recipe.mute)) transcode(src, dst, recipe.trim, recipe.mute)
            else copy(src, dst)
            return@withContext dst
        }
        val plainCopy = !o.edited || (item.edits.isNullOrEmpty() && o.format == "keep" && o.maxSize == 0 && !o.stripLocation)
        if (plainCopy) {
            val dst = unique(File(exportDir, item.name))
            copy(src, dst)
            if (o.stripLocation) stripGps(dst)
            return@withContext dst
        }
        var img = Images.decode(ctx, src, item.name, o.maxSize) ?: throw java.io.IOException("Immagine non leggibile: ${item.name}")
        if (o.edited && !item.edits.isNullOrEmpty()) img = Images.render(img, recipe)
        val fmt = if (o.format == "keep") when (extension(item.name)) { "png" -> "png"; "webp" -> "webp"; else -> "jpeg" } else o.format
        val ext = mapOf("jpeg" to "jpg", "png" to "png", "webp" to "webp").getValue(fmt)
        val dst = unique(File(exportDir, "${stem(item.name)}.$ext"))
        dst.outputStream().use { out ->
            val cf = when (fmt) {
                "png" -> Bitmap.CompressFormat.PNG
                "webp" -> if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.JPEG
            }
            img.compress(cf, o.quality, out)
        }
        if (fmt == "jpeg" || fmt == "png" || fmt == "webp") copyExif(src, dst, o.stripLocation)
        dst
    }

    private fun unique(f: File): File {
        if (!f.exists()) return f
        var i = 1
        while (true) {
            val c = File(f.parentFile, "${f.nameWithoutExtension} ($i).${f.extension}")
            if (!c.exists()) return c
            i++
        }
    }

    private fun copy(src: Uri, dst: File) {
        ctx.contentResolver.openInputStream(src)?.use { input -> dst.outputStream().use { input.copyTo(it, 1 shl 16) } }
            ?: throw java.io.IOException("File non leggibile")
    }

    /** Dati EXIF dell'originale sull'immagine esportata (già ruotata: orientamento 1). */
    private fun copyExif(src: Uri, dst: File, stripLocation: Boolean) {
        try {
            val orig = ctx.contentResolver.openFileDescriptor(src, "r")?.use { ExifInterface(it.fileDescriptor) } ?: return
            val out = ExifInterface(dst.absolutePath)
            for (tag in listOf(ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                    ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME,
                    ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
                    ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_MAKE,
                    ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_FLASH,
                    ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE)) {
                orig.getAttribute(tag)?.let { out.setAttribute(tag, it) }
            }
            if (!stripLocation) orig.latLong?.let { out.setLatLong(it[0], it[1]) }
            out.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            out.saveAttributes()
        } catch (e: Exception) {
        }
    }

    private fun stripGps(f: File) {
        try {
            val ex = ExifInterface(f.absolutePath)
            for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
                    ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF)) ex.setAttribute(tag, null)
            ex.saveAttributes()
        } catch (e: Exception) {
        }
    }

    /** Video tagliato o senza audio (Media3 Transformer, nel thread principale). */
    private suspend fun transcode(src: Uri, dst: File, trim: List<Double>?, mute: Boolean) = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val mi = MediaItem.Builder().setUri(src).apply {
                if (trim != null && trim.size == 2) setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs((trim[0] * 1000).toLong()).setEndPositionMs((trim[1] * 1000).toLong()).build())
            }.build()
            val edited = EditedMediaItem.Builder(mi).setRemoveAudio(mute).build()
            val t = Transformer.Builder(ctx).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    if (cont.isActive) cont.resumeWithException(exportException)
                }
            }).build()
            t.start(edited, dst.absolutePath)
            cont.invokeOnCancellation { t.cancel() }
        }
    }

    /** Salva un file esportato in Immagini/MyPhotoLibrary (o Film per i video). Restituisce l'URI. */
    fun saveToDevice(file: File, subdir: String = "MyPhotoLibrary"): Uri =
        Storage.insertMedia(ctx, file.name, "${if (MediaInfo.isVideo(file.name)) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES}/$subdir",
            Storage.copyFrom(ctx, Uri.fromFile(file)))

    /** Salva in una cartella scelta dall'utente (selettore di sistema: memoria, SD, app cloud). */
    fun saveToTree(file: File, tree: Uri): Uri =
        Storage.createInTree(ctx, tree, DocumentsContract.getTreeDocumentId(tree), file.name, Storage.copyFrom(ctx, Uri.fromFile(file)))

    /** URI condivisibile: l'originale se non ci sono modifiche, altrimenti la versione modificata. */
    suspend fun shareUri(item: Item): Uri = if (item.edits.isNullOrEmpty()) Uri.parse(item.path)
    else FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", render(item, Options()))

    suspend fun shareIntent(items: List<Item>): Intent {
        val uris = ArrayList(items.map { shareUri(it) })
        val mime = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = mime
        i.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(i, "Condividi")
    }

    /** Copia negli appunti (incollabile in chat, email, altre app). */
    suspend fun copyToClipboard(item: Item) {
        val uri = shareUri(item)
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        withContext(Dispatchers.Main) { cm.setPrimaryClip(ClipData.newUri(ctx.contentResolver, item.name, uri)) }
    }

    /** «Usa come»: sfondo, foto del contatto… */
    suspend fun useAsIntent(item: Item): Intent {
        val uri = shareUri(item)
        val i = Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(uri, "image/*").putExtra("mimeType", "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(i, "Usa come")
    }

    fun openWithIntent(item: Item): Intent {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.path), MediaInfo.mimeType(item.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(i, "Apri con")
    }

    fun clearCache() {
        exportDir.listFiles()?.forEach { it.delete() }
    }
}
