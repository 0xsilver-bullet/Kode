package com.silverbullet.kode.feature.threads.domain.git

import com.silverbullet.kode.core.model.GitStackedAction
import com.silverbullet.kode.core.model.VcsStatus
import com.silverbullet.kode.core.model.VcsStatusChangeRequest
import com.silverbullet.kode.core.model.VcsStatusLocal
import com.silverbullet.kode.core.model.VcsStatusRemote
import com.silverbullet.kode.core.model.VcsWorkingTree
import com.silverbullet.kode.core.model.VcsWorkingTreeFile
import com.silverbullet.kode.core.model.applyVcsStatusStreamEvent
import com.silverbullet.kode.core.model.VcsStatusStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun status(
    isRepo: Boolean = true,
    refName: String? = "feature/demo",
    dirtyFiles: Int = 0,
    hasUpstream: Boolean = true,
    hasPrimaryRemote: Boolean = true,
    isDefaultRef: Boolean = false,
    ahead: Int = 0,
    behind: Int = 0,
    pr: VcsStatusChangeRequest? = null,
) = VcsStatus(
    local = VcsStatusLocal(
        isRepo = isRepo,
        hasPrimaryRemote = hasPrimaryRemote,
        isDefaultRef = isDefaultRef,
        refName = refName,
        hasWorkingTreeChanges = dirtyFiles > 0,
        workingTree = VcsWorkingTree(
            files = List(dirtyFiles) { VcsWorkingTreeFile(path = "f$it", insertions = 1, deletions = 0) },
            insertions = dirtyFiles,
            deletions = 0,
        ),
    ),
    remote = VcsStatusRemote(
        hasUpstream = hasUpstream,
        aheadCount = ahead,
        behindCount = behind,
        pr = pr,
    ),
)

private fun openPr() = VcsStatusChangeRequest(
    number = 12,
    title = "Demo",
    url = "https://example.com/pr/12",
    baseRef = "main",
    headRef = "feature/demo",
    state = "open",
)

class GitActionsTest {

    @Test
    fun commitEnabledOnlyWithChanges() {
        val clean = buildGitMenuItems(status(dirtyFiles = 0), isBusy = false)
        assertTrue(clean.first { it.id == "commit" }.disabled)
        assertEquals(
            "Worktree is clean. Make changes before committing.",
            gitActionDisabledReason(clean.first { it.id == "commit" }, status(), false, true),
        )

        val dirty = buildGitMenuItems(status(dirtyFiles = 2), isBusy = false)
        assertFalse(dirty.first { it.id == "commit" }.disabled)
    }

    @Test
    fun pushRequiresAheadCleanAndNotBehind() {
        val ready = buildGitMenuItems(status(ahead = 2), isBusy = false)
        assertFalse(ready.first { it.id == "push" }.disabled)

        val behind = status(ahead = 2, behind = 1)
        val behindItems = buildGitMenuItems(behind, isBusy = false)
        assertTrue(behindItems.first { it.id == "push" }.disabled)
        assertEquals(
            "Branch is behind upstream. Pull/rebase before pushing.",
            gitActionDisabledReason(behindItems.first { it.id == "push" }, behind, false, true),
        )

        val dirty = status(ahead = 2, dirtyFiles = 1)
        assertTrue(buildGitMenuItems(dirty, false).first { it.id == "push" }.disabled)
    }

    @Test
    fun pushWithoutUpstreamAllowedOnlyWithOriginRemote() {
        val noUpstream = status(ahead = 1, hasUpstream = false, hasPrimaryRemote = true)
        assertFalse(
            buildGitMenuItems(noUpstream, false, hasOriginRemote = true)
                .first { it.id == "push" }.disabled,
        )
        val noRemote = status(ahead = 1, hasUpstream = false, hasPrimaryRemote = false)
        val items = buildGitMenuItems(noRemote, false, hasOriginRemote = false)
        assertTrue(items.first { it.id == "push" }.disabled)
        assertEquals(
            "Add an \"origin\" remote before pushing.",
            gitActionDisabledReason(items.first { it.id == "push" }, noRemote, false, false),
        )
    }

