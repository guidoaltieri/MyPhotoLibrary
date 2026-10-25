package io.github.guidoaltieri.myphotolibrary.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.Layouts
import io.github.guidoaltieri.myphotolibrary.core.MediaInfo
import io.github.guidoaltieri.myphotolibrary.data.Importer
import io.github.guidoaltieri.myphotolibrary.data.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun Importer.Candidate.asItem() = Item(-(uri.hashCode().toLong() and 0xFFFFFFF) - 1, 0, uri.toString(), name, relpath,
    if (MediaInfo.isVideo(name)) "video" else "photo", mtime, mtime, null, 0, 0, null, "", false, false, null, null, null, null, size, null, null, null, null)

/**
 * Importa da schede SD, chiavette USB, fotocamere (con adattatore) e cartelle: scegli la cartella,
 * seleziona le foto (quelle già in fototeca non sono preselezionate), scegli destinazione e
 * organizzazione in cartelle per data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen() {
    val repo = repo()
    val nav = nav()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Uri?>(null) }
    var files by remember { mutableStateOf<List<Importer.Candidate>?>(null) }
    val chosen = remember { mutableStateMapOf<Int, Boolean>() }
    var dest by remember { mutableStateOf<Uri?>(null) }   // null = Immagini/MyPhotoLibrary
    var layout by remember { mutableStateOf(repo.settings.importLayout.value) }
    var useExisting by remember { mutableStateOf(repo.settings.importUseExisting.value) }
    var deleteAfter by remember { mutableStateOf(repo.settings.importDeleteAfter.value) }
    var running by remember { mutableStateOf<Importer.Progress?>(null) }
    var result by remember { mutableStateOf<Importer.Result?>(null) }
    var step by remember { mutableStateOf(0) }
    val pickSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            source = uri
            files = null
            scope.launch {
                val known = withContext(Dispatchers.IO) {
                    repo.lib.db.query("SELECT name, filesize FROM media").map { "${it["name"]}|${it["filesize"]}" }.toHashSet()
                }
                val list = try { repo.importer.list(uri) { n, s -> "$n|$s" in known } } catch (e: Exception) { repo.say("Cartella non leggibile"); emptyList() }
                files = list
                chosen.clear()
                list.forEachIndexed { i, c -> if (!c.inLibrary) chosen[i] = true }
                step = 1
            }
        }
    }
    val pickDest = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (e: Exception) {}
            dest = uri
        }
    }
    fun start() {
        val list = files.orEmpty().filterIndexed { i, _ -> chosen.containsKey(i) }
        repo.settings.importLayout.value = layout
        repo.settings.importUseExisting.value = useExisting
        repo.settings.importDeleteAfter.value = deleteAfter
        step = 3
        scope.launch {
            try {
                val target = withContext(Dispatchers.IO) { dest?.let { Storage.TreeTarget(ctx, it) } ?: repo.importer.DeviceTarget() }
                val r = repo.importer.transfer(list, target, layout, useExisting, deleteAfter) { p -> running = p }
                result = r
                if (r.copied.isNotEmpty()) {
                    val importId = withContext(Dispatchers.IO) { repo.lib.newImport(treeName(ctx, source!!)) }
                    val paths = r.copied.map { it.toString() }
                    val srcIds = withContext(Dispatchers.IO) {
                        repo.lib.sources().filter { s -> if (dest == null) s.kind == "device" else s.path == dest.toString() }.map { it.id }
                    }
                    repo.scan(srcIds) { repo.lib.setImport(paths, importId); repo.changed() }
                }
            } catch (e: Exception) {
                repo.say("Importazione non riuscita: ${e.message}")
                step = 2
            }
        }
    }
    Scaffold(topBar = {
        Bar("Importa", when (step) { 1 -> "${chosen.size} di ${files?.size ?: 0} selezionati"; else -> "" }, onBack = { nav.back() }) {
            if (step == 1) TextButton(enabled = chosen.isNotEmpty(), onClick = { step = 2 }) { Text("Avanti") }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (step) {
                0 -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Scegli la cartella da importare: una scheda SD, una chiavetta o un disco USB, la memoria di una fotocamera collegata " +
                            "con l'adattatore, oppure una cartella del telefono o di un'app cloud.", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { pickSource.launch(null) }) { Text("Scegli la cartella…") }
                    if (source != null && files == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                1 -> {
                    val list = files.orEmpty()
                    if (list.isEmpty()) Empty("Nessuna foto o video", "La cartella non contiene file multimediali.")
                    else LazyVerticalGrid(GridCells.Adaptive(100.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { list.indices.forEach { chosen[it] = true } }) { Text("Tutti") }
                                OutlinedButton(onClick = { chosen.clear() }) { Text("Nessuno") }
                                OutlinedButton(onClick = { chosen.clear(); list.forEachIndexed { i, c -> if (!c.inLibrary) chosen[i] = true } }) { Text("Solo nuovi") }
                            }
                        }
                        itemsIndexed(list) { i, c ->
                            val on = chosen.containsKey(i)
                            Box(Modifier.aspectRatio(1f).padding(1.dp).clickable { if (on) chosen.remove(i) else chosen[i] = true }) {
                                ThumbImage(c.asItem(), Modifier.fillMaxSize(), size = 256)
                                if (c.inLibrary) Text("Già in fototeca", color = Color.White, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp))
                                Icon(if (on) Icons.Filled.CheckCircle else Icons.Outlined.Circle, null, tint = if (on) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                            }
                        }
                    }
                }
                2 -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val d0 = dest
                    val destName = load(d0, initial = "…") { d0?.let { treeName(ctx, it) } ?: "" }
                    Text("Destinazione", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { dest = null }) {
                        RadioButton(dest == null, { dest = null }); Text("Questo telefono (Immagini/MyPhotoLibrary)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { pickDest.launch(null) }) {
                        RadioButton(dest != null, { pickDest.launch(null) })
                        Text(if (dest != null) "Cartella: $destName" else "Un'altra cartella (anche di un'app cloud)…")
                    }
                    FolderLayoutPicker(layout, { layout = it }, useExisting, { useExisting = it },
                        if (dest == null) "Immagini/MyPhotoLibrary" else destName)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(deleteAfter, { deleteAfter = it })
                        Text("Elimina gli originali dopo l'importazione")
                    }
                    Text("I file già presenti (stesso nome e dimensione) vengono saltati; con lo stesso nome ma contenuto diverso vengono rinominati «nome (1).jpg».",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { start() }, enabled = Layouts.validate(layout) == null, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Importa ${chosen.size} elementi")
                    }
                }
                else -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val r = result
                    if (r == null) {
                        val p = running
                        Text(if (p == null) "Preparazione…" else "Importazione ${p.done} di ${p.total}: ${p.name}")
                        LinearProgressIndicator(progress = { p?.let { it.done.toFloat() / it.total.coerceAtLeast(1) } ?: 0f }, modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Importazione completata", style = MaterialTheme.typography.titleLarge)
                        Text("Copiati: ${r.copied.size} · già presenti: ${r.skipped} · errori: ${r.errors}")
                        r.messages.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        val d = dest
                        if (d != null) {
                            val inLib = load(d, initial = true) { repo.lib.sources().any { it.path == d.toString() } }
                            if (!inLib) OutlinedButton(onClick = {
                                repo.io { val id = repo.lib.addSource("folder", treeName(ctx, d), d.toString(), d.authority ?: ""); repo.scan(listOf(id)) }
                            }) { Text("Aggiungi la cartella alla fototeca") }
                        }
                        Button(onClick = { nav.back() }) { Text("Fine") }
                    }
                }
            }
        }
    }
}
