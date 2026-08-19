package com.silverbullet.kode.core.model

import kotlinx.serialization.Serializable

/**
 * Review (diff preview) types, mirroring `packages/contracts/src/review.ts` and
 * the turn-diff surface of `packages/contracts/src/orchestration.ts`.
 *
 * The server returns whole unified-diff *strings*, never structured hunks or
 * file lists — parsing is a client concern. A patch that hits the server's
 * output cap (120 KB) ends with the literal marker `\n\n[truncated]` and is
 * flagged via [ReviewDiffPreviewSource.truncated].
 */

/** `ReviewDiffPreviewSourceKind`. */
object ReviewDiffSourceKind {
    const val WORKING_TREE = "working-tree"
    const val BRANCH_RANGE = "branch-range"
}

@Serializable
data class ReviewDiffPreviewInput(
    val cwd: String,
)

@Serializable
data class ReviewDiffPreviewSource(
    val id: String,
    val kind: String,
    val title: String,
    val baseRef: String? = null,
    val headRef: String? = null,
    /** The full unified diff text. Empty when the source has no changes. */
    val diff: String = "",
    val diffHash: String,
    val truncated: Boolean = false,
)

@Serializable
data class ReviewDiffPreviewResult(
    val cwd: String,
    val generatedAt: String,
    val sources: List<ReviewDiffPreviewSource> = emptyList(),
)

// ------------------------------------------------------------------ turn diffs

@Serializable
data class GetTurnDiffInput(
    val threadId: ThreadId,
    val fromTurnCount: Int,
    val toTurnCount: Int,
)

@Serializable
data class GetFullThreadDiffInput(
    val threadId: ThreadId,
    val toTurnCount: Int,
)

/** `ThreadTurnDiff` — the diff a turn (or turn range) produced. */
@Serializable
data class ThreadTurnDiff(
    val threadId: ThreadId,
    val fromTurnCount: Int = 0,
    val toTurnCount: Int = 0,
    val diff: String = "",
)
