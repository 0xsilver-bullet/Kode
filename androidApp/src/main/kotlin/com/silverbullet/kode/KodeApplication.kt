package com.silverbullet.kode

import android.app.Application
import com.silverbullet.kode.core.datastore.KODE_DATASTORE_FILE_NAME
import com.silverbullet.kode.core.datastore.di.DataStorePathQualifier
import com.silverbullet.kode.core.common.AppLifecycleMonitor
import com.silverbullet.kode.core.common.NetworkMonitor
import com.silverbullet.kode.di.initKoin
import com.silverbullet.kode.core.common.ImagePicker
import com.silverbullet.kode.core.common.QrCodeScanner
import com.silverbullet.kode.feature.voice.domain.AudioRecorder
import com.silverbullet.kode.feature.voice.domain.MicPermission
import com.silverbullet.kode.platform.AndroidAppLifecycleMonitor
import com.silverbullet.kode.platform.AndroidAudioRecorder
import com.silverbullet.kode.platform.AndroidImagePicker
import com.silverbullet.kode.platform.AndroidMicPermission
import com.silverbullet.kode.platform.AndroidNetworkMonitor
import com.silverbullet.kode.platform.AndroidQrCodeScanner
import org.koin.dsl.module

class KodeApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // The only Android-specific binding today. Everything else the app
        // needs is resolved from shared modules.
        initKoin(
            platformModule = module {
                single(DataStorePathQualifier) {
                    filesDir.resolve(KODE_DATASTORE_FILE_NAME).absolutePath
                }
                single<NetworkMonitor> { AndroidNetworkMonitor(applicationContext) }
                single<AppLifecycleMonitor> { AndroidAppLifecycleMonitor() }
                single<QrCodeScanner> { AndroidQrCodeScanner(applicationContext) }
                single<AudioRecorder> { AndroidAudioRecorder() }
                // A singleton so MainActivity can attach its result launcher; the
                // voice feature resolves the same instance through the interface.
                single { AndroidMicPermission(applicationContext) }
                single<MicPermission> { get<AndroidMicPermission>() }
                // Same shape as the mic permission: a singleton MainActivity
                // attaches its result launcher to, resolved as the interface.
                single { AndroidImagePicker(applicationContext) }
                single<ImagePicker> { get<AndroidImagePicker>() }
            },
        )
    }
}
