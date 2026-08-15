package com.silverbullet.kode.feature.threads.di

import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.common.SystemTimeProvider
import com.silverbullet.kode.core.common.TimeProvider
import com.silverbullet.kode.core.common.UuidIdGenerator
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import com.silverbullet.kode.feature.threads.presentation.ThreadDetailViewModel
import com.silverbullet.kode.feature.threads.presentation.ThreadListViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val threadsModule = module {
    single<IdGenerator> { UuidIdGenerator() }
    single<TimeProvider> { SystemTimeProvider() }

    single {
        ThreadsRepository(
            supervisor = get(),
            idGenerator = get(),
            timeProvider = get(),
        )
    }

    viewModelOf(::ThreadListViewModel)

    // The thread id comes from navigation, so it is a runtime parameter rather
    // than a resolved dependency.
    viewModel { (threadId: ThreadId) ->
        ThreadDetailViewModel(threadId = threadId, repository = get(), dispatchers = get())
    }
}
