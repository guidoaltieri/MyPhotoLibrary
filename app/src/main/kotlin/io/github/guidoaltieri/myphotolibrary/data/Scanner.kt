package io.github.guidoaltieri.myphotolibrary.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.guidoaltieri.myphotolibrary.core.Library
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import io.github.guidoaltieri.myphotolibrary.core.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Scansione incrementale delle sorgenti (come lo Scanner di library.py):
 * - «Questo dispositivo»: tutte le foto e i video di MediaStore (fotocamera, download, WhatsApp…);
 * - cartelle scelte dall'utente (schede SD, chiavette USB, app cloud che espongono le cartelle).
 *
 * Si rileggono solo i file nuovi o modificati; i file spariti si tolgono solo a scansione
 * completa e con le protezioni di ArchPhotos (cartella vuota, metà dei file spariti).
 */
class Scanner(private val ctx: Context, private val lib: Library, private val reader: MetaReader) {

    data class Progress(val source: String, val done: Int, val total: Int)
    data class Result(val changed: Int, val removed: Int, val errors: Int, val messages: List<String>)

    private data class Entry(val uri: Uri, val name: String, val relpath: String, val size: Long, val mtime: Double, val taken: Double?)

    suspend fun scan(sources: List<Source>, full: Boolean = false, locationAllowed: Boolean,
                     progress: (Progress) -> Unit, committed: () -> Unit): Result = withContext(Dispatchers.IO) {
        var changed = 0; var removed = 0; var errors = 0
        val messages = mutableListOf<String>()
        for (src in sources.filter { it.enabled }) {
            ensureActive()
            val entries = try {
                when (src.kind) {
                    "device" -> listMediaStore()
                    else -> listTree(Uri.parse(src.path))
                }
            } catch (e: Exception) {
                messages += "Sorgente non raggiungibile: ${src.name} (${e.message ?: e.javaClass.simpleName})"
                continue
            }
            if (entries == null) {
                messages += "Sorgente non raggiungibile: ${src.name}"
                continue
            }
            val token = "%.6f".format(java.util.Locale.ROOT, Library.now())
            val known = lib.known(src.id)
            val todo = mutableListOf<Entry>()
            val unchanged = mutableListOf<String>()
            for (e in entries) {
                val old = known[e.uri.toString()]
                val isNew = old == null || full || old.second != e.size || (old.first?.toLong() ?: 0L) != e.mtime.toLong()
                if (isNew) todo += e else unchanged += e.uri.toString()
            }
            lib.markSeen(unchanged, token)
            progress(Progress(src.name, 0, todo.size))
            val sem = Semaphore(4)
            var done = 0
            var lastCommit = System.currentTimeMillis()
            for (chunk in todo.chunked(64)) {
                ensureActive()
                val metas = chunk.map { e ->
                    async { sem.withPermit { e to reader.read(e.uri, e.name, e.relpath, e.size, e.mtime, e.taken, locationAllowed) } }
                }.awaitAll()
                lib.db.transaction {
                    for ((e, m) in metas) {
                        if (m == null) errors++ else {
                            lib.upsert(src.id, e.uri.toString(), e.name, e.relpath, m, token)
                            changed++
                        }
                    }
                }
                done += chunk.size
                progress(Progress(src.name, done, todo.size))
                if (System.currentTimeMillis() - lastCommit > 1500) {
                    lastCommit = System.currentTimeMillis()
                    committed()     // le foto lette compaiono nella libreria man mano
                }
            }
            val (r, msg) = lib.finishScan(src.id, token, known.size, src.kind != "device")
            removed += r
            msg?.let { messages += it }
            committed()
        }
        Result(changed, removed, errors, messages)
    }

    /** Tutte le foto e i video del dispositivo (tutti i volumi esterni). */
    private fun listMediaStore(): List<Entry> {
        val out = ArrayList<Entry>()
        val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.RELATIVE_PATH, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.MEDIA_TYPE, MediaStore.Files.FileColumns.VOLUME_NAME, MediaStore.MediaColumns.DATE_TAKEN)
        val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
        ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), proj, sel, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(proj[0]); val iName = c.getColumnIndexOrThrow(proj[1]); val iRel = c.getColumnIndexOrThrow(proj[2])
            val iSize = c.getColumnIndexOrThrow(proj[3]); val iMod = c.getColumnIndexOrThrow(proj[4]); val iType = c.getColumnIndexOrThrow(proj[5])
            val iVol = c.getColumnIndexOrThrow(proj[6]); val iTaken = c.getColumnIndexOrThrow(proj[7])
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                if (name.startsWith(".")) continue
                val rel = (c.getString(iRel) ?: "").trimEnd('/')
                if (rel.split("/").any { it.startsWith(".") }) continue
                val vol = c.getString(iVol) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val base = if (c.getInt(iType) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO) MediaStore.Video.Media.getContentUri(vol)
                else MediaStore.Images.Media.getContentUri(vol)
                val uri = ContentUris.withAppendedId(base, c.getLong(iId))
                val taken = if (c.isNull(iTaken)) null else c.getLong(iTaken) / 1000.0
                out += Entry(uri, name, if (rel.isEmpty()) name else "$rel/$name", c.getLong(iSize), c.getLong(iMod).toDouble(), taken)
            }
        }
        return out
    }

    /** File multimediali in una cartella scelta con il selettore di sistema (ricorsivo). */
    private fun listTree(tree: Uri): List<Entry>? {
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return null }
        val out = ArrayList<Entry>()
        val queue = ArrayDeque(listOf(rootId to ""))
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        var readable = false
        while (queue.isNotEmpty()) {
            val (docId, rel) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val c = try { ctx.contentResolver.query(children, proj, null, null, null) } catch (e: Exception) { null } ?: continue
            readable = true
            c.use {
                while (it.moveToNext()) {
                    val id = it.getString(0) ?: continue
                    val name = it.getString(1) ?: continue
                    val mime = it.getString(2) ?: ""
                    if (name.startsWith(".") || name.startsWith("@") || name.startsWith("#")) continue
                    val childRel = if (rel.isEmpty()) name else "$rel/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name !in setOf("\$RECYCLE.BIN", "System Volume Information", "lost+found")) queue.addLast(id to childRel)
                        continue
                    }
                    if (!MediaInfo.isMedia(name)) continue
                    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val mtime = if (it.isNull(4)) 0.0 else it.getLong(4) / 1000.0
                    out += Entry(uri, name, childRel, if (it.isNull(3)) 0L else it.getLong(3), mtime, null)
                }
            }
        }
        return if (readable) out else null
    }
}
