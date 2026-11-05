package io.github.guidoaltieri.myphotolibrary.core

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * SRP-6a come lo usa l'accesso ad Apple ID (identico al client «srp» 1.0.22 di icloudpd:
 * gruppo RFC 5054 a 2048 bit, SHA-256, valori con riempimento RFC 5054, nome utente escluso da x).
 * La password non viene mai inviata: Apple riceve solo A, M1 e M2.
 */
object Srp {
    val N = BigInteger("AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310DCD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C38271AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16)
    val g: BigInteger = BigInteger.valueOf(2)
    private val width = bytes(N).size

    /** long_to_bytes di Python: big-endian senza zeri iniziali. */
    fun bytes(x: BigInteger): ByteArray {
        val b = x.toByteArray()
        val i = b.indexOfFirst { it != 0.toByte() }
        return if (i <= 0) (if (i < 0) byteArrayOf(0) else b) else b.copyOfRange(i, b.size)
    }

    fun num(b: ByteArray) = BigInteger(1, b)

    fun sha256(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").run {
        parts.forEach { update(it) }
        digest()
    }

    private fun pad(x: BigInteger): ByteArray = bytes(x).let { ByteArray(width - it.size) + it }

    private val k = num(sha256(pad(N), pad(g)))

    /** Chiave derivata dalla password (protocolli «s2k» e «s2k_fo»), come SrpPassword.encode di icloudpd. */
    fun passwordKey(password: String, protocol: String, salt: ByteArray, iterations: Int): ByteArray {
        val digest = sha256(password.toByteArray(Charsets.UTF_8))
        val input = if (protocol == "s2k_fo") digest.joinToString("") { "%02x".format(it) }.toByteArray() else digest
        return pbkdf2(input, salt, iterations, 32)
    }

    /** PBKDF2-HMAC-SHA256 su byte qualsiasi (PBEKeySpec accetterebbe solo caratteri). */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(password, "HmacSHA256")) }
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            var u = mac.doFinal(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            t.copyInto(out, pos, 0, minOf(t.size, length - pos))
            pos += t.size
            block++
        }
        return out
    }

    /** Lato client di una sessione SRP. */
    class Client(private val username: String, privateKey: ByteArray = ByteArray(256).also { SecureRandom().nextBytes(it) }) {
        private val a = num(privateKey)
        val A: BigInteger = g.modPow(a, N)
        val publicKey: ByteArray get() = bytes(A)

        /** (M1, M2) da inviare ad Apple dopo la sfida (salt, B); [password] è la chiave derivata. */
        fun respond(salt: ByteArray, serverB: ByteArray, password: ByteArray): Pair<ByteArray, ByteArray> {
            // il salt si usa così com'è, con gli eventuali zeri iniziali (come srp 1.0.22)
            val B = num(serverB)
            require(B.mod(N) != BigInteger.ZERO) { "Sfida SRP non valida" }
            val u = num(sha256(pad(A), pad(B)))
            require(u != BigInteger.ZERO) { "Sfida SRP non valida" }
            val x = num(sha256(salt, sha256(":".toByteArray() + password)))
            val v = g.modPow(x, N)
            val S = B.subtract(k.multiply(v)).mod(N).modPow(a.add(u.multiply(x)), N)
            val K = sha256(bytes(S))
            val hN = sha256(bytes(N))
            val hg = sha256(pad(g))
            val xor = ByteArray(hN.size) { (hN[it].toInt() xor hg[it].toInt()).toByte() }
            val m1 = sha256(xor, sha256(username.toByteArray(Charsets.UTF_8)), salt, bytes(A), bytes(B), K)
            val m2 = sha256(bytes(A), m1, K)
            return m1 to m2
        }
    }
}
