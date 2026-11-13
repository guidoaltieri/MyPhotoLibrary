package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Person

/** Persone: volti riconosciuti sul telefono (OpenCV, modelli YuNet e SFace). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeopleScreen() {
    val repo = repo()
    val nav = nav()
    val persons = load(initial = null as List<Person>?) { repo.lib.persons() }
    val task by repo.task.collectAsState()
    val selected = remember { mutableStateMapOf<Long, Boolean>() }
    var merge by remember { mutableStateOf(false) }
    val ready = repo.faces.modelsReady()
    Scaffold(topBar = {
        Bar("Persone", if (selected.isNotEmpty()) "${selected.size} selezionate" else "", onBack = { nav.back() }) {
            if (selected.size >= 2) IconButton(onClick = { merge = true }) { Icon(Icons.Filled.MergeType, "Unisci") }
        }
    }) { pad ->
        LazyVerticalGrid(GridCells.Adaptive(96.dp), Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Riconoscimento dei volti", style = MaterialTheme.typography.titleMedium)
                        Text(if (ready) "Le foto nuove vengono analizzate dopo ogni aggiornamento della fototeca. Tutto avviene sul telefono."
                        else "Per riconoscere le persone servono i modelli YuNet e SFace di OpenCV (circa 40 MB, scaricati una volta sola da GitHub). " +
                            "Le foto non lasciano mai il telefono.", style = MaterialTheme.typography.bodySmall)
                        val t = task
                        if (t != null && t.label.contains("volti", true) || t?.label?.startsWith("Scaricamento") == true) {
                            Text(t!!.label)
                            if (t.total > 0) LinearProgressIndicator(progress = { t.done.toFloat() / t.total }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else Button(onClick = { repo.startFaces() }) { Text(if (ready) "Analizza le foto nuove" else "Scarica i modelli e analizza") }
                        if (selected.isEmpty()) Text("Tieni premuto su più persone per unirle.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (persons != null && persons.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Empty("Nessuna persona", "Le persone compaiono dopo l'analisi dei volti (almeno 3 foto per ogni volto senza nome).")
            }
            items(persons.orEmpty(), key = { it.id }) { p ->
                val sel = selected.containsKey(p.id)
                Box(Modifier.combinedClickable(onClick = {
                    if (selected.isNotEmpty()) { if (sel) selected.remove(p.id) else selected[p.id] = true } else nav.go(Screen.Person(p.id))
                }, onLongClick = { if (sel) selected.remove(p.id) else selected[p.id] = true })
                    .let { if (sel) it.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape) else it }) {
                    PersonTile(p, Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (merge) {
        val list = persons.orEmpty().filter { selected.containsKey(it.id) }
        val into = list.firstOrNull { it.name.isNotEmpty() } ?: list.first()
        ConfirmDialog("Unisci persone", "Le ${list.size} persone selezionate diventano «${into.name.ifEmpty { "Senza nome" }}».", "Unisci",
            { merge = false }) {
            repo.io { repo.lib.mergePersons(into.id, list.map { it.id }.filter { it != into.id }) }
            selected.clear()
        }
    }
}

/** Foto di una persona: nome, preferita, nascondi, «non è questa persona» dalla selezione. */
@Composable
fun PersonScreen(id: Long) {
    val repo = repo()
    val nav = nav()
    val p = load(id, initial = null as Person?) { repo.lib.person(id) } ?: return
    var rename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val names = load(initial = emptyList<String>()) { repo.lib.personNames() }
    GridScreen("person:$id", p.name.ifEmpty { "Senza nome" }, extraActions = {
        IconButton(onClick = { repo.io { repo.lib.setPersonFavorite(id, !p.favorite) } }) {
            Icon(if (p.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Preferita")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text(if (p.name.isEmpty()) "Dai un nome…" else "Rinomina…") }, { menu = false; rename = true })
                DropdownMenuItem({ Text("Nascondi persona") }, { menu = false; repo.io { repo.lib.hidePerson(id) }; nav.back() })
            }
        }
    })
    if (rename) NameDialog(p.name, names, { rename = false }) { n -> repo.io { repo.lib.renamePerson(id, n) } }
}

/** Nome di una persona con i suggerimenti dei nomi già usati (stesso nome = stessa persona). */
@Composable
fun NameDialog(initial: String, names: List<String>, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nome") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Nome della persona") })
                names.filter { text.isNotEmpty() && it.contains(text, true) && it != text }.take(5).forEach { n ->
                    androidx.compose.material3.TextButton(onClick = { text = n }) { Text(n) }
                }
                Text("Se il nome è già usato le due persone vengono unite.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = text.isNotBlank(), onClick = { onOk(text.trim()); onDismiss() }) { Text("OK") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
