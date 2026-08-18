package com.silverbullet.kode.feature.voice.di

import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.feature.voice.domain.AudioRecorder
import com.silverbullet.kode.feature.voice.domain.DeniedMicPermission
import com.silverbullet.kode.feature.voice.domain.KtorVoiceServerApi
import com.silverbullet.kode.feature.voice.domain.MicPermission
import com.silverbullet.kode.feature.voice.domain.PairVoiceServerUseCase
import com.silverbullet.kode.feature.voice.domain.UnavailableAudioRecorder
import com.silverbullet.kode.feature.voice.domain.VoiceServerApi
import com.silverbullet.kode.feature.voice.presentation.VoicePromptViewModel
import com.silverbullet.kode.feature.voice.presentation.VoiceSettingsViewModel
import com.silverbullet.kode.voice.contract.VoiceThreadMessage
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val voiceModule = module {
    single<VoiceServerApi> { KtorVoiceServerApi(httpClient = get()) }
    single { PairVoiceServerUseCase(api = get(), store = get()) }

    // Overridden by the platform module where real capture exists (see
    // `KodeApplication`); these keep the graph resolvable on hosts without it.
    single<AudioRecorder> { UnavailableAudioRecorder() }
    single<MicPermission> { DeniedMicPermission() }

    viewModelOf(::VoiceSettingsViewModel)

    // The prompt's identity and thread hooks come from the hosting screen at runtime.
    viewModel {
            (
                environmentId: EnvironmentId,
                projectDir: String?,
                recentMessages: () -> List<VoiceThreadMessage>,
                sendPrompt: suspend (String) -> Result<Unit>,
            ),
        ->
        VoicePromptViewModel(
            environmentId = environmentId,
            projectDir = projectDir,
            recentMessages = recentMessages,
            sendPrompt = sendPrompt,
            bindingStore = get(),
            settingsStore = get(),
            api = get(),
            recorder = get(),
            permission = get(),
        )
    }
}
