package io.github.guidoaltieri.myphotolibrary.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.guidoaltieri.myphotolibrary.core.ICloudSync
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import io.github.guidoaltieri.myphotolibrary.core.basename
import io.github.guidoaltieri.myphotolibrary.core.dirname
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Accesso ai file condiviso da scansione, importazione ed esportazione. */
object Storage {
    /** File multimediale in una cartella scelta con il selettore di sistema. */
    data class DocFile(val uri: Uri, val name: String, val relpath: String, val size: Long, val mtime: Double)

    private val SKIP_DIRS = setOf("\$RECYCLE.BIN", "System Volume Information", "lost+found")
    private val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)

    /** Esito della lettura di una cartella: [error] se la cartella principale non risponde. */
    class Listing(val files: List<DocFile>, val failedDirs: Int, val error: String?) {
        val reachable get() = error == null
        /** tutte le sottocartelle hanno risposto: solo allora si possono togliere i file spariti */
        val complete get() = reachable && failedDirs == 0
    }

    private class ChildQuery(val cursor: Cursor?, val error: String?)

    /**
     * Elenco dei figli di un documento, tollerante con le app cloud:
     * - se l'app non risponde (processo appena avviato, accesso al servizio in corso) si riprova
     *   con attese crescenti invece di dichiarare subito la cartella non raggiungibile;
     * - se l'app risponde «sto ancora caricando» (EXTRA_LOADING) si aspetta che finisca e si
     *   rilegge, invece di prendere un elenco vuoto o parziale.
     */
    private fun queryChildren(ctx: Context, uri: Uri, cancelled: () -> Boolean, trace: (String) -> Unit = {}): ChildQuery {
        var lastError: String? = null
        for (wait in RETRY_MS) {
            if (cancelled()) break
            if (wait > 0) Thread.sleep(wait)
            var c: Cursor? = try {
                ctx.contentResolver.query(uri, PROJECTION, null, null, null)
            } catch (e: SecurityException) {
                // solo un rifiuto vero di Android: il permesso salvato non c'è più
                trace("rifiuto di Android: ${e.message}")
                return ChildQuery(null, "Android non concede più l'accesso alla cartella: ricollegala da Sorgenti")
            } catch (e: Exception) {
                lastError = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                trace("errore: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            // null: l'app non ha risposto (processo chiuso o andato in errore mentre rispondeva)
            trace(if (c == null) "nessuna risposta" else "${c.count} elementi, in caricamento ${c.extras.getBoolean(DocumentsContract.EXTRA_LOADING)}, " +
                "errore ${c.extras.getString(DocumentsContract.EXTRA_ERROR)}")
            var rounds = 0
            while (c != null && c.extras.getBoolean(DocumentsContract.EXTRA_LOADING) && rounds < LOADING_ROUNDS && !cancelled()) {
                val changed = waitForLoad(ctx, c, uri)
                c.close()
                c = try { ctx.contentResolver.query(uri, PROJECTION, null, null, null) } catch (e: Exception) { trace("errore: $e"); null }
                rounds++
                trace("  dopo ${if (changed) "gli avvisi dell'app" else "20 s senza avvisi"}: " + (c?.let { "${it.count} elementi, in caricamento " +
                    "${it.extras.getBoolean(DocumentsContract.EXTRA_LOADING)}" } ?: "nessuna risposta"))
            }
            if (c != null) {
                c.extras.getString(DocumentsContract.EXTRA_ERROR)?.let { if (c.count == 0) lastError = it }
                if (c.count > 0 || c.extras.getString(DocumentsContract.EXTRA_ERROR) == null)
                    return ChildQuery(if (uri.authority in LOCAL_PROVIDERS) c else followPages(ctx, c, uri, cancelled, trace), null)
                c.close()
            }
        }
        return ChildQuery(null, lastError ?: NOT_RESPONDING)
    }

    /**
     * App cloud che danno l'elenco a pagine (Proton Drive): la pagina successiva si prepara solo
     * quando si rilegge la cartella, e l'app avvisa quando è pronta. Si rilegge finché arrivano
     * avvisi; quando l'app tace per [PAGE_WAIT_MS] e una nuova lettura non porta altro, l'elenco è completo.
     */
    private fun followPages(ctx: Context, first: Cursor, uri: Uri, cancelled: () -> Boolean, trace: (String) -> Unit): Cursor {
        var c = first
        var pages = 0
        while (pages < MAX_PAGES && !cancelled()) {
            val changed = waitForLoad(ctx, c, uri, PAGE_WAIT_MS, PAGE_SETTLE_MS)
            val next = try { ctx.contentResolver.query(uri, PROJECTION, null, null, null) } catch (e: Exception) { null } ?: break
            // l'avviso può arrivare prima che ci si metta in ascolto: conta anche un elenco cresciuto
            if (!changed && next.count <= c.count) {
                next.close()
                break
            }
            c.close()
            c = next
            pages++
        }
        if (pages > 0) trace("  ${c.count} elementi dopo $pages nuove letture")
        return c
    }

    /**
     * Aspetta che l'app finisca di caricare: il primo avviso (al massimo [LOADING_WAIT_MS]) e poi
     * finché gli avvisi continuano. Alcune app (kDrive) avvisano a ogni pagina scaricata e, se si
     * rilegge prima della fine, rispondono «finito» con solo una parte dei file: si rilegge quando
     * l'app tace da [SETTLE_MS].
     */
    private fun waitForLoad(ctx: Context, c: Cursor, uri: Uri, firstWait: Long = LOADING_WAIT_MS, settle: Long = SETTLE_MS): Boolean {
        val events = LinkedBlockingQueue<Unit>()
        val obs = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                events.offer(Unit)
            }
        }
        c.registerContentObserver(obs)
        // alcune app avvisano sull'indirizzo «semplice» della cartella, non su quello del cursore
        val plain = try {
            DocumentsContract.buildChildDocumentsUri(uri.authority, DocumentsContract.getDocumentId(uri))
                .also { ctx.contentResolver.registerContentObserver(it, false, obs) }
        } catch (e: Exception) {
            null
        }
        try {
            events.poll(firstWait, TimeUnit.MILLISECONDS) ?: return false
            val end = System.currentTimeMillis() + SETTLE_MAX_MS
            while (System.currentTimeMillis() < end && events.poll(settle, TimeUnit.MILLISECONDS) != null) events.clear()
            return true
        } finally {
            c.unregisterContentObserver(obs)
            if (plain != null) ctx.contentResolver.unregisterContentObserver(obs)
        }
    }

    /** L'app del cloud non si avvia: spesso il telefono le impedisce di partire su richiesta di altre app. */
    const val NOT_RESPONDING = "l'app del cloud non risponde. Aprila e lasciala in background, oppure consentile " +
        "l'avvio automatico e la batteria «senza restrizioni» nelle impostazioni del telefono"

    /** App che fornisce le cartelle di [authority], per aprirla e farla ripartire. */
    fun providerLaunch(ctx: Context, authority: String): Intent? {
        val pm = ctx.packageManager
        val pkg = try { pm.resolveContentProvider(authority, 0)?.packageName } catch (e: Exception) { null }
        val candidates = listOfNotNull(pkg) + authority.split('.').let { parts -> (parts.size downTo 2).map { parts.take(it).joinToString(".") } }
        return candidates.firstNotNullOfOrNull { pm.getLaunchIntentForPackage(it) }
    }

    /** attese prima di ogni tentativo (circa 30 secondi in tutto) */
    private val RETRY_MS = longArrayOf(0, 1000, 2000, 4000, 8000, 15000)
    private const val LOADING_ROUNDS = 6
    private const val LOADING_WAIT_MS = 20_000L
    private const val SETTLE_MS = 4_000L
    private const val SETTLE_MAX_MS = 120_000L
    private const val PAGE_WAIT_MS = 3_000L
    private const val PAGE_SETTLE_MS = 1_000L
    private const val MAX_PAGES = 1000
    /** memoria del telefono, schede SD, download: rispondono subito con tutto l'elenco */
    private val LOCAL_PROVIDERS = setOf("com.android.externalstorage.documents", "com.android.providers.downloads.documents",
        "com.android.providers.media.documents")

    /** Nome della cartella come lo mostra l'app che la fornisce (non l'identificativo interno). */
    fun displayName(ctx: Context, tree: Uri): String? = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0)?.takeIf { n -> n.isNotBlank() } else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Foto e video di una cartella e delle sue sottocartelle (nascoste e di sistema escluse).
     * Le sottocartelle che non rispondono vengono contate: la lettura è allora incompleta.
     */
    fun walkTree(ctx: Context, tree: Uri, cancelled: () -> Boolean = { false }): Listing {
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return Listing(emptyList(), 0, "indirizzo della cartella non valido") }
        val out = ArrayList<DocFile>()
        val queue = ArrayDeque(listOf(rootId to ""))
        var failed = 0
        while (queue.isNotEmpty() && !cancelled()) {
            val (docId, rel) = queue.removeFirst()
            val q = queryChildren(ctx, DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), cancelled)
            val c = q.cursor
            if (c == null) {
                if (rel.isEmpty()) return Listing(emptyList(), 0, q.error)
                failed++
                continue
            }
            c.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    if (name.startsWith(".") || name.startsWith("@") || name.startsWith("#")) continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name !in SKIP_DIRS) queue.addLast(id to childRel)
                        continue
                    }
                    if (!MediaInfo.isMedia(name)) continue
                    out += DocFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, childRel,
                        if (it.isNull(3)) 0L else it.getLong(3), if (it.isNull(4)) 0.0 else it.getLong(4) / 1000.0)
                }
            }
        }
        return Listing(out, failed + queue.size, null)   // interrotta: le cartelle in coda non sono state lette
    }

    /** Nuovo file nella memoria del telefono ([relativeDir], es. «Pictures/MyPhotoLibrary/2024/05»). */
    fun insertMedia(ctx: Context, name: String, relativeDir: String, write: (OutputStream) -> Unit): Uri {
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, MediaInfo.mimeType(name))
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${relativeDir.trimEnd('/')}/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (MediaInfo.isVideo(name)) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = ctx.contentResolver.insert(coll, cv) ?: throw IOException("Impossibile creare $name")
        try {
            ctx.contentResolver.openOutputStream(uri)?.use(write) ?: throw IOException("Impossibile scrivere $name")
            ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Nuovo file in una cartella (documento [parentId]) di un albero scelto dall'utente. */
    fun createInTree(ctx: Context, tree: Uri, parentId: String, name: String, write: (OutputStream) -> Unit): Uri {
        val doc = DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId),
            MediaInfo.mimeType(name), name) ?: throw IOException("Impossibile creare $name")
        ctx.contentResolver.openOutputStream(doc)?.use(write) ?: throw IOException("Impossibile scrivere $name")
        return doc
    }

    /** Scrittore che copia un file del dispositivo. */
    fun copyFrom(ctx: Context, src: Uri): (OutputStream) -> Unit = { out ->
        ctx.contentResolver.openInputStream(src)?.use { it.copyTo(out, 1 shl 16) } ?: throw IOException("File non leggibile")
    }

    /**
     * Cartella scelta con il selettore di sistema (anche di un'app cloud) come destinazione di
     * importazioni e copie da iCloud: elenca cartelle e file, crea le cartelle mancanti.
     */
    class TreeTarget(private val ctx: Context, val tree: Uri, private val cancelled: () -> Boolean = { false },
                     private val log: (String) -> Unit = {}) : ICloudSync.Destination {
        private val ids = HashMap<String, String>()   // cartella relativa → id del documento
        // ogni cartella si legge una volta sola: con le app cloud un elenco può richiedere decine di secondi
        private val listings = HashMap<String, List<Triple<String, String, Long>>>()

        private inline fun <T> timed(what: String, block: () -> T): T {
            val t0 = System.currentTimeMillis()
            return block().also {
                val ms = System.currentTimeMillis() - t0
                if (ms > 3000) log("$what: ${ms / 1000} s")
            }
        }

        init {
            ids[""] = DocumentsContract.getTreeDocumentId(tree)
        }

        private fun children(rel: String): List<Triple<String, String, Long>> = listings.getOrPut(rel) {   // (nome, mime, dimensione)
            timed("Elenco di «${rel.ifEmpty { "/" }}»") { readChildren(rel) }
        }

        private fun readChildren(rel: String): List<Triple<String, String, Long>> {
            val id = ids[rel] ?: return emptyList()
            val out = mutableListOf<Triple<String, String, Long>>()
            val q = queryChildren(ctx, DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), cancelled) { log("Elenco di «${rel.ifEmpty { "/" }}» ($id): $it") }
            (q.cursor ?: throw ICloudSync.DestinationError("Cartella «${rel.ifEmpty { "/" }}» non leggibile: ${q.error}")).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) ids[if (rel.isEmpty()) name else "$rel/$name"] = c.getString(0)
                    out += Triple(name, mime, if (c.isNull(3)) 0L else c.getLong(3))
                }
            }
            return out
        }

        override fun listDirs(rel: String) = children(rel).filter { it.second == DocumentsContract.Document.MIME_TYPE_DIR }.map { it.first }
        override fun listFiles(rel: String) = children(rel).filter { it.second != DocumentsContract.Document.MIME_TYPE_DIR }.associate { it.first to it.third }

        private fun dirId(rel: String): String {
            ids[rel]?.let { return it }
            val parent = dirname(rel)
            val pid = dirId(parent)
            listDirs(parent)
            ids[rel]?.let { return it }
            val doc = timed("Creazione della cartella «$rel»") {
                DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, pid),
                    DocumentsContract.Document.MIME_TYPE_DIR, basename(rel))
            } ?: throw ICloudSync.DestinationError("Impossibile creare la cartella «$rel»")
            listings[rel] = emptyList()                  // appena creata: è vuota, non serve leggerla
            return DocumentsContract.getDocumentId(doc).also { ids[rel] = it }
        }

        /** Crea il file e lo riempie; l'URI è nella forma che usa la scansione della cartella. */
        override fun put(relFile: String, write: (OutputStream) -> Unit): ICloudSync.Written {
            val parent = dirId(dirname(relFile))
            val doc = timed("Scrittura di «$relFile»") { createInTree(ctx, tree, parent, basename(relFile), write) }
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(doc))
            var size = 0L
            var mtime = System.currentTimeMillis() / 1000.0
            try {
                ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        if (!c.isNull(0)) size = c.getLong(0)
                        if (!c.isNull(1)) mtime = c.getLong(1) / 1000.0
                    }
                }
            } catch (e: Exception) {
                // alcuni fornitori non danno subito i dati del file appena scritto
            }
            return ICloudSync.Written(uri.toString(), size, mtime)
        }
    }
}
