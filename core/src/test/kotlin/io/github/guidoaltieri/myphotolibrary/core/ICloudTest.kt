package io.github.guidoaltieri.myphotolibrary.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.LocalDateTime
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Accesso e sincronizzazione di iCloud Foto (SRP verificato con la libreria «srp» 1.0.22 usata da icloudpd). */
class ICloudTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    private data class Vec(val salt: String, val protocol: String, val a: String, val b: String, val m1: String, val m2: String)

    private val aKey = hex("ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bbbf5d3affb73efd2ec6c36ad3112dd933efed63c4e1cbffcfa88e2759c144f2d8eb48bdfa15fc43dbea3aabb1ee847b6e69232c0f0d9705935e50d60cce77877fd75293db81814e1fd81644f9b40cea3d0d8095acb34538596001de2b7461f6f866f0b2d702f8bfc1f2f9b5d8d9d63e6ec6d28aaf389f25d23a6fe4c717824de12972dd66ea6fc3ecceb02cff6e85969cf35df296c3837486a91e76726687270b84173bc50ae102410182d2c12513f58c2c09beb97a658b6fb41d2912caeced4f963ffea97510de1088ce399fb294be025f5c55b4e743105e59854547e658d967")

    @Test
    fun srpMatchesPysrp() {
        // vettori generati con srp 1.0.22 (rfc5054_enable, no_username_in_x) e la stessa chiave privata;
        // anche il verificatore di pysrp accetta M1 e M2 (pure con lo zero iniziale nel salt)
        val vectors = listOf(
            Vec("a1b2c3d4e5f60718293a4b5c6d7e8f90", "s2k", "oCHwf44Nuo+08LrIuAFDzoM2X1wSO1ztFvLP5DO8CKRHDDhrIzJUMUh5ekxH56gZmvmI+BOcgrpYbBVq34j0qj0cau7RoXL49Cv99XcP8szucHFn/3oCoJZhEqd+xSrtVVFKUuoqrOGg6dZneeFjReToo9fU22N1auJ20/hV8DFiasou1F/ujP0g5kvSaR9WqxjxokMdqo0zv1WuDyc4xlyDmVkjsHNc8mnwi2lvf1DpJDO9OMK7HQWJ70nbvQQYLsGylRGD4g2AIQLRVxF8patETBaM91poCZ88OwUzOvrZ0iAYRWSZ801yQsndkf0RT/VlVs8k09Va5hRhLbNi7A==",
                "d3uvggOM+bA8btDo6AKsGjxkRPpAaHCr2qsAk8qgqJtCuKQmkhDwBN69MHw/wAaHtTKEX2RDoOIYcrNEEr8UEeSG0Osow8UGjfQQd9ST0V8FuKMiPG6ULKe6QmkgRRMV876wnXo++JSCUm6JCbt2Yi38IlE1eMGDHMfRb3zpArbL2OJslzWIBWEh+SwplSou1anhHTEAFpeJQTSoZgr2u+yEu9NgYqUxXcX6pH8WWgURtYYVyTO5V0s8rdhDWSuPvSioV9GNSIgyYiiGFloJiDvl2R/wbiHJkBohMs4rux8hMdUXNe6sT7fpqOJldCvsLADIvJUS2LZCwqzZc3QFvQ==",
                "G5GzDE4ycePYBAQSO/vKpuBpobSwij5aTXw4JaNLtu4=", "oYxZ8ZeXil4uFgJEYmldRAEdOwP1TVL900N8/7ZG50E="),
            Vec("a1b2c3d4e5f60718293a4b5c6d7e8f90", "s2k_fo", "oCHwf44Nuo+08LrIuAFDzoM2X1wSO1ztFvLP5DO8CKRHDDhrIzJUMUh5ekxH56gZmvmI+BOcgrpYbBVq34j0qj0cau7RoXL49Cv99XcP8szucHFn/3oCoJZhEqd+xSrtVVFKUuoqrOGg6dZneeFjReToo9fU22N1auJ20/hV8DFiasou1F/ujP0g5kvSaR9WqxjxokMdqo0zv1WuDyc4xlyDmVkjsHNc8mnwi2lvf1DpJDO9OMK7HQWJ70nbvQQYLsGylRGD4g2AIQLRVxF8patETBaM91poCZ88OwUzOvrZ0iAYRWSZ801yQsndkf0RT/VlVs8k09Va5hRhLbNi7A==",
                "TmAhPScfAz9XVXbuzgRPaQ2CWNRfTiXi3/hcFps7GjXlX4Ra/rjnf7k8pNNlQhE4MsFR1fz98tru87c06OVhpZg2Q6RJ0F/KabBodYNtkbgN9Z5VrCDtfsnwbTtodTMWRshbgBxin9BY+Rv+Xe2/gcTPxthbmdGZmVwkVyCVDJHMFdjlC1PfmWunNufDAu22OKLCtNEdQfvknVQ0IRIz/36NyTVY4Z9034UNK1/r290TSX0XrUOnTxXPIR9dNpLh4HtreRNdQLmJ2NCgV0rSvbsu5toX0sgcttmzDUwUgcU6rEHGmchbxTRaWG7sTPqgXd3e68C0WpomE7+YmsKSxQ==",
                "liv3wqr7m3Nnzk/XRCeAeAOetFago3yizTGbsU/i/5c=", "eVcjXQTjDeXIm9k8uYjbmw9rVm3rhy+4wnbEjc6spgQ="),
            Vec("00a1b2c3d4e5f60718293a4b5c6d7e8f", "s2k", "oCHwf44Nuo+08LrIuAFDzoM2X1wSO1ztFvLP5DO8CKRHDDhrIzJUMUh5ekxH56gZmvmI+BOcgrpYbBVq34j0qj0cau7RoXL49Cv99XcP8szucHFn/3oCoJZhEqd+xSrtVVFKUuoqrOGg6dZneeFjReToo9fU22N1auJ20/hV8DFiasou1F/ujP0g5kvSaR9WqxjxokMdqo0zv1WuDyc4xlyDmVkjsHNc8mnwi2lvf1DpJDO9OMK7HQWJ70nbvQQYLsGylRGD4g2AIQLRVxF8patETBaM91poCZ88OwUzOvrZ0iAYRWSZ801yQsndkf0RT/VlVs8k09Va5hRhLbNi7A==",
                "FSjxJaAfzJklBnL+9OxLBbr8ltkda/ZzwPYV+Q5rXpnfu0ebJC6o7+2GuMdmC3Mxggigilb4iRZ5JLNAPlnkVLFJFe0IWS4JSYEhqDz0FZHFA190oFGqnFDe9Sp43XBSzLhGnyBi1zk5kHiRbbhKamnbGKpZ32SMZHf3ADHAq9sIGKx76pc1AsdUyq52HRhJdVIbaw3ytV3BArmgzvhmkbbVSSrAyQn3PPzUeW2oGvI1OBbjx8++J/dXAhyugYqjOaj6mQA0GG9B1mZnQkzPToG8AGGE5r+WJ5GPGthH9VPMuTYLj1PFR4dogf5jdLb0HmGcHiloTwucFrSmQ3dhsw==",
                "8igwwOdS2cElTwzUdL8MUvDgXRuJ/OIrt4rzFf2z5Fo=", "531cCmBVQfilG7K32WUY5MR/G42ut5yKWfJInUKb0u8="),
            Vec("00a1b2c3d4e5f60718293a4b5c6d7e8f", "s2k_fo", "oCHwf44Nuo+08LrIuAFDzoM2X1wSO1ztFvLP5DO8CKRHDDhrIzJUMUh5ekxH56gZmvmI+BOcgrpYbBVq34j0qj0cau7RoXL49Cv99XcP8szucHFn/3oCoJZhEqd+xSrtVVFKUuoqrOGg6dZneeFjReToo9fU22N1auJ20/hV8DFiasou1F/ujP0g5kvSaR9WqxjxokMdqo0zv1WuDyc4xlyDmVkjsHNc8mnwi2lvf1DpJDO9OMK7HQWJ70nbvQQYLsGylRGD4g2AIQLRVxF8patETBaM91poCZ88OwUzOvrZ0iAYRWSZ801yQsndkf0RT/VlVs8k09Va5hRhLbNi7A==",
                "OSmhdlKd1fX8JPQ51Q/fNfIF5yD5q7j2Olez7gUhLESD+YIOG5NqnfENumsDKfWfzltlkGJ809IKsL6+D9874Vlqde50144vTZscdUezr4fGAodR5rdO8fW4+Sw1+KgOlosbshc3T5S3XvrCiU3Lu0Z1ekrlOHqTkFvDW3optP06YTa/WVpFoLe/Le4uyVIa8M/DhEwvU4YTrrkQIv5c5Y0R9wdGWUuuBzAApgTUXzJYSO2hBhFtcnmH0i3W88J2JGd/1+mfk2lPPpzH/1hqjl17R/lV3VEX+OrNSKivz3zhoOOcEcSqUbN5Euf6b3KwaQYbK3mCoEbhoC4QRmF7RA==",
                "ie3w25vU2gnETuHCBPyZ1yqZrws6+EgFQ0nyNJ3fBGM=", "zuz3YoZDurTne0lEAPVSZfxb2ETGxcloVsM2xn/7tnA="),
        )
        for (v in vectors) {
            val c = Srp.Client("utente@example.com", aKey)
            assertEquals(v.a, b64(c.publicKey), "A")
            val key = Srp.passwordKey("password segreta", v.protocol, hex(v.salt), 1000)
            val (m1, m2) = c.respond(hex(v.salt), Base64.getDecoder().decode(v.b), key)
            assertEquals(v.m1, b64(m1), "M1 ${v.protocol} ${v.salt}")
            assertEquals(v.m2, b64(m2), "M2 ${v.protocol} ${v.salt}")
        }
    }

    @Test
    fun pbkdf2MatchesHashlib() {
        val salt = hex("a1b2c3d4e5f60718293a4b5c6d7e8f90")
        assertEquals("314111c0043cb0e2c0189624bc13ec382e8acb267aede7809d9f65445dfbe48c",
            Srp.passwordKey("password segreta", "s2k", salt, 1000).joinToString("") { "%02x".format(it) })
        assertEquals("73ea841f8f278dc08169043c94c8947af1bd647d5d06038815858129bfe896c6",
            Srp.passwordKey("password segreta", "s2k_fo", salt, 1000).joinToString("") { "%02x".format(it) })
        assertEquals("729c71fb9a668a376315d1862bf80ad9cf4105da2680140bbfe9e7724f0bf8207287cffcdce4e8e4b756abfe7b81527e011410050e13d5ea8da9cc250a66651745dc47a006ba",
            Srp.pbkdf2("x".toByteArray(), "y".toByteArray(), 3, 70).joinToString("") { "%02x".format(it) })
    }

    @Test
    fun cookieJar() {
        val jar = CookieJar()
        jar.store("idmsa.apple.com", "aasp=ABC; Domain=.apple.com; Path=/; Secure; HttpOnly")
        jar.store("setup.icloud.com", "X-APPLE-WEBAUTH-TOKEN=\"v=2:t=xyz\"; Domain=.icloud.com; Path=/")
        jar.store("setup.icloud.com", "X_APPLE_WEB_KB=K1; Path=/; Domain=.icloud.com")
        assertEquals("aasp=ABC", jar.header(java.net.URL("https://idmsa.apple.com/appleauth/auth/signin/init")))
        assertEquals("X-APPLE-WEBAUTH-TOKEN=\"v=2:t=xyz\"; X_APPLE_WEB_KB=K1", jar.header(java.net.URL("https://p42-ckdatabasews.icloud.com/database")))
        jar.store("setup.icloud.com", "X_APPLE_WEB_KB=; Max-Age=0; Domain=.icloud.com; Path=/")   // cancellato dal server
        assertEquals("X-APPLE-WEBAUTH-TOKEN=\"v=2:t=xyz\"", jar.header(java.net.URL("https://www.icloud.com/")))
        val copy = CookieJar().apply { load(jar.toJson()) }
        assertEquals(jar.cookies, copy.cookies)
    }

    /** Server Apple finto: registra le richieste e risponde come l'accesso con codice a due fattori. */
    private class FakeApple : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        var trusted = false
        var codeOk = "123456"

        private fun resp(status: Int, body: String, headers: Map<String, String> = emptyMap(), cookies: List<String> = emptyList()) =
            HttpResponse(status, headers.mapValues { listOf(it.value) } + (if (cookies.isEmpty()) emptyMap() else mapOf("Set-Cookie" to cookies)),
                ByteArrayInputStream(body.toByteArray()))

        private fun account() = """{"dsInfo":{"dsid":"12345","hsaVersion":2,"hasICloudQualifyingDevice":true},"hsaChallengeRequired":${!trusted},
            "hsaTrustedBrowser":$trusted,"webservices":{"ckdatabasews":{"url":"https://p42-ckdatabasews.icloud.com:443"}}}"""

        override fun execute(r: HttpRequest): HttpResponse {
            requests += r
            val path = java.net.URL(r.url).path
            return when {
                path.endsWith("/signin/init") -> resp(200, """{"iteration":1000,"salt":"${Base64.getEncoder().encodeToString(ByteArray(16) { (it + 1).toByte() })}",
                    "protocol":"s2k","b":"${Base64.getEncoder().encodeToString(ByteArray(256) { 7 })}","c":"sfida"}""",
                    mapOf("scnt" to "SCNT1", "X-Apple-ID-Session-Id" to "SESS1"), listOf("aasp=AASP; Domain=.apple.com; Path=/"))
                path.endsWith("/signin/complete") -> resp(409, """{"authType":"hsa2"}""", mapOf("X-Apple-Session-Token" to "TOK1", "scnt" to "SCNT2"))
                path.endsWith("/accountLogin") -> resp(200, account(), cookies = listOf("X-APPLE-WEBAUTH-TOKEN=W; Domain=.icloud.com; Path=/"))
                path.endsWith("/verify/trusteddevice/securitycode") && r.method == "PUT" -> resp(202, "{}")
                path.endsWith("/verify/trusteddevice/securitycode") ->
                    if (r.body!!.contains(codeOk)) resp(204, "", mapOf("X-Apple-Session-Token" to "TOK2"))
                    else resp(400, """{"service_errors":[{"code":"-21669","message":"Codice errato"}]}""")
                path.endsWith("/2sv/trust") -> { trusted = true; resp(204, "", mapOf("X-Apple-TwoSV-Trust-Token" to "TRUST", "X-Apple-Session-Token" to "TOK3")) }
                path.endsWith("/validate") -> if (trusted) resp(200, account()) else resp(421, "{}")
                else -> resp(404, "{}")
            }
        }
    }

    private class MemStore : ICloudStore {
        var text: String? = null
        override fun load() = text
        override fun save(json: String) { text = json }
    }

    @Test
    fun loginWithTwoFactorCode() {
        val apple = FakeApple()
        val store = MemStore()
        val s = ICloudSession("utente@example.com", store, apple)
        assertFailsWith<ICloudNeedsCode> { s.authenticate { "password segreta" } }
        val init = apple.requests.first { it.url.endsWith("/signin/init") }
        assertEquals(ICloudSession.WIDGET_KEY, init.headers["X-Apple-Widget-Key"])
        assertTrue(init.body!!.contains("\"protocols\":[\"s2k\",\"s2k_fo\"]"))
        // come icloudpd: le intestazioni di signin/complete sono quelle preparate per signin/init
        val complete = apple.requests.first { it.url.contains("/signin/complete") }
        assertEquals(init.headers, complete.headers - "Cookie")
        assertEquals("aasp=AASP", complete.headers["Cookie"])
        assertTrue(complete.body!!.contains("\"c\":\"sfida\"") && !complete.body!!.contains("password"))
        assertTrue(apple.requests.first { it.url.endsWith("/accountLogin") }.body!!.contains("\"dsWebAuthToken\":\"TOK1\""))
        assertTrue(s.requestCode())
        val put = apple.requests.last()
        assertEquals("SCNT2", put.headers["scnt"]); assertEquals("SESS1", put.headers["X-Apple-ID-Session-Id"])
        assertFalse(s.verifyCode("000000"))               // codice sbagliato
        assertTrue(s.verifyCode("123456"))
        assertEquals("TRUST", s.data["trust_token"])
        assertTrue(apple.requests.last { it.url.endsWith("/accountLogin") }.body!!.contains("\"trustToken\":\"TRUST\""))
        // nuovo avvio: la sessione salvata basta, senza password né codice
        val again = ICloudSession("utente@example.com", store, apple)
        again.authenticate { error("la password non serve") }
        assertEquals("12345", again.params["dsid"])
        assertTrue(again.photos().let { true })
    }

    private fun record(type: String, name: String, fields: String) = """{"recordType":"$type","recordName":"$name","fields":{$fields}}"""

    private fun page(): JsonArray {
        val name = Base64.getEncoder().encodeToString("IMG_0001.HEIC".toByteArray())
        val recs = listOf(
            record("CPLAsset", "A1", """"masterRef":{"value":{"recordName":"M1"}},"assetDate":{"value":1720000000000},
                "timeZoneOffset":{"value":7200},"isFavorite":{"value":1}"""),
            record("CPLMaster", "M1", """"filenameEnc":{"value":"$name","type":"ENCRYPTED_BYTES"},"itemType":{"value":"public.heic"},
                "resOriginalRes":{"value":{"size":1000,"downloadURL":"https://cvws.icloud-content.com/a","fileChecksum":"x"}},
                "resOriginalWidth":{"value":4032},"resOriginalHeight":{"value":3024},
                "resOriginalVidComplRes":{"value":{"size":300,"downloadURL":"https://cvws.icloud-content.com/a.mov","fileChecksum":"y"}}"""),
            record("CPLAsset", "A2", """"masterRef":{"value":{"recordName":"M2"}},"assetDate":{"value":1690000000000}"""),
            record("CPLMaster", "M2", """"filenameEnc":{"value":"VID_2.MOV","type":"STRING"},"itemType":{"value":"com.apple.quicktime-movie"},
                "resOriginalRes":{"value":{"size":5000,"downloadURL":"https://cvws.icloud-content.com/b","fileChecksum":"z"}}"""),
        )
        return Library.json.parseToJsonElement("[" + recs.joinToString(",") + "]") as JsonArray
    }

    @Test
    fun parseRecords() {
        val assets = ICloudPhotos.parse(page())
        assertEquals(listOf("IMG_0001.HEIC", "VID_2.MOV"), assets.map { it.filename })
        val a = assets[0]
        assertFalse(a.isVideo); assertTrue(a.favorite); assertEquals(4032, a.width); assertEquals("IMG_0001.MOV", a.liveFilename)
        // ora sull'orologio del luogo dello scatto: 1720000000 UTC + 2 h
        assertEquals(LocalDateTime.of(2024, 7, 3, 11, 46, 40), Fmt.dt(a.taken!!))
        assertTrue(assets[1].isVideo)
        assertEquals("a_b_c.jpg", ICloudPhotos.cleanName("a/b:c.jpg"))
    }

    /** Destinazione in memoria (come una cartella di un'app cloud). */
    private class MemDest : ICloudSync.Destination {
        val files = linkedMapOf<String, ByteArray>()
        override fun listDirs(rel: String) = files.keys.filter { rel.isEmpty() || it.startsWith("$rel/") }
            .map { it.removePrefix(if (rel.isEmpty()) "" else "$rel/") }.filter { "/" in it }.map { it.substringBefore("/") }.distinct()
        override fun listFiles(rel: String) = files.filterKeys { dirname(it) == rel }.mapKeys { basename(it.key) }.mapValues { it.value.size.toLong() }
        override fun put(relFile: String, write: (OutputStream) -> Unit): ICloudSync.Written {
            val out = ByteArrayOutputStream().also(write)
            files[relFile] = out.toByteArray()
            return ICloudSync.Written("content://dest/$relFile", out.size().toLong(), 1000.0)
        }
    }

    private class FakeRemote(val assets: List<ICloudAsset>) : ICloudSync.Remote {
        var pages = 0
        override fun page(offset: Int): List<ICloudAsset> { pages++; return assets.drop(offset).take(100) }
        override fun open(url: String) = ByteArrayInputStream(ByteArray(if (url.endsWith(".mov")) 300 else 1000))
    }

    @Test
    fun syncToCloudFolder() {
        val lib = Library(JdbcDb())
        val sid = lib.addSource("folder", "Drive", "content://tree/Drive")
        val src = lib.source(sid)!!
        val base = ICloudPhotos.parse(page())[0]
        // 300 foto, una al giorno dal più recente, più una Live Photo
        val assets = listOf(base) + (1..300).map { i ->
            base.copy(id = "M$i+", filename = "PIC_%04d.JPG".format(i), liveUrl = null, favorite = false,
                assetDate = base.assetDate!! - i * 86400.0, tzOffset = null)
        }
        val dest = MemDest()
        val remote = FakeRemote(assets)
        val r = ICloudSync(lib, src, remote, dest, "{anno}/{mese}", true).run(full = true, importId = null)
        assertEquals(301, r.copied); assertEquals(0, r.errors); assertTrue(r.complete)
        assertTrue("2024/07/IMG_0001.HEIC" in dest.files && "2024/07/IMG_0001.MOV" in dest.files)
        // la galleria è già aggiornata: la Live Photo è abbinata, la preferita è preferita
        assertEquals(301, lib.count("library").first)
        assertEquals(1, lib.count("live").first)
        assertEquals(1, lib.count("favorites").first)
        // sincronizzazione successiva: nessuna copia, e la lettura dell'elenco si ferma presto
        val again = FakeRemote(assets)
        val r2 = ICloudSync(lib, src, again, dest, "{anno}/{mese}", true).run(full = false, importId = null)
        assertEquals(0, r2.copied); assertEquals(1, again.pages)
        // una foto nuova in cima viene copiata
        val newer = base.copy(id = "NEW", filename = "IMG_9999.JPG", liveUrl = null, assetDate = base.assetDate!! + 86400 * 40.0, tzOffset = null)
        val r3 = ICloudSync(lib, src, FakeRemote(listOf(newer) + assets), dest, "{anno}/{mese}/{giorno}", true).run(full = false, importId = null)
        assertEquals(1, r3.copied)
        assertTrue(dest.files.keys.any { it.startsWith("2024/08/") && it.endsWith("IMG_9999.JPG") })
        // un file già presente nella destinazione (stesso nome e dimensione) non si copia di nuovo
        lib.clearIcloudSynced()
        val r4 = ICloudSync(lib, src, FakeRemote(assets), dest, "{anno}/{mese}", true).run(full = true, importId = null)
        assertEquals(0, r4.copied); assertEquals(301, r4.skipped)
        assertEquals(dest.files.size, 303)
        @Suppress("UNUSED_VARIABLE") val unused = page().first().jsonObject
    }
}
