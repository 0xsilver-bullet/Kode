package com.silverbullet.kode.feature.threads.domain.git

import com.silverbullet.kode.core.model.GitStackedAction
import com.silverbullet.kode.core.model.VcsChangeRequestState
import com.silverbullet.kode.core.model.VcsStatus

/**
 * Pure git-sheet logic, ported from t3code's
 * `packages/client-runtime/src/state/gitActions.ts` and
 * `packages/shared/src/git.ts`. Kept free of Compose and transport types so the
 * enable/disable rules and copy can be unit-tested against the reference
 * implementation's cases.
 */

// -------------------------------------------------------------------- summary

/** The one-line status under the branch name — `statusSummary` in t3code. */
fun gitStatusSummary(status: VcsStatus?): String {
    if (status == null) return "Loading branch status…"
    if (!status.isRepo) return "Not a git repository"

    val parts = mutableListOf<String>()
    parts += if (status.hasWorkingTreeChanges) {
        val count = status.workingTree.files.size
        if (count == 1) "1 file changed" else "$count files changed"
    } else {
        "Clean"
    }
    if (status.aheadCount > 0) parts += "${status.aheadCount} ahead"
    if (status.behindCount > 0) parts += "${status.behindCount} behind"
    status.pr?.takeIf { it.state == VcsChangeRequestState.OPEN }?.let { parts += "PR #${it.number} open" }
    return parts.joinToString(" · ")
}

// ------------------------------------------------------------------ menu items

enum class GitMenuItemKind { OpenDialog, OpenPr }

/** One actionable row of the sheet — `GitActionMenuItem` in t3code. */
data class GitMenuItem(
    val id: String,
    val label: String,
    val disabled: Boolean,
    val kind: GitMenuItemKind,
    /** The stacked action a dialog row runs; null for the View PR row. */
    val dialogAction: String? = null,
)

/**
 * The Commit / Push / Create-or-View-PR rows with their enabled state —
 * `buildMenuItems` in t3code, ported condition for condition.
 */
fun buildGitMenuItems(
    status: VcsStatus?,
    isBusy: Boolean,
    hasOriginRemote: Boolean = true,
): List<GitMenuItem> {
    if (status == null) return emptyList()

    val hasBranch = status.refName != null
    val hasChanges = status.hasWorkingTreeChanges
    val hasOpenPr = status.pr?.state == VcsChangeRequestState.OPEN
    val isBehind = status.behindCount > 0
    val canPushWithoutUpstream = hasOriginRemote && !status.hasUpstream
    val canCommit = !isBusy && hasChanges
    val canPush = !isBusy && hasBranch && !hasChanges && !isBehind && status.aheadCount > 0 &&
        (status.hasUpstream || canPushWithoutUpstream)
    val canCreatePr = !isBusy && hasBranch && !hasChanges && !hasOpenPr &&
        status.aheadCount > 0 && !isBehind && (status.hasUpstream || canPushWithoutUpstream)
    val canOpenPr = !isBusy && hasOpenPr

    return listOf(
        GitMenuItem(
            id = "commit",
            label = "Commit",
            disabled = !canCommit,
            kind = GitMenuItemKind.OpenDialog,
            dialogAction = GitStackedAction.COMMIT,
        ),
        GitMenuItem(
            id = "push",
            label = "Push",
            disabled = !canPush,
            kind = GitMenuItemKind.OpenDialog,
            dialogAction = GitStackedAction.PUSH,
        ),
        if (hasOpenPr) {
            GitMenuItem(
                id = "pr",
                label = "View PR",
                disabled = !canOpenPr,
                kind = GitMenuItemKind.OpenPr,
            )
        } else {
            GitMenuItem(
                id = "pr",
                label = "Create PR",
                disabled = !canCreatePr,
                kind = GitMenuItemKind.OpenDialog,
                dialogAction = GitStackedAction.CREATE_PR,
            )
        },
    )
}

/**
 * Why a disabled row is disabled — `getGitActionDisabledReason` in t3code,
 * same precedence order.
 */
fun gitActionDisabledReason(
    item: GitMenuItem,
    status: VcsStatus?,
    isBusy: Boolean,
    hasOriginRemote: Boolean,
): String? {
    if (!item.disabled) return null
    if (isBusy) return "Git action in progress."
    if (status == null) return "Git status is unavailable."

    val hasBranch = status.refName != null
    val hasChanges = status.hasWorkingTreeChanges
    val hasOpenPr = status.pr?.state == VcsChangeRequestState.OPEN
    val isAhead = status.aheadCount > 0
    val isBehind = status.behindCount > 0

    if (item.id == "commit") {
        if (!hasChanges) return "Worktree is clean. Make changes before committing."
        return "Commit is currently unavailable."
    }

    if (item.id == "push") {
        if (!hasBranch) return "Detached HEAD: checkout a branch before pushing."
        if (hasChanges) return "Commit or stash local changes before pushing."
        if (isBehind) return "Branch is behind upstream. Pull/rebase before pushing."
        if (!status.hasUpstream && !hasOriginRemote) return "Add an \"origin\" remote before pushing."
        if (!isAhead) return "No local commits to push."
        return "Push is currently unavailable."
    }

    if (hasOpenPr) return "View PR is currently unavailable."
    if (!hasBranch) return "Detached HEAD: checkout a branch before creating a PR."
    if (hasChanges) return "Commit local changes before creating a PR."
    if (!status.hasUpstream && !hasOriginRemote) return "Add an \"origin\" remote before creating a PR."
    if (!isAhead) return "No local commits to include in a PR."
    if (isBehind) return "Branch is behind upstream. Pull/rebase before creating a PR."
    return "Create PR is currently unavailable."
}

