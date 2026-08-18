package com.silverbullet.kode.feature.threads.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.ThreadId

/**
 * Everything the voice prompt feature needs from a thread, expressed in primitives so
 * `:feature:threads` and `:feature:voice` stay independent — `sharedUI`, which depends on
 * both, adapts this context into the voice entry composable and injects it through
 * [VoiceComposerSlot].
 */
@Stable
class VoiceComposerContext(
    val environmentId: EnvironmentId,
    val threadId: ThreadId,
    val projectDir: String?,
    /** Newest turns as (role, text), oldest first; read at refine time. */
    val recentMessages: () -> List<Pair<String, String>>,
    /**
     * Platform preview handles for the images staged in the composer.
     *
     * The voice feature only *shows* these — it never picks or removes. Images
     * are attached in the composer before recording, and [sendPrompt] carries
     * whatever is staged when it runs, which is what lets "attach, then talk"
     * work without the dialog growing a picker of its own.
     */
    val attachmentPreviews: List<String>,
    /** Dispatches an accepted voice prompt, plus any staged images, as a turn. */
    val sendPrompt: suspend (String) -> Result<Unit>,
)

typealias VoiceComposerSlot = @Composable (VoiceComposerContext) -> Unit
