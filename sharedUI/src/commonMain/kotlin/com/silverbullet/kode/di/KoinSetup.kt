package com.silverbullet.kode.di

import com.silverbullet.kode.core.common.AlwaysOnlineNetworkMonitor
import com.silverbullet.kode.core.common.AppLifecycleMonitor
import com.silverbullet.kode.core.common.DefaultDispatcherProvider
import com.silverbullet.kode.core.common.DispatcherProvider
import com.silverbullet.kode.core.common.NetworkMonitor
import com.silverbullet.kode.core.common.NoOpAppLifecycleMonitor
import com.silverbullet.kode.core.datastore.di.dataStoreModule
import com.silverbullet.kode.core.network.di.networkModule
import com.silverbullet.kode.core.session.EnvironmentFleet
import com.silverbullet.kode.core.session.di.ApplicationScopeQualifier
import com.silverbullet.kode.core.session.di.sessionModule
import com.silverbullet.kode.feature.connection.di.connectionModule
import com.silverbullet.kode.feature.threads.di.threadsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module
import org.koin.dsl.module

private val appModule = module {
    single<DispatcherProvider> { DefaultDispatcherProvider() }
    // Overridden by the platform module where a real implementation exists;
    // these keep the graph resolvable on hosts that have none yet.
    single<NetworkMonitor> { AlwaysOnlineNetworkMonitor() }
    single<AppLifecycleMonitor> { NoOpAppLifecycleMonitor() }
    single(ApplicationScopeQualifier) {
        // SupervisorJob so one failed long-running task cannot take down the
        // rest of the app's background work.
        CoroutineScope(SupervisorJob() + get<DispatcherProvider>().default)
    }
}

/**
 * Starts DI and the environment fleet.
 *
 * [platformModule] carries the few bindings only a platform can provide — the
 * DataStore path, monitors, the QR scanner. Everything else is shared.
 *
 * The fleet is started here rather than from a screen because it owns every
 * session in the app: it must survive navigation and configuration changes.
 */
fun initKoin(platformModule: Module): KoinApplication {
    val application = startKoin {
        // The platform module is applied last and may replace the defaults above.
        allowOverride(true)
        modules(
            appModule,
            networkModule,
            dataStoreModule,
            sessionModule,
            connectionModule,
            threadsModule,
            platformModule,
        )
    }

    val koin = application.koin
    koin.get<EnvironmentFleet>().start(koin.get(ApplicationScopeQualifier))

    return application
}
