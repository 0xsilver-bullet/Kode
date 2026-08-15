package com.silverbullet.kode

import android.app.Application
import com.silverbullet.kode.core.datastore.KODE_DATASTORE_FILE_NAME
import com.silverbullet.kode.core.datastore.di.DataStorePathQualifier
import com.silverbullet.kode.di.initKoin
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
            },
        )
    }
}
