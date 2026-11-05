package io.github.guidoaltieri.myphotolibrary.core

import java.time.LocalDateTime

/**
 * Importazione: in quale cartella va ogni foto (porting di uploader.py).
 *
 * - **Organizzazione**: anno/mese (predefinita), anno/mese/giorno, solo anno, anno-mese…
 *   o uno schema personalizzato con {anno} {mese} {giorno} {nome_mese}.
 * - **Cartelle esistenti**: se attivo, una foto di maggio 2024 finisce nella cartella che già
 *   rappresenta maggio 2024 anche se ha un nome diverso («05 - Maggio», «Maggio», «2024-05»…).
 * - I file già presenti (stesso nome e stessa dimensione) vengono saltati; con lo stesso nome ma
 *   contenuto diverso vengono rinominati «nome (1).jpg».
 */
object Layouts {
    val LAYOUTS = listOf(
        "{anno}/{mese}" to "Anno/Mese  (2024/05)",
        "{anno}/{mese}/{giorno}" to "Anno/Mese/Giorno  (2024/05/12)",
        "{anno}" to "Anno  (2024)",
        "{anno}/{anno}-{mese}" to "Anno/Anno-Mese  (2024/2024-05)",
        "{anno}-{mese}" to "Anno-Mese  (2024-05)",
        "{anno}/{mese} - {nome_mese}" to "Anno/Mese con nome  (2024/05 - Maggio)",
        "{anno}/{anno}-{mese}-{giorno}" to "Anno/Data  (2024/2024-05-12)",
        "" to "Nessuna: tutto nella cartella scelta",
    )
    val DEFAULT = LAYOUTS[0].first
    const val TOKENS = "{anno} {mese} {giorno} {nome_mese}"

    fun formatPart(part: String, t: LocalDateTime): String = part
        .replace("{anno}", "%04d".format(t.year)).replace("{mese}", "%02d".format(t.monthValue))
        .replace("{giorno}", "%02d".format(t.dayOfMonth)).replace("{nome_mese}", Fmt.MONTHS[t.monthValue - 1].capitalizeFirst())
        .replace("{Y}", "%04d".format(t.year)).replace("{M}", "%02d".format(t.monthValue)).replace("{D}", "%02d".format(t.dayOfMonth))

    /** Messaggio d'errore o null se lo schema è valido. */
    fun validate(layout: String): String? {
        if (".." in layout || layout.startsWith("/")) return "Lo schema non può contenere «..» né iniziare con «/»."
        var left = layout
        for (tok in listOf("{anno}", "{mese}", "{giorno}", "{nome_mese}", "{Y}", "{M}", "{D}")) left = left.replace(tok, "")
        if ("{" in left || "}" in left) return "Segnaposto sconosciuto. Usa: $TOKENS"
        return null
    }

    fun granularity(part: String): String = when {
        "{giorno}" in part || "{D}" in part -> "d"
        "{mese}" in part || "{nome_mese}" in part || "{M}" in part -> "m"
        "{anno}" in part || "{Y}" in part -> "y"
        else -> ""
    }

    /** Esempio di destinazione per l'interfaccia: «Immagini/MyPhotoLibrary/2024/05/IMG_0001.JPG». */
    fun preview(layout: String, label: String): String {
        val t = LocalDateTime.of(2024, 5, 12, 10, 30)
        val rel = layout.trim('/').split("/").filter { it.isNotEmpty() }.joinToString("/") { formatPart(it, t) }
        return "${label.trimEnd('/')}/${if (rel.isNotEmpty()) "$rel/" else ""}IMG_0001.JPG"
    }
}

/** Destinazione di un'importazione: elenca cartelle e file (nome → dimensione). */
interface ImportTarget {
    fun listDirs(rel: String): List<String>
    fun listFiles(rel: String): Map<String, Long>
}

class Planner(private val target: ImportTarget, layout: String = Layouts.DEFAULT, private val useExisting: Boolean = true) {
    private val parts = layout.trim('/').split("/").filter { it.isNotEmpty() }
    private val dirs = HashMap<String, MutableList<String>>()
    private val files = HashMap<String, MutableMap<String, Long>>()

    private fun subdirs(rel: String) = dirs.getOrPut(rel) { target.listDirs(rel).toMutableList() }
    fun filesIn(rel: String) = files.getOrPut(rel) { target.listFiles(rel).toMutableMap() }

    companion object {
        /** La cartella [name] rappresenta già l'anno/mese/giorno di [t]? */
        fun matches(name: String, gran: String, t: LocalDateTime): Boolean {
            val c = Dates.parseComponent(name)
            return when (gran) {
                "y" -> c.y == t.year && c.m == null
                "m" -> c.m == t.monthValue && (c.y ?: t.year) == t.year && c.d == null
                "d" -> if (c.d != null) c.d == t.dayOfMonth && c.m == t.monthValue && c.y == t.year else {
                    val m = Regex("^(0?[1-9]|[12]\\d|3[01])(?!\\d)").find(name.trim())   // «12» dentro la cartella del mese
                    m != null && c.y == null && m.groupValues[1].toInt() == t.dayOfMonth
                }
                else -> false
            }
        }
    }

    fun folderFor(t: LocalDateTime): String {
        var rel = ""
        for (part in parts) {
            var chosen = Layouts.formatPart(part, t)
            val gran = Layouts.granularity(part)
            if (useExisting && gran.isNotEmpty()) {
                val existing = subdirs(rel)
                if (chosen !in existing) {
                    val match = existing.firstOrNull { matches(it, gran, t) }
                    if (match != null) chosen = match else existing += chosen   // la creeremo: vale anche per i file successivi
                }
            }
            rel = if (rel.isNotEmpty()) "$rel/$chosen" else chosen
        }
        return rel
    }

    /** (percorso relativo del file, già presente). */
    fun destination(srcName: String, size: Long, t: LocalDateTime): Pair<String, Boolean> {
        val folder = folderFor(t)
        val fs = filesIn(folder)
        var name = basename(srcName)
        if (fs[name] == size) return (if (folder.isNotEmpty()) "$folder/$name" else name) to true
        val st = stem(name)
        val ext = name.substring(st.length)
        var n = 1
        while (name in fs) {
            name = "$st ($n)$ext"
            n++
        }
        fs[name] = size
        return (if (folder.isNotEmpty()) "$folder/$name" else name) to false
    }
}