    @Test
    fun prRowSwapsToViewPrWhenOneIsOpen() {
        val withPr = status(ahead = 1, pr = openPr())
        val items = buildGitMenuItems(withPr, isBusy = false)
        val prItem = items.first { it.id == "pr" }
        assertEquals("View PR", prItem.label)
        assertEquals(GitMenuItemKind.OpenPr, prItem.kind)
        assertFalse(prItem.disabled)
        assertEquals("PR #12 open", gitRowStatusDetail(prItem, withPr))

        val without = buildGitMenuItems(status(ahead = 1), isBusy = false)
        assertEquals("Create PR", without.first { it.id == "pr" }.label)
    }

    @Test
    fun busyDisablesEverythingWithReason() {
        val items = buildGitMenuItems(status(dirtyFiles = 1, ahead = 1), isBusy = true)
        assertTrue(items.all { it.disabled })
        assertEquals(
            "Git action in progress.",
            gitActionDisabledReason(items.first(), status(), isBusy = true, hasOriginRemote = true),
        )
    }

    @Test
    fun defaultBranchConfirmationOnlyForPushLikeActions() {
        assertTrue(requiresDefaultBranchConfirmation(GitStackedAction.PUSH, isDefaultBranch = true))
        assertTrue(requiresDefaultBranchConfirmation(GitStackedAction.CREATE_PR, isDefaultBranch = true))
        assertFalse(requiresDefaultBranchConfirmation(GitStackedAction.COMMIT, isDefaultBranch = true))
        assertFalse(requiresDefaultBranchConfirmation(GitStackedAction.PUSH, isDefaultBranch = false))

        val copy = resolveDefaultBranchDialogCopy(GitStackedAction.PUSH, "main", includesCommit = false)
        assertEquals("Push to default branch?", copy.title)
        assertEquals("Push to main", copy.continueLabel)
    }

    @Test
    fun statusSummaryComposesParts() {
        assertEquals("Loading branch status…", gitStatusSummary(null))
        assertEquals("Not a git repository", gitStatusSummary(status(isRepo = false)))
        assertEquals("Clean", gitStatusSummary(status()))
        assertEquals(
            "2 files changed · 1 ahead · 3 behind · PR #12 open",
            gitStatusSummary(status(dirtyFiles = 2, ahead = 1, behind = 3, pr = openPr())),
        )
    }

    @Test
    fun branchNameSanitizing() {
        assertEquals("feature/add-login-flow", sanitizeFeatureBranchName("Add Login Flow!"))
        assertEquals("feature/update", sanitizeFeatureBranchName("///"))
        assertEquals("fix-crash", sanitizeBranchFragment("Fix: Crash?"))
        assertEquals("fix/crash", sanitizeFeatureBranchName("feature/fix/crash").removePrefix("feature/"))
        assertEquals(
            "feature/update-2",
            resolveAutoFeatureBranchName(listOf("feature/update", "other")),
        )
        assertEquals(
            "feature/update-3",
            resolveAutoFeatureBranchName(listOf("feature/update", "feature/update-2")),
        )
    }

    @Test
    fun statusFoldMergesLocalAndRemoteHalves() {
        val local = VcsStatusLocal(isRepo = true, refName = "main", hasWorkingTreeChanges = true)
        val remote = VcsStatusRemote(hasUpstream = true, aheadCount = 2, behindCount = 0)

        // Snapshot without a remote half reads as "remote unknown yet".
        val snapshot = applyVcsStatusStreamEvent(
            null,
            VcsStatusStreamEvent.Snapshot(local, null),
        )
        assertEquals("main", snapshot?.refName)
        assertFalse(snapshot!!.hasUpstream)

        // Remote arriving later keeps the local half.
        val merged = applyVcsStatusStreamEvent(snapshot, VcsStatusStreamEvent.RemoteUpdated(remote))
        assertEquals("main", merged?.refName)
        assertEquals(2, merged?.aheadCount)

        // Local update keeps the remote half.
        val localUpdate = applyVcsStatusStreamEvent(
            merged,
            VcsStatusStreamEvent.LocalUpdated(local.copy(hasWorkingTreeChanges = false)),
        )
        assertEquals(2, localUpdate?.aheadCount)
        assertFalse(localUpdate!!.hasWorkingTreeChanges)

        // remoteUpdated before any local part fabricates a neutral repo local.
        val remoteFirst = applyVcsStatusStreamEvent(null, VcsStatusStreamEvent.RemoteUpdated(remote))
        assertTrue(remoteFirst!!.isRepo)
        assertNull(remoteFirst.refName)
        assertEquals(2, remoteFirst.aheadCount)
    }
}
