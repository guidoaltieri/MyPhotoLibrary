package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.Search

/** Barra in alto durante la selezione. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(sel: Selection, all: List<Long>) {
    TopAppBar(
        title = { Text(if (sel.size == 1) "1 selezionato" else "${sel.size} selezionati") },
        navigationIcon = { IconButton(onClick = { sel.clear() }) { Icon(Icons.Filled.Close, "Annulla selezione") } },
        actions = { IconButton(onClick = { if (sel.size == all.size) sel.clear() else sel.setAll(all) }) { Icon(Icons.Filled.SelectAll, "Seleziona tutto") } },
    )
}

/** Azioni sulla selezione, diverse secondo la vista (album, persona, eliminati, nascosti). */
@Composable
fun SelectionActions(view: String, sel: Selection, actions: Actions) {
    val repo = repo()
    var menu by remember { mutableStateOf(false) }
    val ids = sel.ids
    BottomAppBar {
        when (view) {
            "deleted" -> {
                IconButton(onClick = { repo.io { repo.lib.restore(ids) }; sel.clear() }) { Icon(Icons.Filled.RestoreFromTrash, "Ripristina") }
                Text("Ripristina", Modifier.padding(end = 16.dp))
                IconButton(onClick = { actions.open("purge", ids); sel.clear() }) { Icon(Icons.Filled.DeleteForever, "Elimina definitivamente") }
                Text("Elimina definitivamente")
                return@BottomAppBar
            }
        }
        IconButton(onClick = { share(repo, ids) }) { Icon(Icons.Filled.Share, "Condividi") }
        IconButton(onClick = { repo.io { repo.lib.toggleFavorite(ids) } }) { Icon(Icons.Filled.FavoriteBorder, "Preferito") }
        IconButton(onClick = { actions.open("album", ids) }) { Icon(Icons.Filled.AddPhotoAlternate, "Aggiungi ad album") }
        if (view == "hidden") IconButton(onClick = { repo.io { repo.lib.setHidden(ids, false) }; sel.clear() }) { Icon(Icons.Filled.Visibility, "Mostra") }
        else IconButton(onClick = { repo.io { repo.lib.setHidden(ids, true) }; sel.clear() }) { Icon(Icons.Filled.VisibilityOff, "Nascondi") }
        IconButton(onClick = { actions.open("delete", ids); sel.clear() }) { Icon(Icons.Filled.Delete, "Elimina") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Modifica data e ora…") }, { menu = false; actions.open("date", ids) }, leadingIcon = { Icon(Icons.Filled.DateRange, null) })
                DropdownMenuItem({ Text("Modifica luogo…") }, { menu = false; actions.open("location", ids) }, leadingIcon = { Icon(Icons.Filled.Place, null) })
                DropdownMenuItem({ Text("Parole chiave…") }, { menu = false; actions.open("keywords", ids) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) })
                DropdownMenuItem({ Text("Esporta…") }, { menu = false; actions.open("export", ids) }, leadingIcon = { Icon(Icons.Filled.FileDownload, null) })
                if (ids.size == 1) DropdownMenuItem({ Text("Copia") }, {
                    menu = false; repo.io { repo.lib.item(ids[0])?.let { repo.exporter.copyToClipboard(it); repo.say("Copiata negli appunti") } }
                }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                if (view.startsWith("album:")) {
                    val aid = view.substringAfter(":").toLong()
                    DropdownMenuItem({ Text("Rimuovi dall'album") }, { menu = false; repo.io { repo.lib.removeFromAlbum(aid, ids) }; sel.clear() })
                    if (ids.size == 1) DropdownMenuItem({ Text("Usa come copertina") }, { menu = false; repo.io { repo.lib.setAlbumCover(aid, ids[0]) } })
                }
                if (view.startsWith("person:")) {
                    val pid = view.substringAfter(":").toLong()
                    DropdownMenuItem({ Text("Non è questa persona") }, { menu = false; repo.io { repo.lib.removeFromPerson(pid, ids) }; sel.clear() })
                }
                if (view == "hidden") DropdownMenuItem({ Text("Mostra nella libreria") }, { menu = false; repo.io { repo.lib.setHidden(ids, false) }; sel.clear() })
            }
        }
    }
}

/**
 * Schermata generica con la griglia di una vista (Preferiti, Video, album, persona, ricerca…).
 * Selezione, azioni e apertura nel visore.
 */
@Composable
fun GridScreen(view: String, title: String, query: String = "", subtitle: String? = null, extraActions: @Composable () -> Unit = {},
               header: (@Composable () -> Unit)? = null) {
    val repo = repo()
    val nav = nav()
    val compiled = remember(query) { try { Search.compile(query) } catch (e: Exception) { null } }
    val items = load(view, query, initial = null as List<Item>?) { repo.lib.media(view, compiled) }
    val sel = rememberSelection()
    val actions = rememberActions()
    val cols by repo.settings.columns.flow.collectAsState()
    Scaffold(
        topBar = {
            if (sel.active) SelectionTopBar(sel, items.orEmpty().map { it.id })
            else Bar(title, subtitle ?: items?.let { Fmt.items(it.count { i -> !i.isVideo }.toLong(), it.count { i -> i.isVideo }.toLong()) } ?: "",
                onBack = { nav.back() }) { extraActions() }
        },
        bottomBar = { if (sel.active) SelectionActions(view, sel, actions) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                items == null -> {}
                items.isEmpty() && header == null -> Empty(if (view == "deleted") "Nessun elemento eliminato" else "Nessun elemento",
                    if (view == "deleted") "Gli elementi eliminati restano qui per 30 giorni." else "")
                else -> MediaGrid(items, sel, onOpen = { i -> nav.go(Screen.Viewer(items.map { it.id }, i, view)) },
                    columns = cols, onColumns = { repo.settings.columns.value = it },
                    header = header?.let { h -> { fullRow { h() } } })
            }
        }
    }
    ActionDialogs(actions)
}

@Composable
fun IdsScreen(ids: List<Long>, title: String, subtitle: String) {
    GridScreen("ids:" + ids.joinToString(","), title, subtitle = subtitle.ifEmpty { null })
}

@Composable
fun ColumnHeader(text: String) {
    Column(Modifier.padding(16.dp)) { Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
fun RowSpacer() = Row(Modifier.padding(4.dp)) {}
