package io.github.guidoaltieri.myphotolibrary.core

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToLong

/** Formattazione di date, dimensioni e dati di scatto in italiano. */
object Fmt {
    val MONTHS = listOf("gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio", "agosto",
        "settembre", "ottobre", "novembre", "dicembre")
    val WEEKDAYS = listOf("lunedì", "martedì", "mercoledì", "giovedì", "venerdì", "sabato", "domenica")

    var zone: ZoneId = ZoneId.systemDefault()

    fun dt(ts: Double): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli((ts * 1000).roundToLong()), zone)
    fun date(ts: Double): LocalDate = dt(ts).toLocalDate()
    fun ts(d: LocalDateTime): Double = d.atZone(zone).toInstant().toEpochMilli() / 1000.0

    /** «12 luglio 2024» */
    fun day(ts: Double?): String {
        if (ts == null || ts == 0.0) return ""
        val d = dt(ts)
        return "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
    }

    /** «venerdì 12 luglio 2024» */
    fun dayLong(ts: Double?): String {
        if (ts == null || ts == 0.0) return ""
        val d = dt(ts)
        return "${WEEKDAYS[d.dayOfWeek.value - 1]} ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${d.year}"
    }

    /** «Luglio 2024» */
    fun month(ts: Double?): String {
        if (ts == null || ts == 0.0) return ""
        val d = dt(ts)
        return "${MONTHS[d.monthValue - 1].capitalizeFirst()} ${d.year}"
    }

    fun monthName(m: Int): String = MONTHS[m - 1]

    fun time(ts: Double?): String = if (ts == null || ts == 0.0) "" else dt(ts).let { "%02d:%02d".format(it.hour, it.minute) }

    fun datetime(ts: Double?): String = if (ts == null || ts == 0.0) "" else "${dayLong(ts)}, ${time(ts)}"

    fun shortDate(ts: Double?): String = if (ts == null || ts == 0.0) "" else dt(ts).let {
        "%02d/%02d/%04d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.year, it.hour, it.minute)
    }

    /** «Oggi», «Ieri», nome del giorno nell'ultima settimana, altrimenti la data. */
    fun relativeDay(ts: Double?, today: LocalDate = LocalDate.now(zone)): String {
        if (ts == null || ts == 0.0) return ""
        val d = date(ts)
        val delta = java.time.temporal.ChronoUnit.DAYS.between(d, today)
        return when {
            delta == 0L -> "Oggi"
            delta == 1L -> "Ieri"
            delta in 2..6 -> WEEKDAYS[d.dayOfWeek.value - 1].capitalizeFirst()
            d.year != today.year -> day(ts)
            else -> "${d.dayOfMonth} ${MONTHS[d.monthValue - 1]}"
        }
    }

    /** «3–9 luglio 2024», «28 giugno – 2 luglio 2024». */
    fun dateRange(a: Double?, b: Double?): String {
        if (a == null || a == 0.0) return ""
        val da = dt(a)
        val db = dt(b ?: a)
        if (da.toLocalDate() == db.toLocalDate()) return day(a)
        if (da.year == db.year && da.monthValue == db.monthValue)
            return "${da.dayOfMonth}–${db.dayOfMonth} ${MONTHS[da.monthValue - 1]} ${da.year}"
        if (da.year == db.year)
            return "${da.dayOfMonth} ${MONTHS[da.monthValue - 1]} – ${db.dayOfMonth} ${MONTHS[db.monthValue - 1]} ${da.year}"
        return "${day(a)} – ${day(b)}"
    }

    fun duration(seconds: Double?): String {
        if (seconds == null || seconds == 0.0) return ""
        val s = seconds.roundToLong()
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun size(n: Long?): String {
        var v = (n ?: 0L).toDouble()
        for (unit in listOf("B", "KB", "MB", "GB", "TB")) {
            if (v < 1024 || unit == "TB") {
                return if (unit == "B") "${v.toLong()} B" else "%.1f %s".format(java.util.Locale.ROOT, v, unit).replace(".", ",")
            }
            v /= 1024
        }
        return ""
    }

    fun thousands(n: Long): String = "%,d".format(java.util.Locale.ROOT, n).replace(",", ".")

    fun count(n: Long, one: String, many: String): String = "${thousands(n)} ${if (n == 1L) one else many}"

    /** «1.204 foto, 37 video» */
    fun items(photos: Long, videos: Long): String {
        val parts = mutableListOf<String>()
        if (photos > 0) parts += "${thousands(photos)} foto"
        if (videos > 0) parts += count(videos, "video", "video")
        return parts.joinToString(", ").ifEmpty { "Nessun elemento" }
    }

    fun megapixels(w: Int?, h: Int?): String {
        if (w == null || h == null || w == 0 || h == 0) return ""
        val mp = w.toDouble() * h / 1_000_000
        return if (mp >= 10) "%.0f MP".format(java.util.Locale.ROOT, mp) else "%.1f MP".format(java.util.Locale.ROOT, mp).replace(".", ",")
    }

    /** Tempo di esposizione: 1/250 s, 2 s. */
    fun exposure(sec: Double?): String {
        if (sec == null || sec == 0.0) return ""
        if (sec >= 1) return "${trimNum(sec)} s"
        return "1/${(1 / sec).roundToLong()} s"
    }

    fun aperture(f: Double?): String = if (f == null || f == 0.0) "" else "ƒ/" + "%.1f".format(java.util.Locale.ROOT, f).replace(".0", "")

    fun focal(mm: Double?): String = if (mm == null || mm == 0.0) "" else "${trimNum(mm)} mm"

    fun coords(lat: Double?, lon: Double?): String {
        if (lat == null || lon == null) return ""
        val ns = if (lat >= 0) "N" else "S"
        val ew = if (lon >= 0) "E" else "O"
        return "%.5f° %s, %.5f° %s".format(java.util.Locale.ROOT, abs(lat), ns, abs(lon), ew)
    }

    fun trimNum(v: Double): String = if (v == Math.floor(v) && abs(v) < 1e15) v.toLong().toString() else
        "%.4g".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
}
