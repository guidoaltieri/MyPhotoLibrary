package io.github.guidoaltieri.myphotolibrary.data

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.extension
import java.io.File

/**
 * Operazioni sui file: eliminazione definitiva e scrittura di data e luogo nei metadati
 * (come export.trash e metawrite.py). Per i file di altre app Android chiede il consenso
 * all'utente (una volta per gruppo di file); con «Gestione contenuti multimediali» no.
 */
class FileOps(private val ctx: Context, private val ask: suspend (IntentSender) -> Boolean) {

    private fun isMediaStore(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    /** Elimina i file. Restituisce i percorsi effettivamente eliminati. */
    suspend fun delete(paths: List<String>): Set<String> {
        val uris = paths.map(Uri::parse)
        val done = mutableSetOf<String>()
        val media = uris.filter(::isMediaStore)
        if (media.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val ok = ask(MediaStore.createDeleteRequest(ctx.contentResolver, media).intentSender)
                if (ok) media.forEach { done += it.toString() }
            } else {
                for (u in media) {
                    try {
                        if (ctx.contentResolver.delete(u, null, null) > 0) done += u.toString()
                    } catch (e: RecoverableSecurityException) {
                        if (ask(e.userAction.actionIntent.intentSender)) {
                            try {
                                if (ctx.contentResolver.delete(u, null, null) > 0) done += u.toString()
                            } catch (e2: Exception) {
                            }
                        }
                    } catch (e: Exception) {
                    }
                }
            }
        }
        for (u in uris.filterNot(::isMediaStore)) {
            try {
                if (u.scheme == "file") {
                    if (File(u.path!!).delete()) done += u.toString()
                } else if (DocumentsContract.deleteDocument(ctx.contentResolver, u)) done += u.toString()
            } catch (e: Exception) {
            }
        }
        return done
    }

    /** Chiede in una volta sola il permesso di modificare i file di altre app. */
    private suspend fun requestWrite(uris: List<Uri>): Boolean {
        val media = uris.filter(::isMediaStore)
        if (media.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        return ask(MediaStore.createWriteRequest(ctx.contentResolver, media).intentSender)
    }

    data class WriteResult(val ok: Int, val unsupported: Int, val failed: Int, val messages: List<String>)

    private val WRITABLE = setOf("jpg", "jpeg", "jpe", "png", "webp", "dng")

    /**
     * Scrive data e/o posizione nei file (EXIF) senza ricomprimere le foto. [values]: elemento →
     * (data, lat, lon); lat/lon null con [clearGps] toglie la posizione. Esito registrato in
     * cache/scritture.log.
     */
    suspend fun writeMetadata(items: List<Item>, date: Boolean, location: Boolean, clearGps: Boolean = false): WriteResult {
        val writable = items.filter { !it.isVideo && extension(it.name) in WRITABLE }
        val unsupported = items.size - writable.size
        if (writable.isEmpty()) return WriteResult(0, unsupported, 0, emptyList())
        if (!requestWrite(writable.map { Uri.parse(it.path) })) return WriteResult(0, unsupported, writable.size, listOf("Permesso di modifica negato"))
        var ok = 0; var failed = 0
        val msgs = mutableListOf<String>()
        val log = File(ctx.cacheDir, "scritture.log")
        for (it in writable) {
            try {
                ctx.contentResolver.openFileDescriptor(Uri.parse(it.path), "rw")?.use { pfd ->
                    val ex = ExifInterface(pfd.fileDescriptor)
                    if (date) {
                        val t = Fmt.dt(it.taken)
                        val s = "%04d:%02d:%02d %02d:%02d:%02d".format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute, t.second)
                        ex.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, s)
                        ex.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, s)
                        ex.setAttribute(ExifInterface.TAG_DATETIME, s)
                        val off = java.time.ZoneId.systemDefault().rules.getOffset(java.time.Instant.ofEpochSecond(it.taken.toLong())).id
                            .let { o -> if (o == "Z") "+00:00" else o }
                        ex.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, off)
                    }
                    if (location) {
                        if (it.lat != null && it.lon != null) ex.setLatLong(it.lat!!, it.lon!!)
                        else if (clearGps) for (tag in listOf(ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
                                ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE,
                                ExifInterface.TAG_GPS_ALTITUDE_REF)) ex.setAttribute(tag, null)
                    }
                    ex.saveAttributes()
                }
                ok++
                log.appendText("${java.time.LocalDateTime.now()}  ok  ${it.relpath}\n")
            } catch (e: Exception) {
                failed++
                if (msgs.size < 20) msgs += "${it.name}: ${e.message ?: e.javaClass.simpleName}"
                log.appendText("${java.time.LocalDateTime.now()}  errore  ${it.relpath}: ${e.message}\n")
            }
        }
        return WriteResult(ok, unsupported, failed, msgs)
    }
}
