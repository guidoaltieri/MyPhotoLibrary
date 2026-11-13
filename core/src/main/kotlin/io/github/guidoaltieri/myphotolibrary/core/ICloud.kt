package io.github.guidoaltieri.myphotolibrary.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID

/**
 * iCloud Foto senza app Apple (come pyicloud/icloudpd usato da ArchPhotos): accesso all'Apple ID
 * con SRP e codice a due fattori, elenco delle foto (CloudKit) e scaricamento degli originali.
 * Funziona solo con «Accedi ai dati iCloud sul web» attivo e senza Protezione avanzata dei dati.
 */
class ICloudError(message: String, val code: String = "", val status: Int = 0) : Exception(message)

/** Serve il codice di verifica (accesso nuovo o sessione scaduta: in genere ogni 1-2 mesi). */
class ICloudNeedsCode : Exception("Serve il codice di verifica dell'Apple ID")

/** Una richiesta HTTP: il trasporto vero (HttpURLConnection) o uno di prova nei test. */
data class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String? = null)

class HttpResponse(val status: Int, val headers: Map<String, List<String>>, val stream: InputStream) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
    fun headerAll(name: String): List<String> = headers.entries.filter { it.key.equals(name, true) }.flatMap { it.value }
    fun text(): String = stream.use { it.readBytes().toString(Charsets.UTF_8) }
}

