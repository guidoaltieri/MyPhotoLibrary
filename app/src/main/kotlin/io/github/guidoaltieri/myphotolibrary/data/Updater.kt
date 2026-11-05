package io.github.guidoaltieri.myphotolibrary.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import io.github.guidoaltieri.myphotolibrary.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamenti dell'app dalle release di GitHub: ogni compilazione pubblica una release
 * «build-N» con l'APK (N = versionCode). All'avvio si controlla se ce n'è una più nuova, la si
 * scarica e la si installa con PackageInstaller: Android chiede conferma, salvo dalla seconda
 * volta in poi (Android 12+), quando l'app è già lei a installare i propri aggiornamenti.
 *
 * Il repository è privato: serve un token di GitHub in sola lettura, salvato cifrato.
 */
class Updater(private val ctx: Context) {
    data class Release(val code: Int, val name: String, val notes: String, val assetUrl: String, val size: Long)

    private val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val secrets = Secrets(ctx, alias = "github-token", prefKey = "github_token")

    /** Versione nuova trovata (null: nessuna, o non ancora controllato). */
    val available = MutableStateFlow<Release?>(null)
    /** Avanzamento dello scaricamento (0..1), null se fermo. */
    val downloading = MutableStateFlow<Float?>(null)

    var token: String?
        get() = secrets.password
        set(v) = run { secrets.password = v?.trim()?.takeIf { it.isNotEmpty() } }

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /** Versione rifiutata con «Più tardi»: non si richiede finché non ne esce una più nuova. */
    private var skipped: Int
        get() = prefs.getInt("skipped", 0)
        set(v) = prefs.edit().putInt("skipped", v).apply()

    val current: Int get() = BuildConfig.VERSION_CODE

    companion object {
        const val REPO = "guidoaltieri/MyPhotoLibrary"
        const val ACTION_STATUS = "io.github.guidoaltieri.myphotolibrary.UPDATE_STATUS"
        private const val CHECK_EVERY_MS = 6 * 3600_000L
    }

    private fun open(url: String, accept: String, auth: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 60_000
            instanceFollowRedirects = false
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "MyPhotoLibrary/${BuildConfig.VERSION_NAME}")
            if (auth) token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }

    /** All'avvio: al massimo ogni 6 ore, e solo se non è stata rifiutata proprio quella versione. */
    suspend fun checkOnStart() {
        if (!auto || System.currentTimeMillis() - prefs.getLong("checked", 0) < CHECK_EVERY_MS) return
        try {
            check()?.let { if (it.code > skipped) available.value = it }
        } catch (e: Exception) {
            // senza rete o senza token: si riprova al prossimo avvio
        }
    }

    /** L'ultima release, se più nuova di questa versione. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val c = open("https://api.github.com/repos/$REPO/releases/latest", "application/vnd.github+json", auth = true)
        val code = c.responseCode
        if (code == 404 || code == 401 || code == 403) {
            c.disconnect()
            throw IOException(if (token == null) "serve il token di GitHub (il repository è privato)" else "token di GitHub non valido o senza accesso al repository")
        }
        if (code >= 400) throw IOException("GitHub ha risposto $code")
        val o = Json.parseToJsonElement(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }).jsonObject
        prefs.edit().putLong("checked", System.currentTimeMillis()).apply()
        val tag = o["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val n = tag.substringAfterLast('-').toIntOrNull() ?: return@withContext null
        val asset = o["assets"]?.jsonArray?.map { it.jsonObject }?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".apk") == true }
            ?: return@withContext null
        if (n <= current) return@withContext null
        Release(n, o["name"]?.jsonPrimitive?.contentOrNull ?: tag, o["body"]?.jsonPrimitive?.contentOrNull ?: "",
            asset["url"]!!.jsonPrimitive.content, asset["size"]?.jsonPrimitive?.intOrNull?.toLong() ?: 0L)
    }

    fun later(r: Release) {
        skipped = r.code
        available.value = null
    }

    /** Android permette all'app di installare APK? Altrimenti si apre l'impostazione da attivare. */
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Scarica l'APK (dal file della release, con un secondo passaggio senza token verso lo storage di GitHub). */
    suspend fun download(r: Release): File = withContext(Dispatchers.IO) {
        val out = File(ctx.cacheDir, "update.apk")
        downloading.value = 0f
        try {
            var c = open(r.assetUrl, "application/octet-stream", auth = true)
            var hops = 0
            while (c.responseCode in 300..399 && hops++ < 5) {
                val next = c.getHeaderField("Location") ?: break
                c.disconnect()
                // lo storage di GitHub rifiuta il token: va solo alla prima richiesta
                c = open(next, "application/octet-stream", auth = false)
            }
            if (c.responseCode >= 400) throw IOException("Scaricamento non riuscito (${c.responseCode})")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            c.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) downloading.value = (done.toFloat() / total).coerceAtMost(1f)
                    }
                }
            }
            out
        } finally {
            downloading.value = null
        }
    }

    /** Installa con PackageInstaller; l'esito (o la richiesta di conferma) arriva a [StatusReceiver]. */
    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            // da Android 12, se l'app ha già installato se stessa, l'aggiornamento non richiede conferma
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o, 1 shl 16) }; s.fsync(o) }
            val intent = Intent(ctx, StatusReceiver::class.java).setAction(ACTION_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }

    /** Esito dell'installazione: se serve, mostra la conferma di Android. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Unit      // l'app si riavvia aggiornata
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "installazione non riuscita"
                    (context.applicationContext as? io.github.guidoaltieri.myphotolibrary.App)?.repo?.say("Aggiornamento non installato: $msg")
                }
            }
        }
    }
}
