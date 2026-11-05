package io.github.guidoaltieri.myphotolibrary.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Modifiche non distruttive (porting di edit.py): la foto originale non viene mai toccata.
 * Una modifica è una *ricetta* JSON salvata nel database e applicata al volo (anteprime,
 * miniature, esportazione), compatibile con quella di ArchPhotos:
 *
 *     {"rotate": 90, "flip_h": false, "flip_v": false, "straighten": -2.5,
 *      "crop": [x, y, w, h],                 // relativi (0..1), dopo rotazione e raddrizzamento
 *      "adjust": {"exposure": 0.2, ...},     // valori da -1 a 1
 *      "filter": "vivid", "filter_amount": 1.0,
 *      "trim": [inizio, fine], "mute": false} // solo video
 */
@Serializable
data class Recipe(
    val rotate: Int = 0,
    @SerialName("flip_h") val flipH: Boolean = false,
    @SerialName("flip_v") val flipV: Boolean = false,
    val straighten: Double = 0.0,
    val crop: List<Double>? = null,
    val adjust: Map<String, Double> = emptyMap(),
    val filter: String? = null,
    @SerialName("filter_amount") val filterAmount: Double = 1.0,
    val trim: List<Double>? = null,
    val mute: Boolean = false,
    val aspect: String? = null,
) {
    fun toJson(): String = Edit.json.encodeToString(serializer(), this)
}

object Edit {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    data class Adjustment(val key: String, val label: String, val group: String)

    val ADJUSTMENTS = listOf(
        Adjustment("exposure", "Esposizione", "Luce"), Adjustment("brilliance", "Brillantezza", "Luce"),
        Adjustment("highlights", "Luci", "Luce"), Adjustment("shadows", "Ombre", "Luce"), Adjustment("contrast", "Contrasto", "Luce"),
        Adjustment("brightness", "Luminosità", "Luce"), Adjustment("blackpoint", "Punto di nero", "Luce"),
        Adjustment("saturation", "Saturazione", "Colore"), Adjustment("vibrance", "Vividezza", "Colore"),
        Adjustment("warmth", "Calore", "Colore"), Adjustment("tint", "Tinta", "Colore"),
        Adjustment("bw", "Bianco e nero", "Bianco e nero"),
        Adjustment("sharpness", "Nitidezza", "Dettagli"), Adjustment("definition", "Definizione", "Dettagli"),
        Adjustment("noise", "Riduzione rumore", "Dettagli"),
        Adjustment("vignette", "Vignettatura", "Vignettatura"),
    )
    val POSITIVE_ONLY = setOf("bw", "noise")   // cursori da 0 a 1

    data class Filter(val key: String, val label: String, val adjust: Map<String, Double>)

    val FILTERS = listOf(
        Filter("", "Originale", emptyMap()),
        Filter("vivid", "Vivido", mapOf("saturation" to 0.35, "contrast" to 0.15, "vibrance" to 0.2)),
        Filter("vivid_warm", "Vivido caldo", mapOf("saturation" to 0.3, "contrast" to 0.15, "warmth" to 0.35)),
        Filter("vivid_cool", "Vivido freddo", mapOf("saturation" to 0.3, "contrast" to 0.15, "warmth" to -0.35)),
        Filter("dramatic", "Drammatico", mapOf("contrast" to 0.4, "saturation" to -0.25, "shadows" to -0.2, "highlights" to -0.3)),
        Filter("dramatic_warm", "Drammatico caldo", mapOf("contrast" to 0.4, "saturation" to -0.2, "shadows" to -0.2, "warmth" to 0.35)),
        Filter("dramatic_cool", "Drammatico freddo", mapOf("contrast" to 0.4, "saturation" to -0.2, "shadows" to -0.2, "warmth" to -0.35)),
        Filter("mono", "Mono", mapOf("bw" to 1.0)),
        Filter("silvertone", "Argento", mapOf("bw" to 1.0, "brightness" to 0.15, "contrast" to 0.1, "warmth" to -0.05)),
        Filter("noir", "Noir", mapOf("bw" to 1.0, "contrast" to 0.6, "blackpoint" to 0.3, "vignette" to 0.3)),
    )
    val FILTER_NAMES = FILTERS.associate { it.key to it.label }

