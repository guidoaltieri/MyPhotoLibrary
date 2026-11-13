package io.github.guidoaltieri.myphotolibrary.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn as AndroidOptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import io.github.guidoaltieri.myphotolibrary.core.Edit
import io.github.guidoaltieri.myphotolibrary.core.Face
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.ItemDetail
import io.github.guidoaltieri.myphotolibrary.data.Full
import io.github.guidoaltieri.myphotolibrary.data.Images
import io.github.guidoaltieri.myphotolibrary.data.Thumb

/**
 * Visore: scorrimento tra le foto, zoom fino all'800 % con caricamento ad alta risoluzione, video,
 * Live Photo, pannello Informazioni e tutte le azioni sull'elemento.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(initialIds: List<Long>, index: Int, view: String) {
    val repo = repo()
    val nav = nav()
    val ids = remember { mutableStateListOf<Long>().apply { addAll(initialIds) } }
    val pager = rememberPagerState(initialPage = index.coerceIn(0, maxOf(0, initialIds.size - 1))) { ids.size }
    var chrome by remember { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val actions = rememberActions()
    BackHandler { nav.back() }
    if (ids.isEmpty()) {
        LaunchedEffect(Unit) { nav.back() }
        return
    }
    val currentId = ids.getOrNull(pager.currentPage) ?: ids.last()
    val item = load(currentId, initial = null as Item?) { repo.lib.item(currentId) }
    Box(Modifier.fillMaxSize().background(if (chrome) MaterialTheme.colorScheme.background else Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { ids.getOrNull(it) ?: it }) { page ->
            val id = ids.getOrNull(page) ?: return@HorizontalPager
            val it = load(id, initial = null as Item?) { repo.lib.item(id) }
            if (it != null) {
                if (it.isVideo) VideoPage(it, active = page == pager.currentPage, onTap = { chrome = !chrome })
                else PhotoPage(it, active = page == pager.currentPage, onTap = { chrome = !chrome })
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                Column(Modifier.weight(1f)) {
                    item?.let {
                        Text(Fmt.relativeDay(it.taken), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(Fmt.time(it.taken), it.place?.takeIf { p -> p.isNotEmpty() }).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (item != null && item.isLive) LivePlayButton(item)
                if (item != null && view != "deleted") IconButton(onClick = { nav.go(Screen.Editor(item.id)) }) { Icon(Icons.Filled.Edit, "Modifica") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Altro") }
                    DropdownMenu(menu, { menu = false }) {
                        val one = listOf(currentId)
                        DropdownMenuItem({ Text("Aggiungi ad album…") }, { menu = false; actions.open("album", one) }, leadingIcon = { Icon(Icons.Filled.AddPhotoAlternate, null) })
                        DropdownMenuItem({ Text("Modifica data e ora…") }, { menu = false; actions.open("date", one) }, leadingIcon = { Icon(Icons.Filled.DateRange, null) })
                        DropdownMenuItem({ Text("Modifica luogo…") }, { menu = false; actions.open("location", one) }, leadingIcon = { Icon(Icons.Filled.Place, null) })
                        DropdownMenuItem({ Text("Parole chiave…") }, { menu = false; actions.open("keywords", one) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, null) })
                        if (item?.isVideo == false) DropdownMenuItem({ Text("Ruota") }, {
                            menu = false
                            item?.let { cur -> repo.io {
                                val r = Edit.load(cur.edits)
                                repo.lib.setEdits(cur.id, Edit.clean(r.copy(rotate = (r.rotate + 270) % 360))?.toJson())
                            } }
                        }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.RotateLeft, null) })
                        DropdownMenuItem({ Text("Esporta…") }, { menu = false; actions.open("export", one) }, leadingIcon = { Icon(Icons.Filled.FileDownload, null) })
                        DropdownMenuItem({ Text("Copia") }, { menu = false; item?.let { cur -> repo.io { repo.exporter.copyToClipboard(cur); repo.say("Copiata negli appunti") } } },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, null) })
                        if (item?.isVideo == false) DropdownMenuItem({ Text("Usa come sfondo…") }, { menu = false; item?.let { cur -> repo.io { repo.launch(repo.exporter.useAsIntent(cur)) } } },
                            leadingIcon = { Icon(Icons.Filled.Wallpaper, null) })
                        DropdownMenuItem({ Text("Apri con…") }, { menu = false; item?.let { repo.launch(repo.exporter.openWithIntent(it)) } },
                            leadingIcon = { Icon(Icons.Filled.OpenInNew, null) })
                        if (item?.hidden == true) DropdownMenuItem({ Text("Mostra") }, { menu = false; repo.io { repo.lib.setHidden(one, false) } }, leadingIcon = { Icon(Icons.Filled.Visibility, null) })
                        else DropdownMenuItem({ Text("Nascondi") }, { menu = false; repo.io { repo.lib.setHidden(one, true) }; ids.remove(currentId) },
                            leadingIcon = { Icon(Icons.Filled.VisibilityOff, null) })
                        if (view.startsWith("album:")) DropdownMenuItem({ Text("Usa come copertina dell'album") }, {
                            menu = false; repo.io { repo.lib.setAlbumCover(view.substringAfter(":").toLong(), currentId) }
                        })
                    }
                }
            }
        }
        AnimatedVisibility(chrome, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f)).navigationBarsPadding().padding(4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly) {
                if (item?.deleted != null) {
                    TextButton(onClick = { repo.io { repo.lib.restore(listOf(currentId)) }; ids.remove(currentId) }) {
                        Icon(Icons.Filled.RestoreFromTrash, null); Text(" Ripristina")
                    }
                    TextButton(onClick = { actions.open("purge", listOf(currentId)) }) { Icon(Icons.Filled.DeleteForever, null); Text(" Elimina") }
                } else {
                    IconButton(onClick = { share(repo, listOf(currentId)) }) { Icon(Icons.Filled.Share, "Condividi") }
                    IconButton(onClick = { repo.io { repo.lib.toggleFavorite(listOf(currentId)) } }) {
                        Icon(if (item?.favorite == true) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Preferito",
                            tint = if (item?.favorite == true) Color(0xFFFF375F) else MaterialTheme.colorScheme.onSurface)
                    }
                    IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, "Informazioni") }
                    IconButton(onClick = { actions.open("delete", listOf(currentId)) }) { Icon(Icons.Filled.Delete, "Elimina") }
                }
            }
        }
    }
    if (info && item != null) {
        ModalBottomSheet(onDismissRequest = { info = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
            InfoPanel(item.id, actions)
        }
    }
    // dopo l'eliminazione l'elemento sparisce dal visore
    ActionDialogs(actions) {
        repo.io {
            val still = repo.lib.item(currentId)
            if (still == null || (view != "deleted" && still.deleted != null) || (view == "deleted" && still.deleted == null))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ids.remove(currentId) }
        }
    }
}

/** Foto con zoom (pizzico, doppio tocco) e caricamento a risoluzione maggiore quando si ingrandisce. */
@Composable
private fun PhotoPage(item: Item, active: Boolean, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    fun clamp(o: Offset, s: Float): Offset {
        val mx = size.width * (s - 1) / 2f
        val my = size.height * (s - 1) / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }
    Box(
        Modifier.fillMaxSize().onSizeChanged { size = it }
            .pointerInput(item.id) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val c = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, 8f)
                            // lo zoom resta centrato sul punto tra le dita
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val k = newScale / scale
                            offset = clamp((offset + pan) * 1f + (c - center - offset) * (1 - k), newScale)
                            scale = newScale
                            if (scale <= 1.01f) offset = Offset.Zero
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(item.id) {
                detectTapGestures(onTap = { onTap() }, onDoubleTap = { p ->
                    if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else {
                        scale = 2.5f
                        offset = clamp((Offset(size.width / 2f, size.height / 2f) - p) * 1.5f, 2.5f)
                    }
                })
            }
    ) {
        val layer = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
        AsyncImage(model = Thumb(item), contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
        AsyncImage(model = Full(item, 2048), contentDescription = item.name, contentScale = ContentScale.Fit, modifier = layer)
        if (scale > 1.6f) AsyncImage(model = Full(item, 4096), contentDescription = null, contentScale = ContentScale.Fit, modifier = layer)
    }
}

