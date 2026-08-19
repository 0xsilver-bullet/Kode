package com.silverbullet.kode.feature.threads.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.GitStackedAction
import com.silverbullet.kode.core.model.VcsStatus
import com.silverbullet.kode.core.model.VcsWorkingTreeFile
import com.silverbullet.kode.feature.threads.domain.git.GitMenuItemKind
import com.silverbullet.kode.feature.threads.domain.git.buildGitMenuItems
import com.silverbullet.kode.feature.threads.domain.git.gitActionDisabledReason
import com.silverbullet.kode.feature.threads.domain.git.gitRowStatusDetail
import com.silverbullet.kode.feature.threads.domain.git.gitStatusSummary
import com.silverbullet.kode.feature.threads.domain.git.requiresDefaultBranchConfirmation
import com.silverbullet.kode.feature.threads.domain.git.resolveDefaultBranchDialogCopy

/**
 * The git bottom sheet — Kode's port of t3code's `GitOverviewSheet`, plus its
 * stacked `GitCommitSheet` and `GitConfirmSheet` as drill-in panes (Kode's
 * established sheet pattern, see `ThreadConfigSheet`) rather than a second
 * native sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitOverviewSheet(
    status: VcsStatus?,
    busy: Boolean,
    fallbackBranch: String?,
    worktreePath: String?,
    onRefresh: () -> Unit,
    onRunAction: (action: String, commitMessage: String?, featureBranch: Boolean, filePaths: List<String>?) -> Unit,
    onRunOnNewFeatureBranch: (action: String) -> Unit,
    onPull: () -> Unit,
    onOpenReview: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pane by remember { mutableStateOf<GitSheetPane>(GitSheetPane.Overview) }
    val uriHandler = LocalUriHandler.current

    // The same quiet refresh t3code runs when its sheet mounts.
    LaunchedEffect(Unit) { onRefresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            when (val open = pane) {
                GitSheetPane.Overview -> OverviewPane(
                    status = status,
                    busy = busy,
                    fallbackBranch = fallbackBranch,
                    worktreePath = worktreePath,
                    onRefresh = onRefresh,
                    onCommit = { pane = GitSheetPane.Commit },
                    onRunConfirmable = { action ->
                        val branch = status?.refName
                        if (branch != null &&
                            requiresDefaultBranchConfirmation(action, status.isDefaultRef)
                        ) {
                            pane = GitSheetPane.Confirm(action = action, branchName = branch)
                        } else {
                            onDismiss()
                            onRunAction(action, null, false, null)
                        }
                    },
                    onOpenPr = { url -> uriHandler.openUri(url) },
                    onPull = onPull,
                    onOpenReview = {
                        onDismiss()
                        onOpenReview()
                    },
                )

                GitSheetPane.Commit -> CommitPane(
                    status = status,
                    busy = busy,
                    onBack = { pane = GitSheetPane.Overview },
                    onSubmit = { featureBranch, message, filePaths ->
                        onDismiss()
                        onRunAction(GitStackedAction.COMMIT, message, featureBranch, filePaths)
                    },
                )

                is GitSheetPane.Confirm -> ConfirmPane(
                    pane = open,
                    onBack = { pane = GitSheetPane.Overview },
                    onContinue = {
                        onDismiss()
                        onRunAction(open.action, null, false, null)
                    },
                    onFeatureBranch = {
                        onDismiss()
                        onRunOnNewFeatureBranch(open.action)
                    },
                )
            }
        }
    }
}

private sealed interface GitSheetPane {
    data object Overview : GitSheetPane
    data object Commit : GitSheetPane
    data class Confirm(val action: String, val branchName: String) : GitSheetPane
}

// ------------------------------------------------------------------- overview

@Composable
private fun OverviewPane(
    status: VcsStatus?,
    busy: Boolean,
    fallbackBranch: String?,
    worktreePath: String?,
    onRefresh: () -> Unit,
    onCommit: () -> Unit,
    onRunConfirmable: (String) -> Unit,
    onOpenPr: (String) -> Unit,
    onPull: () -> Unit,
    onOpenReview: () -> Unit,
) {
    val colors = KodeTheme.colors
    val isRepo = status?.isRepo ?: true
    val hasOriginRemote = status?.hasPrimaryRemote ?: false
    val branchLabel = status?.refName ?: fallbackBranch ?: "Detached HEAD"

    // Header: branch, status summary, refresh.
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = branchLabel,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = gitStatusSummary(status),
                style = MaterialTheme.typography.labelMedium,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh, enabled = !busy) {
            Icon(
                imageVector = KodeIcons.Refresh,
                contentDescription = "Refresh repository status",
                tint = if (busy) colors.muted else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    HorizontalDivider(color = colors.divider, modifier = Modifier.padding(horizontal = 16.dp))

    // Commit / Push / Create-or-View-PR — empty when this is not a repo.
    val menuItems = if (isRepo) buildGitMenuItems(status, busy, hasOriginRemote) else emptyList()
    menuItems.forEach { item ->
        val subtitle = gitActionDisabledReason(item, status, busy, hasOriginRemote)
            ?: gitRowStatusDetail(item, status)
        GitSheetRow(
            icon = when (item.id) {
                "commit" -> KodeIcons.Check
                "push" -> KodeIcons.ArrowUp
                else -> KodeIcons.ArrowUpRight
            },
            title = item.label,
            subtitle = subtitle,
            enabled = !item.disabled,
            onClick = {
                when {
                    item.kind == GitMenuItemKind.OpenPr -> status?.pr?.url?.let(onOpenPr)
                    item.dialogAction == GitStackedAction.COMMIT -> onCommit()
                    item.dialogAction != null -> onRunConfirmable(item.dialogAction)
                }
            },
        )
    }

    // Pull latest — rendered only when the branch is behind upstream.
    if ((status?.behindCount ?: 0) > 0) {
        val behind = status?.behindCount ?: 0
        GitSheetRow(
            icon = KodeIcons.ArrowDown,
            title = "Pull latest",
            subtitle = if (behind == 1) {
                "1 commit behind upstream"
            } else {
                "$behind commits behind upstream"
            },
            enabled = !busy && isRepo,
            onClick = onPull,
        )
    }

    GitSheetRow(
        icon = KodeIcons.Eye,
        title = "Review changes",
        subtitle = "Inspect turn diffs, worktree changes, and base branch diff",
        enabled = !busy && isRepo,
        onClick = onOpenReview,
    )

    worktreePath?.let { path ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHigh,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = "WORKTREE",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One actionable sheet row: icon chip, title, muted subtitle, chevron. */
