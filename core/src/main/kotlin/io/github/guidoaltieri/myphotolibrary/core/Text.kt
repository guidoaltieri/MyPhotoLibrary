package io.github.guidoaltieri.myphotolibrary.core

import java.text.Normalizer
import java.util.Locale

private val COMBINING = Regex("\\p{Mn}+")

/** Minuscolo e senza accenti, per ricerche tolleranti (Cefalu = Cefalù). */
fun fold(s: String?): String {
    if (s == null) return ""
    val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
    return COMBINING.replace(n, "").replace("ß", "ss")
}

fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/')

fun dirname(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")

fun stem(name: String): String = basename(name).let { b -> if (b.lastIndexOf('.') > 0) b.substring(0, b.lastIndexOf('.')) else b }

fun extension(name: String): String = basename(name).let { b ->
    val i = b.lastIndexOf('.')
    if (i > 0) b.substring(i + 1).lowercase(Locale.ROOT) else ""
}

fun String.capitalizeFirst(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ITALIAN) else it.toString() }
