package io.github.guidoaltieri.myphotolibrary.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Source
import io.github.guidoaltieri.myphotolibrary.core.basename

/** Nome della cartella: quello mostrato dall'app che la fornisce, altrimenti ricavato dall'indirizzo. */
fun treeName(ctx: android.content.Context, uri: Uri): String = io.github.guidoaltieri.myphotolibrary.data.Storage.displayName(ctx, uri) ?: treeIdName(uri)

/** Nome ricavato dall'identificativo della cartella (per alcune app è un codice, non il nome). */
fun treeIdName(uri: Uri): String = try {
    val id = DocumentsContract.getTreeDocumentId(uri)
    basename(id.substringAfter(":")).ifEmpty { id.substringBefore(":").ifEmpty { "Cartella" } }
} catch (e: Exception) {
    "Cartella"
}

/**
 * Sorgenti: «Questo dispositivo» (tutte le foto di Android) e cartelle aggiunte (schede SD,
 * chiavette USB, app cloud come Drive, Proton Drive, Nextcloud… se mostrano le cartelle nel
 * selettore di sistema). Scollega, ricollega, rinomina, aggiorna, rimuovi.
 */
@Composable
fun SourcesScreen() {
    val repo = repo()
    val nav = nav()
    val ctx = LocalContext.current
    val sources = load(initial = emptyList<Source>()) { repo.lib.sources() }
    val counts = load(initial = emptyMap<Long, Int>()) { repo.lib.sourceCounts() }
    var rename by remember { mutableStateOf<Source?>(null) }
    var remove by remember { mutableStateOf<Source?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) {
                try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e2: Exception) {}
            }
            repo.io {
                val id = repo.lib.addSource("folder", treeName(ctx, uri), uri.toString(), uri.authority ?: "")
                repo.scan(listOf(id))
            }
        }
    }
    Scaffold(topBar = {
        Bar("Sorgenti", onBack = { nav.back() }) {
            TextButton(onClick = { repo.scan(full = false) }) { Text("Aggiorna") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(sources, key = { it.id }) { s ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = {
                        Text(listOfNotNull(Fmt.count((counts[s.id] ?: 0).toLong(), "elemento", "elementi"),
                            s.lastScan?.let { "aggiornata ${Fmt.relativeDay(it).lowercase()} alle ${Fmt.time(it)}" },
                            if (!s.enabled) "scollegata" else null).joinToString(" · "))
                    },
                    leadingContent = { Icon(if (s.kind == "device") Icons.Filled.Smartphone else Icons.Filled.Folder, null) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
                            DropdownMenu(menu, { menu = false }) {
                                DropdownMenuItem({ Text("Aggiorna") }, { menu = false; repo.scan(listOf(s.id)) })
                                DropdownMenuItem({ Text("Rileggi tutto") }, { menu = false; repo.scan(listOf(s.id), full = true) })
                                DropdownMenuItem({ Text(if (s.enabled) "Scollega" else "Ricollega") }, {
                                    menu = false; repo.io { repo.lib.setSourceEnabled(s.id, !s.enabled) }; if (!s.enabled) repo.scan(listOf(s.id))
                                })
                                DropdownMenuItem({ Text("Rinomina…") }, { menu = false; rename = s })
                                if (s.kind != "device") DropdownMenuItem({ Text("Rimuovi…") }, { menu = false; remove = s })
                            }
                        }
                    },
                    modifier = Modifier.clickable { nav.go(Screen.Grid("source:${s.id}", s.name)) },
                )
            }
            item {
                ListItem(headlineContent = { Text("Aggiungi una cartella…") },
                    supportingContent = { Text("Scheda SD, chiavetta USB o la cartella di un'app cloud (Drive, Proton Drive, Nextcloud…)") },
                    leadingContent = { Icon(Icons.Filled.Add, null) }, modifier = Modifier.clickable { picker.launch(null) })
            }
            item {
                ListItem(headlineContent = { Text("iCloud Foto…") }, supportingContent = { Text("Copia le foto di iCloud in una di queste cartelle") },
                    leadingContent = { Icon(Icons.Filled.Cloud, null) }, modifier = Modifier.clickable { nav.go(Screen.ICloud) })
            }
            item {
                Text("Le cartelle aggiunte sono lette senza essere copiate. Scollega nasconde le foto di una sorgente senza dimenticarla; " +
                        "Rimuovi la toglie dalla fototeca (i file non vengono toccati). Una cartella vuota o non raggiungibile non svuota mai la fototeca.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
    rename?.let { s -> TextDialog("Rinomina", s.name, onDismiss = { rename = null }) { t -> repo.io { repo.lib.renameSource(s.id, t) } } }
    remove?.let { s ->
        ConfirmDialog("Rimuovi «${s.name}»", "Le foto di questa cartella spariscono dalla fototeca (con album e modifiche). I file restano dove sono.", "Rimuovi",
            { remove = null }) {
            repo.io {
                repo.lib.removeSource(s.id)
                try { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(s.path), Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) {}
            }
        }
    }
}

/** Preferenze: aspetto, luoghi, volti, file, presentazione, informazioni. */
@Composable
fun SettingsScreen() {
    val repo = repo()
    val nav = nav()
    val ctx = LocalContext.current
    val theme by repo.settings.theme.flow.collectAsState()
    val places by repo.settings.placeNames.flow.collectAsState()
    val write by repo.settings.writeFiles.flow.collectAsState()
    val faces by repo.settings.faces.flow.collectAsState()
    val slide by repo.settings.slideSeconds.flow.collectAsState()
    Scaffold(topBar = { Bar("Preferenze", onBack = { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { SectionTitle("Aspetto") }
            for ((k, l) in listOf("system" to "Come il sistema", "light" to "Chiaro", "dark" to "Scuro", "black" to "Nero (neri puri, per OLED e per guardare le foto)")) item {
                ListItem(headlineContent = { Text(l) }, leadingContent = { RadioButton(theme == k, { repo.settings.theme.value = k }) },
                    modifier = Modifier.clickable { repo.settings.theme.value = k })
            }
            item { SectionTitle("Luoghi") }
            item {
                ListItem(headlineContent = { Text("Nomi dei luoghi da OpenStreetMap") },
                    supportingContent = { Text("Vengono inviate solo coordinate arrotondate a ~1 km (Nominatim). Senza, i luoghi sono indicati con le coordinate.") },
                    trailingContent = { Switch(places, { repo.settings.placeNames.value = it; if (it) repo.background() }) })
            }
            item {
                ListItem(headlineContent = { Text("Ricalcola i nomi dei luoghi") }, modifier = Modifier.clickable {
                    repo.io { repo.lib.clearPlaceNames(); repo.background() }
                })
            }
            item { SectionTitle("Persone") }
            item {
                ListItem(headlineContent = { Text("Riconoscimento dei volti") },
                    supportingContent = { Text(if (repo.faces.modelsReady()) "Modelli presenti. Le foto nuove vengono analizzate sul telefono."
                    else "I modelli (circa 40 MB) vengono scaricati alla prima analisi da Persone.") },
                    trailingContent = { Switch(faces, { repo.settings.faces.value = it }) })
            }
            item { SectionTitle("File") }
            item {
                ListItem(headlineContent = { Text("Scrivi le modifiche anche nei file") },
                    supportingContent = { Text("Data e posizione modificate vengono scritte nell'EXIF (JPEG, PNG, WebP, DNG) senza ricomprimere le foto. " +
                            "Android chiede il permesso per i file di altre app.") },
                    trailingContent = { Switch(write, { repo.settings.writeFiles.value = it }) })
            }
            item {
                ListItem(headlineContent = { Text("Scrivi data e luogo nei file") },
                    supportingContent = { Text("Scrive i valori attuali della fototeca, ad esempio per correzioni fatte prima di attivare la scrittura.") },
                    modifier = Modifier.clickable {
                        repo.io {
                            val prev = repo.settings.writeFiles.value
                            repo.settings.writeFiles.value = true
                            val ids = repo.lib.db.query("SELECT id FROM media WHERE instr(user, ',taken,') > 0 OR instr(user, ',lat,') > 0").mapNotNull { it["id"] as? Long }
                            repo.writeToFiles(ids, date = true, location = true)
                            repo.settings.writeFiles.value = prev
                        }
                    })
            }
            if (android.os.Build.VERSION.SDK_INT >= 31) item {
                ListItem(headlineContent = { Text("Gestione dei contenuti multimediali") },
                    supportingContent = { Text(if (repo.canManageMedia()) "Consentita: eliminazioni e modifiche senza conferme ripetute."
                    else "Permetti all'app di eliminare e modificare le foto senza chiedere conferma ogni volta.") },
                    modifier = Modifier.clickable {
                        repo.launch(Intent(AndroidSettings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${ctx.packageName}")))
                    })
            }
            item { SectionTitle("Presentazione") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("Durata di ogni foto: $slide secondi")
                    Slider(slide.toFloat(), { repo.settings.slideSeconds.value = it.toInt() }, valueRange = 2f..10f, steps = 7)
                }
            }
            item { SectionTitle("Manutenzione") }
            item {
                ListItem(headlineContent = { Text("Rileggi tutta la fototeca") }, supportingContent = { Text("Rilegge i metadati di tutti i file.") },
                    modifier = Modifier.clickable { repo.scan(full = true) })
            }
            item {
                ListItem(headlineContent = { Text("Svuota la cache") }, supportingContent = { Text("File temporanei di esportazioni e Live Photo.") },
                    modifier = Modifier.clickable {
                        repo.exporter.clearCache()
                        ctx.cacheDir.resolve("motion").listFiles()?.forEach { it.delete() }
                        repo.say("Cache svuotata")
                    })
            }
            item { SectionTitle("Informazioni") }
            item {
                Text("MyPhotoLibrary ${io.github.guidoaltieri.myphotolibrary.BuildConfig.VERSION_NAME}: la fototeca di ArchPhotos per Android.\n\n" +
                        "Mappe © OpenStreetMap contributors. Nomi dei luoghi: OpenStreetMap Nominatim. Elenco dei luoghi per i suggerimenti: " +
                        "GeoNames (CC BY 4.0). Riconoscimento dei volti: OpenCV (YuNet, SFace).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