@Composable
private fun GitSheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = KodeTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(18.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Icon(
            imageVector = KodeIcons.ChevronRight,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

// --------------------------------------------------------------------- commit

@Composable
private fun CommitPane(
    status: VcsStatus?,
    busy: Boolean,
    onBack: () -> Unit,
    onSubmit: (featureBranch: Boolean, commitMessage: String?, filePaths: List<String>?) -> Unit,
) {
    val colors = KodeTheme.colors
    val files = status?.workingTree?.files.orEmpty()

    var message by remember { mutableStateOf("") }
    var excluded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingFiles by remember { mutableStateOf(false) }

    val selected = remember(files, excluded) { files.filterNot { it.path in excluded } }
    val allSelected = excluded.isEmpty()
    val noneSelected = selected.isEmpty()
    val selectedInsertions = selected.sumOf { it.insertions }
    val selectedDeletions = selected.sumOf { it.deletions }

    fun submit(featureBranch: Boolean) {
        onSubmit(
            featureBranch,
            message.trim().takeIf { it.isNotEmpty() },
            if (!allSelected) selected.map { it.path } else null,
        )
    }

    PaneHeader(title = "Commit changes", onBack = onBack)

    // Branch card, with the inline default-branch warning (commit itself never
    // routes through the confirm pane — same as t3code).
    SheetCard {
        Text(text = "BRANCH", style = MaterialTheme.typography.labelSmall, color = colors.muted)
        Text(
            text = status?.refName ?: "(detached HEAD)",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (status?.isDefaultRef == true) {
            Text(
                text = "Warning: this is the default branch.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.warning,
            )
        }
    }

    // Files card.
    SheetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "FILES", style = MaterialTheme.typography.labelSmall, color = colors.muted)
                Text(
                    text = "${selected.size} selected · +$selectedInsertions / −$selectedDeletions",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                )
            }
            if (editingFiles && !allSelected) {
                Text(
                    text = "Reset",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { excluded = emptySet() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Text(
                text = if (editingFiles) "Done" else "Edit",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { editingFiles = !editingFiles }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        when {
            files.isEmpty() -> Text(
                text = "No changed files are available to commit.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                modifier = Modifier.padding(top = 6.dp),
            )

            editingFiles -> files.forEach { file ->
                val isExcluded = file.path in excluded
                CommitFileRow(
                    file = file,
                    subtitle = if (isExcluded) "Excluded from this commit" else null,
                    dimmed = isExcluded,
                    onClick = {
                        excluded = if (isExcluded) excluded - file.path else excluded + file.path
                    },
                )
            }

            else -> {
                selected.take(COLLAPSED_FILE_COUNT).forEach { file ->
                    CommitFileRow(file = file, subtitle = null, dimmed = false, onClick = null)
                }
                val more = selected.size - COLLAPSED_FILE_COUNT
                if (more > 0) {
                    Text(
                        text = "+$more more files",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }

    // Commit message.
    SheetCard {
        Text(text = "COMMIT MESSAGE", style = MaterialTheme.typography.labelSmall, color = colors.muted)
        BasicTextField(
            value = message,
            onValueChange = { message = it },
            textStyle = MaterialTheme.typography.bodyMedium
                .copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 96.dp)
                .padding(top = 6.dp),
            decorationBox = { inner ->
                if (message.isEmpty()) {
                    Text(
                        text = "Leave empty to auto-generate",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.muted,
                    )
                }
                inner()
            },
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalButton(
            onClick = { submit(true) },
            enabled = !noneSelected && !busy,
            modifier = Modifier.weight(1f),
        ) {
            Text("Commit on new branch", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = { submit(false) },
            enabled = !noneSelected && !busy,
            modifier = Modifier.weight(1f),
        ) {
            Text("Commit")
        }
    }
}

@Composable
private fun CommitFileRow(
    file: VcsWorkingTreeFile,
    subtitle: String?,
    dimmed: Boolean,
    onClick: (() -> Unit)?,
) {
    val colors = KodeTheme.colors
    var modifier = Modifier.fillMaxWidth()
    if (onClick != null) modifier = modifier.clickable(onClick = onClick)
    Row(
        modifier = modifier.padding(vertical = 4.dp).alpha(if (dimmed) 0.5f else 1f),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.path,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            subtitle?.let {
                Text(text = it, style = MaterialTheme.typography.labelSmall, color = colors.muted)
            }
        }
        Text(
            text = "+${file.insertions}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.success,
        )
        Text(
            text = "−${file.deletions}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.danger,
        )
    }
}

private const val COLLAPSED_FILE_COUNT = 3

// -------------------------------------------------------------------- confirm

@Composable
private fun ConfirmPane(
    pane: GitSheetPane.Confirm,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    onFeatureBranch: () -> Unit,
) {
    val colors = KodeTheme.colors
    val copy = resolveDefaultBranchDialogCopy(
        action = pane.action,
        branchName = pane.branchName,
        includesCommit = false,
    )

    PaneHeader(title = "Confirm", onBack = onBack)

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Text(text = copy.title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = copy.description,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            modifier = Modifier.padding(top = 8.dp),
        )

        FilledTonalButton(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        ) {
            Text(copy.continueLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = onFeatureBranch,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Feature branch & continue", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// -------------------------------------------------------------------- shared

@Composable
private fun PaneHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = KodeIcons.ChevronRight,
            contentDescription = "Back",
            tint = KodeTheme.colors.muted,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer { rotationZ = 180f },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}
