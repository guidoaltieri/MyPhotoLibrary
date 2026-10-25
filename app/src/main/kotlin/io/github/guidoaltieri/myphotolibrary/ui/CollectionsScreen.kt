package io.github.guidoaltieri.myphotolibrary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CameraFront
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MotionPhotosOn
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PortableWifiOff
import androidx.compose.material.icons.filled.RawOn
import androidx.compose.material.icons.filled.Screenshot
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoCameraFront
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.FaceRetouchingNatural
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.guidoaltieri.myphotolibrary.core.Album
import io.github.guidoaltieri.myphotolibrary.core.Library
import io.github.guidoaltieri.myphotolibrary.core.Person
import io.github.guidoaltieri.myphotolibrary.core.Source
import io.github.guidoaltieri.myphotolibrary.data.FaceThumb

fun viewIcon(key: String): ImageVector = when (key) {
    "favorites" -> Icons.Filled.Favorite
    "recents" -> Icons.Filled.History
    "videos" -> Icons.Filled.Videocam
    "selfies" -> Icons.Filled.CameraFront
    "live" -> Icons.Filled.MotionPhotosOn
    "portrait" -> Icons.Filled.FaceRetouchingNatural
    "panorama" -> Icons.Filled.Panorama
    "slomo" -> Icons.Filled.SlowMotionVideo
    "burst" -> Icons.Filled.BurstMode
    "animated" -> Icons.Filled.Animation
    "screenshots" -> Icons.Filled.Screenshot
    "screenrec" -> Icons.Filled.VideoCameraFront
    "raw" -> Icons.Filled.RawOn
    "hidden" -> Icons.Filled.VisibilityOff
    "deleted" -> Icons.Filled.Delete
    "edited" -> Icons.Filled.Edit
    "geotagged" -> Icons.Filled.Map
    else -> Icons.Filled.PhotoCamera
}

/**
 * Raccolte (la barra laterale di ArchPhotos): album, persone, luoghi, preferiti, tipi di media,
 * utilità e sorgenti.
 */
