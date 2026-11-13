package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.guidoaltieri.myphotolibrary.data.Repo
import io.github.guidoaltieri.myphotolibrary.data.Updater
import kotlinx.coroutines.launch

/** Scarica e installa [r]; se Android non lo consente ancora, apre l'impostazione da attivare. */
private suspend fun update(repo: Repo, r: Updater.Release) {
    val u = repo.updater
    if (!u.canInstall()) {
        repo.say("Consenti a MyPhotoLibrary di installare app, poi torna indietro e tocca di nuovo «Aggiorna»")
        repo.launch(u.installPermissionIntent())
        return
    }
    try {
        u.install(u.download(r))
        u.available.value = null
    } catch (e: Exception) {
        repo.say("Aggiornamento non riuscito: ${e.message ?: e.javaClass.simpleName}")
    }
}

/** Richiesta di aggiornamento all'avvio, quando c'è una versione nuova. */
@Composable
fun UpdateDialog() {
    val repo = repo()
    val scope = rememberCoroutineScope()
    val r by repo.updater.available.collectAsState()
    val progress by repo.updater.downloading.collectAsState()
    val rel = r ?: return
    AlertDialog(
        onDismissRequest = { if (progress == null) repo.updater.later(rel) },
        title = { Text("Nuova versione disponibile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${rel.name} (versione ${rel.code}; installata ${repo.updater.current}).")
                if (rel.notes.isNotBlank()) Text(rel.notes.lines().take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                progress?.let {
                    Text("Scaricamento…")
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            Button(enabled = progress == null, onClick = { scope.launch { update(repo, rel) } }) { Text("Aggiorna") }
        },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = { repo.updater.later(rel) }) { Text("Più tardi") }
        },
    )
}

/** Sezione «Aggiornamenti» delle Preferenze. */
fun LazyListScope.updateSettings(repo: Repo) {
    item { SectionTitle("Aggiornamenti") }
    item {
        val u = repo.updater
        var auto by remember { mutableStateOf(u.auto) }
        ListItem(headlineContent = { Text("Controlla all'avvio") },
            supportingContent = { Text("Versione installata: ${io.github.guidoaltieri.myphotolibrary.BuildConfig.VERSION_NAME} (${u.current}). " +
                    "Le versioni nuove arrivano dalle release del repository GitHub.") },
            trailingContent = { Switch(auto, { auto = it; u.auto = it }) })
    }
    item {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        ListItem(headlineContent = { Text(if (busy) "Controllo…" else "Controlla adesso") }, modifier = Modifier.clickable(enabled = !busy) {
            busy = true
            scope.launch {
                try {
                    val r = repo.updater.check()
                    if (r == null) repo.say("Hai già l'ultima versione") else repo.updater.available.value = r
                } catch (e: Exception) {
                    repo.say("Controllo non riuscito: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    busy = false
                }
            }
        })
    }
    item {
        var token by remember { mutableStateOf("") }
        var saved by remember { mutableStateOf(repo.updater.token != null) }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Token di GitHub", style = MaterialTheme.typography.titleSmall)
            Text("Il repository è privato: serve un token in sola lettura (github.com → Settings → Developer settings → " +
                    "Fine-grained tokens: solo il repository MyPhotoLibrary, permesso «Contents: Read-only»). Resta cifrato sul telefono." +
                    if (saved) "\nToken salvato." else "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(token, { token = it }, label = { Text(if (saved) "Nuovo token" else "Token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = token.isNotBlank(), onClick = { repo.updater.token = token; token = ""; saved = true; repo.say("Token salvato") }) { Text("Salva") }
                if (saved) TextButton(onClick = { repo.updater.token = null; saved = false }) { Text("Rimuovi") }
            }
        }
    }
}
