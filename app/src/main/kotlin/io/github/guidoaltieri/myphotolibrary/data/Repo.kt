package io.github.guidoaltieri.myphotolibrary.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import io.github.guidoaltieri.myphotolibrary.core.Geo
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.Library
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lavoro in corso mostrato nella barra di stato dell'app. */
data class Task(val label: String, val done: Int = 0, val total: Int = 0)

/**
 * Coordinamento condiviso dall'interfaccia (come core.py): fototeca, sorgenti, scansione,
 * lavori in background (luoghi, hash, volti), richieste di consenso del sistema.
 */
class Repo(val ctx: Context) {
    val db = AndroidDb(ctx)
    val lib = Library(db)
    val settings = Settings(ctx)
    val reader = MetaReader(ctx)
    val scanner = Scanner(ctx, lib, reader)
    val exporter = Exporter(ctx)
    val importer = Importer(ctx, reader)
    val faces = FaceAnalyzer(ctx, lib)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Cresce a ogni modifica della fototeca: le schermate si ricaricano. */
    val version = MutableStateFlow(0)
    fun changed() {
        version.value = version.value + 1
    }

    val task = MutableStateFlow<Task?>(null)
    /** Messaggi brevi per l'utente (snackbar). */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    fun say(text: String) {
        messages.tryEmit(text)
    }

    // --- richieste al sistema (consenso per eliminare/modificare, attività da avviare) -----------
    class Pending(val sender: IntentSender, val result: CompletableDeferred<Boolean>)
    val pending = MutableSharedFlow<Pending>(extraBufferCapacity = 4)
    val intents = MutableSharedFlow<Intent>(extraBufferCapacity = 4)

    suspend fun ask(sender: IntentSender): Boolean {
        val p = Pending(sender, CompletableDeferred())
        pending.emit(p)
        return p.result.await()
    }

    fun launch(intent: Intent) {
        intents.tryEmit(intent)
    }

    val files = FileOps(ctx, ::ask)

    init {
        Geo.userAgent = "MyPhotoLibrary/1.0 (Android; +https://github.com/guidoaltieri/MyPhotoLibrary)"
        if (lib.sources().none { it.kind == "device" }) lib.addSource("device", "Questo dispositivo", "")
    }

    fun hasMediaPermission(): Boolean {
        val perms = if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val full = perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val partial = Build.VERSION.SDK_INT >= 34 &&
            ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        return full || partial
    }

    private fun locationAllowed() = ctx.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun canManageMedia() = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(ctx)

    // --- scansione ---------------------------------------------------------------------------
    private val scanLock = Mutex()
    private var scanJob: Job? = null

    /** Aggiorna le sorgenti (tutte o [sourceIds]), poi luoghi, hash e volti. */
    fun scan(sourceIds: List<Long>? = null, full: Boolean = false, after: (suspend () -> Unit)? = null): Job = scope.launch {
        scanLock.withLock {
            val srcs = lib.sources().filter { (sourceIds == null || it.id in sourceIds) && (it.kind != "device" || hasMediaPermission()) }
            task.value = Task("Lettura della fototeca")
            try {
                val res = scanner.scan(srcs, full, locationAllowed(), { p -> task.value = Task("Lettura: ${p.source}", p.done, p.total) }, ::changed)
                res.messages.forEach { say(it) }
                if (res.errors > 0) say("${res.errors} file non leggibili")
            } catch (e: Exception) {
                say("Scansione interrotta: ${e.message}")
            } finally {
                task.value = null
                changed()
            }
            after?.invoke()
        }
        background()
    }.also { scanJob = it }

    private var bgJob: Job? = null

    /** Lavori di sottofondo dopo la scansione: hash percettivi, nomi dei luoghi, volti. */
    fun background() {
        if (bgJob?.isActive == true) return
        bgJob = scope.launch {
            try {
                task.value = Task("Ricerca dei duplicati")
                computeHashes(ctx, lib, ::changed)
                if (settings.placeNames.value) {
                    task.value = Task("Nomi dei luoghi")
                    resolvePlaces(lib, { settings.placeNames.value }, ::changed)
                }
                if (settings.faces.value && faces.modelsReady()) analyzeFaces()
            } catch (e: Exception) {
                // riprova alla prossima scansione
            } finally {
                task.value = null
            }
        }
    }

    suspend fun analyzeFaces() {
        try {
            val n = faces.run({ label, d, t -> task.value = Task(label, d, t) }, ::changed)
            if (n > 0) say("Trovati $n volti")
        } catch (e: Exception) {
            say("Riconoscimento dei volti non riuscito: ${e.message}")
        } finally {
            task.value = null
        }
    }

    fun startFaces() {
        settings.faces.value = true
        scope.launch { analyzeFaces() }
    }

    // --- aggiornamenti automatici quando cambiano le foto del dispositivo ---------------------
    private var observer: ContentObserver? = null
    private var pendingScan: Job? = null

    fun watchMediaStore() {
        if (observer != null) return
        observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                pendingScan?.cancel()
                pendingScan = scope.launch {
                    delay(3000)
                    scan(lib.sources().filter { it.kind == "device" }.map { it.id })
                }
            }
        }.also {
            ctx.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, it)
            ctx.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, it)
        }
    }

    // --- operazioni sugli elementi -------------------------------------------------------------
    fun io(block: suspend () -> Unit) = scope.launch {
        try {
            block()
        } catch (e: Exception) {
            say(e.message ?: e.javaClass.simpleName)
        } finally {
            changed()
        }
    }

    /** Elimina definitivamente: i file nel cestino del sistema (con il consenso) e poi dalla fototeca. */
    suspend fun purge(ids: Collection<Long>) {
        val rows = lib.purge(ids)
        val paths = rows.flatMap { listOfNotNull(it.path, it.liveVideo) }
        val deleted = files.delete(paths)
        val gone = rows.filter { it.path in deleted }.map { it.id }
        lib.forget(gone)
        // file non eliminati (consenso negato): tornano tra gli eliminati di recente
        val kept = rows.filter { it.path !in deleted }.map { it.id }
        if (kept.isNotEmpty()) {
            val (q, p) = io.github.guidoaltieri.myphotolibrary.core.inClause(kept)
            db.exec("UPDATE media SET paired = 0 WHERE id IN $q", p)
            say("${kept.size} elementi non eliminati")
        }
        changed()
    }

    /** Elementi negli eliminati da più di 30 giorni. */
    fun expired(): List<Long> = lib.expiredDeleted()

    /** Scrive nei file data e/o posizione degli elementi indicati, se l'opzione è attiva. */
    suspend fun writeToFiles(ids: Collection<Long>, date: Boolean, location: Boolean, clearGps: Boolean = false) {
        if (!settings.writeFiles.value) return
        val items = ids.mapNotNull { lib.item(it) }
        val r = files.writeMetadata(items, date, location, clearGps)
        val parts = mutableListOf<String>()
        if (r.ok > 0) parts += "Scritti nei file: ${r.ok}"
        if (r.unsupported > 0) parts += "${r.unsupported} in formati non modificabili (restano nella fototeca)"
        if (r.failed > 0) parts += "${r.failed} errori"
        if (parts.isNotEmpty()) say(parts.joinToString(" · "))
        if (r.ok > 0) scan(items.map { it.sourceId }.distinct())
    }

    fun items(ids: Collection<Long>): List<Item> = ids.mapNotNull { lib.item(it) }
}
