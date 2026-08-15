package com.silverbullet.kode.feature.connection.di

import com.silverbullet.kode.feature.connection.domain.PairEnvironmentUseCase
import com.silverbullet.kode.feature.connection.presentation.ConnectionViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Pairing and connection status. The supervisor itself lives in
 * `:core:session` because more than one feature subscribes through it.
 */
val connectionModule = module {
    single { PairEnvironmentUseCase(authApi = get(), environmentStore = get()) }
    viewModelOf(::ConnectionViewModel)
}