@AndroidOptIn(UnstableApi::class)
@Composable
private fun VideoPage(item: Item, active: Boolean, onTap: () -> Unit) {
    val ctx = LocalContext.current
    if (!active) {
        Box(Modifier.fillMaxSize().clickable { onTap() }) {
            AsyncImage(Thumb(item, 1024), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        return
    }
    val recipe = remember(item.edits) { Edit.load(item.edits) }
    val player = remember(item.id) {
        ExoPlayer.Builder(ctx).build().apply {
            val mi = MediaItem.Builder().setUri(Uri.parse(item.path)).apply {
                recipe.trim?.takeIf { it.size == 2 }?.let { t ->
                    setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs((t[0] * 1000).toLong()).setEndPositionMs((t[1] * 1000).toLong()).build())
                }
            }.build()
            setMediaItem(mi)
            volume = if (recipe.mute) 0f else 1f
            repeatMode = Player.REPEAT_MODE_OFF
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { c -> PlayerView(c).apply { this.player = player; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
        modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) })
}

/** Tasto «LIVE»: riproduce il video della Live Photo o della Motion Photo. */
@AndroidOptIn(UnstableApi::class)
@Composable
private fun LivePlayButton(item: Item) {
    val ctx = LocalContext.current
    val repo = repo()
    var uri by remember(item.id) { mutableStateOf<Uri?>(null) }
    TextButton(onClick = { repo.scope.launch { uri = Images.liveVideo(ctx, item) ?: run { repo.say("Video della Live Photo non disponibile"); null } } }) {
        Text("LIVE", fontWeight = FontWeight.Bold)
    }
    uri?.let { u ->
        androidx.compose.ui.window.Dialog(onDismissRequest = { uri = null },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            val player = remember(u) {
                ExoPlayer.Builder(ctx).build().apply {
                    setMediaItem(MediaItem.fromUri(u)); prepare(); playWhenReady = true
                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) uri = null
                        }
                    })
                }
            }
            DisposableEffect(player) { onDispose { player.release() } }
            AndroidView({ c -> PlayerView(c).apply { this.player = player; useController = false } },
                Modifier.fillMaxSize().background(Color.Black).clickable { uri = null })
        }
    }
}

