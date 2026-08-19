package com.silverbullet.kode.feature.threads.di

import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.common.ImagePicker
import com.silverbullet.kode.core.common.UnavailableImagePicker
import com.silverbullet.kode.core.common.SystemTimeProvider
import com.silverbullet.kode.core.common.TimeProvider
import com.silverbullet.kode.core.common.UuidIdGenerator
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.session.di.ApplicationScopeQualifier
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import com.silverbullet.kode.feature.threads.domain.git.GitRepository
import com.silverbullet.kode.feature.threads.presentation.ReviewViewModel
import com.silverbullet.kode.feature.threads.presentation.ThreadDetailViewModel
import com.silverbullet.kode.feature.threads.presentation.NewThreadViewModel
import com.silverbullet.kode.feature.threads.presentation.ThreadListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val threadsModule = module {
    single<IdGenerator> { UuidIdGenerator() }
    single<TimeProvider> { SystemTimeProvider() }

    // Platforms with a gallery override this binding from their platform module.
    single<ImagePicker> { UnavailableImagePicker() }

    single {
        ThreadsRepository(
            fleet = get(),
            idGenerator = get(),
            timeProvider = get(),
            // The shared shell subscriptions outlive any one screen, so they
            // are hosted in the process-lifetime scope.
            appScope = get(ApplicationScopeQualifier),
        )
    }

    single { GitRepository(fleet = get(), idGenerator = get()) }

    viewModelOf(::ThreadListViewModel)
    viewModelOf(::NewThreadViewModel)

    // The environment and thread ids come from navigation, so they are runtime
    // parameters rather than resolved dependencies.
    viewModel { (environmentId: EnvironmentId, threadId: ThreadId) ->
        ThreadDetailViewModel(
            environmentId = environmentId,
            threadId = threadId,
            repository = get(),
            gitRepository = get(),
            imagePicker = get(),
            idGenerator = get(),
            dispatchers = get(),
        )
    }

    viewModel { (environmentId: EnvironmentId, threadId: ThreadId) ->
        ReviewViewModel(
            environmentId = environmentId,
            threadId = threadId,
            repository = get(),
            gitRepository = get(),
            dispatchers = get(),
        )
    }
}
