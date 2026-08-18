package com.silverbullet.kode.feature.voice.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.feature.voice.domain.Transcript
import com.silverbullet.kode.feature.voice.presentation.VoicePromptUiState
import com.silverbullet.kode.feature.voice.presentation.VoicePromptViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * The voice prompt overlay.
 *
 * Spec-driven shape: the transcript owns most of the surface and grows while the user
 * talks; the voice-reactive indicator sits bottom right; the config affordance
 * (refinement toggle) is reachable from the bottom edge; and the dialog is never
 * dismissed implicitly — tapping outside or back does nothing, every exit is a button.
 */
@Composable
fun VoicePromptDialog(
    viewModel: VoicePromptViewModel,
    onDismissed: () -> Unit,
    attachmentPreviews: List<String> = emptyList(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.begin() }
    LaunchedEffect(state) {
        if (state == VoicePromptUiState.Dismissed) {
            viewModel.reset()
            onDismissed()
        }
    }

    Dialog(
        onDismissRequest = { /* explicit actions only — never auto-dismiss */ },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                Header(state)

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when (val current = state) {
                        is VoicePromptUiState.Idle,
                        VoicePromptUiState.Connecting,
                        -> CenteredStatus("Connecting to the voice server…")

                        VoicePromptUiState.PermissionDenied -> CenteredMessage(
                            title = "Microphone access is required",
                            detail = "Grant the permission and try again.",
                        )

                        is VoicePromptUiState.Recording -> TranscriptPane(current.transcript)

                        is VoicePromptUiState.Finalizing -> TranscriptPane(current.transcript)

                        is VoicePromptUiState.Refining -> Column {
                            TranscriptText(current.transcript, muted = true)
                            Spacer(Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text(
                                    "Refining…",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = KodeTheme.colors.muted,
                                )
                            }
                        }

                        is VoicePromptUiState.Review -> ReviewPane(
                            state = current,
                            attachmentPreviews = attachmentPreviews,
                        )

                        is VoicePromptUiState.Editing -> EditPane(
                            state = current,
                            attachmentPreviews = attachmentPreviews,
                            onDraftChanged = viewModel::onDraftChanged,
                        )

                        is VoicePromptUiState.Failed -> CenteredMessage(
                            title = "Something went wrong",
                            detail = current.message,
                        )

                        VoicePromptUiState.Dismissed -> Unit
                    }

                    // The reactive indicator, docked bottom-right while the mic is live.
                    if (state.isLive) {
                        VoiceIndicator(
                            amplitude = viewModel.amplitude,
                            active = state is VoicePromptUiState.Recording,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                BottomBar(viewModel, state)
            }
        }
    }
}