@Composable
fun CollectionsTab() {
    val repo = repo()
    val nav = nav()
    data class Data(val counts: Map<String, Int>, val albums: List<Album>, val persons: List<Person>, val sources: List<Source>,
                    val sourceCounts: Map<Long, Int>, val imports: Int)
    val d = load(initial = null as Data?) {
        Data(repo.lib.viewCounts(), repo.lib.albums(), repo.lib.persons().take(12), repo.lib.sources(), repo.lib.sourceCounts(),
            repo.lib.imports().size)
    } ?: return
    fun row(key: String, label: String = Library.VIEWS.getValue(key).label, count: Int = d.counts[key] ?: 0, onClick: () -> Unit = {
        nav.go(Screen.Grid(key, label))
    }): @Composable () -> Unit = {
        ListItem(headlineContent = { Text(label) }, leadingContent = { Icon(viewIcon(key), null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { if (count > 0) Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier = Modifier.clickable(onClick = onClick))
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SectionTitle("Album") { TextButton(onClick = { nav.go(Screen.Albums(null, "Album")) }) { Text("Vedi tutti") } }
            if (d.albums.isEmpty()) Text("Crea un album dal menu ⋮ o selezionando delle foto.", Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(d.albums, key = { it.id }) { a -> AlbumTile(a, Modifier.width(150.dp)) }
            }
        }
        item {
            SectionTitle("Persone") { TextButton(onClick = { nav.go(Screen.People) }) { Text("Vedi tutte") } }
            if (d.persons.isEmpty()) ListItem(headlineContent = { Text("Riconosci le persone") },
                supportingContent = { Text("I volti vengono analizzati sul telefono, nessuna foto viene inviata.") },
                leadingContent = { Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { nav.go(Screen.People) })
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(d.persons, key = { it.id }) { p -> PersonTile(p, Modifier.width(84.dp)) }
            }
        }
        item {
            SectionTitle("Luoghi")
            ListItem(headlineContent = { Text("Mappa") }, leadingContent = { Icon(Icons.Filled.Map, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { Text("${d.counts["geotagged"] ?: 0}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { nav.go(Screen.Places) })
        }
        item { SectionTitle("Raccolte") }
        item { row("favorites")() }
        item { row("recents")() }
        item { row("edited")() }
        item { SectionTitle("Tipi di media") }
        Library.MEDIA_TYPES.filter { (d.counts[it] ?: 0) > 0 || it == "videos" }.forEach { k -> item(key = "t$k") { row(k)() } }
        item { SectionTitle("Utilità") }
        item {
            ListItem(headlineContent = { Text("Importazioni") }, leadingContent = { Icon(Icons.Filled.Download, null, tint = MaterialTheme.colorScheme.primary) },
                trailingContent = { if (d.imports > 0) Text("${d.imports}") }, modifier = Modifier.clickable { nav.go(Screen.Imports) })
        }
        item {
            ListItem(headlineContent = { Text("Duplicati") }, leadingContent = { Icon(Icons.Filled.ContentCopy, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { nav.go(Screen.Duplicates) })
        }
        item {
            ListItem(headlineContent = { Text("Suggerimenti") }, supportingContent = { Text("Date e luoghi dai nomi di cartelle e file") },
                leadingContent = { Icon(Icons.Filled.Lightbulb, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { nav.go(Screen.Suggestions) })
        }
        item { row("hidden")() }
        item { row("deleted")() }
        item { SectionTitle("Sorgenti") { TextButton(onClick = { nav.go(Screen.Sources) }) { Text("Gestisci") } } }
        item {
            ListItem(headlineContent = { Text("iCloud Foto") }, supportingContent = { Text("Copia le foto di iCloud su un cloud collegato") },
                leadingContent = { Icon(Icons.Filled.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { nav.go(Screen.ICloud) })
        }
        d.sources.forEach { s ->
            item(key = "s${s.id}") {
                ListItem(headlineContent = { Text(s.name) },
                    supportingContent = { if (!s.enabled) Text("Scollegata") },
                    leadingContent = { Icon(if (s.kind == "device") Icons.Filled.Smartphone else if (s.enabled) Icons.Filled.Folder else Icons.Filled.PortableWifiOff,
                        null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Text("${d.sourceCounts[s.id] ?: 0}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    modifier = Modifier.clickable { nav.go(Screen.Grid("source:${s.id}", s.name)) })
            }
        }
        item { HorizontalDivider(Modifier.padding(top = 12.dp)) }
        item {
            ListItem(headlineContent = { Text("Strumenti avanzati") }, supportingContent = { Text("Album smart con criteri, ricerca avanzata") },
                leadingContent = { Icon(Icons.Filled.AutoAwesome, null) }, trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { nav.go(Screen.SmartEditor(null)) })
        }
    }
}

@Composable
fun AlbumTile(a: Album, modifier: Modifier = Modifier) {
    val nav = nav()
    Column(modifier.clickable {
        if (a.kind == "folder") nav.go(Screen.Albums(a.id, a.name)) else nav.go(Screen.Album(a.id))
    }) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).rounded()) {
            if (a.cover != null) ThumbImage(a.cover!!, Modifier.fillMaxSize())
            else Box(Modifier.fillMaxSize().clip(Rounded).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(if (a.kind == "folder") Icons.Filled.Folder else if (a.kind == "smart") Icons.Filled.AutoAwesome else Icons.Filled.PhotoAlbum,
                    null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (a.kind == "smart") Icon(Icons.Filled.AutoAwesome, null, tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
        }
        Text(a.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(if (a.kind == "folder") "${a.count} album" else "${a.count}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PersonTile(p: Person, modifier: Modifier = Modifier) {
    val nav = nav()
    val repo = repo()
    Column(modifier.clickable { nav.go(Screen.Person(p.id)) }, horizontalAlignment = Alignment.CenterHorizontally) {
        val face = p.face
        val item = load(face?.mediaId, initial = null as io.github.guidoaltieri.myphotolibrary.core.Item?) { face?.let { repo.lib.item(it.mediaId) } }
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (face != null && item != null) AsyncImage(FaceThumb(face, item), p.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Icon(Icons.Filled.Person, null, Modifier.align(Alignment.Center).size(40.dp))
        }
        Text(p.name.ifEmpty { "Senza nome" }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (p.name.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 4.dp))
    }
}