    /** proporzioni del ritaglio: chiave, etichetta, rapporto (null = libero, 0 = originale) */
    val ASPECTS = listOf(Triple("free", "Libero", null), Triple("original", "Originale", 0.0), Triple("square", "Quadrato", 1.0),
        Triple("16:9", "16:9", 16 / 9.0), Triple("4:3", "4:3", 4 / 3.0), Triple("3:2", "3:2", 3 / 2.0), Triple("5:4", "5:4", 5 / 4.0),
        Triple("9:16", "9:16", 9 / 16.0), Triple("3:4", "3:4", 3 / 4.0), Triple("2:3", "2:3", 2 / 3.0))

    fun load(recipe: String?): Recipe = if (recipe.isNullOrBlank()) Recipe() else try {
        json.decodeFromString(Recipe.serializer(), recipe)
    } catch (e: Exception) {
        Recipe()
    }

    fun fullCrop(c: List<Double>?) = c == null || (abs(c[0]) < 1e-4 && abs(c[1]) < 1e-4 && abs(c[2] - 1) < 1e-4 && abs(c[3] - 1) < 1e-4)

    fun isIdentity(r: Recipe): Boolean {
        val adj = r.adjust.filterValues { abs(it) > 1e-3 }
        return adj.isEmpty() && r.filter.isNullOrEmpty() && r.rotate % 360 == 0 && !r.flipH && !r.flipV &&
            abs(r.straighten) < 0.05 && fullCrop(r.crop) && r.trim == null && !r.mute
    }

    /** Ricetta senza valori neutri; null se non resta nessuna modifica. */
    fun clean(r: Recipe): Recipe? {
        val c = r.copy(
            adjust = r.adjust.filterValues { abs(it) > 1e-3 }.mapValues { Math.round(it.value * 1000) / 1000.0 },
            filter = r.filter?.takeIf { it.isNotEmpty() },
            filterAmount = if (r.filter.isNullOrEmpty()) 1.0 else r.filterAmount,
            rotate = ((r.rotate % 360) + 360) % 360,
            straighten = if (abs(r.straighten) < 0.05) 0.0 else r.straighten,
            crop = if (fullCrop(r.crop)) null else r.crop,
            aspect = null,
        )
        return if (isIdentity(c)) null else c
    }

    /** Regolazioni manuali + filtro (pesato per l'intensità). */
    fun combinedAdjust(r: Recipe): Map<String, Double> {
        val adj = r.adjust.toMutableMap()
        val f = r.filter
        if (!f.isNullOrEmpty()) {
            for ((k, v) in FILTERS.firstOrNull { it.key == f }?.adjust ?: emptyMap())
                adj[k] = (adj.getOrDefault(k, 0.0) + v * r.filterAmount).coerceIn(-1.0, 1.0)
        }
        return adj
    }

    /** Ingrandimento necessario perché una foto raddrizzata non mostri angoli vuoti. */
    fun inscribedScale(w: Int, h: Int, degrees: Double): Double {
        val a = Math.toRadians(abs(degrees))
        if (a < 1e-6) return 1.0
        val long = max(w, h).toDouble()
        val short = min(w, h).toDouble()
        return 1.0 / (cos(a) + long / short * sin(a))
    }

    /** Dimensioni dell'immagine dopo la rotazione della ricetta, senza ritaglio. */
    fun orientedSize(width: Int, height: Int, r: Recipe): Pair<Int, Int> =
        if (r.rotate % 180 != 0) height to width else width to height

    /** Rapporto da imporre al ritaglio: `0` in [ASPECTS] vuol dire «come la foto». */
    fun aspectRatio(ratio: Double?, width: Int, height: Int, r: Recipe): Double? =
        if (ratio == 0.0) orientedSize(width, height, r).let { (w, h) -> w.toDouble() / h } else ratio

    /** Il riquadro (relativo) segue la foto ruotata di 90° ([degrees] = 90) o -90°. */
    fun rotateCrop(crop: List<Double>, degrees: Int): List<Double> {
        val (x, y, w, h) = crop
        return if (degrees == 90) listOf(1 - y - h, x, h, w) else listOf(y, 1 - x - w, h, w)
    }

    /** Il riquadro segue la foto riflessa in orizzontale ([horizontal]) o in verticale. */
    fun flipCrop(crop: List<Double>, horizontal: Boolean): List<Double> {
        val (x, y, w, h) = crop
        return if (horizontal) listOf(1 - x - w, y, w, h) else listOf(x, 1 - y - h, w, h)
    }