/**
 * Pannello Informazioni: didascalia, data (modificabile), fotocamera, obiettivo, dati di scatto,
 * mappa, luogo (modificabile), persone, parole chiave, album e tutti i metadati EXIF.
 */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
fun InfoPanel(id: Long, actions: Actions) {
    val repo = repo()
    val nav = nav()
    val d = load(id, initial = null as ItemDetail?) { repo.lib.detail(id) } ?: return
    val faces = load(id, initial = emptyList<Face>()) { repo.lib.facesOf(id) }
    val names = load(initial = emptyList<String>()) { repo.lib.personNames() }
    var caption by remember(d.item.id) { mutableStateOf(d.s("caption") ?: "") }
    var naming by remember { mutableStateOf<Face?>(null) }
    var showExif by remember { mutableStateOf(false) }
    val it = d.item
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(caption, { caption = it }, placeholder = { Text("Aggiungi una didascalia") }, modifier = Modifier.fillMaxWidth(),
            trailingIcon = { if (caption != (d.s("caption") ?: "")) TextButton(onClick = { repo.io { repo.lib.setCaption(id, caption) } }) { Text("Salva") } })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.datetime(it.taken).replaceFirstChar { c -> c.uppercase() }, fontWeight = FontWeight.SemiBold)
                if (d.i("taken_guess") == 1) Text("Data stimata (dal nome o dalla modifica del file)", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { actions.open("date", listOf(id)) }) { Text("Modifica") }
        }
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp)) {
            val camera = listOfNotNull(d.s("make"), d.s("model")).joinToString(" ").ifEmpty { if (it.isVideo) "Video" else "Fotocamera sconosciuta" }
            Text(camera, fontWeight = FontWeight.SemiBold)
            d.s("lens")?.let { l -> Text(l, style = MaterialTheme.typography.bodySmall) }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text(listOf(it.name, Fmt.megapixels(it.width, it.height), "${it.width} × ${it.height}", Fmt.size(it.filesize))
                .filter { s -> s.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            val shot = listOf(d.i("iso")?.let { v -> "ISO $v" } ?: "", Fmt.focal(d.d("focal35") ?: d.d("focal")), Fmt.aperture(d.d("fnumber")),
                Fmt.exposure(d.d("exposure")), if (it.isVideo) Fmt.duration(it.duration) else "", d.d("fps")?.let { f -> "${Fmt.trimNum(f)} fps" } ?: "")
                .filter { s -> s.isNotEmpty() }
            if (shot.isNotEmpty()) Text(shot.joinToString("   "), style = MaterialTheme.typography.bodySmall)
            if (it.edits != null) Text("Modificata: ${Edit.describe(Edit.load(it.edits))}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            Text("${d.sourceName} · ${it.relpath}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (it.lat != null && it.lon != null) {
            Box(Modifier.fillMaxWidth().height(180.dp).rounded().clickable { nav.go(Screen.Places) }) {
                PhotoMap(listOf(it), Modifier.fillMaxSize(), interactive = false)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(it.place?.takeIf { p -> p.isNotEmpty() } ?: if (it.lat != null) Fmt.coords(it.lat, it.lon) else "Nessuna posizione")
                if (it.lat != null && !it.place.isNullOrEmpty()) Text(Fmt.coords(it.lat, it.lon), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { actions.open("location", listOf(id)) }) { Text(if (it.lat == null) "Aggiungi" else "Modifica") }
        }
        if (faces.isNotEmpty()) {
            Text("Persone", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                faces.forEach { f ->
                    AssistChip({ naming = f }, { Text(f.name.ifEmpty { "Dai un nome…" }) },
                        leadingIcon = { AsyncImage(io.github.guidoaltieri.myphotolibrary.data.FaceThumb(f, it, 96), null,
                            contentScale = ContentScale.Crop, modifier = Modifier.size(24.dp).rounded()) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Parole chiave", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { actions.open("keywords", listOf(id)) }) { Text("Modifica") }
        }
        if (d.keywords.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            d.keywords.forEach { k -> AssistChip({ nav.go(Screen.Grid("library", k, "keyword:\"$k\"")) }, { Text(k) }) }
        }
        if (d.albums.isNotEmpty()) {
            Text("Album", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                d.albums.forEach { (aid, name) -> AssistChip({ nav.go(Screen.Album(aid)) }, { Text(name) }) }
            }
        }
        if (d.exif.isNotEmpty()) {
            TextButton(onClick = { showExif = !showExif }) { Text(if (showExif) "Nascondi i metadati" else "Mostra tutti i metadati") }
            if (showExif) d.exif.forEach { (k, v) ->
                Row {
                    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text(v, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.2f))
                }
            }
        }
    }
    naming?.let { f -> NameDialog(f.name, names, { naming = null }) { n -> repo.io { repo.lib.assignFace(f.id, n) } } }
}
