package io.github.guidoaltieri.myphotolibrary

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import io.github.guidoaltieri.myphotolibrary.data.FaceKeyer
import io.github.guidoaltieri.myphotolibrary.data.FaceThumb
import io.github.guidoaltieri.myphotolibrary.data.Full
import io.github.guidoaltieri.myphotolibrary.data.FullKeyer
import io.github.guidoaltieri.myphotolibrary.data.MediaFetcher
import io.github.guidoaltieri.myphotolibrary.data.Repo
import io.github.guidoaltieri.myphotolibrary.data.Thumb
import io.github.guidoaltieri.myphotolibrary.data.ThumbKeyer
import org.osmdroid.config.Configuration

class App : Application(), SingletonImageLoader.Factory {
    lateinit var repo: Repo
        private set

    override fun onCreate() {
        super.onCreate()
        repo = Repo(this)
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = cacheDir.resolve("osmdroid")
            osmdroidTileCache = cacheDir.resolve("osmdroid/tiles")
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components {
            add(MediaFetcher.ThumbFactory(this@App), Thumb::class)
            add(MediaFetcher.FullFactory(this@App), Full::class)
            add(MediaFetcher.FaceFactory(this@App), FaceThumb::class)
            add(ThumbKeyer(), Thumb::class)
            add(FullKeyer(), Full::class)
            add(FaceKeyer(), FaceThumb::class)
        }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.3).build() }
        .build()
}
