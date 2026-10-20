package io.github.guidoaltieri.myphotolibrary.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.ImportTarget
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import io.github.guidoaltieri.myphotolibrary.core.Planner
import io.github.guidoaltieri.myphotolibrary.core.basename
import io.github.guidoaltieri.myphotolibrary.core.dirname
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Importazione da schede SD, chiavette USB, fotocamere e cartelle (come uploader.py e importer.py):
 * le foto vengono copiate in Immagini/MyPhotoLibrary oppure in una cartella scelta (anche di
 * un'app cloud), organizzate per data secondo lo schema scelto.
 */
class Importer(private val ctx: Context, private val reader: MetaReader) {

    data class Candidate(val uri: Uri, val name: String, val relpath: String, val size: Long, val mtime: Double, val inLibrary: Boolean)

    /** File multimediali in una cartella (ricorsivo). */
    suspend fun list(tree: Uri, known: (String, Long) -> Boolean): List<Candidate> = withContext(Dispatchers.IO) {
        val out = ArrayList<Candidate>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val queue = ArrayDeque(listOf(rootId to ""))
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        while (queue.isNotEmpty()) {
            ensureActive()
            val (id, rel) = queue.removeFirst()
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val cid = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    if (name.startsWith(".")) continue
                    val r = if (rel.isEmpty()) name else "$rel/$name"
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        queue.addLast(cid to r); continue
                    }
                    if (!MediaInfo.isMedia(name)) continue
                    val size = if (c.isNull(3)) 0L else c.getLong(3)
                    out += Candidate(DocumentsContract.buildDocumentUriUsingTree(tree, cid), name, r, size,
                        if (c.isNull(4)) 0.0 else c.getLong(4) / 1000.0, known(name, size))
                }
            }
        }
        out.sortedBy { it.relpath }
    }

    /** Destinazione: Immagini/MyPhotoLibrary sul dispositivo (MediaStore). */
    inner class DeviceTarget(val base: String = "${Environment.DIRECTORY_PICTURES}/MyPhotoLibrary") : ImportTarget {
        private val rows: List<Triple<String, String, Long>> by lazy {   // (cartella relativa, nome, dimensione)
            val out = mutableListOf<Triple<String, String, Long>>()
            val proj = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
            ctx.contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), proj,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$base/%"), null)?.use { c ->
                while (c.moveToNext()) {
                    val rel = (c.getString(0) ?: "").trimEnd('/').removePrefix(base).trimStart('/')
                    out += Triple(rel, c.getString(1) ?: "", c.getLong(2))
                }
            }
            out
        }

        override fun listDirs(rel: String): List<String> = rows.map { it.first }
            .filter { if (rel.isEmpty()) it.isNotEmpty() else it.startsWith("$rel/") }
            .map { (if (rel.isEmpty()) it else it.removePrefix("$rel/")).substringBefore("/") }.distinct()

        override fun listFiles(rel: String): Map<String, Long> = rows.filter { it.first == rel }.associate { it.second to it.third }

        fun put(src: Uri, relFile: String): Uri {
            val name = basename(relFile)
            val dir = dirname(relFile)
            val video = MediaInfo.isVideo(name)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MediaInfo.mimeType(name))
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (dir.isEmpty()) "$base/" else "$base/$dir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = ctx.contentResolver.insert(coll, cv) ?: throw java.io.IOException("Impossibile creare $name")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> ctx.contentResolver.openInputStream(src)?.use { it.copyTo(out, 1 shl 16) } }
                ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
    }

    /** Destinazione: una cartella scelta con il selettore di sistema (anche di un'app cloud). */
    inner class TreeTarget(val tree: Uri) : ImportTarget {
        private val ids = HashMap<String, String>()   // cartella relativa → id del documento

        init {
            ids[""] = DocumentsContract.getTreeDocumentId(tree)
        }

        private fun children(rel: String): List<Triple<String, String, Long>> {   // (nome, mime, dimensione)
            val id = ids[rel] ?: return emptyList()
            val out = mutableListOf<Triple<String, String, Long>>()
            ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { c ->
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
            val doc = DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, pid),
                DocumentsContract.Document.MIME_TYPE_DIR, basename(rel)) ?: throw java.io.IOException("Impossibile creare la cartella $rel")
            val id = DocumentsContract.getDocumentId(doc)
            ids[rel] = id
            return id
        }

        fun put(src: Uri, relFile: String): Uri {
            val pid = dirId(dirname(relFile))
            val name = basename(relFile)
            val doc = DocumentsContract.createDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, pid),
                MediaInfo.mimeType(name), name) ?: throw java.io.IOException("Impossibile creare $name")
            ctx.contentResolver.openOutputStream(doc)?.use { out -> ctx.contentResolver.openInputStream(src)?.use { it.copyTo(out, 1 shl 16) } }
            return doc
        }
    }

    data class Progress(val done: Int, val total: Int, val name: String)
    data class Result(val copied: List<Uri>, val skipped: Int, val errors: Int, val messages: List<String>)

    /** Copia i file nella destinazione. */
    suspend fun transfer(files: List<Candidate>, target: ImportTarget, layout: String, useExisting: Boolean, deleteAfter: Boolean,
                         progress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        val planner = Planner(target, layout, useExisting)
        val copied = mutableListOf<Uri>()
        var skipped = 0; var errors = 0
        val messages = mutableListOf<String>()
        files.forEachIndexed { i, f ->
            ensureActive()
            try {
                // data dello scatto (EXIF), poi quella nel nome del file, poi la data di modifica
                val meta = reader.read(f.uri, f.name, f.relpath, f.size, f.mtime, null, false)
                val t = Fmt.dt(meta?.taken ?: MediaInfo.dateFromName(f.name) ?: f.mtime)
                val (rel, exists) = planner.destination(f.name, f.size, t)
                if (exists) skipped++ else {
                    copied += when (target) {
                        is DeviceTarget -> target.put(f.uri, rel)
                        is TreeTarget -> target.put(f.uri, rel)
                        else -> throw IllegalArgumentException()
                    }
                }
                if (deleteAfter) try { DocumentsContract.deleteDocument(ctx.contentResolver, f.uri) } catch (e: Exception) {}
            } catch (e: Exception) {
                errors++
                if (messages.size < 20) messages += "${f.name}: ${(e.message ?: e.javaClass.simpleName).take(200)}"
            }
            progress(Progress(i + 1, files.size, f.name))
        }
        Result(copied, skipped, errors, messages)
    }
}
