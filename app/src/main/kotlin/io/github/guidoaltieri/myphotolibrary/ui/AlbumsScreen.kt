package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Album
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.QueryError
import io.github.guidoaltieri.myphotolibrary.core.Search

/** Album e cartelle di album di un livello, con creazione, rinomina, spostamento ed eliminazione. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumsScreen(parent: Long?, title: String) {
    val repo = repo()
    val nav = nav()
    val albums = load(parent, initial = null as List<Album>?) { repo.lib.albums(parent) }
    var create by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<Album?>(null) }
    var rename by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        Bar(title, onBack = { nav.back() }) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.Add, "Nuovo") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Nuovo album…") }, { menu = false; create = "album" })
                    DropdownMenuItem({ Text("Nuova cartella…") }, { menu = false; create = "folder" })
                    DropdownMenuItem({ Text("Nuovo album smart…") }, { menu = false; nav.go(Screen.SmartEditor(null)) })
                }
            }
        }
    }) { pad ->
        when {
            albums == null -> {}
            albums.isEmpty() -> Empty("Nessun album", "Tocca + per creare un album, una cartella o un album smart.", Modifier.padding(pad))
            else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.padding(pad), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                items(albums, key = { it.id }) { a ->
                    Box(Modifier.combinedClickable(onClick = {
                        if (a.kind == "folder") nav.go(Screen.Albums(a.id, a.name)) else nav.go(Screen.Album(a.id))
                    }, onLongClick = { target = a })) {
                        AlbumTile(a, Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
    target?.let { a ->
        if (!rename && !move && !delete) AlertDialog(onDismissRequest = { target = null }, title = { Text(a.name) }, text = {
            Column {
                ListItem(headlineContent = { Text("Rinomina…") }, modifier = Modifier.clickable { rename = true })
                ListItem(headlineContent = { Text("Sposta in una cartella…") }, modifier = Modifier.clickable { move = true })
                if (a.kind == "smart") ListItem(headlineContent = { Text("Modifica criteri…") }, modifier = Modifier.clickable { target = null; nav.go(Screen.SmartEditor(a.id)) })
                ListItem(headlineContent = { Text("Elimina") }, modifier = Modifier.clickable { delete = true })
            }
        }, confirmButton = { TextButton(onClick = { target = null }) { Text("Chiudi") } })
        if (rename) TextDialog("Rinomina", a.name, onDismiss = { rename = false; target = null }) { t -> repo.io { repo.lib.renameAlbum(a.id, t) } }
        if (delete) ConfirmDialog("Elimina «${a.name}»", if (a.kind == "folder") "Vengono eliminati anche gli album contenuti. Le foto restano nella libreria."
            else "Le foto restano nella libreria.", "Elimina", { delete = false; target = null }) { repo.io { repo.lib.deleteAlbum(a.id) } }
        if (move) {
            val folders = load(initial = emptyList<Album>()) { repo.lib.albums(allLevels = true).filter { it.kind == "folder" && it.id != a.id } }
            AlertDialog(onDismissRequest = { move = false; target = null }, title = { Text("Sposta «${a.name}»") }, text = {
                LazyColumn {
                    item { ListItem(headlineContent = { Text("Livello principale") }, modifier = Modifier.clickable { repo.io { repo.lib.moveAlbum(a.id, null) }; move = false; target = null }) }
                    items(folders) { f ->
                        ListItem(headlineContent = { Text(f.name) }, leadingContent = { Icon(Icons.Filled.Folder, null) },
                            modifier = Modifier.clickable { repo.io { repo.lib.moveAlbum(a.id, f.id) }; move = false; target = null })
                    }
                }
            }, confirmButton = { TextButton(onClick = { move = false; target = null }) { Text("Annulla") } })
        }
    }
    create?.let { kind ->
        TextDialog(if (kind == "folder") "Nuova cartella" else "Nuovo album", label = "Nome", confirm = "Crea", onDismiss = { create = null }) { name ->
            repo.io { repo.lib.createAlbum(name, kind = kind, parent = parent) }
        }
    }
}

/** Contenuto di un album (o album smart). */
@Composable
fun AlbumScreen(id: Long) {
    val repo = repo()
    val nav = nav()
    val a = load(id, initial = null as Album?) { repo.lib.album(id) } ?: return
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    GridScreen("album:$id", a.name, subtitle = if (a.kind == "smart") "Album smart · ${a.query}" else null, extraActions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Presentazione") }, { menu = false; repo.io {
                    val ids = repo.lib.media("album:$id").map { it.id }
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { nav.go(Screen.Slideshow(ids, a.name)) }
                } })
                DropdownMenuItem({ Text("Rinomina…") }, { menu = false; rename = true })
                if (a.kind == "smart") DropdownMenuItem({ Text("Modifica criteri…") }, { menu = false; nav.go(Screen.SmartEditor(id)) })
                DropdownMenuItem({ Text("Elimina album") }, { menu = false; delete = true })
            }
        }
    })
    if (rename) TextDialog("Rinomina", a.name, onDismiss = { rename = false }) { t -> repo.io { repo.lib.renameAlbum(id, t) } }
    if (delete) ConfirmDialog("Elimina «${a.name}»", "Le foto restano nella libreria.", "Elimina", { delete = false }) {
        repo.io { repo.lib.deleteAlbum(id) }; nav.back()
    }
}

