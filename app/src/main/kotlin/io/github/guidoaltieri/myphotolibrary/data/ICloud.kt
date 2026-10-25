package io.github.guidoaltieri.myphotolibrary.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.guidoaltieri.myphotolibrary.App
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.HttpTransport
import io.github.guidoaltieri.myphotolibrary.core.ICloudError
import io.github.guidoaltieri.myphotolibrary.core.ICloudNeedsCode
import io.github.guidoaltieri.myphotolibrary.core.ICloudPhotos
import io.github.guidoaltieri.myphotolibrary.core.ICloudSession
import io.github.guidoaltieri.myphotolibrary.core.ICloudStore
import io.github.guidoaltieri.myphotolibrary.core.ICloudSync
import io.github.guidoaltieri.myphotolibrary.core.Layouts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** La password dell'Apple ID, cifrata con una chiave del Keystore di Android (non lascia il telefono). */
private class Secrets(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val alias = "icloud-password"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }

    var password: String?
        get() = try {
            val data = Base64.decode(prefs.getString("pw", null) ?: return null, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
        set(v) {
            if (v == null) {
                prefs.edit().remove("pw").apply(); return
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            prefs.edit().putString("pw", Base64.encodeToString(c.iv + c.doFinal(v.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)).apply()
        }
}

private class FileStore(private val file: File) : ICloudStore {
    override fun load(): String? = if (file.exists()) file.readText() else null
    override fun save(json: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json)
        tmp.renameTo(file)
    }
}

/**
 * iCloud Foto → cartella di un cloud collegato: accesso, configurazione e avvio della copia in
 * background (WorkManager), anche a ogni apertura dell'app per le foto nuove.
 */
class ICloudManager(private val ctx: Context, private val repo: Repo) {
    private val secrets = Secrets(ctx)
    private val prefs = ctx.getSharedPreferences("icloud", Context.MODE_PRIVATE)
    private var session: ICloudSession? = null

    /** Stato mostrato nell'interfaccia (ultimo esito, errore, avanzamento). */
    val status = MutableStateFlow(prefs.getString("status", "") ?: "")
    val running = MutableStateFlow(false)
    private val active = java.util.concurrent.atomic.AtomicInteger()

    /** Il worker entra o esce: «in corso» finché ne resta almeno uno (uno fermato può finire dopo il nuovo). */
    fun working(on: Boolean) {
        running.value = (if (on) active.incrementAndGet() else active.decrementAndGet()) > 0
    }

    var appleId: String
        get() = prefs.getString("apple_id", "") ?: ""
        private set(v) = prefs.edit().putString("apple_id", v).apply()
    /** cartella di destinazione e copia automatica, osservabili dall'interfaccia */
    val destination = MutableStateFlow(prefs.getLong("dest", -1))
    val autoCopy = MutableStateFlow(prefs.getBoolean("enabled", false))

    var destSource: Long
        get() = destination.value
        set(v) {
            destination.value = v
            prefs.edit().putLong("dest", v).apply()
        }
    var layout: String
        get() = prefs.getString("layout", Layouts.DEFAULT) ?: Layouts.DEFAULT
        set(v) = prefs.edit().putString("layout", v).apply()
    var useExisting: Boolean
        get() = prefs.getBoolean("use_existing", true)
        set(v) = prefs.edit().putBoolean("use_existing", v).apply()
    var enabled: Boolean
        get() = autoCopy.value
        set(v) {
            autoCopy.value = v
            prefs.edit().putBoolean("enabled", v).apply()
        }
    /** la prima copia completa è finita: da qui in poi si cercano solo le foto nuove */
    var fullDone: Boolean
        get() = prefs.getBoolean("full_done", false)
        set(v) = prefs.edit().putBoolean("full_done", v).apply()
    var lastSync: Long
        get() = prefs.getLong("last_sync", 0)
        set(v) = prefs.edit().putLong("last_sync", v).apply()
    /** serve un nuovo codice di verifica (la copia in background si è fermata) */
    var needsLogin: Boolean
        get() = prefs.getBoolean("needs_login", false)
        set(v) = prefs.edit().putBoolean("needs_login", v).apply()

    fun setStatus(text: String) {
        status.value = text
        prefs.edit().putString("status", text).apply()
        log(text)
    }

    /** Registro per capire cosa succede in background (mai password né contenuti delle risposte). */
    val logFile = File(ctx.filesDir, "icloud.log")

    @Synchronized
    fun log(text: String) {
        try {
            if (logFile.length() > 256 * 1024) logFile.writeText(logFile.readText().takeLast(128 * 1024))
            val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
            logFile.appendText("$time  $text\n")
        } catch (e: Exception) {
        }
    }

    /** Le richieste ad Apple finiscono nel registro: metodo, indirizzo senza parametri, esito. */
    private val transport = HttpTransport { r ->
        val where = r.url.substringBefore('?')
        try {
            HttpTransport.DEFAULT.execute(r).also { log("${r.method} $where → ${it.status}") }
        } catch (e: Exception) {
            log("${r.method} $where → ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    val signedIn: Boolean get() = appleId.isNotEmpty() && secrets.password != null && !needsLogin
    val configured: Boolean get() = signedIn && destSource > 0 && repo.lib.source(destSource) != null

    private fun sessionFor(id: String): ICloudSession =
        session?.takeIf { it.appleId == id } ?: ICloudSession(id, FileStore(File(ctx.filesDir, "icloud/session.json")), transport).also { session = it }

    sealed interface Login {
        data object Ok : Login
        data object NeedsCode : Login
        data class Failed(val message: String) : Login
    }

    /** Accesso con Apple ID e password (la password resta cifrata sul telefono per i rinnovi). */
    suspend fun signIn(id: String, password: String): Login = withContext(Dispatchers.IO) {
        try {
            if (id != appleId) {
                File(ctx.filesDir, "icloud").deleteRecursively()
                session = null
                repo.lib.clearIcloudSynced()
                fullDone = false
            }
            appleId = id.trim()
            secrets.password = password
            log("Accesso con ${appleId.replace(Regex("(?<=.).(?=[^@]*@)"), "*")}")
            val s = sessionFor(appleId)
            s.signIn(password)
            s.accountLogin()
            if (s.requires2fa) {
                s.requestCode()
                log("Richiesto il codice di verifica")
                Login.NeedsCode
            } else {
                needsLogin = false
                Login.Ok
            }
        } catch (e: ICloudError) {
            log("Accesso non riuscito: ${e.message} (${e.code} ${e.status})")
            Login.Failed(e.message ?: "Accesso non riuscito")
        } catch (e: Exception) {
            log("Accesso non riuscito: $e")
            Login.Failed("Accesso non riuscito: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun resendCode(): Boolean = withContext(Dispatchers.IO) { sessionFor(appleId).requestCode() }

    suspend fun verifyCode(code: String): Login = withContext(Dispatchers.IO) {
        try {
            if (sessionFor(appleId).verifyCode(code)) {
                needsLogin = false
                setStatus("Accesso effettuato")
                Login.Ok
            } else Login.Failed("Codice non corretto")
        } catch (e: Exception) {
            log("Verifica del codice non riuscita: $e")
            Login.Failed(e.message ?: "Verifica non riuscita")
        }
    }

    /** Esce dall'account: password, sessione e stato della copia vengono cancellati (le foto copiate restano). */
    fun signOut() {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        try { session?.logout() } catch (e: Exception) {}
        File(ctx.filesDir, "icloud").deleteRecursively()
        session = null
        secrets.password = null
        repo.lib.clearIcloudSynced()
        prefs.edit().clear().apply()
        status.value = ""
        destination.value = -1
        autoCopy.value = false
        logFile.delete()
    }

    // --- copia in background --------------------------------------------------------------------
    companion object {
        const val WORK = "icloud-sync"
        const val CHANNEL = "sync"
        const val NOTIFICATION = 7101
    }

    /**
     * Avvia (o lascia proseguire) la copia: alla configurazione e a ogni apertura dell'app. Una copia in
     * corso non viene mai sostituita (prima ripartiva da capo a ogni tocco); [replace] serve solo a
     * cambiare destinazione o a non aspettare un nuovo tentativo programmato.
     */
    fun schedule(replace: Boolean = false) {
        if (!enabled || !configured) return
        if (replace && running.value) {
            log("Copia già in corso: prosegue")
            return
        }
        log(if (replace) "Copia avviata" else "Controllo delle foto nuove all'apertura")
        enqueue(if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP)
    }

    private fun enqueue(policy: ExistingWorkPolicy) {
        val req = OneTimeWorkRequestBuilder<ICloudWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, policy, req)
    }

    fun stop() {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK)
        if (running.value) setStatus("Copia fermata: riprenderà da dove era arrivata")
    }

    /** Nuova destinazione: la copia in corso (verso la vecchia) si ferma e si riparte. */
    fun restart() {
        if (!enabled || !configured) return
        log("Nuova destinazione: copia avviata")
        enqueue(ExistingWorkPolicy.REPLACE)
    }

    /** Il lavoro vero, chiamato dal worker. False: si ferma finché l'utente non interviene (accesso, destinazione). */
    suspend fun sync(cancelled: () -> Boolean, progress: suspend (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val src = repo.lib.source(destSource) ?: run { setStatus("La cartella di destinazione non esiste più"); return@withContext false }
        setStatus("Accesso a iCloud…")
        val s = sessionFor(appleId)
        try {
            s.authenticate { secrets.password }
        } catch (e: ICloudNeedsCode) {
            needsLogin = true
            setStatus("Serve un nuovo codice di verifica: apri iCloud Foto e accedi di nuovo")
            return@withContext false
        } catch (e: ICloudError) {
            setStatus("Accesso a iCloud non riuscito: ${e.message}")
            if (e.status == 0 || e.status >= 500) throw e         // rete o server: il worker riprova più tardi
            if (e.code == "auth") needsLogin = true
            return@withContext false
        }
        val photos = s.photos()
        if (!photos.ready()) {
            setStatus("Apple sta ancora preparando la fototeca iCloud: riprova più tardi")
            return@withContext true
        }
        val total = try { photos.count() } catch (e: Exception) { log("Conteggio non riuscito: $e"); 0 }
        setStatus("Fototeca iCloud: ${Fmt.thousands(total.toLong())} elementi. Copia verso ${src.name}…")
        val remote = object : ICloudSync.Remote {
            override fun page(offset: Int) = photos.page(offset)
            override fun open(url: String): java.io.InputStream {
                val t0 = System.currentTimeMillis()
                return photos.open(url).also { val ms = System.currentTimeMillis() - t0; if (ms > 3000) log("Risposta di iCloud: ${ms / 1000} s") }
            }
        }
        val dest = Storage.TreeTarget(ctx, Uri.parse(src.path), cancelled) { log(it) }
        log("Destinazione: ${src.name} (${Uri.parse(src.path).authority})")
        val importId = repo.lib.newImport("iCloud Foto")
        var lastUpdate = 0L
        val result = ICloudSync(repo.lib, src, remote, dest, layout, useExisting) { log(it) }.run(!fullDone, importId, cancelled) { p ->
            val text = if (p.current.isEmpty()) "Controllate ${Fmt.thousands(p.checked.toLong())} di ${Fmt.thousands(total.toLong())}"
            else "Copiate ${p.copied} · ${p.current}"
            repo.task.value = Task(if (p.current.isEmpty()) "iCloud Foto → ${src.name}" else "Copia di ${p.current}", p.checked, total)
            if (p.current.isNotEmpty() && p.copied + p.skipped + p.errors < 5) log("Copia di ${p.current}")
            if (System.currentTimeMillis() - lastUpdate > 1500) {
                lastUpdate = System.currentTimeMillis()
                repo.changed()                  // la galleria si aggiorna man mano
                kotlinx.coroutines.runBlocking { progress(text) }
            }
        }
        repo.task.value = null
        repo.changed()
        result.messages.forEach { log("Errore: $it") }
        log("Fine: copiate ${result.copied}, già presenti ${result.skipped}, errori ${result.errors}, completa ${result.complete}")
        if (cancelled()) return@withContext true        // fermata o sostituita: lo stato lo scrive chi è subentrato
        if (result.complete) {
            fullDone = true
            lastSync = System.currentTimeMillis()
        }
        val parts = mutableListOf(if (result.copied == 0) "Nessuna foto nuova" else "Copiate ${Fmt.count(result.copied.toLong(), "foto", "foto")}")
        if (result.skipped > 0) parts += "${result.skipped} già presenti"
        if (result.errors > 0) parts += "${result.errors} errori (${result.messages.firstOrNull() ?: ""})"
        setStatus((if (result.complete) "" else "Interrotta: riprenderà da qui. ") + parts.joinToString(" · ") +
            " · ${Fmt.relativeDay(System.currentTimeMillis() / 1000.0).lowercase()} alle ${Fmt.time(System.currentTimeMillis() / 1000.0)}")
        true
    }

    fun ensureChannel() {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Copia da iCloud Foto", NotificationManager.IMPORTANCE_LOW))
    }

    fun notification(text: String) = NotificationCompat.Builder(ctx, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle("iCloud Foto")
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .build()
}

/** Copia in background, con notifica: continua anche con l'app chiusa. */
class ICloudWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val icloud get() = (applicationContext as App).repo.icloud

    private fun foreground(text: String): ForegroundInfo {
        icloud.ensureChannel()
        return if (android.os.Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(ICloudManager.NOTIFICATION, icloud.notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(ICloudManager.NOTIFICATION, icloud.notification(text))
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foreground("Copia in corso…")

    override suspend fun doWork(): Result {
        try {
            setForeground(foreground("Controllo delle foto nuove…"))
        } catch (e: Exception) {
            // senza permesso per il servizio in primo piano si continua finché Android lo consente
        }
        icloud.working(true)
        return try {
            val ok = icloud.sync({ isStopped }) { text ->
                if (!isStopped) try {
                    // l'avanzamento è solo informativo: un errore qui non deve fermare la copia
                    setProgress(workDataOf("text" to text))
                    setForeground(foreground(text))
                } catch (e: Exception) {
                }
            }
            if (ok) Result.success() else Result.failure()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException || isStopped) {
                icloud.log("Copia fermata")
                Result.retry()
            } else {
                icloud.log(e.stackTraceToString().take(2000))
                icloud.setStatus("Copia interrotta: ${e.message ?: e.javaClass.simpleName}. Riprenderà da qui.")
                Result.retry()
            }
        } finally {
            icloud.working(false)
            if (!icloud.running.value) (applicationContext as App).repo.task.value = null
        }
    }
}