    /** Riquadro del ritaglio in pixel (sinistra, alto, destra, basso), o null se non serve. */
    fun cropBox(w: Int, h: Int, crop: List<Double>?): IntArray? {
        if (crop == null || crop.size < 4) return null
        val (x, y, cw, ch) = crop
        val box = intArrayOf(max(0, Math.round(x * w).toInt()), max(0, Math.round(y * h).toInt()),
            min(w, Math.round((x + cw) * w).toInt()), min(h, Math.round((y + ch) * h).toInt()))
        return if (box[2] - box[0] >= 2 && box[3] - box[1] >= 2) box else null
    }

    /** Riassunto leggibile delle modifiche («Ritagliata, Vivido, Esposizione +0,20»). */
    fun describe(r: Recipe): String {
        val parts = mutableListOf<String>()
        if (r.crop != null || r.straighten != 0.0 || r.rotate != 0 || r.flipH || r.flipV) parts += "Ritagliata"
        r.filter?.takeIf { it.isNotEmpty() }?.let { parts += FILTER_NAMES[it] ?: it }
        val labels = ADJUSTMENTS.associate { it.key to it.label }
        for ((k, v) in r.adjust) parts += "${labels[k] ?: k} ${"%+.2f".format(java.util.Locale.ROOT, v).replace(".", ",")}"
        if (r.trim != null) parts += "Video tagliato"
        if (r.mute) parts += "Senza audio"
        return parts.joinToString(", ")
    }

    // --- elaborazione dei pixel (ARGB) -------------------------------------------------------
    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun lum(r: Double, g: Double, b: Double) = r * 0.2126 + g * 0.7152 + b * 0.0722

    /** Sfocatura gaussiana approssimata con tre passaggi di media mobile (per canale). */
    fun blur(px: IntArray, w: Int, h: Int, radius: Double): IntArray {
        if (radius <= 0.1) return px.copyOf()
        // larghezze dei tre box per una gaussiana di deviazione standard = radius
        val n = 3
        val wIdeal = sqrt(12 * radius * radius / n + 1)
        var wl = wIdeal.toInt(); if (wl % 2 == 0) wl--
        val wu = wl + 2
        val m = Math.round((12 * radius * radius - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4)).toInt()
        val sizes = IntArray(n) { if (it < m) wl else wu }
        val ch = Array(3) { c -> IntArray(px.size) { (px[it] shr (16 - 8 * c)) and 0xFF } }
        val tmp = IntArray(px.size)
        for (c in 0 until 3) for (s in sizes) {
            val r = (s - 1) / 2
            boxH(ch[c], tmp, w, h, r); boxV(tmp, ch[c], w, h, r)
        }
        return IntArray(px.size) { (px[it] and 0xFF000000.toInt()) or (ch[0][it] shl 16) or (ch[1][it] shl 8) or ch[2][it] }
    }

