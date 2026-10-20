package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.data.Thumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dati letti dalla fototeca in background, ricaricati a ogni modifica (repo.version). */
@Composable
fun <T> load(vararg keys: Any?, initial: T, block: suspend () -> T): T {
    val repo = repo()
    val v by repo.version.collectAsState()
    val state = produceState(initial, v, *keys) {
        value = withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                repo.say(e.message ?: e.javaClass.simpleName)
                value
            }
        }
    }
    return state.value
}

@Composable
fun ThumbImage(item: Item, modifier: Modifier = Modifier, size: Int = 384, crop: Boolean = true) {
    AsyncImage(
        model = Thumb(item, size),
        contentDescription = item.name,
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/** Simboli sulle miniature: preferito, video con durata, Live Photo, modificata. */
@Composable
fun ThumbBadges(item: Item, compact: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        if (item.favorite) Icon(Icons.Filled.Favorite, null, tint = Color.White,
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(if (compact) 12.dp else 16.dp))
        Row(Modifier.align(Alignment.BottomEnd).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item.edits != null && !compact) Icon(Icons.Filled.Edit, null, tint = Color.White, modifier = Modifier.size(12.dp))
            if (item.isVideo) {
                if (!compact) Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(14.dp))
                Text(Fmt.duration(item.duration), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (item.isLive && !compact) Icon(Icons.Filled.MotionPhotosOn, null, tint = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(14.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, subtitle: String = "", onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
        },
        actions = { actions() },
    )
}

@Composable
fun Empty(title: String, text: String = "", modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun TextDialog(title: String, initial: String = "", label: String = "", confirm: String = "OK", onDismiss: () -> Unit, onOk: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, label = { if (label.isNotEmpty()) Text(label) }, singleLine = true, modifier = Modifier.focusRequester(focus)) },
        confirmButton = { TextButton(onClick = { onOk(text.trim()); onDismiss() }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (e: Exception) {} }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onOk(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

val Rounded = RoundedCornerShape(12.dp)

fun Modifier.rounded() = this.clip(Rounded)
