package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Group
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.capitalizeFirst

/**
 * Libreria per Anni, Mesi, Giorni e Tutte le foto, come in Foto: le viste raggruppate mostrano
 * le foto migliori del periodo (preferite, modificate, poi distribuite nel tempo).
 */
@Composable
fun LibraryTab(sel: Selection, actions: Actions) {
    val repo = repo()
    val nav = nav()
    val level by repo.settings.libraryLevel.flow.collectAsState()
    var focus by remember { mutableStateOf<String?>(null) }   // gruppo da mostrare dopo il cambio di livello
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (level) {
                "years", "months", "days" -> {
                    val groups = load(level, initial = null as List<Group>?) { repo.lib.groups(level.removeSuffix("s"), perGroup = if (level == "days") 9 else 6) }
                    if (groups != null) {
                        if (groups.isEmpty()) Empty("Nessuna foto", "Le foto e i video del dispositivo e delle cartelle aggiunte compaiono qui.")
                        else GroupList(level, groups, focus) { g ->
                            when (level) {
                                "years" -> { focus = g.key; repo.settings.libraryLevel.value = "months" }
                                "months" -> { focus = g.key; repo.settings.libraryLevel.value = "days" }
                                else -> nav.go(Screen.Ids(g.ids, Fmt.dayLong(g.start), g.places.joinToString(", ")))
                            }
                        }
                    }
                }
                else -> {
                    val items = load(initial = null as List<Item>?) { repo.lib.media("library") }
                    val cols by repo.settings.columns.flow.collectAsState()
                    val state = rememberLazyGridState()
                    if (items != null) {
                        if (items.isEmpty()) Empty("Nessuna foto", "Le foto e i video del dispositivo e delle cartelle aggiunte compaiono qui.")
                        else {
                            LaunchedEffect(focus, items.size) {
                                val f = focus ?: return@LaunchedEffect
                                val i = items.indexOfFirst { Fmt.dt(it.taken).toLocalDate().toString() >= f }
                                if (i >= 0) state.scrollToItem(i)
                                focus = null
                            }
                            MediaGrid(items, sel, onOpen = { i -> nav.go(Screen.Viewer(items.map { it.id }, i, "library")) }, state = state,
                                columns = cols, onColumns = { repo.settings.columns.value = it }, scrollToEnd = focus == null,
                                contentPadding = PaddingValues(bottom = 8.dp))
                        }
                    }
                }
            }
        }
        if (!sel.active) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val opts = listOf("years" to "Anni", "months" to "Mesi", "days" to "Giorni", "all" to "Tutte")
            opts.forEachIndexed { i, (k, l) ->
                SegmentedButton(selected = level == k, onClick = { repo.settings.libraryLevel.value = k },
                    shape = SegmentedButtonDefaults.itemShape(i, opts.size)) { Text(l) }
            }
        }
    }
}

@Composable
private fun GroupList(level: String, groups: List<Group>, focus: String?, onOpen: (Group) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(groups.size, focus) {
        val i = if (focus != null) groups.indexOfFirst { it.key.startsWith(focus) }.takeIf { it >= 0 } else null
        state.scrollToItem(i ?: (groups.size - 1).coerceAtLeast(0))
    }
    LazyColumn(state = state, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(groups, key = { it.key }) { g ->
            when (level) {
                "years" -> GroupCard(g, g.key, Fmt.items(g.photos.toLong(), g.videos.toLong()), onOpen, big = true)
                "months" -> {
                    val d = Fmt.dt(g.start)
                    GroupCard(g, "${Fmt.monthName(d.monthValue).capitalizeFirst()} ${d.year}",
                        listOf(g.places.take(2).joinToString(", "), Fmt.items(g.photos.toLong(), g.videos.toLong())).filter { it.isNotEmpty() }.joinToString(" · "), onOpen)
                }
                else -> DayCard(g, onOpen)
            }
        }
    }
}

/** Scheda di un anno o di un mese: mosaico delle foto migliori con il titolo sopra. */
@Composable
private fun GroupCard(g: Group, title: String, subtitle: String, onOpen: (Group) -> Unit, big: Boolean = false) {
    Box(Modifier.fillMaxWidth().aspectRatio(if (big) 1.2f else 1.5f).rounded().clickable { onOpen(g) }) {
        val best = g.best
        if (best.size >= 3 && !big) {
            Row(Modifier.fillMaxSize()) {
                ThumbImage(best[0], Modifier.weight(2f).fillMaxSize(), size = 768)
                Column(Modifier.weight(1f).fillMaxSize()) {
                    ThumbImage(best[1], Modifier.weight(1f).fillMaxWidth().padding(start = 2.dp, bottom = 1.dp))
                    ThumbImage(best[2], Modifier.weight(1f).fillMaxWidth().padding(start = 2.dp, top = 1.dp))
                }
            }
        } else best.getOrNull(best.size / 2)?.let { ThumbImage(it, Modifier.fillMaxSize(), size = 1024) }
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))))
        Column(Modifier.padding(14.dp)) {
            Text(title, color = Color.White, style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Color.White, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Un giorno: intestazione con data e luoghi, fino a 9 foto migliori, «Mostra tutte». */
@Composable
private fun DayCard(g: Group, onOpen: (Group) -> Unit) {
    val nav = nav()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.relativeDay(g.start), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val sub = listOf(g.places.joinToString(", "), Fmt.items(g.photos.toLong(), g.videos.toLong())).filter { it.isNotEmpty() }.joinToString(" · ")
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (g.count > g.best.size) TextButton(onClick = { onOpen(g) }) { Text("Mostra tutte") }
        }
        val rows = g.best.chunked(3)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.rounded()) {
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clickable {
                            nav.go(Screen.Viewer(g.best.map { it.id }, g.best.indexOf(item)))
                        }) {
                            ThumbImage(item, Modifier.fillMaxSize())
                            ThumbBadges(item)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}
