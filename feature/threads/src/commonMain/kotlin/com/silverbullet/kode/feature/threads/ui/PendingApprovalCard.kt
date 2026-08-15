package com.silverbullet.kode.feature.threads.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.ApprovalDecision
import com.silverbullet.kode.core.model.ApprovalRequestKind
import com.silverbullet.kode.feature.threads.presentation.ApprovalUiState

/**
 * The agent asking permission to perform one action.
 *
 * Docked and collapsible like the question card, for the same reason: the turn
 * is blocked until it is decided, so it must not be able to scroll away. Unlike
 * questions this is a single tap, so the card stays short.
 */
@Composable
fun PendingApprovalCard(
    state: ApprovalUiState,
    onToggleCollapsed: () -> Unit,
    onDecide: (decision: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pending = state.pending ?: return
    val colors = KodeTheme.colors

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleCollapsed)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = pending.requestKind.icon(),
                    contentDescription = null,
                    tint = colors.warning,
                    modifier = Modifier.size(18.dp),
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (state.otherPendingCount > 0) {
                            "${pending.title} · +${state.otherPendingCount} more"
                        } else {
                            pending.title
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The detail is echoed on the bar so a collapsed card still
                    // says *what* is waiting, not just that something is.
                    Text(
                        text = pending.detail?.singleLine() ?: "Needs your approval",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Icon(
                    imageVector = KodeIcons.ChevronDown,
                    contentDescription = if (state.collapsed) {
                        "Expand approval"
                    } else {
                        "Collapse approval"
                    },
                    tint = colors.muted,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = if (state.collapsed) 0f else 180f },
                )
            }

            AnimatedVisibility(
                visible = !state.collapsed,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    pending.detail?.let { detail ->
                        Text(
                            text = detail,
                            style = if (pending.isDetailLiteral) {
                                ToolDetailStyle
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.codeBackground, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                                // A long command must be readable in full before
                                // the user decides whether to allow it.
                                .heightIn(max = 200.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }

                    state.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Button(
                        onClick = { onDecide(ApprovalDecision.ACCEPT) },
                        enabled = !state.isDeciding,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        DecisionLabel(
                            label = "Allow once",
                            busy = state.decidingWith == ApprovalDecision.ACCEPT,
                            busyColor = MaterialTheme.colorScheme.onPrimary,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onDecide(ApprovalDecision.ACCEPT_FOR_SESSION) },
                            enabled = !state.isDeciding,
                            modifier = Modifier.weight(1f),
                        ) {
                            DecisionLabel(
                                label = "Allow session",
                                busy = state.decidingWith == ApprovalDecision.ACCEPT_FOR_SESSION,
                                busyColor = MaterialTheme.colorScheme.primary,
                            )
                        }

                        TextButton(
                            onClick = { onDecide(ApprovalDecision.DECLINE) },
                            enabled = !state.isDeciding,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = colors.danger,
                            ),
                        ) {
                            DecisionLabel(
                                label = "Decline",
                                busy = state.decidingWith == ApprovalDecision.DECLINE,
                                busyColor = colors.danger,
                            )
                        }
                    }

                    // Breathing room above the composer's inset padding.
                    Text(
                        text = "\"Allow session\" also covers later actions of this kind.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

/** Keeps the button width stable while a decision is in flight. */
@Composable
private fun DecisionLabel(
    label: String,
    busy: Boolean,
    busyColor: androidx.compose.ui.graphics.Color,
) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = busyColor,
        )
    } else {
        Text(label)
    }
}

private fun String.icon() = when (this) {
    ApprovalRequestKind.COMMAND -> KodeIcons.Command
    ApprovalRequestKind.FILE_READ -> KodeIcons.Eye
    ApprovalRequestKind.FILE_CHANGE -> KodeIcons.Edit
    else -> KodeIcons.Warning
}

private fun String.singleLine(): String =
    lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
