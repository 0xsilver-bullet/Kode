package com.silverbullet.kode.feature.connection.di

import com.silverbullet.kode.feature.connection.domain.PairEnvironmentUseCase
import com.silverbullet.kode.core.common.QrCodeScanner
import com.silverbullet.kode.core.common.UnavailableQrCodeScanner
import com.silverbullet.kode.feature.connection.domain.UpdateEnvironmentUseCase
import com.silverbullet.kode.feature.connection.presentation.AddEnvironmentViewModel
import com.silverbullet.kode.feature.connection.presentation.EnvironmentsViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/**
 * Pairing, settings and environment management. The fleet itself lives in
 * `:core:session` because more than one feature subscribes through it.
 */
val connectionModule = module {
    single { PairEnvironmentUseCase(authApi = get(), environmentStore = get()) }
    single { UpdateEnvironmentUseCase(environmentStore = get()) }

    // Platforms with a camera override this binding from their platform module.
    single<QrCodeScanner> { UnavailableQrCodeScanner() }

    viewModelOf(::AddEnvironmentViewModel)
    viewModelOf(::EnvironmentsViewModel)
}