/**
 * Album smart e ricerca avanzata: criteri (fotocamera, persona, luogo, date, ISO, tipo…) combinati
 * con «tutti» o «almeno uno», oppure la query testuale con la sintassi completa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartEditorScreen(albumId: Long?, initialQuery: String) {
    val repo = repo()
    val nav = nav()
    val existing = load(albumId, initial = null as Album?) { albumId?.let { repo.lib.album(it) } }
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var query by remember(existing) { mutableStateOf(existing?.query ?: initialQuery) }
    val rules = remember { mutableStateListOf<Search.Rule>() }
    var matchAll by remember { mutableStateOf(true) }
    val effective = if (rules.isNotEmpty()) Search.rulesToQuery(rules, matchAll).let { r -> if (query.isNotBlank()) "$query $r" else r } else query
    val result = load(effective, initial = "") {
        try {
            val c = Search.compile(effective)
            val (p, v) = repo.lib.count("library", c)
            Fmt.items(p.toLong(), v.toLong())
        } catch (e: QueryError) {
            e.message ?: "Query non valida"
        }
    }
    Scaffold(topBar = {
        Bar(if (albumId != null) "Modifica album smart" else "Album smart", onBack = { nav.back() }) {
            TextButton(enabled = name.isNotBlank() && effective.isNotBlank(), onClick = {
                repo.io {
                    if (albumId != null) {
                        repo.lib.renameAlbum(albumId, name); repo.lib.updateSmartAlbum(albumId, effective)
                    } else repo.lib.createAlbum(name, kind = "smart", query = effective)
                }
                nav.back()
            }) { Text("Salva") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { OutlinedTextField(name, { name = it }, label = { Text("Nome dell'album") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Query (es. camera:iphone year:2023 -is:screenshot)") },
                    modifier = Modifier.fillMaxWidth())
            }
            item { Text("Corrispondenze: $result", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Criteri:")
                    FilterChip(matchAll, { matchAll = true }, { Text("Tutti") })
                    FilterChip(!matchAll, { matchAll = false }, { Text("Almeno uno") })
                }
            }
            itemsIndexed(rules) { i, r -> RuleRow(r, onChange = { rules[i] = it }, onRemove = { rules.removeAt(i) }) }
            item { OutlinedButton(onClick = { rules += Search.Rule("year", "eq", "") }) { Icon(Icons.Filled.Add, null); Text("Aggiungi criterio") } }
            item {
                Button(onClick = { nav.go(Screen.Grid("library", "Risultati", effective)) }, enabled = effective.isNotBlank()) { Text("Mostra i risultati") }
            }
            item {
                Text(SYNTAX_HELP, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

val SYNTAX_HELP = """
Sintassi della ricerca:
camera:iphone  lens:"24-70"  — il campo contiene il valore
year:2023  year:2019..2021  — numeri, intervalli, confronti (> < >= <= != =)
date:2024-07  date:2024-07-01..2024-07-15
iso:>=1600  f:<2.8  focal:>85  mp:>20  size:>10 (MB)  duration:>1:00
is:favorite is:video is:selfie is:live is:panorama is:portrait is:screenshot is:raw is:edited
has:location has:caption has:faces has:keywords   orientation:landscape|portrait|square
place:roma  person:anna  album:vacanze  keyword:mare  caption:compleanno  file:IMG_12
added:<30d   -is:screenshot   roma OR milano
Alias italiani ammessi (anno, mese, fotocamera, obiettivo, luogo, persona, parola…).
""".trimIndent()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleRow(r: Search.Rule, onChange: (Search.Rule) -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            var openF by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(openF, { openF = it }, Modifier.weight(1f)) {
                OutlinedTextField(Search.FIELDS.getValue(r.field).label, {}, readOnly = true, label = { Text("Campo") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(openF) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(openF, { openF = false }) {
                    Search.FIELDS.forEach { (k, f) ->
                        DropdownMenuItem({ Text(f.label) }, {
                            openF = false
                            val ops = Search.fieldOps(k)
                            onChange(Search.Rule(k, ops.first().first, Search.fieldChoices(k).firstOrNull() ?: ""))
                        })
                    }
                }
            }
            var openO by remember { mutableStateOf(false) }
            val ops = Search.fieldOps(r.field)
            ExposedDropdownMenuBox(openO, { openO = it }, Modifier.weight(0.8f)) {
                OutlinedTextField(ops.firstOrNull { it.first == r.op }?.second ?: r.op, {}, readOnly = true, label = { Text("Condizione") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(openO) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable))
                ExposedDropdownMenu(openO, { openO = false }) {
                    ops.forEach { (k, l) -> DropdownMenuItem({ Text(l) }, { openO = false; onChange(r.copy(op = k)) }) }
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Rimuovi") }
        }
        val choices = Search.fieldChoices(r.field)
        if (choices.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(choices) { c -> FilterChip(r.value == c, { onChange(r.copy(value = c)) }, { Text(c) }) }
                }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(r.value, { onChange(r.copy(value = it)) }, label = { Text("Valore") }, singleLine = true, modifier = Modifier.weight(1f))
            if (r.op == "between") OutlinedTextField(r.value2, { onChange(r.copy(value2 = it)) }, label = { Text("e") }, singleLine = true, modifier = Modifier.weight(1f))
        }
    }
}
