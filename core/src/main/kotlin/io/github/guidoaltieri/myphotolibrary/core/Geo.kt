package io.github.guidoaltieri.myphotolibrary.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Nomi dei luoghi dalle coordinate GPS (OpenStreetMap Nominatim), come geocode.py.
 * Le coordinate vengono arrotondate a ~1 km prima di essere inviate; una richiesta al secondo al
 * massimo; ogni zona viene chiesta una volta sola (tabella places).
 */
object Geo {
    var userAgent = "MyPhotoLibrary/1.0 (+https://github.com/guidoaltieri/MyPhotoLibrary)"
    private const val REVERSE = "https://nominatim.openstreetmap.org/reverse"
    private const val SEARCH = "https://nominatim.openstreetmap.org/search"

    data class Named(val name: String, val city: String, val region: String, val country: String)
    data class Found(val name: String, val detail: String, val lat: Double, val lon: Double)

    fun placeKey(lat: Double, lon: Double) = "%.2f,%.2f".format(java.util.Locale.ROOT, Math.round(lat * 100) / 100.0, Math.round(lon * 100) / 100.0)

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", userAgent)
        c.connectTimeout = 20000
        c.readTimeout = 20000
        try {
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun reverse(lat: Double, lon: Double, lang: String = "it"): Named {
        val url = "$REVERSE?lat=${"%.2f".format(java.util.Locale.ROOT, lat)}&lon=${"%.2f".format(java.util.Locale.ROOT, lon)}" +
            "&format=jsonv2&zoom=12&addressdetails=1&accept-language=$lang"
        return parseReverse(get(url))
    }

    fun parseReverse(text: String): Named {
        val data = Library.json.parseToJsonElement(text) as JsonObject
        if ("error" in data) return Named("", "", "", "")
        val a = data["address"] as? JsonObject ?: JsonObject(emptyMap())
        fun f(k: String) = a[k]?.jsonPrimitive?.content ?: ""
        val city = listOf("city", "town", "village", "municipality", "hamlet", "suburb", "county").map(::f).firstOrNull { it.isNotEmpty() } ?: ""
        val region = listOf("state", "region", "county").map(::f).firstOrNull { it.isNotEmpty() } ?: ""
        val country = f("country")
        val name = listOf(city, country).filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { data["display_name"]?.jsonPrimitive?.content ?: "" }
        return Named(name, city, region, country)
    }

    /** Ricerca di un luogo su OpenStreetMap. Bloccante: da chiamare fuori dal thread principale. */
    fun searchOnline(query: String, limit: Int = 8): List<Found> {
        val res = Library.json.parseToJsonElement(get("$SEARCH?q=${enc(query)}&format=jsonv2&limit=$limit&accept-language=it")) as JsonArray
        return res.map { e ->
            val x = e as JsonObject
            val display = x["display_name"]?.jsonPrimitive?.content ?: ""
            val parts = display.split(",").map { it.trim() }
            val name = x["name"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: parts[0]
            Found(if (parts.size > 1 && parts.last() != name) "$name, ${parts.last()}" else name, display,
                x["lat"]!!.jsonPrimitive.content.toDouble(), x["lon"]!!.jsonPrimitive.content.toDouble())
        }
    }

    /** «45.46, 9.19» → (45.46, 9.19); null se il testo non sono coordinate valide. */
    fun parseCoords(text: String): Pair<Double, Double>? {
        val p = text.replace(";", ",").split(",").map { it.trim() }
        if (p.size != 2) return null
        val lat = p[0].toDoubleOrNull() ?: return null
        val lon = p[1].toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }
}
