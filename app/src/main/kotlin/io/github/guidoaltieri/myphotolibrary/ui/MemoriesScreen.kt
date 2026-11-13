package io.github.guidoaltieri.myphotolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.guidoaltieri.myphotolibrary.core.Memories
import io.github.guidoaltieri.myphotolibrary.core.Memory
import io.github.guidoaltieri.myphotolibrary.data.Full
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Ricordi creati da soli (un giorno come oggi, viaggi, giornate speciali, il meglio di…). */
@Composable
fun MemoriesTab() {
    val repo = repo()
    val nav = nav()
    var onlyFav by remember { mutableStateOf(false) }
    val mems = load(initial = null as List<Memory>?) { Memories.generate(repo.lib) }
    when {
        mems == null -> {}
        mems.isEmpty() -> Empty("Nessun ricordo per ora", "I ricordi compaiono quando la fototeca contiene abbastanza foto con data e luogo.")
        else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!onlyFav, { onlyFav = false }, { Text("Tutti") })
                    FilterChip(onlyFav, { onlyFav = true }, { Text("Preferiti") })
                }
            }
            items(mems.filter { !onlyFav || it.favorite }, key = { it.key }) { m ->
                MemoryCard(m) { nav.go(Screen.Memory(m.key)) }
            }
        }
    }
}

@Composable
fun MemoryCard(m: Memory, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(0.95f).rounded().clickable(onClick = onClick)) {
        m.cover?.let { ThumbImage(it, Modifier.fillMaxSize(), size = 1024) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)), startY = 300f)))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
            Text(m.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(m.subtitle, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall)
        }
        if (m.favorite) Icon(Icons.Filled.Favorite, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}

/** Dettaglio di un ricordo: presentazione, preferito, rinomina, salva come album, elimina. */
@Composable
fun MemoryScreen(key: String) {
    val repo = repo()
    val nav = nav()
    val m = load(key, initial = null as Memory?) { Memories.generate(repo.lib).firstOrNull { it.key == key } }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    if (m == null) {
        Scaffold(topBar = { Bar("Ricordo", onBack = { nav.back() }) }) { Box(Modifier.padding(it)) }
        return
    }
    val ids = if (all) m.allIds else m.ids
    GridScreen("ids:" + ids.joinToString(","), m.title, subtitle = m.subtitle, extraActions = {
        IconButton(onClick = { nav.go(Screen.Slideshow(m.ids, m.title)) }) { Icon(Icons.Filled.PlayArrow, "Presentazione") }
        IconButton(onClick = { repo.io { repo.lib.setMemoryState(m.key, favorite = !m.favorite) } }) {
            Icon(if (m.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Preferito")
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Rinomina…") }, { menu = false; rename = true })
                DropdownMenuItem({ Text(if (all) "Mostra le migliori" else "Mostra tutte (${m.count})") }, { menu = false; all = !all })
                DropdownMenuItem({ Text("Salva come album") }, {
                    menu = false
                    repo.io { val id = repo.lib.createAlbum(m.title); repo.lib.addToAlbum(id, m.allIds); repo.say("Album «${m.title}» creato") }
                })
                DropdownMenuItem({ Text("Elimina ricordo") }, { menu = false; repo.io { repo.lib.setMemoryState(m.key, hidden = true) }; nav.back() })
            }
        }
    }, header = {
        Box(Modifier.fillMaxWidth().height(260.dp).clickable { nav.go(Screen.Slideshow(m.ids, m.title)) }) {
            m.cover?.let { ThumbImage(it, Modifier.fillMaxSize(), size = 1024) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
            Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                Text(m.title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(m.subtitle, color = Color.White)
            }
            Icon(Icons.Filled.PlayArrow, "Presentazione", tint = Color.White, modifier = Modifier.align(Alignment.Center).size(64.dp))
        }
    })
    if (rename) TextDialog("Rinomina ricordo", m.title, onDismiss = { rename = false }) { t -> repo.io { repo.lib.setMemoryState(m.key, title = t) } }
}

/** Presentazione a schermo intero con dissolvenza e lento ingrandimento (effetto Ken Burns). */
@Composable
fun SlideshowScreen(ids: List<Long>, title: String) {
    val repo = repo()
    val nav = nav()
    val items = load(ids, initial = emptyList<io.github.guidoaltieri.myphotolibrary.core.Item>()) { repo.items(ids).filter { !it.isVideo } }
    var index by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0f) }
    val seconds = repo.settings.slideSeconds.value.coerceAtLeast(2)
    BackHandler { nav.back() }
    LaunchedEffect(items.size, playing, index) {
        if (items.isEmpty() || !playing) return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (isActive) {
            val t = (System.currentTimeMillis() - start) / (seconds * 1000f)
            progress = t.coerceAtMost(1f)
            if (t >= 1f) {
                index = (index + 1) % items.size
                break
            }
            delay(40)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable { playing = !playing }) {
        if (items.isNotEmpty()) {
            AnimatedContent(index, transitionSpec = { fadeIn(tween(900)) togetherWith fadeOut(tween(900)) }, label = "slide") { i ->
                val item = items[i % items.size]
                AsyncImage(Full(item, 2048), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val s = 1f + 0.08f * (if (i == index) progress else 1f)
                        scaleX = s; scaleY = s
                    })
            }
        }
        Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(16.dp)) {
            LinearProgressIndicator(progress = { (index + progress) / items.size.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(top = 8.dp))
                IconButton(onClick = { playing = !playing }) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Pausa", tint = Color.White) }
                IconButton(onClick = { nav.back() }) { Icon(Icons.Filled.Close, "Chiudi", tint = Color.White) }
            }
        }
    }
}
