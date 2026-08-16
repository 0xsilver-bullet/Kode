package com.silverbullet.kode.feature.threads.di

import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.common.SystemTimeProvider
import com.silverbullet.kode.core.common.TimeProvider
import com.silverbullet.kode.core.common.UuidIdGenerator
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.core.session.di.ApplicationScopeQualifier
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import com.silverbullet.kode.feature.threads.presentation.ThreadDetailViewModel
import com.silverbullet.kode.feature.threads.presentation.NewThreadViewModel
import com.silverbullet.kode.feature.threads.presentation.ThreadListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val threadsModule = module {
    single<IdGenerator> { UuidIdGenerator() }
    single<TimeProvider> { SystemTimeProvider() }

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

    viewModelOf(::ThreadListViewModel)
    viewModelOf(::NewThreadViewModel)

    // The environment and thread ids come from navigation, so they are runtime
    // parameters rather than resolved dependencies.
    viewModel { (environmentId: EnvironmentId, threadId: ThreadId) ->
        ThreadDetailViewModel(
            environmentId = environmentId,
            threadId = threadId,
            repository = get(),
            dispatchers = get(),
        )
    }
}
