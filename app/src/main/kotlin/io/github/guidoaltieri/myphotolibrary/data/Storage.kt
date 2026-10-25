package io.github.guidoaltieri.myphotolibrary.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import java.io.IOException
import java.io.OutputStream

/** Accesso ai file condiviso da scansione, importazione ed esportazione. */
object Storage {
    /** File multimediale in una cartella scelta con il selettore di sistema. */
    data class DocFile(val uri: Uri, val name: String, val relpath: String, val size: Long, val mtime: Double)

    private val SKIP_DIRS = setOf("\$RECYCLE.BIN", "System Volume Information", "lost+found")
    private val PROJECTION = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)

    /**
     * Foto e video di una cartella e delle sue sottocartelle (nascoste e di sistema escluse).
     * null se la cartella non è leggibile (scheda tolta, permesso revocato).
     */
    fun walkTree(ctx: Context, tree: Uri, cancelled: () -> Boolean = { false }): List<DocFile>? {
        val rootId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { return null }
        val out = ArrayList<DocFile>()
        val queue = ArrayDeque(listOf(rootId to ""))
        var readable = false
        while (queue.isNotEmpty() && !cancelled()) {
            val (docId, rel) = queue.removeFirst()
            val c = try {
                ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId), PROJECTION, null, null, null)
            } catch (e: Exception) {
                null
            } ?: continue
            readable = true
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
        return if (readable) out else null
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
}
