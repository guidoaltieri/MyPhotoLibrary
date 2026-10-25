package io.github.guidoaltieri.myphotolibrary

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import io.github.guidoaltieri.myphotolibrary.data.Repo
import io.github.guidoaltieri.myphotolibrary.ui.AppContent
import io.github.guidoaltieri.myphotolibrary.ui.LocalNav
import io.github.guidoaltieri.myphotolibrary.ui.LocalRepo
import io.github.guidoaltieri.myphotolibrary.ui.Nav
import io.github.guidoaltieri.myphotolibrary.ui.Screen
import io.github.guidoaltieri.myphotolibrary.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val repo: Repo get() = (application as App).repo
    private val nav = Nav()
    private var hasPermission by mutableStateOf(false)
    private var pendingResult: CompletableDeferred<Boolean>? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = repo.hasMediaPermission()
        if (hasPermission) {
            repo.watchMediaStore()
            repo.scan()
        }
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        pendingResult?.complete(r.resultCode == RESULT_OK)
        pendingResult = null
    }

    private fun requestPermissions() {
        val list = mutableListOf(Manifest.permission.ACCESS_MEDIA_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) list += listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else list += Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT <= 29) list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        permissions.launch(list.toTypedArray())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hasPermission = repo.hasMediaPermission()
        if (hasPermission) {
            repo.watchMediaStore()
            repo.scan()
        } else {
            requestPermissions()
            repo.scan(repo.lib.sources().filter { it.kind != "device" }.map { it.id })
        }
        lifecycleScope.launch {
            repo.pending.collect { p ->
                pendingResult = p.result
                try {
                    consent.launch(IntentSenderRequest.Builder(p.sender).build())
                } catch (e: Exception) {
                    p.result.complete(false)
                }
            }
        }
        lifecycleScope.launch {
            repo.intents.collect { i ->
                try { startActivity(i) } catch (e: Exception) { repo.say("Nessuna app per questa azione") }
            }
        }
        handleIntent(intent)
        setContent {
            val theme by repo.settings.theme.flow.collectAsState()
            val snackbar = remember { SnackbarHostState() }
            // messaggi visibili in tutte le schermate, con l'eventuale «Annulla»
            LaunchedEffect(Unit) {
                repo.messages.collect { m ->
                    val r = snackbar.showSnackbar(m.text, actionLabel = m.action, duration = if (m.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
                    if (r == SnackbarResult.ActionPerformed) m.onAction?.let { repo.io(it) }
                }
            }
            AppTheme(theme) {
                CompositionLocalProvider(LocalRepo provides repo, LocalNav provides nav) {
                    BackHandler(nav.stack.size > 1) { nav.back() }
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        AppContent(hasPermission) { requestPermissions() }
                        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 80.dp))
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Foto o video aperti da un'altra app: se fanno parte della fototeca si aprono nel visore. */
    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val item = repo.lib.itemByPath(uri.toString())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (item != null) nav.go(Screen.Viewer(listOf(item.id), 0))
                else repo.say("Il file non fa parte della fototeca")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val now = repo.hasMediaPermission()
        if (now && !hasPermission) {
            hasPermission = true
            repo.watchMediaStore()
            repo.scan()
        }
    }
}
