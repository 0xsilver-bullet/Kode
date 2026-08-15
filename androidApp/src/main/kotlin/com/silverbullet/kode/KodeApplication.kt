package com.silverbullet.kode

import android.app.Application
import com.silverbullet.kode.core.datastore.KODE_DATASTORE_FILE_NAME
import com.silverbullet.kode.core.datastore.di.DataStorePathQualifier
import com.silverbullet.kode.core.common.AppLifecycleMonitor
import com.silverbullet.kode.core.common.NetworkMonitor
import com.silverbullet.kode.di.initKoin
import com.silverbullet.kode.platform.AndroidAppLifecycleMonitor
import com.silverbullet.kode.platform.AndroidNetworkMonitor
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
            },
        )
    }
}
