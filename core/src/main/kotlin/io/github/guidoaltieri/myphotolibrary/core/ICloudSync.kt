package io.github.guidoaltieri.myphotolibrary.core

import java.io.InputStream
import java.io.OutputStream

/**
 * Copia di iCloud Foto in una cartella di un cloud (o del telefono), organizzata per data come le
 * importazioni. Ogni foto passa direttamente da iCloud alla destinazione (nessun file temporaneo)
 * e compare subito nella fototeca. Le foto già copiate non si copiano di nuovo; nelle
 * sincronizzazioni successive la lettura dell'elenco si ferma appena trova solo foto già copiate.
 */
class ICloudSync(
    private val lib: Library,
    private val source: Source,                 // la sorgente della fototeca che corrisponde alla destinazione
    private val photos: Remote,
    private val dest: Destination,
    layout: String,
    useExisting: Boolean,
) {
    /** L'elenco e gli originali di iCloud (ICloudPhotos, o uno finto nei test). */
    interface Remote {
        fun page(offset: Int): List<ICloudAsset>
        fun open(url: String): InputStream
    }

    /** Dove si scrivono i file (cartella SAF dell'app cloud scelta). */
    interface Destination : ImportTarget {
        /** Crea [relFile] e lo riempie con [write]; restituisce il file creato. */
        fun put(relFile: String, write: (OutputStream) -> Unit): Written
    }

    data class Written(val path: String, val size: Long, val mtime: Double)
    data class Progress(val checked: Int, val copied: Int, val skipped: Int, val errors: Int, val current: String)
    data class Result(val copied: Int, val skipped: Int, val errors: Int, val messages: List<String>, val complete: Boolean)

    private val planner = Planner(dest, layout, useExisting)

    companion object {
        /** foto già copiate consecutive dopo le quali una sincronizzazione successiva si ferma */
        const val STOP_AFTER_KNOWN = 50
    }

    /**
     * Sincronizza dal più recente. [full]: legge tutta la fototeca (prima volta o completamento di
     * una copia interrotta). [cancelled] interrompe tra un file e l'altro.
     */
    fun run(full: Boolean, importId: Long?, cancelled: () -> Boolean = { false }, progress: (Progress) -> Unit = {}): Result {
        var offset = 0
        var checked = 0; var copied = 0; var skipped = 0; var errors = 0
        var knownRun = 0
        val messages = mutableListOf<String>()
        val token = "icloud-%.0f".format(java.util.Locale.ROOT, Library.now())
        while (!cancelled()) {
            val page = photos.page(offset)
            if (page.isEmpty()) {
                lib.pairLivePhotos(source.id)
                return Result(copied, skipped, errors, messages, complete = true)
            }
            offset += page.size
            for (a in page) {
                if (cancelled()) break
                checked++
                if (lib.icloudSynced(a.id)) {
                    knownRun++
                    if (!full && knownRun >= STOP_AFTER_KNOWN) {
                        lib.pairLivePhotos(source.id)
                        return Result(copied, skipped, errors, messages, complete = true)
                    }
                    continue
                }
                knownRun = 0
                progress(Progress(checked, copied, skipped, errors, a.filename))
                try {
                    if (copyAsset(a, token, importId)) copied++ else skipped++
                } catch (e: Exception) {
                    errors++
                    if (messages.size < 20) messages += "${a.filename}: ${(e.message ?: e.javaClass.simpleName).take(200)}"
                }
            }
            progress(Progress(checked, copied, skipped, errors, ""))
        }
        lib.pairLivePhotos(source.id)
        return Result(copied, skipped, errors, messages, complete = false)
    }

    /** Copia una foto (e il video della Live Photo). False se c'era già nella destinazione. */
    private fun copyAsset(a: ICloudAsset, token: String, importId: Long?): Boolean {
        val taken = a.taken ?: a.addedDate ?: Library.now()
        val t = Fmt.dt(taken)
        val (rel, exists) = planner.destination(a.filename, a.size, t)
        var copied = false
        if (!exists) {
            val w = dest.put(rel) { out -> photos.open(a.url).use { it.copyTo(out, 1 shl 16) } }
            addToLibrary(a, w, rel, a.filename, a.isVideo, a.width, a.height, taken, token, importId)
            copied = true
        }
        if (a.liveUrl != null) {
            val (liveRel, liveExists) = planner.destination(a.liveFilename, a.liveSize ?: 0, t)
            if (!liveExists) {
                val w = dest.put(liveRel) { out -> photos.open(a.liveUrl).use { it.copyTo(out, 1 shl 16) } }
                addToLibrary(a, w, liveRel, a.liveFilename, true, a.width, a.height, taken, token, importId, duration = 3.0)
            }
        }
        lib.markIcloudSynced(a.id, rel)
        return copied
    }

    /** La foto compare subito nella fototeca, con i dati di iCloud (senza rileggere il file dal cloud). */
    private fun addToLibrary(a: ICloudAsset, w: Written, rel: String, name: String, video: Boolean, width: Int, height: Int,
                             taken: Double, token: String, importId: Long?, duration: Double? = null) {
        val m = MediaMeta(kind = if (video) "video" else "photo", width = width, height = height, taken = taken,
            lat = a.lat, lon = a.lon, caption = a.caption, duration = duration)
        if (!video && a.liveUrl != null) m.flags += "live"
        m.finish(name, w.size, w.mtime)
        val id = lib.upsert(source.id, w.path, name, rel, m, token, importId)
        if (a.favorite && !video) lib.setFavorite(listOf(id), true)
    }
}
