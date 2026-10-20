package io.github.guidoaltieri.myphotolibrary.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Filter
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.guidoaltieri.myphotolibrary.core.Edit
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.Recipe
import io.github.guidoaltieri.myphotolibrary.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Modifica non distruttiva (come l'editor di ArchPhotos): Regola, Migliora automaticamente,
 * Filtri con intensità, Ritaglia (raddrizza, ruota, rifletti, proporzioni). Tieni premuta la foto
 * per vedere l'originale; «Ripristina originale» in ogni momento. Per i video: taglio e audio.
 */
@Composable
fun EditorScreen(id: Long) {
    val repo = repo()
    val nav = nav()
    val item = load(id, initial = null as Item?) { repo.lib.item(id) } ?: return
    if (item.isVideo) VideoEditor(item) else PhotoEditor(item)
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoEditor(item: Item) {
    val repo = repo()
    val nav = nav()
    val ctx = LocalContext.current
    var recipe by remember { mutableStateOf(Edit.load(item.edits)) }
    var tab by remember { mutableStateOf("adjust") }
    var base by remember { mutableStateOf<Bitmap?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var comparing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("exposure") }
    var filterThumbs by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    var confirmExit by remember { mutableStateOf(false) }
    val changed = (Edit.clean(recipe)?.toJson() ?: "") != (item.edits ?: "")
    BackHandler { if (changed) confirmExit = true else nav.back() }
    LaunchedEffect(item.id) {
        base = withContext(Dispatchers.IO) { Images.open(ctx, item, 1600)?.let { if (it.config == Bitmap.Config.ARGB_8888) it else it.copy(Bitmap.Config.ARGB_8888, false) } }
        val b = base ?: return@LaunchedEffect
        // anteprime dei filtri su una versione piccola
        filterThumbs = withContext(Dispatchers.Default) {
            val small = Bitmap.createScaledBitmap(b, 160, maxOf(1, 160 * b.height / b.width), true)
            Edit.FILTERS.associate { f -> f.key to Images.tone(small, f.adjust).asImageBitmap() }
        }
    }
    LaunchedEffect(recipe, base, tab) {
        val b = base ?: return@LaunchedEffect
        delay(40)
        preview = withContext(Dispatchers.Default) {
            if (tab == "crop") Images.geometry(b, recipe, withCrop = false).asImageBitmap() else Images.render(b, recipe).asImageBitmap()
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (changed) confirmExit = true else nav.back() }) { Text("Annulla", color = Color.White) }
            Box(Modifier.weight(1f))
            if (!Edit.isIdentity(recipe)) TextButton(onClick = { recipe = Recipe() }) { Text("Ripristina originale", color = Color(0xFFFF453A)) }
            TextButton(onClick = {
                repo.io { repo.lib.setEdits(item.id, Edit.clean(recipe)?.toJson()) }
                nav.back()
            }) { Text("Fine", color = Color(0xFFFFD60A), fontWeight = FontWeight.Bold) }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
            val b = base
            if (tab == "crop" && preview != null) {
                CropBox(preview!!, recipe) { recipe = it }
            } else if (b != null) {
                val img = if (comparing) b.asImageBitmap() else (preview ?: b.asImageBitmap())
                Image(img, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                    detectTapGestures(onPress = { comparing = true; tryAwaitRelease(); comparing = false })
                })
                if (comparing) Text("Originale", color = Color.White, modifier = Modifier.align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        Column(Modifier.fillMaxWidth().background(Color(0xFF111111)).padding(vertical = 8.dp)) {
            when (tab) {
                "adjust" -> AdjustPanel(recipe, selected, { selected = it }, { recipe = it }) {
                    val b = base ?: return@AdjustPanel
                    val small = Bitmap.createScaledBitmap(b, minOf(400, b.width), maxOf(1, minOf(400, b.width) * b.height / b.width), true)
                    val auto = Edit.autoEnhance(Images.pixels(small), small.width, small.height)
                    recipe = recipe.copy(adjust = recipe.adjust + auto)
                    repo.say(if (auto.isEmpty()) "La foto non ha bisogno di correzioni" else "Migliorata: ${auto.keys.size} regolazioni")
                }
                "filters" -> FiltersPanel(recipe, filterThumbs) { recipe = it }
                "crop" -> CropPanel(recipe, base) { recipe = it }
            }
        }
        NavigationBar(containerColor = Color.Black, modifier = Modifier.navigationBarsPadding()) {
            NavigationBarItem(tab == "adjust", { tab = "adjust" }, { Icon(Icons.Filled.Tune, null) }, label = { Text("Regola") })
            NavigationBarItem(tab == "filters", { tab = "filters" }, { Icon(Icons.Filled.Filter, null) }, label = { Text("Filtri") })
            NavigationBarItem(tab == "crop", { tab = "crop" }, { Icon(Icons.Filled.Crop, null) }, label = { Text("Ritaglia") })
        }
    }
    if (confirmExit) ConfirmDialog("Annullare le modifiche?", "Le modifiche non salvate andranno perse.", "Annulla le modifiche",
        { confirmExit = false }) { nav.back() }
}

@Composable
private fun AdjustPanel(recipe: Recipe, selected: String, onSelect: (String) -> Unit, onChange: (Recipe) -> Unit, onAuto: () -> Unit) {
    val adj = Edit.ADJUSTMENTS.first { it.key == selected }
    val value = recipe.adjust[selected] ?: 0.0
    val positive = selected in Edit.POSITIVE_ONLY
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                FilterChip(false, onAuto, { Text("Migliora") }, leadingIcon = { Icon(Icons.Filled.AutoFixHigh, null, Modifier.size(18.dp)) })
            }
            items(Edit.ADJUSTMENTS) { a ->
                val v = recipe.adjust[a.key] ?: 0.0
                FilterChip(a.key == selected, { onSelect(a.key) }, {
                    Text(if (abs(v) > 1e-3) "${a.label} ${(v * 100).toInt()}" else a.label)
                })
            }
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${adj.group} · ${adj.label}", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${(value * 100).toInt()}", color = Color.White)
            TextButton(onClick = { onChange(recipe.copy(adjust = recipe.adjust - selected)) }) { Text("Azzera") }
        }
        Slider(value.toFloat(), { v ->
            val nv = if (abs(v) < 0.02f) 0.0 else v.toDouble()
            onChange(recipe.copy(adjust = recipe.adjust + (selected to nv)))
        }, valueRange = (if (positive) 0f else -1f)..1f, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

@Composable
private fun FiltersPanel(recipe: Recipe, thumbs: Map<String, ImageBitmap>, onChange: (Recipe) -> Unit) {
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(Edit.FILTERS) { f ->
                val on = (recipe.filter ?: "") == f.key
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(72.dp).clickable {
                    onChange(recipe.copy(filter = f.key.ifEmpty { null }, filterAmount = 1.0))
                }) {
                    Box(Modifier.size(72.dp).rounded().let { if (on) it.border(2.dp, Color(0xFFFFD60A), RoundedCornerShape(12.dp)) else it }) {
                        thumbs[f.key]?.let { Image(it, f.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Text(f.label, color = if (on) Color(0xFFFFD60A) else Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        if (!recipe.filter.isNullOrEmpty()) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Intensità ${(recipe.filterAmount * 100).toInt()}", color = Color.White)
            }
            Slider(recipe.filterAmount.toFloat(), { onChange(recipe.copy(filterAmount = it.toDouble())) }, valueRange = 0f..1f,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun CropPanel(recipe: Recipe, base: Bitmap?, onChange: (Recipe) -> Unit) {
    Column {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(recipe.copy(rotate = (recipe.rotate + 270) % 360, crop = null)) }) {
                Icon(Icons.AutoMirrored.Filled.RotateLeft, "Ruota", tint = Color.White)
            }
            IconButton(onClick = { onChange(recipe.copy(flipH = !recipe.flipH)) }) { Icon(Icons.Filled.Flip, "Rifletti", tint = Color.White) }
            Text("Raddrizza ${"%.1f".format(recipe.straighten)}°", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { onChange(recipe.copy(crop = null, straighten = 0.0, aspect = null)) }) { Text("Azzera") }
        }
        Slider(recipe.straighten.toFloat(), { onChange(recipe.copy(straighten = if (abs(it) < 0.2f) 0.0 else it.toDouble())) },
            valueRange = -45f..45f, modifier = Modifier.padding(horizontal = 16.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(Edit.ASPECTS) { (key, label, ratio) ->
                FilterChip((recipe.aspect ?: "free") == key, {
                    if (ratio == null) onChange(recipe.copy(aspect = key)) else {
                        // proporzioni dell'immagine dopo rotazione e raddrizzamento
                        val g = base?.let { b -> if (((recipe.rotate % 180) + 180) % 180 == 90) b.height to b.width else b.width to b.height } ?: (1 to 1)
                        val r = if (ratio == 0.0) g.first.toDouble() / g.second else ratio
                        onChange(recipe.copy(aspect = key, crop = fitAspect(recipe.crop, r, g.first.toDouble(), g.second.toDouble())))
                    }
                }, { Text(label) })
            }
        }
    }
}

/** Ritaglio più grande con proporzioni [ratio] centrato nel ritaglio attuale. */
private fun fitAspect(crop: List<Double>?, ratio: Double, w: Double, h: Double): List<Double> {
    val c = crop ?: listOf(0.0, 0.0, 1.0, 1.0)
    val pw = c[2] * w; val ph = c[3] * h
    val (nw, nh) = if (pw / ph > ratio) ph * ratio to ph else pw to pw / ratio
    val cx = (c[0] + c[2] / 2) * w; val cy = (c[1] + c[3] / 2) * h
    return listOf((cx - nw / 2) / w, (cy - nh / 2) / h, nw / w, nh / h)
}

/** Rettangolo di ritaglio con maniglie sugli angoli; trascinando all'interno si sposta. */
@Composable
private fun CropBox(image: ImageBitmap, recipe: Recipe, onChange: (Recipe) -> Unit) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val current by rememberUpdatedState(recipe)
    val handle = with(LocalDensity.current) { 28.dp.toPx() }
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }) {
        Image(image, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        val k = if (box.width == 0) 1f else min(box.width.toFloat() / image.width, box.height.toFloat() / image.height)
        val dw = image.width * k; val dh = image.height * k
        val left = (box.width - dw) / 2; val top = (box.height - dh) / 2
        var mode by remember { mutableStateOf("") }
        Canvas(Modifier.fillMaxSize().pointerInput(image.width, image.height, box) {
            detectDragGestures(onDragStart = { p ->
                val c = current.crop ?: listOf(0.0, 0.0, 1.0, 1.0)
                val x0 = left + c[0].toFloat() * dw; val y0 = top + c[1].toFloat() * dh
                val x1 = x0 + c[2].toFloat() * dw; val y1 = y0 + c[3].toFloat() * dh
                fun near(x: Float, y: Float) = abs(p.x - x) < handle && abs(p.y - y) < handle
                mode = when {
                    near(x0, y0) -> "tl"; near(x1, y0) -> "tr"; near(x0, y1) -> "bl"; near(x1, y1) -> "br"
                    p.x in x0..x1 && p.y in y0..y1 -> "move"
                    else -> ""
                }
            }, onDragEnd = { mode = "" }) { change, drag ->
                change.consume()
                val r = current
                val c = (r.crop ?: listOf(0.0, 0.0, 1.0, 1.0)).toMutableList()
                val dx = drag.x / dw.toDouble(); val dy = drag.y / dh.toDouble()
                val min = 0.05
                var x0 = c[0]; var y0 = c[1]; var x1 = c[0] + c[2]; var y1 = c[1] + c[3]
                when (mode) {
                    "move" -> {
                        val w = x1 - x0; val h = y1 - y0
                        x0 = (x0 + dx).coerceIn(0.0, 1 - w); y0 = (y0 + dy).coerceIn(0.0, 1 - h); x1 = x0 + w; y1 = y0 + h
                    }
                    "tl" -> { x0 = (x0 + dx).coerceIn(0.0, x1 - min); y0 = (y0 + dy).coerceIn(0.0, y1 - min) }
                    "tr" -> { x1 = (x1 + dx).coerceIn(x0 + min, 1.0); y0 = (y0 + dy).coerceIn(0.0, y1 - min) }
                    "bl" -> { x0 = (x0 + dx).coerceIn(0.0, x1 - min); y1 = (y1 + dy).coerceIn(y0 + min, 1.0) }
                    "br" -> { x1 = (x1 + dx).coerceIn(x0 + min, 1.0); y1 = (y1 + dy).coerceIn(y0 + min, 1.0) }
                    else -> return@detectDragGestures
                }
                // proporzioni fisse: l'altezza segue la larghezza
                val ratio = Edit.ASPECTS.firstOrNull { it.first == r.aspect }?.third?.let { q ->
                    if (q == 0.0) image.width.toDouble() / image.height else q
                }
                if (ratio != null && mode != "move") {
                    val wpx = (x1 - x0) * image.width
                    var hRel = wpx / ratio / image.height
                    if (mode.startsWith("t")) { y0 = (y1 - hRel).coerceAtLeast(0.0); hRel = y1 - y0 } else { y1 = (y0 + hRel).coerceAtMost(1.0); hRel = y1 - y0 }
                    val wRel = hRel * image.height * ratio / image.width
                    if (mode.endsWith("l")) x0 = x1 - wRel else x1 = x0 + wRel
                }
                onChange(r.copy(crop = listOf(x0, y0, x1 - x0, y1 - y0)))
            }
        }) {
            val c = current.crop ?: listOf(0.0, 0.0, 1.0, 1.0)
            val x0 = left + c[0].toFloat() * dw; val y0 = top + c[1].toFloat() * dh
            val w = c[2].toFloat() * dw; val h = c[3].toFloat() * dh
            val shade = Color.Black.copy(alpha = 0.55f)
            drawRect(shade, Offset(left, top), Size(dw, y0 - top))
            drawRect(shade, Offset(left, y0 + h), Size(dw, top + dh - y0 - h))
            drawRect(shade, Offset(left, y0), Size(x0 - left, h))
            drawRect(shade, Offset(x0 + w, y0), Size(left + dw - x0 - w, h))
            drawRect(Color.White, Offset(x0, y0), Size(w, h), style = Stroke(2.dp.toPx()))
            for (i in 1..2) {
                drawLine(Color.White.copy(alpha = 0.5f), Offset(x0 + w * i / 3, y0), Offset(x0 + w * i / 3, y0 + h))
                drawLine(Color.White.copy(alpha = 0.5f), Offset(x0, y0 + h * i / 3), Offset(x0 + w, y0 + h * i / 3))
            }
            val hl = 18.dp.toPx(); val sw = 4.dp.toPx()
            for ((cx, cy) in listOf(x0 to y0, x0 + w to y0, x0 to y0 + h, x0 + w to y0 + h)) {
                val sx = if (cx == x0) 1 else -1; val sy = if (cy == y0) 1 else -1
                drawLine(Color.White, Offset(cx, cy), Offset(cx + sx * hl, cy), sw)
                drawLine(Color.White, Offset(cx, cy), Offset(cx, cy + sy * hl), sw)
            }
        }
    }
}

/** Video: taglio dell'inizio e della fine e audio disattivato (applicati in riproduzione e all'esportazione). */
@AndroidOptIn(UnstableApi::class)
@Composable
private fun VideoEditor(item: Item) {
    val repo = repo()
    val nav = nav()
    val ctx = LocalContext.current
    val dur = (item.duration ?: 0.0).coerceAtLeast(0.1)
    var recipe by remember { mutableStateOf(Edit.load(item.edits)) }
    var range by remember { mutableStateOf((recipe.trim?.get(0)?.toFloat() ?: 0f)..(recipe.trim?.get(1)?.toFloat() ?: dur.toFloat())) }
    val player = remember { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(Uri.parse(item.path))); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(range.start) { player.seekTo((range.start * 1000).toLong()) }
    LaunchedEffect(recipe.mute) { player.volume = if (recipe.mute) 0f else 1f }
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            if (player.isPlaying && player.currentPosition / 1000f >= range.endInclusive) {
                player.pause(); player.seekTo((range.start * 1000).toLong())
            }
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { nav.back() }) { Text("Annulla", color = Color.White) }
            Box(Modifier.weight(1f))
            if (!Edit.isIdentity(recipe) || range.start > 0.05f || range.endInclusive < dur - 0.05) TextButton(onClick = {
                recipe = Recipe(); range = 0f..dur.toFloat()
            }) { Text("Ripristina originale", color = Color(0xFFFF453A)) }
            TextButton(onClick = {
                val trim = if (range.start > 0.05f || range.endInclusive < dur - 0.05) listOf(range.start.toDouble(), range.endInclusive.toDouble()) else null
                repo.io { repo.lib.setEdits(item.id, Edit.clean(recipe.copy(trim = trim))?.toJson()) }
                nav.back()
            }) { Text("Fine", color = Color(0xFFFFD60A), fontWeight = FontWeight.Bold) }
        }
        AndroidView({ c -> PlayerView(c).apply { this.player = player } }, Modifier.weight(1f).fillMaxWidth())
        Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
            Text("Taglio: ${Fmt.duration(range.start.toDouble())} – ${Fmt.duration(range.endInclusive.toDouble())}", color = Color.White)
            RangeSlider(range, { range = it }, valueRange = 0f..dur.toFloat())
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { recipe = recipe.copy(mute = !recipe.mute) }) {
                    Icon(if (recipe.mute) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, "Audio", tint = Color.White)
                }
                Text(if (recipe.mute) "Audio disattivato" else "Audio attivo", color = Color.White)
            }
        }
    }
}
