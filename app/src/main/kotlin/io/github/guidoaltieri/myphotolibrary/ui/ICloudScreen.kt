package io.github.guidoaltieri.myphotolibrary.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Layouts
import io.github.guidoaltieri.myphotolibrary.core.Source
import io.github.guidoaltieri.myphotolibrary.data.ICloudManager
import kotlinx.coroutines.launch

/**
 * iCloud Foto → cloud: accesso all'Apple ID, cartella di destinazione tra quelle collegate
 * (app cloud nel selettore di sistema), organizzazione per data e copia in background. Le foto
 * passano direttamente da iCloud alla cartella, senza restare sul telefono, e compaiono subito
 * nella libreria; a ogni apertura dell'app si copiano quelle nuove.
 */
@Composable
fun ICloudScreen() {
    val repo = repo()
    val nav = nav()
    val ic = repo.icloud
    var signedIn by remember { mutableStateOf(ic.signedIn) }
    Scaffold(topBar = { Bar("iCloud Foto", if (signedIn) ic.appleId else "", onBack = { nav.back() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!signedIn) item { LoginCard(ic) { signedIn = true; ic.schedule(replace = true) } }
            else {
                item { SyncCard(ic) }
                item { DestinationCard(ic) }
                item {
                    TextButton(onClick = { ic.signOut(); signedIn = false }) { Text("Esci dall'account iCloud") }
                    Text("Le foto già copiate restano nella cartella e nella libreria.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { LogButton(ic) }
        }
    }
}

/** Il registro di accesso e copia, da inviare se qualcosa non va. */
@Composable
private fun LogButton(ic: ICloudManager) {
    val repo = repo()
    val ctx = LocalContext.current
    TextButton(onClick = {
        if (!ic.logFile.exists()) { repo.say("Il registro è vuoto"); return@TextButton }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", ic.logFile)
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "MyPhotoLibrary: registro iCloud").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = android.content.ClipData.newRawUri("", uri)
        ctx.startActivity(Intent.createChooser(send, "Condividi il registro"))
    }) { Text("Condividi il registro") }
}

@Composable
private fun LoginCard(ic: ICloudManager, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var id by remember { mutableStateOf(ic.appleId) }
    var pw by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var askCode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun handle(r: ICloudManager.Login) {
        busy = false
        when (r) {
            ICloudManager.Login.Ok -> onDone()
            ICloudManager.Login.NeedsCode -> { askCode = true; error = null }
            is ICloudManager.Login.Failed -> error = r.message
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ic.needsLogin) "Accedi di nuovo" else "Collega iCloud Foto", style = MaterialTheme.typography.titleMedium)
            Text("Servono «Accedi ai dati iCloud sul web» attivo e la Protezione avanzata dei dati disattivata (Impostazioni → " +
                    "[il tuo nome] → iCloud sull'iPhone). La password resta cifrata su questo telefono; ad Apple arriva solo la prova SRP, " +
                    "come sul sito di iCloud.", style = MaterialTheme.typography.bodySmall)
            if (!askCode) {
                OutlinedTextField(id, { id = it }, label = { Text("Apple ID (email)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && id.contains("@") && pw.isNotEmpty(), onClick = {
                    busy = true; scope.launch { handle(ic.signIn(id, pw)) }
                }) { Text(if (busy) "Accesso…" else "Accedi") }
            } else {
                Text("Inserisci il codice di verifica a sei cifre comparso sui tuoi dispositivi Apple.")
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Codice") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !busy && code.length == 6, onClick = { busy = true; scope.launch { handle(ic.verifyCode(code)) } }) { Text("Verifica") }
                    TextButton(onClick = { scope.launch { ic.resendCode() } }) { Text("Invia di nuovo il codice") }
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun SyncCard(ic: ICloudManager) {
    val repo = repo()
    val status by ic.status.collectAsState()
    val running by ic.running.collectAsState()
    val task by repo.task.collectAsState()
    val enabled by ic.autoCopy.collectAsState()
    val destId by ic.destination.collectAsState()
    val configured = load(destId, initial = destId > 0) { ic.configured }
    val synced = load(running, status, task?.done?.div(10), initial = 0) { repo.lib.icloudSyncedCount() }   // aggiornato man mano
    val ctx = LocalContext.current
    val work by remember { WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(ICloudManager.WORK) }.collectAsState(emptyList())
    val workState = work.firstOrNull()?.let { w ->
        when (w.state) {
            WorkInfo.State.ENQUEUED -> if (w.runAttemptCount > 0) "Nuovo tentativo in attesa (rete o server non disponibili)" else "In coda: parte appena c'è la rete"
            WorkInfo.State.BLOCKED -> "In attesa"
            WorkInfo.State.RUNNING -> null
            WorkInfo.State.FAILED -> "Ferma: serve un tuo intervento (vedi sopra)"
            WorkInfo.State.CANCELLED -> "Fermata"
            WorkInfo.State.SUCCEEDED -> null
        }
    }
    // la notifica mostra l'avanzamento della copia in background (Android 13+ chiede il permesso)
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun start(replace: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notify.launch(Manifest.permission.POST_NOTIFICATIONS)
        ic.schedule(replace)
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Copia automatica", style = MaterialTheme.typography.titleMedium)
                    Text("Copia tutte le foto e poi, a ogni apertura dell'app, quelle nuove.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(enabled, { on ->
                    ic.enabled = on
                    if (on) start() else ic.stop()
                }, enabled = configured)
            }
            if (!configured) Text("Scegli prima la cartella di destinazione.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            if (running) {
                val t = task
                Text(t?.let { "${it.label} · ${Fmt.thousands(it.done.toLong())}/${Fmt.thousands(it.total.toLong())}" } ?: "Copia in corso…")
                if (t != null && t.total > 0) LinearProgressIndicator(progress = { t.done.toFloat() / t.total }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (enabled && !running) workState?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Foto di iCloud già copiate: ${Fmt.thousands(synced.toLong())}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && configured && !running, onClick = { start(replace = true) }) { Text("Controlla ora") }
                if (running) OutlinedButton(onClick = { ic.stop() }) { Text("Ferma") }
            }
        }
    }
}

@Composable
private fun DestinationCard(ic: ICloudManager) {
    val repo = repo()
    val ctx = LocalContext.current
    val folders = load(initial = emptyList<Source>()) {
        repo.lib.sources().filter { it.kind == "folder" && it.enabled }.map { s ->
            // cartelle collegate con un codice al posto del nome: si mostra quello vero (o il nome dell'app)
            if (!isCodeName(s.name)) s
            else treeName(ctx, android.net.Uri.parse(s.path)).let { n -> if (n == s.name) s else { repo.lib.renameSource(s.id, n); s.copy(name = n) } }
        }
    }
    var dest by remember { mutableLongStateOf(ic.destSource) }
    var layout by remember { mutableStateOf(ic.layout) }
    var useExisting by remember { mutableStateOf(ic.useExisting) }
    fun choose(id: Long) {
        val changed = id != ic.destSource
        if (changed) {
            // nuova destinazione: le foto vanno copiate anche lì
            repo.lib.clearIcloudSynced(); ic.fullDone = false
        }
        ic.destSource = id; dest = id
        // scelta la cartella, la copia parte subito (si può sempre fermare con l'interruttore)
        ic.enabled = true
        if (changed) ic.restart() else ic.schedule()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            } catch (e: Exception) {
                repo.say("Questa cartella non consente la scrittura: scegline un'altra")
                return@rememberLauncherForActivityResult
            }
            repo.io {
                val id = repo.lib.addSource("folder", treeName(ctx, uri), uri.toString(), uri.authority ?: "")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { choose(id) }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Destinazione", style = MaterialTheme.typography.titleMedium)
            Text("Una cartella di un cloud già collegato all'app (Sorgenti): le foto vengono scritte direttamente lì tramite l'app " +
                    "del servizio, senza copie sul telefono, e compaiono nella libreria man mano.", style = MaterialTheme.typography.bodySmall)
            folders.forEach { s ->
                ListItem(headlineContent = { Text(s.name) },
                    leadingContent = { RadioButton(dest == s.id, { choose(s.id) }) }, trailingContent = { Icon(Icons.Filled.Folder, null) },
                    modifier = Modifier.clickable { choose(s.id) })
            }
            ListItem(headlineContent = { Text("Collega un'altra cartella…") },
                supportingContent = { Text("Nel selettore scegli l'app del cloud (es. Nextcloud, Proton Drive, OneDrive) e la cartella") },
                leadingContent = { Icon(Icons.Filled.Add, null) }, modifier = Modifier.clickable { picker.launch(null) })
            val destName = folders.firstOrNull { it.id == dest }?.name ?: "Cloud"
            FolderLayoutPicker(layout, { layout = it; if (Layouts.validate(it) == null) ic.layout = it }, useExisting,
                { useExisting = it; ic.useExisting = it }, destName)
            Text("I file già presenti nella cartella (stesso nome e dimensione) non vengono copiati di nuovo. Un cambio di " +
                    "organizzazione vale per le foto copiate da quel momento.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
