package io.github.guidoaltieri.myphotolibrary.core

/** Pagina «Suggerimenti»: testi e riepiloghi dei gruppi (porting di suggestions.py). */
object Suggestions {
    const val EMPTY_TITLE = "Nessun suggerimento"
    const val EMPTY_DATES = "Le date delle foto sono coerenti con le cartelle che le contengono (es. 2024/05 → maggio 2024)."
    const val EMPTY_PLACES = "Nessuna foto senza posizione ha un luogo nel nome della cartella o del file."
    const val DATES_TEXT = "Queste foto si trovano in cartelle che indicano una data diversa da quella della foto. Applicando il " +
        "suggerimento la data diventa coerente con la cartella (anno, oppure anno e mese); giorno e ora restano quelli " +
        "originali, oppure si usa la data nel nome del file se corrisponde."
    const val PLACES_TEXT = "Queste foto non hanno una posizione nei metadati, ma il nome della cartella o del file cita un luogo " +
        "(es. «Vacanze Parigi», «Roma_Colosseo_014.jpg»). Applicando il suggerimento la foto viene collocata al centro della " +
        "località e compare nella mappa e nei luoghi."
    val LEVEL_NOTE = mapOf("zona" to "posizione approssimativa (centro della zona)",
        "regione" to "posizione approssimativa (centro della regione)", "paese" to "posizione approssimativa (centro del paese)")

    private fun photos(n: Int) = Fmt.count(n.toLong(), "foto", "foto")

    /** «marzo 2026» o «3 date diverse» per un gruppo di foto. */
    private fun datesOf(values: List<Double>, level: String): String {
        val set = values.map { ts ->
            when (level) {
                "giorno" -> Fmt.day(ts)
                "mese" -> Fmt.month(ts).lowercase()
                else -> Fmt.dt(ts).year.toString()
            }
        }.toSet()
        return if (set.size == 1) set.first() else "${set.size} date diverse"
    }

    fun datesHeader(groups: List<Dates.SuggestionGroup>): String =
        "${photos(groups.sumOf { it.items.size })} in ${Fmt.count(groups.size.toLong(), "cartella", "cartelle")}"

    fun placesHeader(groups: List<PlaceNames.PlaceGroup>): String =
        "${photos(groups.sumOf { it.items.size })} in ${Fmt.count(groups.size.toLong(), "luogo", "luoghi")}"

    /** (titolo, sottotitolo) di un gruppo di date. */
    fun dateCard(g: Dates.SuggestionGroup): Pair<String, String> {
        val level = g.items.first().level
        return g.folder.ifEmpty { g.dir } to "${photos(g.items.size)} · data attuale: ${datesOf(g.items.map { it.item.taken }, level)} → " +
            "suggerita: ${datesOf(g.items.map { it.suggested }, level)}"
    }

    /** (titolo, sottotitolo) di un gruppo di luoghi. */
    fun placeCard(g: PlaceNames.PlaceGroup): Pair<String, String> {
        val fromName = g.evidence == "nome del file"
        var sub = "${photos(g.items.size)} · " + if (fromName) "dal nome del file" else "dalla ${g.evidence}"
        if (fromName) sub += " · ${basename(g.dir).ifEmpty { g.dir }}"
        LEVEL_NOTE[g.level]?.let { sub += " · $it" }
        return g.place to sub
    }
}

/** Finestra «Modifica data e luogo»: descrizione dello stato attuale della selezione (come infoedit.py). */
object InfoEdit {
    fun dateDescription(items: List<Item>): String {
        if (items.isEmpty()) return ""
        val first = items.minOf { it.taken }
        if (items.size == 1) return "Attuale: ${Fmt.datetime(first)}"
        return "${Fmt.count(items.size.toLong(), "elemento", "elementi")}, dal ${Fmt.datetime(first)} al ${Fmt.datetime(items.maxOf { it.taken })}"
    }

    fun placeDescription(items: List<Item>): String {
        val places = items.map { it.place?.takeIf { p -> p.isNotEmpty() } ?: if (it.lat != null) "%.4f, %.4f".format(java.util.Locale.ROOT, it.lat, it.lon) else "" }
            .filter { it.isNotEmpty() }.toSet()
        return when {
            places.isEmpty() -> "Attuale: nessuno"
            places.size > 1 -> "Attuale: ${places.size} luoghi diversi"
            items.all { it.lat != null } -> "Attuale: ${places.first()}"
            else -> "Attuale: ${places.first()} (solo per alcuni elementi)"
        }
    }
}