@Composable
private fun Header(state: VoicePromptUiState) {
    val title = when (state) {
        is VoicePromptUiState.Recording -> "Listening…"
        is VoicePromptUiState.Finalizing -> "Wrapping up…"
        is VoicePromptUiState.Refining -> "Voice prompt"
        is VoicePromptUiState.Review -> "Review your prompt"
        is VoicePromptUiState.Editing -> "Edit your prompt"
        else -> "Voice prompt"
    }
    Text(text = title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun TranscriptPane(transcript: Transcript) {
    if (transcript.isEmpty) {
        CenteredStatus("Start talking — your words appear here.")
    } else {
        TranscriptText(transcript)
    }
}

/** Settled words in full color; the in-flight interim tail muted, firming up in place. */
@Composable
private fun TranscriptText(transcript: Transcript, muted: Boolean = false) {
    val mutedColor = KodeTheme.colors.muted
    val bodyColor = if (muted) mutedColor else MaterialTheme.colorScheme.onSurface
    val text = buildAnnotatedString {
        append(transcript.settled)
        if (transcript.interim.isNotEmpty()) {
            if (transcript.settled.isNotEmpty()) append(' ')
            withStyle(SpanStyle(color = mutedColor)) { append(transcript.interim) }
        }
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = bodyColor,
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState(), reverseScrolling = true),
    )
}

@Composable
private fun TranscriptText(text: String, muted: Boolean) {
    TranscriptText(Transcript(settled = text), muted = muted)
}

@Composable
private fun ReviewPane(
    state: VoicePromptUiState.Review,
    attachmentPreviews: List<String>,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.refineFailed) {
            Text(
                text = "Refinement was unavailable — showing the raw transcript.",
                style = MaterialTheme.typography.labelMedium,
                color = KodeTheme.colors.warning,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else if (state.wasRefined) {
            Text(
                text = "Polished for technical accuracy.",
                style = MaterialTheme.typography.labelMedium,
                color = KodeTheme.colors.muted,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Text(
            text = state.text,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
        AttachmentChip(attachmentPreviews)
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun EditPane(
    state: VoicePromptUiState.Editing,
    attachmentPreviews: List<String>,
    onDraftChanged: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(16.dp),
                )
                .padding(14.dp),
        ) {
            BasicTextField(
                value = state.draft,
                onValueChange = onDraftChanged,
                modifier = Modifier.fillMaxSize(),
                textStyle = MaterialTheme.typography.bodyLarge
                    .copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            )
        }
        AttachmentChip(attachmentPreviews)
        state.error?.let { error ->
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * A read-only reminder of what the prompt will carry.
 *
 * Deliberately inert: no picker, no remove. Images are staged in the composer
 * *before* recording, and the dialog's job is to make that visible at the
 * moment of sending, not to become a second attachment surface. Anything beyond
 * four thumbnails collapses into a count, so a full set of eight cannot crowd
 * out the transcript.
 */
@Composable
private fun AttachmentChip(previews: List<String>) {
    if (previews.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            previews.take(MAX_ATTACHMENT_THUMBNAILS).forEach { preview ->
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
        Text(
            text = if (previews.size == 1) {
                "1 image will be sent"
            } else {
                "${previews.size} images will be sent"
            },
            style = MaterialTheme.typography.labelMedium,
            color = KodeTheme.colors.muted,
        )
    }
}

private const val MAX_ATTACHMENT_THUMBNAILS = 4

@Composable
private fun CenteredStatus(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = KodeTheme.colors.muted,
        )
    }
}

@Composable
private fun CenteredMessage(title: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = KodeTheme.colors.muted,
            )
        }
    }
}

/**
 * The voice-reactive indicator: three concentric rings breathing with the RMS loudness.
 *
 * Amplitude arrives ~25×/s on its own flow, so only this composable recomposes with it —
 * the spring smooths chunk-to-chunk jitter into an organic pulse.
 */
@Composable
private fun VoiceIndicator(
    amplitude: StateFlow<Float>,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val raw by amplitude.collectAsStateWithLifecycle()
    val level by animateFloatAsState(
        targetValue = if (active) raw.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "voice-level",
    )
    val ringColor = MaterialTheme.colorScheme.primary
    val coreColor = if (active) MaterialTheme.colorScheme.primary else KodeTheme.colors.muted

    Box(
        modifier = modifier
            .size(72.dp)
            .drawBehind {
                val center = Offset(size.width / 2f, size.height / 2f)
                val base = size.minDimension / 5f
                // Outer rings swell with loudness; alpha fades as they grow.
                for (ring in 1..3) {
                    val radius = base + (base * 1.1f * ring * (0.35f + level))
                    drawCircle(
                        color = ringColor.copy(alpha = (0.28f - ring * 0.07f).coerceAtLeast(0.04f)),
                        radius = radius,
                        center = center,
                    )
                }
                drawCircle(color = coreColor, radius = base * (1f + level * 0.35f), center = center)
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = KodeIcons.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun BottomBar(viewModel: VoicePromptViewModel, state: VoicePromptUiState) {
    var configOpen by remember { mutableStateOf(false) }
    val refinementEnabled by viewModel.refinementEnabled.collectAsStateWithLifecycle()

    Column {
        if (configOpen) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Refine transcript", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "A fast model fixes misheard technical terms. Applies when you stop talking.",
                        style = MaterialTheme.typography.bodySmall,
                        color = KodeTheme.colors.muted,
                    )
                }
                Switch(checked = refinementEnabled, onCheckedChange = { viewModel.toggleRefinement() })
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The config affordance anchors the bottom edge, per the spec.
            IconButton(onClick = { configOpen = !configOpen }) {
                Icon(
                    imageVector = KodeIcons.Gear,
                    contentDescription = "Voice settings",
                    tint = if (configOpen) MaterialTheme.colorScheme.primary else KodeTheme.colors.muted,
                )
            }
            Spacer(Modifier.weight(1f))

            when (state) {
                is VoicePromptUiState.Recording -> {
                    TextButton(onClick = viewModel::cancel) { Text("Cancel") }
                    Button(onClick = viewModel::stopTalking) { Text("Done") }
                }

                is VoicePromptUiState.Finalizing, is VoicePromptUiState.Refining -> {
                    TextButton(onClick = viewModel::cancel) { Text("Cancel") }
                }

                is VoicePromptUiState.Review -> {
                    TextButton(onClick = viewModel::decline, enabled = !state.isSending) { Text("Decline") }
                    OutlinedButton(onClick = viewModel::edit, enabled = !state.isSending) { Text("Edit") }
                    Button(onClick = viewModel::accept, enabled = !state.isSending) {
                        if (state.isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Accept & send")
                        }
                    }
                }

                is VoicePromptUiState.Editing -> {
                    TextButton(onClick = viewModel::decline, enabled = !state.isSending) { Text("Discard") }
                    Button(
                        onClick = viewModel::sendEdited,
                        enabled = !state.isSending && state.draft.isNotBlank(),
                    ) {
                        if (state.isSending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Send")
                        }
                    }
                }

                is VoicePromptUiState.Failed, VoicePromptUiState.PermissionDenied -> {
                    TextButton(onClick = viewModel::cancel) { Text("Close") }
                    Button(onClick = viewModel::retry) { Text("Try again") }
                }

                else -> TextButton(onClick = viewModel::cancel) { Text("Cancel") }
            }
        }
    }
}

/** Circular mic control shown beside the composer's send button. */
@Composable
fun VoiceMicButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .let { if (enabled) it else it },
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = KodeIcons.Mic,
                contentDescription = "Voice prompt",
                tint = if (enabled) MaterialTheme.colorScheme.onSurface else KodeTheme.colors.muted,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
