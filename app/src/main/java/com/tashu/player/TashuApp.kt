package com.tashu.player

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.tashu.player.drive.scan.ScanWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TashuApp : Application(), Configuration.Provider, ImageLoaderFactory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    /**
     * Coil sait alors jouer les pochettes animées partout (grilles, lecteur, Cover Flow).
     * Les fichiers de pochettes sont gardés tels quels (pas de recompression), donc l'animation est conservée.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        ScanWorker.schedulePeriodic(this)
    }
}
