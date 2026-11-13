package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Fmt

/** Schermata principale con le quattro sezioni: Libreria, Per te, Raccolte, Cerca. */
@Composable
fun HomeScreen(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val repo = repo()
    val nav = nav()
    var tab by rememberSaveable { mutableStateOf(0) }
    val sel = rememberSelection()
    val actions = rememberActions()
    var menu by remember { mutableStateOf(false) }
    val task by repo.task.collectAsState()
    val counts = load(initial = 0 to 0) { repo.lib.count("library") }
    val expired = load(initial = emptyList<Long>()) { repo.expired() }
    LaunchedEffect(tab) { sel.clear() }
    Scaffold(
        topBar = {
            if (sel.active) SelectionTopBar(sel, emptyList())
            else Bar(listOf("Libreria", "Per te", "Raccolte", "Cerca")[tab],
                if (tab == 0) Fmt.items(counts.first.toLong(), counts.second.toLong()) else "") {
                IconButton(onClick = { nav.go(Screen.Import) }) { Icon(Icons.Filled.Download, "Importa") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Aggiorna la fototeca") }, { menu = false; repo.scan() }, leadingIcon = { Icon(Icons.Filled.Refresh, null) })
                        DropdownMenuItem({ Text("Importa…") }, { menu = false; nav.go(Screen.Import) }, leadingIcon = { Icon(Icons.Filled.Download, null) })
                        DropdownMenuItem({ Text("Nuovo album…") }, { menu = false; nav.go(Screen.Albums(null, "Album")) })
                        DropdownMenuItem({ Text("Sorgenti e cartelle…") }, { menu = false; nav.go(Screen.Sources) })
                        DropdownMenuItem({ Text("Preferenze") }, { menu = false; nav.go(Screen.Settings) }, leadingIcon = { Icon(Icons.Filled.Settings, null) })
                    }
                }
            }
        },
        bottomBar = {
            if (sel.active) SelectionActions("library", sel, actions)
            else NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Filled.PhotoLibrary, null) }, label = { Text("Libreria") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Filled.AutoAwesomeMosaic, null) }, label = { Text("Per te") })
                NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Filled.Collections, null) }, label = { Text("Raccolte") })
                NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.Filled.Search, null) }, label = { Text("Cerca") })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            task?.let { t ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(if (t.total > 0) "${t.label} · ${t.done}/${t.total}" else t.label, style = MaterialTheme.typography.labelSmall)
                    if (t.total > 0) LinearProgressIndicator(progress = { t.done.toFloat() / t.total }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (!hasPermission) Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Accesso alle foto", style = MaterialTheme.typography.titleMedium)
                    Text("Per mostrare le foto e i video del telefono serve il permesso di accesso. Puoi anche aggiungere solo alcune cartelle da Sorgenti.",
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onRequestPermission, modifier = Modifier.padding(top = 8.dp)) { Text("Consenti l'accesso") }
                }
            }
            AnimatedVisibility(expired.isNotEmpty() && tab == 0 && !sel.active) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${Fmt.count(expired.size.toLong(), "elemento eliminato", "elementi eliminati")} da più di 30 giorni",
                            style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { repo.io { repo.purge(expired) } }) { Text("Elimina definitivamente") }
                    }
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> LibraryTab(sel, actions)
                    1 -> MemoriesTab()
                    2 -> CollectionsTab()
                    else -> SearchTab(sel)
                }
            }
        }
    }
    ActionDialogs(actions) { sel.clear() }
}

/** Contenuto della pila di navigazione. */
@Composable
fun AppContent(hasPermission: Boolean, onRequestPermission: () -> Unit) {
    val nav = nav()
    when (val s = nav.top) {
        Screen.Home -> HomeScreen(hasPermission, onRequestPermission)
        is Screen.Grid -> GridScreen(s.view, s.title, s.query)
        is Screen.Ids -> IdsScreen(s.ids, s.title, s.subtitle)
        is Screen.Viewer -> ViewerScreen(s.ids, s.index, s.view)
        is Screen.Editor -> EditorScreen(s.id)
        is Screen.Memory -> MemoryScreen(s.key)
        is Screen.Slideshow -> SlideshowScreen(s.ids, s.title)
        is Screen.Albums -> AlbumsScreen(s.parent, s.title)
        is Screen.Album -> AlbumScreen(s.id)
        is Screen.SmartEditor -> SmartEditorScreen(s.albumId, s.initialQuery)
        Screen.People -> PeopleScreen()
        is Screen.Person -> PersonScreen(s.id)
        Screen.Places -> PlacesScreen()
        Screen.Duplicates -> DuplicatesScreen()
        Screen.Imports -> ImportsScreen()
        Screen.Suggestions -> SuggestionsScreen()
        Screen.Sources -> SourcesScreen()
        Screen.Settings -> SettingsScreen()
        Screen.Import -> ImportScreen()
        Screen.ICloud -> ICloudScreen()
    }
}
