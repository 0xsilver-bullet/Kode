package com.silverbullet.kode.feature.voice.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.datastore.VoiceBindingStore
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.feature.voice.domain.bindingFor
import com.silverbullet.kode.feature.voice.presentation.VoicePromptViewModel
import com.silverbullet.kode.voice.contract.VoiceThreadMessage
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * The composer-side entry point, deliberately typed with primitives and lambdas only:
 * `feature:threads` renders it through a slot without depending on this module (the
 * adapter lives in `sharedUI`, which sees both).
 *
 * The mic button appears only when the thread's environment has a voice server bound;
 * an unbound environment simply has no voice affordance rather than a dead control.
 */
@Composable
fun VoicePromptEntry(
    environmentId: EnvironmentId,
    threadKey: String,
    projectDir: String?,
    recentMessages: () -> List<VoiceThreadMessage>,
    sendPrompt: suspend (String) -> Result<Unit>,
) {
    val bindingStore = koinInject<VoiceBindingStore>()
    val binding by remember(environmentId) {
        bindingStore.bindingFor(environmentId)
    }.collectAsStateWithLifecycle(initialValue = null)

    val bound = binding ?: return

    var dialogOpen by remember { mutableStateOf(false) }
    VoiceMicButton(enabled = true) { dialogOpen = true }

    if (dialogOpen) {
        val viewModel = koinViewModel<VoicePromptViewModel>(
            key = "voice-prompt-${bound.environmentId.value}-$threadKey",
        ) { parametersOf(environmentId, projectDir, recentMessages, sendPrompt) }

        VoicePromptDialog(
            viewModel = viewModel,
            onDismissed = { dialogOpen = false },
        )
    }
}
