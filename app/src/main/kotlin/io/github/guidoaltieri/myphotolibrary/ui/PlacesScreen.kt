package io.github.guidoaltieri.myphotolibrary.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.guidoaltieri.myphotolibrary.core.Fmt
import io.github.guidoaltieri.myphotolibrary.core.Item
import io.github.guidoaltieri.myphotolibrary.core.PlaceSummary
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.floor
import kotlin.math.pow

/** Luoghi: mappa con le foto raggruppate (OpenStreetMap) ed elenco dei luoghi con nome. */
@Composable
fun PlacesScreen() {
    val repo = repo()
    val nav = nav()
    var tab by remember { mutableIntStateOf(0) }
    val points = load(initial = null as List<Item>?) { repo.lib.geotagged() }
    val places = load(initial = emptyList<PlaceSummary>()) { repo.lib.placesSummary() }
    Scaffold(topBar = { Bar("Luoghi", points?.let { Fmt.count(it.size.toLong(), "elemento con posizione", "elementi con posizione") } ?: "", onBack = { nav.back() }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Mappa") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Elenco") })
            }
            if (tab == 0) {
                if (points != null) PhotoMap(points, Modifier.fillMaxSize()) { ids, title -> nav.go(Screen.Ids(ids, title)) }
            } else LazyColumn {
                items(places, key = { it.place }) { p ->
                    ListItem(headlineContent = { Text(p.place) }, supportingContent = { Text(Fmt.count(p.count.toLong(), "elemento", "elementi")) },
                        leadingContent = {
                            val cover = load(p.coverId, initial = null as Item?) { repo.lib.item(p.coverId) }
                            Box(Modifier.size(56.dp).rounded()) { cover?.let { ThumbImage(it, Modifier.fillMaxSize()) } }
                        },
                        modifier = Modifier.clickable { nav.go(Screen.Ids(p.ids, p.place)) })
                }
            }
        }
    }
}

/** Mappa OpenStreetMap con i punti raggruppati per zona secondo lo zoom. */
@Composable
fun PhotoMap(points: List<Item>, modifier: Modifier = Modifier, interactive: Boolean = true, onCluster: (List<Long>, String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(interactive)
            zoomController.setVisibility(org.osmdroid.views.CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 2.0
            isTilesScaledToDpi = true
        }
    }
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> map.onResume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> map.onPause()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        map.onResume()
        onDispose { lifecycle.removeObserver(obs); map.onPause(); map.onDetach() }
    }
    fun redraw() {
        map.overlays.clear()
        val zoom = map.zoomLevelDouble
        val cell = 360.0 / 2.0.pow(zoom) * (64.0 / 256.0)    // ~64 pixel per gruppo
        val groups = points.filter { it.lat != null }.groupBy { floor(it.lat!! / cell) to floor(it.lon!! / cell) }
        for (g in groups.values) {
            val lat = g.sumOf { it.lat!! } / g.size
            val lon = g.sumOf { it.lon!! } / g.size
            val m = Marker(map)
            m.position = GeoPoint(lat, lon)
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            m.icon = clusterIcon(ctx, g.size)
            m.title = g.firstOrNull { !it.place.isNullOrEmpty() }?.place ?: Fmt.coords(lat, lon)
            m.setOnMarkerClickListener { _, _ ->
                if (interactive) onCluster(g.map { it.id }, m.title ?: "")
                true
            }
            map.overlays.add(m)
        }
        map.invalidate()
    }
    AndroidView({ map }, modifier) {
        redraw()
    }
    DisposableEffect(points) {
        val withPos = points.filter { it.lat != null }
        if (withPos.isNotEmpty()) map.post {
            if (withPos.size == 1) {
                map.controller.setZoom(12.0); map.controller.setCenter(GeoPoint(withPos[0].lat!!, withPos[0].lon!!))
            } else {
                val box = BoundingBox(withPos.maxOf { it.lat!! }, withPos.maxOf { it.lon!! }, withPos.minOf { it.lat!! }, withPos.minOf { it.lon!! })
                try { map.zoomToBoundingBox(box.increaseByScale(1.3f), false) } catch (e: Exception) {}
            }
            redraw()
        }
        val l = object : MapListener {
            override fun onScroll(event: ScrollEvent?) = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                redraw(); return false
            }
        }
        map.addMapListener(l)
        onDispose { map.removeMapListener(l) }
    }
}

private fun clusterIcon(ctx: Context, n: Int): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val size = ((if (n > 99) 44 else if (n > 9) 38 else 32) * d).toInt()
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = 0xFFFFFFFF.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f, p)
    p.color = 0xFF007AFF.toInt()
    c.drawCircle(size / 2f, size / 2f, size / 2f - 2 * d, p)
    p.color = 0xFFFFFFFF.toInt()
    p.textSize = 13 * d
    p.textAlign = Paint.Align.CENTER
    p.isFakeBoldText = true
    c.drawText(if (n > 999) "${n / 1000}k" else "$n", size / 2f, size / 2f - (p.descent() + p.ascent()) / 2, p)
    return BitmapDrawable(ctx.resources, b)
}