fun interface HttpTransport {
    fun execute(r: HttpRequest): HttpResponse

    companion object {
        /** Trasporto con HttpURLConnection (Android e JVM): i cookie li gestisce [CookieJar]. */
        val DEFAULT = HttpTransport { r ->
            val c = URL(r.url).openConnection() as HttpURLConnection
            c.requestMethod = r.method
            c.instanceFollowRedirects = true
            c.connectTimeout = 30_000
            c.readTimeout = 60_000
            c.useCaches = false
            r.headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (r.body != null) {
                // senza tipo HttpURLConnection invierebbe «application/x-www-form-urlencoded»
                if (r.headers.keys.none { it.equals("Content-Type", true) }) c.setRequestProperty("Content-Type", "text/plain;charset=UTF-8")
                c.doOutput = true
                c.outputStream.use { it.write(r.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = (if (status >= 400) c.errorStream else c.inputStream) ?: java.io.ByteArrayInputStream(ByteArray(0))
            HttpResponse(status, c.headerFields.filterKeys { it != null }, stream)
        }
    }
}

/** Cookie delle sessioni Apple (salvati tra un avvio e l'altro, come la LWPCookieJar di icloudpd). */
class CookieJar {
    data class Cookie(val name: String, val value: String, val domain: String, val path: String)

    val cookies = mutableListOf<Cookie>()

    fun store(host: String, setCookie: String) {
        val parts = setCookie.split(";").map { it.trim() }
        val nv = parts.firstOrNull()?.split("=", limit = 2) ?: return
        if (nv.size < 2 || nv[0].isEmpty()) return
        var domain = host
        var path = "/"
        var expired = false
        for (attr in parts.drop(1)) {
            val (k, v) = attr.split("=", limit = 2).let { it[0].lowercase() to it.getOrElse(1) { "" } }
            when (k) {
                "domain" -> domain = v.trimStart('.').lowercase()
                "path" -> path = v.ifEmpty { "/" }
                "max-age" -> expired = (v.toLongOrNull() ?: 1) <= 0
            }
        }
        cookies.removeAll { it.name == nv[0] && it.domain == domain && it.path == path }
        if (!expired && nv[1].isNotEmpty()) cookies += Cookie(nv[0], nv[1], domain, path)
    }

    fun header(url: URL): String? {
        val host = url.host.lowercase()
        val path = url.path.ifEmpty { "/" }
        return cookies.filter { (host == it.domain || host.endsWith(".${it.domain}")) && path.startsWith(it.path) }
            .joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null }
    }

    fun toJson(): JsonArray = buildJsonArray {
        cookies.forEach { c -> add(buildJsonObject { put("n", c.name); put("v", c.value); put("d", c.domain); put("p", c.path) }) }
    }

    fun load(a: JsonArray?) {
        cookies.clear()
        a?.forEach { e ->
            val o = e.jsonObject
            cookies += Cookie(o.s("n"), o.s("v"), o.s("d"), o.s("p"))
        }
    }
}

private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.contentOrNull ?: ""
private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
private fun JsonObject.path(vararg keys: String): JsonElement? {
    var e: JsonElement? = this
    for (k in keys) e = (e as? JsonObject)?.get(k) ?: return null
    return e
}

/** Dove salvare la sessione (cookie e token): un file privato dell'app. */
interface ICloudStore {
    fun load(): String?
    fun save(json: String)
}

/** Sessione iCloud di un Apple ID (porting di pyicloud_ipd.base e session). */
class ICloudSession(val appleId: String, private val store: ICloudStore, private val transport: HttpTransport = HttpTransport.DEFAULT) {
    companion object {
        const val AUTH = "https://idmsa.apple.com/appleauth/auth"
        const val AUTH_ROOT = "https://idmsa.apple.com"
        const val HOME = "https://www.icloud.com"
        const val SETUP = "https://setup.icloud.com/setup/ws/1"
        const val WIDGET_KEY = "d39ba9916b7251055b22c7f910e2ea796ee65e98b2ddecea8f5dde8d9d1a815d"
        const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

        /** intestazioni delle risposte che vanno conservate (HEADER_DATA di pyicloud) */
        val HEADER_DATA = mapOf("X-Apple-ID-Account-Country" to "account_country", "X-Apple-ID-Session-Id" to "session_id",
            "X-Apple-Session-Token" to "session_token", "X-Apple-TwoSV-Trust-Token" to "trust_token",
            "X-Apple-TwoSV-Trust-Eligible" to "trust_eligible", "X-Apple-I-Rscd" to "apple_rscd", "X-Apple-I-Ercd" to "apple_ercd", "scnt" to "scnt")
    }

    val jar = CookieJar()
    val data = mutableMapOf<String, String>()
    /** risposta di accountLogin/validate: dsInfo, webservices… */
    var account: JsonObject = JsonObject(emptyMap())
        private set

    init {
        store.load()?.let { text ->
            try {
                val o = Library.json.parseToJsonElement(text).jsonObject
                o["data"]?.jsonObject?.forEach { (k, v) -> v.jsonPrimitive.contentOrNull?.let { data[k] = it } }
                jar.load(o["cookies"] as? JsonArray)
            } catch (e: Exception) {
                // sessione illeggibile: si ricomincia dall'accesso
            }
        }
        if (data["client_id"].isNullOrEmpty()) data["client_id"] = "auth-${UUID.randomUUID().toString().lowercase()}"
    }

    val clientId: String get() = data.getValue("client_id")
    val params: Map<String, String>
        get() = buildMap {
            put("clientBuildNumber", "2522Project44"); put("clientMasteringNumber", "2522B2"); put("clientId", clientId)
            account.path("dsInfo", "dsid")?.jsonPrimitive?.contentOrNull?.let { put("dsid", it) }
        }

    private fun save() = store.save(buildJsonObject {
        putJsonObject("data") { data.forEach { (k, v) -> put(k, v) } }
        put("cookies", jar.toJson())
    }.toString())

    fun query(p: Map<String, String>) = p.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

    /** Richiesta con i cookie della sessione; salva intestazioni e cookie ricevuti. */
    fun request(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse {
        val h = linkedMapOf("Origin" to HOME, "Referer" to "$HOME/", "User-Agent" to USER_AGENT)
        h.putAll(headers)
        jar.header(URL(url))?.let { h["Cookie"] = it }
        val r = transport.execute(HttpRequest(method, url, h, body))
        val host = URL(url).host.lowercase()
        r.headerAll("Set-Cookie").forEach { jar.store(host, it) }
        HEADER_DATA.forEach { (header, key) -> r.header(header)?.takeIf { it.isNotEmpty() }?.let { data[key] = it } }
        save()
        return r
    }

    /** Richiesta che restituisce JSON; errori HTTP e di servizio diventano [ICloudError]. */
    fun json(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(), allow: Set<Int> = emptySet()): Pair<Int, JsonObject> {
        val r = request(method, url, body, headers)
        val text = r.text()
        val o = try { Library.json.parseToJsonElement(text).obj() } catch (e: Exception) { null } ?: JsonObject(emptyMap())
        if (r.status >= 400 && r.status !in allow) {
            val err = o["service_errors"]?.jsonArray?.firstOrNull()?.jsonObject
            val code = err?.s("code") ?: o["errorCode"]?.jsonPrimitive?.contentOrNull ?: o["serverErrorCode"]?.jsonPrimitive?.contentOrNull ?: "${r.status}"
            val msg = err?.s("message") ?: o["errorMessage"]?.jsonPrimitive?.contentOrNull ?: o["reason"]?.jsonPrimitive?.contentOrNull ?: "Errore ${r.status}"
            throw ICloudError(msg, code, r.status)
        }
        return r.status to o
    }

    private fun authHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> = buildMap {
        put("Accept", "application/json, text/javascript"); put("Content-Type", "application/json")
        put("X-Apple-OAuth-Client-Id", WIDGET_KEY); put("X-Apple-OAuth-Client-Type", "firstPartyAuth")
        put("X-Apple-OAuth-Redirect-URI", HOME); put("X-Apple-OAuth-Require-Grant-Code", "true")
        put("X-Apple-OAuth-Response-Mode", "web_message"); put("X-Apple-OAuth-Response-Type", "code")
        put("X-Apple-OAuth-State", clientId); put("X-Apple-Widget-Key", WIDGET_KEY)
        data["scnt"]?.let { put("scnt", it) }
        data["session_id"]?.let { put("X-Apple-ID-Session-Id", it) }
        putAll(extra)
    }

    // --- accesso ---------------------------------------------------------------------------------

    /**
     * Accede: riusa la sessione salvata se è ancora valida, altrimenti entra con la password
     * (e il token di fiducia, che evita il codice per circa due mesi).
     * Lancia [ICloudNeedsCode] quando Apple chiede il codice a due fattori.
     */
    fun authenticate(password: () -> String?) {
        var ok = false
        if (!data["session_token"].isNullOrEmpty()) {
            try {
                account = json("POST", "$SETUP/validate", "null", mapOf("Origin" to HOME, "Referer" to "$HOME/")).second
                ok = true
            } catch (e: ICloudError) {
                // token scaduto: nuovo accesso
            }
        }
        if (!ok) {
            val pw = password() ?: throw ICloudError("Password dell'Apple ID mancante", "no-password")
            signIn(pw)
            accountLogin()
        }
        if (requires2fa) throw ICloudNeedsCode()
    }

    /** Accesso con SRP: la password resta sul telefono. */
    fun signIn(password: String, privateKey: ByteArray? = null) {
        val client = if (privateKey != null) Srp.Client(appleId, privateKey) else Srp.Client(appleId)
        val headers = authHeaders(mapOf("Origin" to AUTH_ROOT, "Referer" to "$AUTH_ROOT/"))
        val init = json("POST", "$AUTH/signin/init", buildJsonObject {
            put("a", Base64.getEncoder().encodeToString(client.publicKey)); put("accountName", appleId)
            putJsonArray("protocols") { add(JsonPrimitive("s2k")); add(JsonPrimitive("s2k_fo")) }
        }.toString(), headers).second
        val salt = Base64.getDecoder().decode(init.s("salt"))
        val b = Base64.getDecoder().decode(init.s("b"))
        val key = Srp.passwordKey(password, init.s("protocol"), salt, init["iteration"]!!.jsonPrimitive.int)
        val (m1, m2) = client.respond(salt, b, key)
        val (status, _) = json("POST", "$AUTH/signin/complete?isRememberMeEnabled=true", buildJsonObject {
            put("accountName", appleId); put("c", init.s("c"))
            put("m1", Base64.getEncoder().encodeToString(m1)); put("m2", Base64.getEncoder().encodeToString(m2))
            put("rememberMe", true)
            putJsonArray("trustTokens") { data["trust_token"]?.let { add(JsonPrimitive(it)) } }
        }.toString(), headers, allow = setOf(409, 412, 401, 403))
        when (status) {
            409 -> {}                                             // serve il codice a due fattori
            412 -> json("POST", "$AUTH/repair/complete", "{}", authHeaders())   // account senza due fattori
            401, 403 -> throw ICloudError("Apple ID o password non corretti", "auth", status)
        }
    }

    /** Scambia il token della sessione con l'accesso ai servizi iCloud. */
    fun accountLogin() {
        account = json("POST", "$SETUP/accountLogin", buildJsonObject {
            put("accountCountryCode", data["account_country"])
            put("dsWebAuthToken", data["session_token"]); put("extended_login", true); put("trustToken", data["trust_token"] ?: "")
        }.toString()).second
        account["domainToUse"]?.jsonPrimitive?.contentOrNull?.let { throw ICloudError("Apple richiede il dominio $it (iCloud Cina non è supportato)", "domain") }
    }

    val requires2fa: Boolean
        get() {
            val ds = account["dsInfo"]?.jsonObject ?: return false
            val hsa = ds["hsaVersion"]?.jsonPrimitive?.intOrNull ?: 0
            val challenge = account["hsaChallengeRequired"]?.jsonPrimitive?.booleanOrNull ?: false
            val trusted = account["hsaTrustedBrowser"]?.jsonPrimitive?.booleanOrNull ?: false
            return hsa >= 1 && (challenge || !trusted)
        }

    /** Chiede ad Apple di mostrare il codice sui dispositivi fidati (flusso 2026 di icloudpd). */
    fun requestCode(): Boolean = try {
        request("PUT", "$AUTH/verify/trusteddevice/securitycode", null, authHeaders(mapOf("Accept" to "application/json"))).status < 400
    } catch (e: Exception) {
        false
    }

    /** Verifica il codice a sei cifre; poi rende fidata la sessione. False se il codice è sbagliato. */
    fun verifyCode(code: String): Boolean {
        try {
            json("POST", "$AUTH/verify/trusteddevice/securitycode", buildJsonObject { putJsonObject("securityCode") { put("code", code.trim()) } }.toString(),
                authHeaders(mapOf("Accept" to "application/json")))
        } catch (e: ICloudError) {
            if (e.code == "-21669" || e.status == 400) return false
            throw e
        }
        request("GET", "$AUTH/2sv/trust", null, authHeaders()).text()
        accountLogin()
        return !requires2fa
    }

    fun logout() {
        data.keys.retainAll(setOf("client_id"))
        jar.cookies.clear()
        account = JsonObject(emptyMap())
        save()
    }

    fun photos(): ICloudPhotos {
        val root = account.path("webservices", "ckdatabasews", "url")?.jsonPrimitive?.contentOrNull
            ?: throw ICloudError("iCloud Foto non è attivo per questo account", "no-photos")
        return ICloudPhotos(this, "$root/database/1/com.apple.photos.cloud/production/private")
    }
}

/** Una foto o un video di iCloud con la versione originale (e il video della Live Photo). */
data class ICloudAsset(
    val id: String,                 // recordName del master (stabile)
    val filename: String,
    val itemType: String,
    val isVideo: Boolean,
    val size: Long,
    val url: String,
    val width: Int,
    val height: Int,
    val assetDate: Double?,         // secondi
    val addedDate: Double?,
    val tzOffset: Int?,             // secondi: fuso del luogo dello scatto
    val favorite: Boolean,
    val lat: Double?,
    val lon: Double?,
    val caption: String?,
    val liveUrl: String?,
    val liveSize: Long?,
) {
    /** Data di scatto «sull'orologio» del luogo dello scatto, come le date EXIF (vedi MediaInfo). */
    val taken: Double?
        get() {
            val t = assetDate ?: return null
            val off = tzOffset ?: return t
            return Fmt.ts(java.time.LocalDateTime.ofEpochSecond(Math.floorDiv(t.toLong() + off, 1L), 0, java.time.ZoneOffset.UTC))
        }

    /** Nome del video della Live Photo: stesso nome, estensione .MOV (si abbina da solo nella fototeca). */
    val liveFilename: String get() = "${stem(filename)}.MOV"
}

/** iCloud Foto: elenco della fototeca (dal più recente) e scaricamento degli originali. */
class ICloudPhotos(private val session: ICloudSession, private val endpoint: String) {
    private val zone = buildJsonObject { put("zoneName", "PrimarySync") }
    private val headers = mapOf("Content-type" to "text/plain")
    private fun url(path: String) = "$endpoint/$path?" + session.query(session.params + mapOf("remapEnums" to "true", "getCurrentSyncToken" to "true"))

    companion object {
        val ITEM_EXT = mapOf("public.heic" to "HEIC", "public.heif" to "HEIF", "public.jpeg" to "JPG", "public.png" to "PNG",
            "com.apple.quicktime-movie" to "MOV", "com.adobe.raw-image" to "DNG", "com.canon.cr2-raw-image" to "CR2",
            "com.canon.crw-raw-image" to "CRW", "com.sony.arw-raw-image" to "ARW", "com.fuji.raw-image" to "RAF",
            "com.panasonic.rw2-raw-image" to "RW2", "com.nikon.nrw-raw-image" to "NRW", "com.pentax.raw-image" to "PEF",
            "com.nikon.raw-image" to "NEF", "com.olympus.raw-image" to "ORF", "com.canon.cr3-raw-image" to "CR3",
            "com.olympus.or-raw-image" to "ORF")
        private val IMAGE_TYPES = ITEM_EXT.keys - "com.apple.quicktime-movie"
        val DESIRED = listOf("resOriginalRes", "resOriginalFileType", "resOriginalWidth", "resOriginalHeight", "resOriginalVidComplRes",
            "resOriginalVidComplFileType", "itemType", "filenameEnc", "recordName", "recordType", "masterRef", "assetDate", "addedDate",
            "isFavorite", "isHidden", "duration", "captionEnc", "locationLatitude", "locationLongitude", "timeZoneOffset")

        private fun decodeText(f: JsonObject?): String? {
            val v = f?.get("value")?.jsonPrimitive?.contentOrNull ?: return null
            return if (f.s("type") == "ENCRYPTED_BYTES") try { String(Base64.getDecoder().decode(v), Charsets.UTF_8) } catch (e: Exception) { null } else v
        }

        /** Coppie asset/master di una pagina di risultati (come PhotoAlbum.photos). */
        /**
         * Foto della pagina nell'ordine di iCloud, una per CPLAsset (come icloudpd: più asset possono
         * condividere un originale, e il CPLMaster può essere arrivato nella pagina precedente, [previous]).
         */
        fun parse(records: JsonArray, previous: Map<String, JsonObject> = emptyMap()): List<ICloudAsset> {
            val assets = mutableListOf<JsonObject>()
            val masters = HashMap<String, JsonObject>()
            for (e in records) {
                val r = e.jsonObject
                when (r.s("recordType")) {
                    "CPLAsset" -> assets += r
                    "CPLMaster" -> masters[r.s("recordName")] = r
                }
            }
            return assets.mapNotNull { a ->
                val id = a.path("fields", "masterRef", "value", "recordName")?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val m = masters[id] ?: previous[id] ?: return@mapNotNull null
                val mf = m["fields"]?.jsonObject ?: return@mapNotNull null
                val af = a["fields"]?.jsonObject ?: JsonObject(emptyMap())
                fun field(prefix: String) = (af[prefix] ?: mf[prefix])?.jsonObject
                val res = field("resOriginalRes")?.get("value")?.jsonObject ?: return@mapNotNull null
                val type = mf["itemType"]?.jsonObject?.s("value") ?: ""
                val ext = ITEM_EXT[type] ?: "JPG"
                val name = decodeText(mf["filenameEnc"]?.jsonObject)?.let(::cleanName)
                    ?: "${id.replace(Regex("[^0-9a-zA-Z]"), "_").take(12)}.$ext"
                val isVideo = when {
                    type in IMAGE_TYPES -> false
                    type == "com.apple.quicktime-movie" -> true
                    else -> !Regex("\\.(heic|png|jpe?g)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
                }
                val live = if (isVideo) null else field("resOriginalVidComplRes")?.get("value")?.jsonObject
                fun num(o: JsonObject?) = o?.get("value")?.jsonPrimitive
                ICloudAsset(
                    id = id, filename = name, itemType = type, isVideo = isVideo,
                    size = res["size"]?.jsonPrimitive?.longOrNull ?: 0, url = res.s("downloadURL"),
                    width = num(mf["resOriginalWidth"]?.jsonObject)?.intOrNull ?: 0, height = num(mf["resOriginalHeight"]?.jsonObject)?.intOrNull ?: 0,
                    assetDate = num(af["assetDate"]?.jsonObject)?.longOrNull?.div(1000.0), addedDate = num(af["addedDate"]?.jsonObject)?.longOrNull?.div(1000.0),
                    tzOffset = num(af["timeZoneOffset"]?.jsonObject)?.intOrNull,
                    favorite = (num(af["isFavorite"]?.jsonObject)?.longOrNull ?: 0) == 1L,
                    lat = num(af["locationLatitude"]?.jsonObject)?.doubleOrNull, lon = num(af["locationLongitude"]?.jsonObject)?.doubleOrNull,
                    caption = decodeText(af["captionEnc"]?.jsonObject)?.takeIf { it.isNotBlank() },
                    liveUrl = live?.s("downloadURL")?.takeIf { it.isNotEmpty() }, liveSize = live?.get("size")?.jsonPrimitive?.longOrNull,
                )
            }
        }

        /** Nome di file sicuro (come clean_filename di icloudpd). */
        fun cleanName(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().ifEmpty { "foto" }
    }

    /** La fototeca è pronta? (Apple la indicizza dopo l'attivazione di iCloud Foto sul web.) */
    fun ready(): Boolean {
        val o = session.json("POST", url("records/query"), buildJsonObject {
            putJsonObject("query") { put("recordType", "CheckIndexingState") }
            put("zoneID", zone)
        }.toString(), headers).second
        return o["records"]?.jsonArray?.firstOrNull()?.jsonObject?.path("fields", "state", "value")?.jsonPrimitive?.contentOrNull == "FINISHED"
    }

    /** Numero di foto e video nella fototeca. */
    fun count(): Int {
        val o = session.json("POST", "$endpoint/internal/records/query/batch?" + session.query(session.params), buildJsonObject {
            putJsonArray("batch") {
                add(buildJsonObject {
                    put("resultsLimit", 1)
                    putJsonObject("query") {
                        putJsonObject("filterBy") {
                            put("fieldName", "indexCountID"); put("comparator", "IN")
                            putJsonObject("fieldValue") { put("type", "STRING_LIST"); putJsonArray("value") { add(JsonPrimitive("CPLAssetByAssetDateWithoutHiddenOrDeleted")) } }
                        }
                        put("recordType", "HyperionIndexCountLookup")
                    }
                    put("zoneWide", true); put("zoneID", zone)
                })
            }
        }.toString(), headers).second
        return (o["batch"]?.jsonArray?.firstOrNull()?.jsonObject?.get("records")?.jsonArray?.firstOrNull()?.jsonObject
            ?.path("fields", "itemCount", "value")?.jsonPrimitive?.intOrNull ?: 0).also { total = it }
    }

    @Volatile private var total: Int? = null
    private var previousMasters: Map<String, JsonObject> = emptyMap()

    /**
     * Una pagina della fototeca, dalla più recente: come icloudpd, «startRank» = foto già lette
     * (0 = la più recente) e direzione ASCENDING. [ICloudSync.Page.consumed] conta i CPLAsset
     * ricevuti, da cui riparte la pagina successiva.
     */
    fun page(offset: Int, size: Int = 100): ICloudSync.Page {
        val o = session.json("POST", url("records/query"), buildJsonObject {
            putJsonObject("query") {
                putJsonArray("filterBy") {
                    add(buildJsonObject {
                        put("fieldName", "startRank"); put("comparator", "EQUALS")
                        putJsonObject("fieldValue") { put("type", "INT64"); put("value", offset) }
                    })
                    add(buildJsonObject {
                        put("fieldName", "direction"); put("comparator", "EQUALS")
                        putJsonObject("fieldValue") { put("type", "STRING"); put("value", "ASCENDING") }
                    })
                }
                put("recordType", "CPLAssetAndMasterByAssetDateWithoutHiddenOrDeleted")
            }
            put("resultsLimit", size * 2)
            putJsonArray("desiredKeys") { DESIRED.forEach { add(JsonPrimitive(it)) } }
            put("zoneID", zone)
        }.toString(), headers).second
        val records = o["records"]?.jsonArray ?: JsonArray(emptyList())
        val assets = parse(records, previousMasters)
        previousMasters = records.map { it.jsonObject }.filter { it.s("recordType") == "CPLMaster" }.associateBy { it.s("recordName") }
        return ICloudSync.Page(assets, records.count { it.jsonObject.s("recordType") == "CPLAsset" })
    }

    /** Flusso dell'originale (si copia direttamente nella destinazione, senza file temporanei). */
    fun open(url: String): InputStream {
        val r = session.request("GET", url, null, mapOf("Range" to "bytes=0-"))
        if (r.status >= 400) {
            r.stream.close()
            throw ICloudError("Scaricamento non riuscito (${r.status})", "${r.status}", r.status)
        }
        return r.stream
    }
}
