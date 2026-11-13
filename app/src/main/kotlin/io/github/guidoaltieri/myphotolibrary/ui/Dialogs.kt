package io.github.guidoaltieri.myphotolibrary.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Geo
import io.github.guidoaltieri.myphotolibrary.core.InfoEdit
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.PlaceNames
import io.github.guidoaltieri.myphotolibrary.data.Exporter
import io.github.guidoaltieri.myphotolibrary.data.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Finestre di dialogo delle azioni su uno o più elementi (griglie e visore). */
class Actions {
    var dialog by mutableStateOf<String?>(null)
    var ids by mutableStateOf<List<Long>>(emptyList())
    fun open(kind: String, target: List<Long>) {
        ids = target; dialog = kind
    }
    fun close() {
        dialog = null
    }
}

@Composable
fun rememberActions() = remember { Actions() }

@Composable
fun ActionDialogs(a: Actions, onDone: () -> Unit = {}) {
    val close = { a.close(); onDone() }
    when (a.dialog) {
        "date" -> DateDialog(a.ids, close)
        "location" -> LocationDialog(a.ids, close)
        "keywords" -> KeywordsDialog(a.ids, close)
        "album" -> AddToAlbumDialog(a.ids, close)
        "export" -> ExportDialog(a.ids, close)
        "delete" -> {
            val repo = repo()
            ConfirmDialog("Elimina ${if (a.ids.size == 1) "elemento" else "${a.ids.size} elementi"}",
                "Gli elementi vanno in «Eliminati di recente» per ${io.github.guidoaltieri.myphotolibrary.core.Library.DELETED_KEEP_DAYS} giorni, poi vengono eliminati definitivamente.",
                "Elimina", close) { repo.io { repo.lib.delete(a.ids) } }
        }
        "purge" -> {
            val repo = repo()
            ConfirmDialog("Elimina definitivamente", "I file verranno eliminati dal dispositivo. L'operazione non si può annullare.", "Elimina",
                close) { repo.io { repo.purge(a.ids) } }
        }
    }
}

// --- data e ora ----------------------------------------------------------------------------------

