package com.silverbullet.kode.core.session.di

import com.silverbullet.kode.core.session.EnvironmentFleet
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Qualifier for the process-lifetime coroutine scope. Declared here rather than
 * in the composition root because features that share process-lifetime flows
 * (a single shell subscription, the fleet itself) resolve it too.
 */
val ApplicationScopeQualifier = named("kode.applicationScope")

/**
 * The fleet is a singleton: it owns every live session in the app, so it must
 * outlive any screen. Features observe it, they never create supervisors.
 */
val sessionModule = module {
    single {
        EnvironmentFleet(
            store = get(),
            authApi = get(),
            transport = get(),
            networkMonitor = get(),
            appLifecycleMonitor = get(),
        )
    }
}
