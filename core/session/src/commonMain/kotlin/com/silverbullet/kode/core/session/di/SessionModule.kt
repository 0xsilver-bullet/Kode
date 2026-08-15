package com.silverbullet.kode.core.session.di

import com.silverbullet.kode.core.session.EnvironmentSupervisor
import org.koin.dsl.module

/**
 * The supervisor is a singleton: it owns the one live session for the app, so
 * it must outlive any screen. Features observe it, they never create one.
 */
val sessionModule = module {
    single {
        EnvironmentSupervisor(
            authApi = get(),
            transport = get(),
            environmentStore = get(),
        )
    }
}
