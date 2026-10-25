package io.github.guidoaltieri.myphotolibrary.data

import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import io.github.guidoaltieri.myphotolibrary.core.Face
import io.github.guidoaltieri.myphotolibrary.core.Item
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException

/** Miniatura di un elemento (con le modifiche applicate, se ce ne sono). */
data class Thumb(val item: Item, val size: Int = 256) {
    companion object {
        /** Dimensione della miniatura per una cella di [cellPx] pixel fisici. */
        fun sizeFor(cellPx: Int) = if (cellPx <= 320) 256 else 512
    }
}

/** Immagine per il visore con lato lungo ≤ [maxSide] (0 = piena risoluzione), modifiche comprese. */
data class Full(val item: Item, val maxSide: Int, val applyEdits: Boolean = true)

/** Volto ritagliato (copertine delle persone). */
data class FaceThumb(val face: Face, val item: Item, val size: Int = 256)

/** Recupero delle immagini per Coil: niente rete, solo file del dispositivo. */
class MediaFetcher(private val ctx: Context, private val load: () -> Bitmap?) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val b = limiter.withPermit { withContext(Dispatchers.IO) { load() } } ?: throw IOException("Immagine non leggibile")
        return ImageFetchResult(image = b.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    companion object {
        // al massimo 4 decodifiche alla volta: scorrendo veloce non si accumula lavoro inutile
        val limiter = Semaphore(4)
    }

    class ThumbFactory(private val ctx: Context) : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher = MediaFetcher(ctx) {
            val b = Images.thumbnail(ctx, data.item, data.size)
            if (b != null && !data.item.edits.isNullOrEmpty()) Images.render(b, data.item.edits) else b
        }
    }

    class FullFactory(private val ctx: Context) : Fetcher.Factory<Full> {
        override fun create(data: Full, options: Options, imageLoader: ImageLoader): Fetcher = MediaFetcher(ctx) {
            val b = Images.open(ctx, data.item, data.maxSide)
            if (b != null && data.applyEdits && !data.item.edits.isNullOrEmpty()) Images.render(b, data.item.edits) else b
        }
    }

    class FaceFactory(private val ctx: Context) : Fetcher.Factory<FaceThumb> {
        override fun create(data: FaceThumb, options: Options, imageLoader: ImageLoader): Fetcher = MediaFetcher(ctx) {
            Images.faceCrop(ctx, data.item, data.face.x, data.face.y, data.face.w, data.face.h, data.size)
        }
    }
}

class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options) = "t:${data.item.path}:${data.size}:${data.item.mtime}:${data.item.edits?.hashCode()}"
}

class FullKeyer : Keyer<Full> {
    override fun key(data: Full, options: Options) =
        "f:${data.item.path}:${data.maxSide}:${data.item.mtime}:${if (data.applyEdits) data.item.edits?.hashCode() else 0}"
}

class FaceKeyer : Keyer<FaceThumb> {
    override fun key(data: FaceThumb, options: Options) = "v:${data.face.id}:${data.size}"
}
