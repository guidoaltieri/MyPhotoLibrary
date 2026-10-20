package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.Library
import io.github.guidoaltieri.myphotolibrary.core.QueryError
import io.github.guidoaltieri.myphotolibrary.core.Search
import kotlinx.coroutines.delay

/**
 * Ricerca come in Foto: «mare luglio 2023», «roma», «anna 2022», «selfie», «video»… più la
 * sintassi avanzata (camera:, year:, is:, has:, place:, person:…).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchTab(sel: Selection) {
    val repo = repo()
    val nav = nav()
    var text by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(text) {
        delay(250)
        query = text
    }
    data class Res(val items: List<Item>, val error: String?)
    val res = load(query, initial = Res(emptyList(), null)) {
        if (query.isBlank()) Res(emptyList(), null) else try {
            Res(repo.lib.media("library", Search.compile(query), order = "taken DESC"), null)
        } catch (e: QueryError) {
            Res(emptyList(), e.message)
        }
    }
    data class Sugg(val persons: List<String>, val places: List<String>, val years: List<String>, val keywords: List<String>, val counts: Map<String, Int>)
    val sugg = load(initial = Sugg(emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())) {
        Sugg(repo.lib.personNames().take(12), repo.lib.placesSummary().take(12).map { it.place.substringBefore(",") }.distinct(),
            repo.lib.groups("year").map { it.key }.reversed(), repo.lib.allKeywords().take(20), repo.lib.viewCounts())
    }
    val cols by repo.settings.columns.flow.collectAsState()
    Column(Modifier.fillMaxSize()) {
        if (!sel.active) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            placeholder = { Text("Luoghi, persone, date, tipi…") }, singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = "" }) { Icon(Icons.Filled.Clear, "Cancella") } })
        if (query.isBlank()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                @Composable
                fun chips(title: String, values: List<String>, toQuery: (String) -> String) {
                    if (values.isEmpty()) return
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        values.forEach { v -> AssistChip({ text = toQuery(v) }, { Text(v) }) }
                    }
                }
                chips("Persone", sugg.persons) { "person:\"$it\"" }
                chips("Luoghi", sugg.places) { "place:\"$it\"" }
                chips("Tipi di media", Library.MEDIA_TYPES.filter { (sugg.counts[it] ?: 0) > 0 }.map { Library.VIEWS.getValue(it).label }) { label ->
                    val key = Library.VIEWS.entries.first { it.value.label == label }.key
                    mapOf("videos" to "is:video", "selfies" to "is:selfie", "live" to "is:live", "portrait" to "is:portrait",
                        "panorama" to "is:panorama", "slomo" to "is:slomo", "burst" to "is:burst", "animated" to "is:animated",
                        "screenshots" to "is:screenshot", "screenrec" to "is:screenrec", "raw" to "is:raw").getValue(key)
                }
                chips("Anni", sugg.years) { it }
                chips("Parole chiave", sugg.keywords) { "keyword:\"$it\"" }
                Text(SYNTAX_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(res.error ?: Fmt.items(res.items.count { !it.isVideo }.toLong(), res.items.count { it.isVideo }.toLong()),
                    color = if (res.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                if (res.error == null) TextButton(onClick = { nav.go(Screen.SmartEditor(null, query)) }) {
                    Icon(Icons.Filled.AutoAwesome, null); Text(" Salva come album smart")
                }
            }
            Box(Modifier.weight(1f)) {
                MediaGrid(res.items, sel, onOpen = { i -> nav.go(Screen.Viewer(res.items.map { it.id }, i)) }, state = rememberLazyGridState(),
                    columns = cols, onColumns = { repo.settings.columns.value = it })
            }
        }
    }
}
