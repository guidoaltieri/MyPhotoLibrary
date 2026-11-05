package io.github.guidoaltieri.myphotolibrary.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Raggruppamento dei volti in persone (come faces.py): ogni volto è un vettore di 128 numeri
 * (SFace); un volto appartiene alla persona il cui centroide è abbastanza simile.
 */
object Faces {
    const val MATCH = 0.42f        // somiglianza coseno oltre la quale due volti sono la stessa persona
    const val MIN_SCORE = 0.86f    // affidabilità minima del rilevamento
    const val MIN_FACE = 0.045     // lato minimo del volto rispetto all'immagine

    const val DETECTOR_NAME = "face_detection_yunet_2023mar.onnx"
    const val DETECTOR_URL = "https://github.com/opencv/opencv_zoo/raw/main/models/face_detection_yunet/face_detection_yunet_2023mar.onnx"
    const val RECOGNIZER_NAME = "face_recognition_sface_2021dec.onnx"
    const val RECOGNIZER_URL = "https://github.com/opencv/opencv_zoo/raw/main/models/face_recognition_sface/face_recognition_sface_2021dec.onnx"

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val norm = sqrt(n).toFloat().takeIf { it > 0f } ?: 1f
        return FloatArray(v.size) { v[it] / norm }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun toBytes(v: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { bb.putFloat(it) }
        return bb.array()
    }

    fun fromBytes(b: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { bb.getFloat() }
    }

    /** Centroidi delle persone note, aggiornati man mano che si aggiungono volti. */
    class Clusters(embeddings: List<Pair<Long, FloatArray>>) {
        private val centroids = HashMap<Long, Pair<FloatArray, Int>>()

        init {
            val acc = HashMap<Long, Pair<FloatArray, Int>>()
            for ((pid, v) in embeddings) {
                val cur = acc[pid]
                acc[pid] = if (cur == null) v.copyOf() to 1 else FloatArray(v.size) { cur.first[it] + v[it] } to cur.second + 1
            }
            for ((pid, p) in acc) centroids[pid] = normalize(p.first) to p.second
        }

        fun match(feat: FloatArray): Long? {
            var best: Long? = null
            var bestSim = -2f
            for ((pid, c) in centroids) {
                val s = dot(c.first, feat)
                if (s > bestSim) {
                    bestSim = s; best = pid
                }
            }
            return if (best != null && bestSim >= MATCH) best else null
        }

        fun add(pid: Long, feat: FloatArray) {
            val cur = centroids[pid]
            centroids[pid] = if (cur == null) feat.copyOf() to 1 else {
                val k = cur.second
                normalize(FloatArray(feat.size) { (cur.first[it] * k + feat[it]) / (k + 1) }) to k + 1
            }
        }
    }
}