/**
 * Modifica di data e ora (come in ArchPhotos): una foto → nuova data; più foto → stessa data per
 * tutte, «la prima diventa…» (le altre spostate della stessa quantità) oppure spostamento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateDialog(ids: List<Long>, onDismiss: () -> Unit) {
    val repo = repo()
    val items = load(ids, initial = emptyList<Item>()) { repo.items(ids).sortedBy { it.taken } }
    if (items.isEmpty()) return
    val first = items.first()
    var mode by remember { mutableStateOf(if (items.size == 1) "same" else "first") }
    var date by remember { mutableStateOf(Fmt.dt(first.taken)) }
    var shiftDays by remember { mutableStateOf("0") }
    var shiftHours by remember { mutableStateOf("0") }
    var shiftMinutes by remember { mutableStateOf("0") }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica data e ora") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (items.size > 1) {
                    for ((k, label) in listOf("first" to "La prima diventa… (le altre spostate della stessa quantità)",
                            "same" to "Stessa data e ora per tutte", "shift" to "Sposta di giorni, ore e minuti")) {
                        Row(Modifier.fillMaxWidth().clickable { mode = k }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(mode == k, { mode = k }); Text(label)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                }
                if (mode == "shift") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(shiftDays, { shiftDays = it }, label = { Text("Giorni") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(shiftHours, { shiftHours = it }, label = { Text("Ore") }, singleLine = true, modifier = Modifier.weight(1f))
                        OutlinedTextField(shiftMinutes, { shiftMinutes = it }, label = { Text("Minuti") }, singleLine = true, modifier = Modifier.weight(1f))
                    }
                    Text("Usa valori negativi per spostare indietro.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(InfoEdit.dateDescription(items), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { pickDate = true }) { Text(Fmt.dayLong(Fmt.ts(date))) }
                    TextButton(onClick = { pickTime = true }) { Text("%02d:%02d".format(date.hour, date.minute)) }
                }
                if (repo.settings.writeFiles.value) Text("La data verrà scritta anche nei file (JPEG, PNG, WebP).", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = ids
                repo.io {
                    when (mode) {
                        "same" -> repo.lib.setTaken(target, ts = Fmt.ts(date))
                        "first" -> repo.lib.setTaken(target, shift = Fmt.ts(date) - first.taken)
                        else -> {
                            val s = (shiftDays.toDoubleOrNull() ?: 0.0) * 86400 + (shiftHours.toDoubleOrNull() ?: 0.0) * 3600 + (shiftMinutes.toDoubleOrNull() ?: 0.0) * 60
                            repo.lib.setTaken(target, shift = s)
                        }
                    }
                    repo.changed()
                    repo.writeToFiles(target, date = true, location = false)
                }
                onDismiss()
            }) { Text("Applica") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
    if (pickDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = date.toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false }, confirmButton = {
            TextButton(onClick = {
                st.selectedDateMillis?.let {
                    val d = LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneOffset.UTC).toLocalDate()
                    date = date.withYear(d.year).withMonth(d.monthValue).withDayOfMonth(d.dayOfMonth)
                }
                pickDate = false
            }) { Text("OK") }
        }) { DatePicker(st) }
    }
    if (pickTime) {
        val st = rememberTimePickerState(date.hour, date.minute, true)
        AlertDialog(onDismissRequest = { pickTime = false }, text = { TimePicker(st) }, confirmButton = {
            TextButton(onClick = { date = date.withHour(st.hour).withMinute(st.minute); pickTime = false }) { Text("OK") }
        })
    }
}

// --- luogo ------------------------------------------------------------------------------------------

/** Luogo cercato senza rete nell'elenco delle località o su OpenStreetMap, coordinate, oppure rimosso. */
@Composable
fun LocationDialog(ids: List<Long>, onDismiss: () -> Unit) {
    val repo = repo()
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var offline by remember { mutableStateOf<List<io.github.guidoaltieri.myphotolibrary.core.Place>>(emptyList()) }
    var online by remember { mutableStateOf<List<Geo.Found>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val current = load(ids, initial = "") { InfoEdit.placeDescription(repo.items(ids)) }
    LaunchedEffect(query) {
        delay(200)
        offline = withContext(Dispatchers.Default) { PlaceNames.search(query, 8) }
        online = emptyList()
    }
    fun apply(lat: Double?, lon: Double?, name: String?) {
        repo.io {
            repo.lib.setLocation(ids, lat, lon, name)
            repo.changed()
            repo.writeToFiles(ids, date = false, location = true, clearGps = lat == null)
        }
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica luogo") },
        text = {
            Column {
                if (current.isNotEmpty()) Text(current, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                OutlinedTextField(query, { query = it }, label = { Text("Luogo o coordinate (45.46, 9.19)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    val coords = Geo.parseCoords(query)
                    if (coords != null) item {
                        ListItem(headlineContent = { Text("Coordinate ${Fmt.coords(coords.first, coords.second)}") },
                            leadingContent = { Icon(Icons.Filled.Place, null) },
                            modifier = Modifier.clickable { apply(coords.first, coords.second, null) })
                    }
                    items(offline) { p ->
                        ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text(p.level) },
                            leadingContent = { Icon(Icons.Filled.Place, null) },
                            modifier = Modifier.clickable { apply(p.lat, p.lon, p.name) })
                    }
                    items(online) { p ->
                        ListItem(headlineContent = { Text(p.name) }, supportingContent = { Text(p.detail, maxLines = 2) },
                            leadingContent = { Icon(Icons.Filled.Public, null) },
                            modifier = Modifier.clickable { apply(p.lat, p.lon, p.name) })
                    }
                    item {
                        if (query.length >= 2) TextButton(enabled = !searching, onClick = {
                            searching = true
                            scope.launch {
                                online = try { withContext(Dispatchers.IO) { Geo.searchOnline(query) } } catch (e: Exception) {
                                    repo.say("Ricerca su OpenStreetMap non riuscita"); emptyList()
                                }
                                searching = false
                            }
                        }) { Text(if (searching) "Ricerca…" else "Cerca su OpenStreetMap") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { apply(null, null, null) }) { Text("Rimuovi posizione") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

// --- parole chiave ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeywordsDialog(ids: List<Long>, onDismiss: () -> Unit) {
    val repo = repo()
    val all = load(initial = emptyList<String>()) { repo.lib.allKeywords() }
    val current = remember { mutableStateListOf<String>() }
    LaunchedEffect(ids) {
        val k = withContext(Dispatchers.IO) { ids.mapNotNull { repo.lib.detail(it)?.keywords } }
        current.clear()
        // per più elementi si parte dalle parole comuni a tutti
        if (k.isNotEmpty()) current.addAll(k.reduce { acc, l -> acc.intersect(l.toSet()).toList() })
    }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Parole chiave") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    current.forEach { k -> InputChip(true, { current.remove(k) }, { Text(k) }, trailingIcon = { Icon(Icons.Filled.Close, null, Modifier.size(16.dp)) }) }
                }
                OutlinedTextField(text, { text = it }, label = { Text("Nuova parola chiave") }, singleLine = true,
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = {
                            text.split(",").map { it.trim() }.filter { it.isNotEmpty() && it !in current }.forEach { current.add(it) }
                            text = ""
                        }) { Icon(Icons.Filled.Add, "Aggiungi") }
                    }, modifier = Modifier.fillMaxWidth())
                val sugg = all.filter { it !in current && (text.isEmpty() || it.contains(text, true)) }.take(20)
                if (sugg.isNotEmpty()) {
                    Text("Suggerite", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sugg.forEach { k -> FilterChip(false, { current.add(k) }, { Text(k) }) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val extra = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val final = (current + extra).distinct()
                if (ids.size == 1) repo.io { repo.lib.setKeywords(ids, final) }
                else repo.io { ids.forEach { id -> repo.lib.setKeywords(listOf(id), ((repo.lib.detail(id)?.keywords ?: emptyList()) + final).distinct()) } }
                onDismiss()
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

// --- album ------------------------------------------------------------------------------------------

@Composable
fun AddToAlbumDialog(ids: List<Long>, onDismiss: () -> Unit) {
    val repo = repo()
    val albums = load(initial = emptyList<io.github.guidoaltieri.myphotolibrary.core.Album>()) {
        repo.lib.albums(allLevels = true).filter { it.kind == "album" }
    }
    var newAlbum by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi ad album") },
        text = {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
                item {
                    ListItem(headlineContent = { Text("Nuovo album…") }, leadingContent = { Icon(Icons.Filled.Add, null) },
                        modifier = Modifier.clickable { newAlbum = true })
                }
                items(albums) { a ->
                    ListItem(headlineContent = { Text(a.name) }, supportingContent = { Text(Fmt.count(a.count.toLong(), "elemento", "elementi")) },
                        leadingContent = { Icon(Icons.Filled.PhotoAlbum, null) },
                        modifier = Modifier.clickable {
                            repo.io { val n = repo.lib.addToAlbum(a.id, ids); repo.say("Aggiunti $n elementi a «${a.name}»") }
                            onDismiss()
                        })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
    if (newAlbum) TextDialog("Nuovo album", label = "Nome", confirm = "Crea", onDismiss = { newAlbum = false }) { name ->
        repo.io { val id = repo.lib.createAlbum(name); repo.lib.addToAlbum(id, ids); repo.say("Album «$name» creato") }
        onDismiss()
    }
}

// --- esportazione -------------------------------------------------------------------------------------

/** Esporta con o senza modifiche (JPEG/PNG/WebP, qualità, dimensioni, senza posizione). */
@Composable
fun ExportDialog(ids: List<Long>, onDismiss: () -> Unit) {
    val repo = repo()
    var edited by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf("jpeg") }
    var quality by remember { mutableStateOf(92f) }
    var maxSize by remember { mutableStateOf(0) }
    var strip by remember { mutableStateOf(false) }
    fun run(tree: Uri?) {
        val o = Exporter.Options(edited, format, quality.toInt(), maxSize, strip)
        repo.io {
            val items = repo.items(ids)
            var n = 0
            items.forEachIndexed { i, it ->
                repo.task.value = io.github.guidoaltieri.myphotolibrary.data.Task("Esportazione", i, items.size)
                val f = repo.exporter.render(it, o)
                if (tree != null) repo.exporter.saveToTree(f, tree) else repo.exporter.saveToDevice(f)
                f.delete()
                n++
            }
            repo.task.value = null
            repo.say("Esportati $n elementi${if (tree == null) " in Immagini/MyPhotoLibrary" else ""}")
        }
        onDismiss()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) run(uri) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Esporta ${if (ids.size == 1) "" else "${ids.size} elementi"}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(edited, { edited = true }); Text("Con le modifiche") }
                Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(!edited, { edited = false }); Text("Originale non modificato") }
                if (edited) {
                    Text("Formato", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((k, l) in listOf("jpeg" to "JPEG", "png" to "PNG", "webp" to "WebP", "keep" to "Come l'originale"))
                            FilterChip(format == k, { format = k }, { Text(l) })
                    }
                    if (format == "jpeg" || format == "webp") {
                        Text("Qualità ${quality.toInt()}", style = MaterialTheme.typography.labelLarge)
                        Slider(quality, { quality = it }, valueRange = 50f..100f)
                    }
                    Text("Dimensioni", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((k, l) in listOf(0 to "Originali", 4096 to "4096", 2048 to "2048", 1024 to "1024"))
                            FilterChip(maxSize == k, { maxSize = k }, { Text(l) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(strip, { strip = it }); Text("Senza posizione (GPS)") }
            }
        },
        confirmButton = { TextButton(onClick = { run(null) }) { Text("In Immagini") } },
        dismissButton = {
            Row {
                TextButton(onClick = { picker.launch(null) }) { Text("Scegli cartella…") }
                TextButton(onClick = onDismiss) { Text("Annulla") }
            }
        },
    )
}

/** Avvia l'intento di condivisione per gli elementi. */
fun share(repo: Repo, ids: List<Long>) {
    repo.io {
        val items = repo.items(ids)
        if (items.isNotEmpty()) repo.launch(repo.exporter.shareIntent(items))
    }
}