    private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { src.copyInto(dst); return }
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var acc = 0
            for (k in -r..r) acc += src[row + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                dst[row + x] = acc / div
                acc += src[row + (x + r + 1).coerceAtMost(w - 1)] - src[row + (x - r).coerceAtLeast(0)]
            }
        }
    }

    private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0) { src.copyInto(dst); return }
        val div = 2 * r + 1
        for (x in 0 until w) {
            var acc = 0
            for (k in -r..r) acc += src[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = acc / div
                acc += src[(y + r + 1).coerceAtMost(h - 1) * w + x] - src[(y - r).coerceAtLeast(0) * w + x]
            }
        }
    }

    private fun blend(a: IntArray, b: IntArray, t: Double): IntArray = IntArray(a.size) { i ->
        val pa = a[i]; val pb = b[i]
        fun c(s: Int) = (((pa shr s) and 0xFF) * (1 - t) + ((pb shr s) and 0xFF) * t + 0.5).toInt().coerceIn(0, 255)
        (pa and 0xFF000000.toInt()) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    private fun unsharp(px: IntArray, w: Int, h: Int, radius: Double, percent: Int, threshold: Int): IntArray {
        val bl = blur(px, w, h, radius)
        return IntArray(px.size) { i ->
            val p = px[i]; val q = bl[i]
            fun c(s: Int): Int {
                val o = (p shr s) and 0xFF
                val d = o - ((q shr s) and 0xFF)
                return if (abs(d) < threshold) o else (o + d * percent / 100).coerceIn(0, 255)
            }
            (p and 0xFF000000.toInt()) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
    }

    private fun median3(px: IntArray, w: Int, h: Int): IntArray {
        val out = px.copyOf()
        val buf = IntArray(9)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var res = px[y * w + x] and 0xFF000000.toInt()
            for (s in intArrayOf(16, 8, 0)) {
                var k = 0
                for (dy in -1..1) for (dx in -1..1) buf[k++] = (px[(y + dy) * w + x + dx] shr s) and 0xFF
                buf.sort()
                res = res or (buf[4] shl s)
            }
            out[y * w + x] = res
        }
        return out
    }

    /** Regolazioni di luce e colore. Restituisce i nuovi pixel (ARGB). */
    fun tone(input: IntArray, w: Int, h: Int, adj: Map<String, Double>): IntArray {
        if (adj.values.none { abs(it) > 1e-3 }) return input
        var px = input
        // dettagli prima delle curve, su RGB a 8 bit
        val sharp = adj["sharpness"] ?: 0.0
        if (sharp > 0) px = unsharp(px, w, h, 1.6, (180 * sharp).toInt(), 2)
        else if (sharp < 0) px = blend(px, blur(px, w, h, 1.5), min(1.0, -sharp))
        val definition = adj["definition"] ?: 0.0
        if (abs(definition) > 1e-3) {
            val radius = max(8, min(w, h) / 60).toDouble()
            px = if (definition > 0) unsharp(px, w, h, radius, (60 * definition).toInt(), 0)
            else blend(px, blur(px, w, h, radius / 3), min(1.0, -definition) * 0.6)
        }
        val noise = adj["noise"] ?: 0.0
        if (noise > 0) px = blend(px, blur(median3(px, w, h), w, h, 0.8), min(1.0, noise))

        val brilliance = adj["brilliance"] ?: 0.0
        val shadows = (adj["shadows"] ?: 0.0) + brilliance * 0.6
        val highlights = (adj["highlights"] ?: 0.0) - brilliance * 0.45
        val contrast = (adj["contrast"] ?: 0.0) + brilliance * 0.1
        val ev = adj["exposure"] ?: 0.0
        val evMul = 2.0.pow(ev * 2.0)
        val bp = adj["blackpoint"] ?: 0.0
        val br = adj["brightness"] ?: 0.0
        val brPow = 2.0.pow(-br * 0.8)
        val k = 1 + contrast * (if (contrast > 0) 0.9 else 0.7)
        val warmth = adj["warmth"] ?: 0.0
        val tint = adj["tint"] ?: 0.0
        val sat = adj["saturation"] ?: 0.0
        val vib = adj["vibrance"] ?: 0.0
        val bw = adj["bw"] ?: 0.0
        val vig = adj["vignette"] ?: 0.0
        val out = IntArray(px.size)
        val cx = w / 2.0; val cy = h / 2.0
        val sqrt2 = sqrt(2.0)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val p = px[i]
            var r = ((p shr 16) and 0xFF) / 255.0
            var g = ((p shr 8) and 0xFF) / 255.0
            var b = (p and 0xFF) / 255.0
            if (ev != 0.0) { r *= evMul; g *= evMul; b *= evMul }
            if (bp != 0.0) {
                val bb = bp * 0.12
                if (bb > 0) { r = (r - bb) / (1 - bb); g = (g - bb) / (1 - bb); b = (b - bb) / (1 - bb) }
                else { r = r * (1 + bb) - bb; g = g * (1 + bb) - bb; b = b * (1 + bb) - bb }
            }
            if (shadows != 0.0 || highlights != 0.0) {
                val l = lum(r, g, b).coerceIn(0.0, 1.0)
                if (shadows != 0.0) {
                    val m = 1 - smoothstep(0.0, 0.55, l)
                    if (shadows > 0) { val d = shadows * 0.35 * m * (1 - l); r += d; g += d; b += d }
                    else { val f = 1 + shadows * 0.5 * m; r *= f; g *= f; b *= f }
                }
                if (highlights != 0.0) {
                    val m = smoothstep(0.45, 1.0, l)
                    val d = highlights * 0.3 * m * (if (highlights < 0) l else (1 - l))
                    r += d; g += d; b += d
                }
            }
            if (br != 0.0) { r = max(0.0, r).pow(brPow); g = max(0.0, g).pow(brPow); b = max(0.0, b).pow(brPow) }
            if (contrast != 0.0) { r = (r - 0.5) * k + 0.5; g = (g - 0.5) * k + 0.5; b = (b - 0.5) * k + 0.5 }
            if (warmth != 0.0) { r *= 1 + warmth * 0.12; b *= 1 - warmth * 0.12 }
            if (tint != 0.0) g *= 1 - tint * 0.08
            if (sat != 0.0 || vib != 0.0 || bw != 0.0) {
                val l = lum(r, g, b)
                var cr = r - l; var cg = g - l; var cb = b - l
                if (vib != 0.0) {
                    val cur = (max(r, max(g, b)) - min(r, min(g, b))).coerceIn(0.0, 1.0)
                    val f = 1 + vib * (1 - cur) * 1.2
                    cr *= f; cg *= f; cb *= f
                }
                if (sat != 0.0) { cr *= 1 + sat; cg *= 1 + sat; cb *= 1 + sat }
                if (bw != 0.0) { cr *= 1 - bw; cg *= 1 - bw; cb *= 1 - bw }
                r = l + cr; g = l + cg; b = l + cb
            }
            if (vig != 0.0) {
                val d = sqrt(((x - cx) / cx).pow(2) + ((y - cy) / cy).pow(2)) / sqrt2
                val m = smoothstep(0.35, 1.0, d)
                if (vig > 0) { val f = 1 - vig * 0.75 * m; r *= f; g *= f; b *= f }
                else { r += -vig * 0.5 * m * (1 - r); g += -vig * 0.5 * m * (1 - g); b += -vig * 0.5 * m * (1 - b) }
            }
            out[i] = (p and 0xFF000000.toInt()) or (to8(r) shl 16) or (to8(g) shl 8) or to8(b)
        }
        return out
    }

    private fun to8(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()

    /** «Migliora»: regolazioni suggerite dall'istogramma (esposizione, contrasto, ombre, luci). */
    fun autoEnhance(px: IntArray, w: Int, h: Int): Map<String, Double> {
        val step = max(1, max(w, h) / 400)
        val ls = ArrayList<Double>()
        var satSum = 0.0
        var midR = 0.0; var midB = 0.0; var midN = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                val r = ((p shr 16) and 0xFF) / 255.0; val g = ((p shr 8) and 0xFF) / 255.0; val b = (p and 0xFF) / 255.0
                val l = lum(r, g, b)
                ls += l
                satSum += max(r, max(g, b)) - min(r, min(g, b))
                if (l > 0.25 && l < 0.8) { midR += r; midB += b; midN++ }
                x += step
            }
            y += step
        }
        if (ls.isEmpty()) return emptyMap()
        val sorted = ls.sorted()
        fun pct(q: Double): Double {
            val pos = q / 100 * (sorted.size - 1)
            val lo = pos.toInt(); val hi = min(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
        val p2 = pct(2.0); val p50 = pct(50.0); val p98 = pct(98.0)
        val satNow = satSum / ls.size
        val adj = LinkedHashMap<String, Double>()
        val target = 0.46
        if (abs(p50 - target) > 0.04) adj["exposure"] = (ln(target / max(p50, 0.02)) / ln(2.0) / 2.0 * 0.8).coerceIn(-0.5, 0.5)
        val spread = p98 - p2
        if (spread < 0.8) adj["contrast"] = ((0.85 - spread) * 0.8).coerceIn(0.0, 0.35)
        if (p2 > 0.06) adj["blackpoint"] = (p2 * 2.5).coerceIn(0.0, 0.4)
        val dark = ls.count { it < 0.15 }.toDouble() / ls.size
        if (dark > 0.15) adj["shadows"] = (dark * 0.9).coerceIn(0.0, 0.45)
        val bright = ls.count { it > 0.92 }.toDouble() / ls.size
        if (bright > 0.05) adj["highlights"] = -(bright * 2.5).coerceIn(0.0, 0.5)
        if (satNow < 0.25) adj["vibrance"] = ((0.3 - satNow) * 1.2).coerceIn(0.0, 0.3)
        // dominante di colore: bilanciamento del bianco sui grigi medi
        if (midN > 500) {
            val cast = midR / midN - midB / midN
            if (abs(cast) > 0.05) adj["warmth"] = (-cast * 1.5).coerceIn(-0.3, 0.3)
        }
        return adj.mapValues { Math.round(it.value * 1000) / 1000.0 }.filterValues { abs(it) > 0.01 }
    }

    // --- hash percettivo ------------------------------------------------------------------
    /** Difference hash a 64 bit da un'immagine in scala di grigi 9×8. */
    fun dhash(gray9x8: IntArray): Long {
        var bits = 0L
        for (row in 0 until 8) for (col in 0 until 8) {
            bits = (bits shl 1) or (if (gray9x8[row * 9 + col] > gray9x8[row * 9 + col + 1]) 1L else 0L)
        }
        return bits
    }
}
