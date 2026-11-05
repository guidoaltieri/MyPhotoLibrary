package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Dates
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.ImportInfo
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.Library
import io.github.guidoaltieri.myphotolibrary.core.PlaceNames
import io.github.guidoaltieri.myphotolibrary.core.Suggestions

/** Duplicati: stessa immagine (hash percettivo) o stesso file; l'unione conserva preferiti, album e parole chiave. */
@Composable
fun DuplicatesScreen() {
    val repo = repo()
    val nav = nav()
    val groups = load(initial = null as List<List<Item>>?) { repo.lib.duplicates() }
    val pending = load(initial = 0) { repo.lib.missingHashes(1).size }
    Scaffold(topBar = {
        Bar("Duplicati", groups?.let { Fmt.count(it.size.toLong(), "gruppo", "gruppi") } ?: "", onBack = { nav.back() }) {
            if (!groups.isNullOrEmpty()) TextButton(onClick = {
                repo.io {
                    var n = 0
                    groups.forEach { g -> n += repo.lib.mergeDuplicates(g.map { it.id }) }
                    repo.say("$n duplicati spostati negli eliminati di recente")
                }
            }) { Text("Unisci tutti") }
        }
    }) { pad ->
        when {
            groups == null -> {}
            groups.isEmpty() -> Empty("Nessun duplicato", if (pending > 0) "Il confronto delle foto è ancora in corso." else "", Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(groups, key = { g -> g.first().id }) { g ->
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${Fmt.day(g[0].taken)} · ${g.size} copie", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Button(onClick = { repo.io { repo.lib.mergeDuplicates(g.map { it.id }) } }) { Text("Unisci") }
                            }
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                items(g, key = { it.id }) { it ->
                                    Column(Modifier.width(120.dp).clickable { nav.go(Screen.Viewer(g.map { x -> x.id }, g.indexOf(it))) }) {
                                        Box(Modifier.size(120.dp).rounded()) { ThumbImage(it, Modifier.fillMaxSize()) }
                                        Text("${it.width}×${it.height}", style = MaterialTheme.typography.bodySmall)
                                        Text(Fmt.size(it.filesize), style = MaterialTheme.typography.bodySmall)
                                        Text(it.relpath.substringBeforeLast("/", ""), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Importazioni passate, dalla più recente. */
@Composable
fun ImportsScreen() {
    val repo = repo()
    val nav = nav()
    val list = load(initial = null as List<ImportInfo>?) { repo.lib.imports() }
    Scaffold(topBar = {
        Bar("Importazioni", onBack = { nav.back() }) { TextButton(onClick = { nav.go(Screen.Import) }) { Text("Importa…") } }
    }) { pad ->
        when {
            list == null -> {}
            list.isEmpty() -> Empty("Nessuna importazione", "Importa foto da schede SD, chiavette USB, fotocamere o cartelle.", Modifier.padding(pad))
            else -> LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(list, key = { it.id }) { imp ->
                    Column(Modifier.clickable { nav.go(Screen.Grid("import:${imp.id}", Fmt.day(imp.started))) }) {
                        Text("${Fmt.datetime(imp.started)}", fontWeight = FontWeight.SemiBold)
                        Text("${imp.origin} · ${Fmt.count(imp.count.toLong(), "elemento", "elementi")}", style = MaterialTheme.typography.bodySmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 6.dp)) {
                            items(imp.items, key = { it.id }) { ThumbImage(it, Modifier.size(72.dp)) }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Suggerimenti: foto la cui data non corrisponde alla cartella (es. 2024/05 con data 2026) e foto
 * senza posizione con un luogo nel nome della cartella o del file. Applica, Ignora, Mostra.
 */
@Composable
fun SuggestionsScreen() {
    val repo = repo()
    val nav = nav()
    var tab by remember { mutableIntStateOf(0) }
    val dates = load(initial = null as List<Dates.SuggestionGroup>?) { Dates.suggestions(repo.lib) }
    val places = load(initial = null as List<PlaceNames.PlaceGroup>?) { PlaceNames.suggestions(repo.lib) }
    fun applyDates(groups: List<Dates.SuggestionGroup>) = repo.io {
        val values = groups.flatMap { g -> g.items.map { it.item.id to it.suggested } }
        repo.lib.setTakenMany(values)
        repo.say("Data corretta per ${Fmt.count(values.size.toLong(), "foto", "foto")}")
        repo.changed()
        repo.writeToFiles(values.map { it.first }, date = true, location = false)
    }
    fun applyPlaces(groups: List<PlaceNames.PlaceGroup>) = repo.io {
        val values = groups.flatMap { g -> g.items.map { Library.Located(it.id, g.lat, g.lon, g.place) } }
        repo.lib.setLocationMany(values)
        repo.say("Posizione aggiunta a ${Fmt.count(values.size.toLong(), "foto", "foto")}")
        repo.changed()
        repo.writeToFiles(values.map { it.id }, date = false, location = true)
    }
    /** Ignora, con «Annulla» nel messaggio (come in ArchPhotos). */
    fun ignore(ids: List<Long>, set: (Collection<Long>, Boolean) -> Unit) = repo.io {
        set(ids, true)
        repo.undoable(Fmt.count(ids.size.toLong(), "suggerimento ignorato", "suggerimenti ignorati")) { set(ids, false); repo.changed() }
    }
    Scaffold(topBar = {
        Bar("Suggerimenti", onBack = { nav.back() }) {
            if (tab == 0 && !dates.isNullOrEmpty()) TextButton(onClick = { applyDates(dates) }) { Text("Applica tutti") }
            if (tab == 1 && !places.isNullOrEmpty()) TextButton(onClick = { applyPlaces(places) }) { Text("Applica tutti") }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Date (${dates?.sumOf { it.items.size } ?: 0})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Luoghi (${places?.sumOf { it.items.size } ?: 0})") })
            }
            if (tab == 0) {
                if (dates != null && dates.isEmpty()) Empty(Suggestions.EMPTY_TITLE, Suggestions.EMPTY_DATES)
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!dates.isNullOrEmpty()) item { SuggestionIntro(Suggestions.datesHeader(dates), Suggestions.DATES_TEXT) }
                    items(dates.orEmpty(), key = { "${it.sourceId}:${it.dir}" }) { g ->
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                val (title, sub) = Suggestions.dateCard(g)
                                Text(title, fontWeight = FontWeight.SemiBold)
                                Text(sub, style = MaterialTheme.typography.bodySmall)
                                g.items.take(3).forEach { s ->
                                    Text("${s.item.name}: ${Fmt.shortDate(s.item.taken)} → ${Fmt.shortDate(s.suggested)}${if (s.fromName) " (dal nome)" else ""}",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (g.items.size > 3) Text("…e altre ${g.items.size - 3}", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Button(onClick = { applyDates(listOf(g)) }) { Text("Applica") }
                                    OutlinedButton(onClick = { ignore(g.items.map { it.item.id }, repo.lib::ignoreSuggestions) }) { Text("Ignora") }
                                    TextButton(onClick = { nav.go(Screen.Ids(g.items.map { it.item.id }, g.dir.substringAfterLast("/"))) }) { Text("Mostra") }
                                }
                            }
                        }
                    }
                }
            } else {
                if (places != null && places.isEmpty()) Empty(Suggestions.EMPTY_TITLE, Suggestions.EMPTY_PLACES)
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!places.isNullOrEmpty()) item { SuggestionIntro(Suggestions.placesHeader(places), Suggestions.PLACES_TEXT) }
                    items(places.orEmpty(), key = { "${it.sourceId}:${it.dir}:${it.place}:${it.evidence}" }) { g ->
                        Card {
                            Column(Modifier.padding(12.dp)) {
                                val (title, sub) = Suggestions.placeCard(g)
                                Text(title, fontWeight = FontWeight.SemiBold)
                                Text(sub, style = MaterialTheme.typography.bodySmall)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    items(g.items.take(12), key = { it.id }) { ThumbImage(it, Modifier.size(56.dp)) }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Button(onClick = { applyPlaces(listOf(g)) }) { Text("Applica") }
                                    OutlinedButton(onClick = { ignore(g.items.map { it.id }, repo.lib::ignorePlaceSuggestions) }) { Text("Ignora") }
                                    TextButton(onClick = { nav.go(Screen.Ids(g.items.map { it.id }, g.place)) }) { Text("Mostra") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionIntro(header: String, text: String) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(header, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