/** The status fact shown under an *enabled* row — `rowStatusDetail` in t3code. */
fun gitRowStatusDetail(item: GitMenuItem, status: VcsStatus?): String? {
    if (status == null) return null
    if (item.dialogAction == GitStackedAction.COMMIT && status.hasWorkingTreeChanges) {
        val count = status.workingTree.files.size
        return if (count == 1) "1 file changed" else "$count files changed"
    }
    if (item.dialogAction == GitStackedAction.PUSH && status.aheadCount > 0) {
        val ahead = status.aheadCount
        return if (ahead == 1) "1 commit ahead" else "$ahead commits ahead"
    }
    if (item.kind == GitMenuItemKind.OpenPr) {
        val pr = status.pr ?: return null
        return "PR #${pr.number} ${pr.state}"
    }
    return null
}

// -------------------------------------------------- default-branch confirmation

fun requiresDefaultBranchConfirmation(action: String, isDefaultBranch: Boolean): Boolean {
    if (!isDefaultBranch) return false
    return action == GitStackedAction.PUSH ||
        action == GitStackedAction.CREATE_PR ||
        action == GitStackedAction.COMMIT_PUSH ||
        action == GitStackedAction.COMMIT_PUSH_PR
}

data class DefaultBranchDialogCopy(
    val title: String,
    val description: String,
    val continueLabel: String,
)

/** `resolveDefaultBranchActionDialogCopy` in t3code, text preserved verbatim. */
fun resolveDefaultBranchDialogCopy(
    action: String,
    branchName: String,
    includesCommit: Boolean,
): DefaultBranchDialogCopy {
    val suffix = " on \"$branchName\". You can continue on this branch or create a feature " +
        "branch and run the same action there."

    if (action == GitStackedAction.PUSH || action == GitStackedAction.COMMIT_PUSH) {
        if (includesCommit) {
            return DefaultBranchDialogCopy(
                title = "Commit & push to default branch?",
                description = "This action will commit and push changes$suffix",
                continueLabel = "Commit & push to $branchName",
            )
        }
        return DefaultBranchDialogCopy(
            title = "Push to default branch?",
            description = "This action will push local commits$suffix",
            continueLabel = "Push to $branchName",
        )
    }

    if (includesCommit) {
        return DefaultBranchDialogCopy(
            title = "Commit, push & create PR from default branch?",
            description = "This action will commit, push, and create a PR$suffix",
            continueLabel = "Commit, push & create PR",
        )
    }
    return DefaultBranchDialogCopy(
        title = "Push & create PR from default branch?",
        description = "This action will push local commits and create a PR$suffix",
        continueLabel = "Push & create PR",
    )
}

// ------------------------------------------------------------- branch naming

/**
 * Sanitizes an arbitrary string into a valid, lowercase git refName fragment —
 * `sanitizeBranchFragment` in t3code's `packages/shared/src/git.ts`.
 */
fun sanitizeBranchFragment(raw: String): String {
    val normalized = raw
        .trim()
        .lowercase()
        .replace(Regex("['\"`]"), "")
        .replace(Regex("^[./\\s_-]+|[./\\s_-]+$"), "")

    val fragment = normalized
        .replace(Regex("[^a-z0-9/_-]+"), "-")
        .replace(Regex("/+"), "/")
        .replace(Regex("-+"), "-")
        .replace(Regex("^[./_-]+|[./_-]+$"), "")
        .take(64)
        .replace(Regex("[./_-]+$"), "")

    return fragment.ifEmpty { "update" }
}

/** `sanitizeFeatureBranchName` — forces a `feature/…` namespace. */
fun sanitizeFeatureBranchName(raw: String): String {
    val sanitized = sanitizeBranchFragment(raw)
    if (sanitized.contains("/")) {
        return if (sanitized.startsWith("feature/")) sanitized else "feature/$sanitized"
    }
    return "feature/$sanitized"
}

private const val AUTO_FEATURE_BRANCH_FALLBACK = "feature/update"

/**
 * A unique `feature/…` name that avoids [existingBranchNames], appending a
 * numeric suffix on collision — `resolveAutoFeatureBranchName` in t3code.
 */
fun resolveAutoFeatureBranchName(
    existingBranchNames: List<String>,
    preferredBranch: String? = null,
): String {
    val preferred = preferredBranch?.trim().orEmpty()
    val base = sanitizeFeatureBranchName(preferred.ifEmpty { AUTO_FEATURE_BRANCH_FALLBACK })
    val existing = existingBranchNames.map { it.lowercase() }.toSet()

    if (base !in existing) return base

    var suffix = 2
    while ("$base-$suffix" in existing) suffix++
    return "$base-$suffix"
}
