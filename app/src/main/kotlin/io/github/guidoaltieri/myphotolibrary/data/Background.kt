package io.github.guidoaltieri.myphotolibrary.data

import android.content.Context
import android.graphics.Bitmap
import io.github.guidoaltieri.myphotolibrary.core.Faces
import io.github.guidoaltieri.myphotolibrary.core.Geo
import io.github.guidoaltieri.myphotolibrary.core.Library
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Nomi dei luoghi in background (come Geocoder di geocode.py): prima le zone già note
 * (nessuna richiesta), poi quelle nuove, una al secondo.
 */
suspend fun resolvePlaces(lib: Library, enabled: () -> Boolean, updated: () -> Unit): Int {
    val known = lib.knownPlaces()
    val todo = mutableListOf<Pair<String, Pair<Double, Double>>>()
    for ((la, lo) in lib.placeKeysToResolve()) {
        val key = Geo.placeKey(la, lo)
        val name = known[key]
        if (name != null) lib.applyPlace(key, name) else todo += key to (la to lo)
    }
    if (known.isNotEmpty()) updated()
    var done = 0
    var fails = 0
    for ((key, ll) in todo) {
        currentCoroutineContext().ensureActive()
        if (!enabled()) break
        val res = try {
            Geo.reverse(ll.first, ll.second).also { fails = 0 }
        } catch (e: Exception) {
            if (++fails >= 3) break          // rete assente: riprova più tardi
            delay(5000)
            continue
        }
        lib.savePlace(key, res.name, res.city, res.region, res.country)
        lib.applyPlace(key, res.name)
        done++
        if (done % 10 == 0) updated()
        delay(1100)                          // regole d'uso di Nominatim: una richiesta al secondo
    }
    if (done > 0) updated()
    return done
}

/** Hash percettivi delle foto che non ce l'hanno ancora (per i duplicati). */
suspend fun computeHashes(ctx: Context, lib: Library, updated: () -> Unit): Int {
    var n = 0
    for (chunk in lib.missingHashes().chunked(50)) {
        currentCoroutineContext().ensureActive()
        val hashes = chunk.mapNotNull { item ->
            val b = Images.thumbnail(ctx, item, 96) ?: return@mapNotNull item.id to -1L
            item.id to Images.dhash(b)
        }
        lib.db.transaction { hashes.forEach { (id, h) -> lib.setDhash(id, h) } }
        n += hashes.size
    }
    if (n > 0) updated()
    return n
}

/**
 * Persone: rilevamento e riconoscimento dei volti, tutto sul telefono (come faces.py), con i
 * modelli YuNet e SFace di OpenCV Zoo scaricati una volta sola. Nessuna foto lascia il dispositivo.
 */
class FaceAnalyzer(private val ctx: Context, private val lib: Library) {
    private val dir = File(ctx.filesDir, "models")

    fun modelsReady() = File(dir, Faces.DETECTOR_NAME).exists() && File(dir, Faces.RECOGNIZER_NAME).exists()

    private fun download(progress: (String) -> Unit) {
        dir.mkdirs()
        for ((name, url) in listOf(Faces.DETECTOR_NAME to Faces.DETECTOR_URL, Faces.RECOGNIZER_NAME to Faces.RECOGNIZER_URL)) {
            val dst = File(dir, name)
            if (dst.exists()) continue
            progress("Scaricamento del modello $name…")
            val tmp = File(dir, "$name.part")
            val c = URL(url).openConnection() as HttpURLConnection
            c.setRequestProperty("User-Agent", Geo.userAgent)
            c.instanceFollowRedirects = true
            c.connectTimeout = 30000; c.readTimeout = 60000
            try {
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            } finally {
                c.disconnect()
            }
            if (tmp.length() < 10000) {     // pagina di errore invece del modello
                tmp.delete()
                throw java.io.IOException("download di $name non riuscito")
            }
            tmp.renameTo(dst)
        }
    }

    /** Analizza le foto non ancora esaminate. Restituisce il numero di volti trovati. */
    suspend fun run(progress: (String, Int, Int) -> Unit, updated: () -> Unit): Int {
        if (!OpenCVLoader.initLocal()) throw IllegalStateException("OpenCV non disponibile su questo dispositivo")
        if (!modelsReady()) download { progress(it, 0, 0) }
        val det = FaceDetectorYN.create(File(dir, Faces.DETECTOR_NAME).absolutePath, "", Size(320.0, 320.0), Faces.MIN_SCORE, 0.3f, 5000)
        val rec = FaceRecognizerSF.create(File(dir, Faces.RECOGNIZER_NAME).absolutePath, "")
        val todo = lib.faceTodo()
        val clusters = Faces.Clusters(lib.faceEmbeddings())
        var found = 0
        todo.forEachIndexed { n, item ->
            currentCoroutineContext().ensureActive()
            val bmp: Bitmap? = try { Images.open(ctx, item, 1024) } catch (e: Exception) { null }
            if (bmp == null) {
                lib.setFacesDone(item.id)
                return@forEachIndexed
            }
            val rgba = Mat()
            val bgr = Mat()
            val faces = Mat()
            try {
                val soft = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
                Utils.bitmapToMat(soft, rgba)
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                val w = bgr.cols().toDouble(); val h = bgr.rows().toDouble()
                det.setInputSize(Size(w, h))
                det.detect(bgr, faces)
                lib.db.transaction {
                    for (i in 0 until faces.rows()) {
                        val row = faces.row(i)
                        val f = FloatArray(15)
                        row.get(0, 0, f)
                        val score = f[14]
                        if (score < Faces.MIN_SCORE || minOf(f[2] / w, f[3] / h) < Faces.MIN_FACE) continue
                        val aligned = Mat()
                        val feat = Mat()
                        try {
                            rec.alignCrop(bgr, row, aligned)
                            rec.feature(aligned, feat)
                            val v = FloatArray(feat.total().toInt())
                            feat.get(0, 0, v)
                            val e = Faces.normalize(v)
                            val pid = clusters.match(e) ?: lib.newPerson()
                            clusters.add(pid, e)
                            lib.addFace(item.id, maxOf(0.0, f[0] / w), maxOf(0.0, f[1] / h), f[2] / w, f[3] / h, score.toDouble(), e, pid)
                            found++
                        } catch (ex: Exception) {
                            // volto non allineabile
                        } finally {
                            aligned.release(); feat.release()
                        }
                    }
                    lib.setFacesDone(item.id)
                }
            } finally {
                rgba.release(); bgr.release(); faces.release()
            }
            if ((n + 1) % 20 == 0) {
                progress("Analisi dei volti", n + 1, todo.size)
                updated()
            }
        }
        progress("Analisi dei volti", todo.size, todo.size)
        updated()
        return found
    }
}
