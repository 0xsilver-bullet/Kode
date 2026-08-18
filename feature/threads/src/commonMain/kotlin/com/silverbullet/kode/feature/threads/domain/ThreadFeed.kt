package com.silverbullet.kode.feature.threads.domain

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.designsystem.MarkdownBlockGroup
import com.silverbullet.kode.core.model.ChatAttachment
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.OrchestrationLatestTurn
import com.silverbullet.kode.core.model.OrchestrationMessage
import com.silverbullet.kode.core.model.TurnState

/**
 * A row in the rendered thread feed.
 *
 * This is the *presented* feed, not the raw timeline: settled turns are folded
 * away and runs of tool activity are collapsed. Both matter enormously for
 * scroll performance — an untouched 40-turn thread is hundreds of rows, most of
 * which the user does not want to read, and each markdown row is expensive.
 * Ported from `deriveThreadFeedPresentation` in
 * `apps/mobile/src/lib/threadActivity.ts`.
 */
@Immutable
sealed interface FeedEntry {
    val id: String
    val createdAt: String

    @Immutable
    data class Message(val message: OrchestrationMessage) : FeedEntry {
        // Stored, not computed: `items(key = …)` reads this for every row on
        // every interval rebuild, and a getter allocated a String each time.
        override val id: String = "message:" + message.id
        override val createdAt: String = message.createdAt
    }

    /**
     * One markdown block of a settled assistant message.
     *
     * A settled message is exploded into one entry per top-level block by
     * [splitSettledAssistantMessages], so the lazy list composes a paragraph
     * at a time as the message scrolls in, instead of laying out the whole
     * message in the frame its row first appears — which was the scroll hitch
     * felt whenever a new turn entered the viewport.
     */
    @Immutable
    data class MessageBlock(
        val messageId: String,
        val blockIndex: Int,
        val block: MarkdownBlockGroup,
        override val createdAt: String,
    ) : FeedEntry {
        override val id: String = "message:$messageId:block:$blockIndex"
    }

    /** The image strip of a settled assistant message, after its last block. */
    @Immutable
    data class MessageAttachments(
        val messageId: String,
        val attachments: List<ChatAttachment>,
        override val createdAt: String,
    ) : FeedEntry {
        override val id: String = "message:$messageId:attachments"
    }

    @Immutable
    data class Activity(val activity: ActivityPresentation) : FeedEntry {
        override val id: String = "activity:" + activity.id
        override val createdAt: String = activity.createdAt
    }

    /** "+3 previous tool calls" / "Show fewer tool calls". */
    @Immutable
    data class WorkToggle(
        val groupId: String,
        val hiddenCount: Int,
        val expanded: Boolean,
        val onlyToolActivities: Boolean,
        override val createdAt: String,
    ) : FeedEntry {
        override val id: String = "work-toggle:$groupId"
    }

    /** "Worked for 12s" — one row standing in for a whole settled turn. */
    @Immutable
    data class TurnFold(
        val turnId: String,
        val hiddenCount: Int,
        val interrupted: Boolean,
        override val createdAt: String,
    ) : FeedEntry {
        override val id: String = "turn-fold:$turnId"
    }
}

/** Which turns and work groups the user has explicitly opened. */
@Immutable
data class FeedExpansion(
    val turns: Set<String> = emptySet(),
    val workGroups: Set<String> = emptySet(),
)

/**
 * Builds the presented feed.
 *
 * The pipeline mirrors T3 Code's, in order:
 *  1. collapse work-log entries (per-subagent identity, then adjacent tool
 *     lifecycle rows sharing a collapse key);
 *  2. interleave with messages by time;
 *  3. group adjacent activities that belong to the same turn;
 *  4. fold settled turns down to one row plus their final assistant message;
 *  5. within a still-visible group, hide all but the newest row behind a toggle.
 */
fun buildFeed(
    messages: List<OrchestrationMessage>,
    activities: List<ActivityPresentation>,
    latestTurn: OrchestrationLatestTurn?,
    expansion: FeedExpansion,
): List<FeedEntry> {
    val collapsed = collapseWorkLog(activities)
    val groups = groupAdjacentActivities(messages, collapsed)
    return present(groups, latestTurn, expansion)
}

/**
 * Explodes settled assistant messages into per-block entries.
 *
 * Runs after [buildFeed], so only messages that survived turn folding are ever
 * parsed. [parse] is expected to be cached and off the main thread — the feed
 * pipeline already runs on a background dispatcher, which means every block
 * that reaches the UI is parsed *before* it can be scrolled to. That closes
 * both halves of the turn-entry hitch: no whole-message layout in the frame a
 * row appears, and no zero-height "still parsing" placeholder for the lazy
 * list to over-fill past.
 *
 * A null from [parse] (the parser errored) keeps the original entry, which
 * falls back to the monolithic rendering path. User messages and messages
 * still streaming are left untouched: the user bubble is plain text, and the
 * streaming renderer needs the whole document to re-parse its unstable tail.
 */
suspend fun splitSettledAssistantMessages(
    entries: List<FeedEntry>,
    parse: suspend (cacheKey: String, text: String) -> List<MarkdownBlockGroup>?,
): List<FeedEntry> {
    if (entries.none { it.isSplittableMessage() }) return entries

    val out = ArrayList<FeedEntry>(entries.size + 16)
    for (entry in entries) {
        if (entry !is FeedEntry.Message || !entry.isSplittableMessage()) {
            out.add(entry)
            continue
        }

        val message = entry.message
        // Keyed on id *and* length, mirroring the render-side cache: a settled
        // message reuses its parse forever, an edited one re-parses.
        val groups = parse(message.id + ":" + message.text.length, message.text)
        if (groups.isNullOrEmpty()) {
            out.add(entry)
            continue
        }

        groups.forEachIndexed { index, group ->
            out.add(
                FeedEntry.MessageBlock(
                    messageId = message.id,
                    blockIndex = index,
                    block = group,
                    createdAt = message.createdAt,
                ),
            )
        }
        if (message.attachments.isNotEmpty()) {
            out.add(
                FeedEntry.MessageAttachments(
                    messageId = message.id,
                    attachments = message.attachments,
                    createdAt = message.createdAt,
                ),
            )
        }
    }
    return out
}

private fun FeedEntry.isSplittableMessage(): Boolean =
    this is FeedEntry.Message &&
        message.role != MessageRole.USER &&
        !message.streaming &&
        message.text.isNotEmpty()

// -------------------------------------------------------------- intermediates

private sealed interface FeedItem {
    val createdAt: String
    val turnId: String?

    data class Message(val message: OrchestrationMessage) : FeedItem {
        override val createdAt: String get() = message.createdAt
        override val turnId: String? get() = message.turnId
    }

    data class Group(
        val id: String,
        override val createdAt: String,
        override val turnId: String?,
        val activities: List<ActivityPresentation>,
    ) : FeedItem
}

/**
 * Port of `collapseDerivedWorkLogEntries`.
 *
 * Two different rules. Subagent rows collapse by *identity* — one row per
 * `taskId` no matter how far apart they are — while tool lifecycle rows collapse
 * only when *adjacent* and sharing a collapse key, and a terminal row never
 * absorbs another.
 */
internal fun collapseWorkLog(activities: List<ActivityPresentation>): List<ActivityPresentation> {
    if (activities.size < 2) return activities

    val result = ArrayList<ActivityPresentation>(activities.size)
    val taskRowIndex = HashMap<String, Int>()

    for (activity in activities) {
        val taskId = activity.taskId
        if (taskId != null) {
            val existing = taskRowIndex[taskId]
            if (existing != null) {
                // Keep the row's original position, take the newer content.
                result[existing] = activity
                continue
            }
            taskRowIndex[taskId] = result.size
            result.add(activity)
            continue
        }

        val previous = result.lastOrNull()
        val canCollapse = previous != null &&
            previous.taskId == null &&
            !previous.isTerminal() &&
            previous.collapseKey != null &&
            previous.collapseKey == activity.collapseKey

        if (canCollapse) {
            result[result.lastIndex] = activity
        } else {
            result.add(activity)
        }
    }

    return result
}

/**
 * Port of `groupAdjacentActivities`: a run of consecutive activities in the same
 * turn becomes one list item, so the lazy list sees one row per tool run rather
 * than one per tool call.
 */
private fun groupAdjacentActivities(
    messages: List<OrchestrationMessage>,
    activities: List<ActivityPresentation>,
): List<FeedItem> {
    val ordered = ArrayList<Any>(messages.size + activities.size)
    // Empty assistant messages would otherwise break an activity run in two.
    messages.filterTo(ordered) { it.text.isNotEmpty() || it.role == MessageRole.USER }
    ordered.addAll(activities)
    ordered.sortBy { if (it is OrchestrationMessage) it.createdAt else (it as ActivityPresentation).createdAt }

    val items = ArrayList<FeedItem>(ordered.size)
    var open: MutableList<ActivityPresentation>? = null
    var openTurn: String? = null

    fun closeGroup() {
        val batch = open ?: return
        val first = batch.first()
        items.add(
            FeedItem.Group(
                id = first.id,
                createdAt = first.createdAt,
                turnId = openTurn,
                activities = batch,
            ),
        )
        open = null
    }

    for (entry in ordered) {
        when (entry) {
            is OrchestrationMessage -> {
                closeGroup()
                items.add(FeedItem.Message(entry))
            }

            is ActivityPresentation -> {
                val batch = open
                if (batch != null && openTurn == entry.turnId) {
                    // Appending to a mutable batch keeps this linear; copying
                    // per activity made the equivalent TS loop quadratic.
                    batch.add(entry)
                } else {
                    closeGroup()
                    open = mutableListOf(entry)
                    openTurn = entry.turnId
                }
            }
        }
    }
    closeGroup()

    return items
}

/**
 * Applies turn folds and work-group collapsing.
 *
 * A settled turn shows a single fold row plus its final assistant message; the
 * running turn is left fully visible so live work stays legible.
 */
private fun present(
    items: List<FeedItem>,
    latestTurn: OrchestrationLatestTurn?,
    expansion: FeedExpansion,
): List<FeedEntry> {
    val activeTurnId = latestTurn?.takeIf { it.state == TurnState.RUNNING }?.turnId
    val streamingTurnIds = items.asSequence()
        .filterIsInstance<FeedItem.Message>()
        .filter { it.message.streaming }
        .mapNotNull { it.message.turnId }
        .toSet()

    // The last assistant message of a turn survives its fold.
    val terminalAssistantIds = items.asSequence()
        .filterIsInstance<FeedItem.Message>()
        .filter { it.message.role == MessageRole.ASSISTANT && it.message.turnId != null }
        .groupBy { it.message.turnId }
        .values
        .mapNotNull { group -> group.lastOrNull()?.message?.id }
        .toSet()

    val out = ArrayList<FeedEntry>(items.size)
    var foldedTurn: String? = null
    var foldedCount = 0
    var foldIndex = -1

    fun flushFold() {
        if (foldIndex < 0) return
        val turnId = foldedTurn ?: return
        val existing = out[foldIndex] as FeedEntry.TurnFold
        out[foldIndex] = existing.copy(hiddenCount = foldedCount)
        foldIndex = -1
        foldedTurn = null
        foldedCount = 0
    }

    for (item in items) {
        val turnId = item.turnId
        val foldable = turnId != null &&
            turnId != activeTurnId &&
            turnId !in streamingTurnIds &&
            turnId !in expansion.turns

        if (!foldable) {
            flushFold()
            out += item.toEntries(expansion)
            continue
        }

        if (turnId != foldedTurn) {
            flushFold()
            foldedTurn = turnId
            foldedCount = 0
            foldIndex = out.size
            out.add(
                FeedEntry.TurnFold(
                    turnId = turnId,
                    hiddenCount = 0,
                    interrupted = latestTurn?.turnId == turnId &&
                        latestTurn.state == TurnState.INTERRUPTED,
                    createdAt = item.createdAt,
                ),
            )
        }

        // The turn's closing assistant message stays visible above the fold.
        val keep = item is FeedItem.Message &&
            item.message.role == MessageRole.ASSISTANT &&
            item.message.id in terminalAssistantIds
        if (keep) {
            out += item.toEntries(expansion)
        } else {
            foldedCount += if (item is FeedItem.Group) item.activities.size else 1
        }
    }
    flushFold()

    // A fold that ended up hiding nothing is pure noise.
    return out.filterNot { it is FeedEntry.TurnFold && it.hiddenCount == 0 }
}

/**
 * Expands one item into rows, hiding all but the newest activity behind a
 * toggle. Tool rows with no signal (`neutral` status) are dropped outright —
 * they are the bulk of a long run and carry nothing the user can act on.
 */
private fun FeedItem.toEntries(expansion: FeedExpansion): List<FeedEntry> = when (this) {
    is FeedItem.Message -> listOf(FeedEntry.Message(message))

    is FeedItem.Group -> {
        val meaningful = activities.filterNot {
            it.isToolLike && it.status == ActivityStatus.Neutral
        }
        when {
            meaningful.isEmpty() -> emptyList()
            meaningful.size == 1 -> listOf(FeedEntry.Activity(meaningful.single()))
            else -> {
                val expanded = id in expansion.workGroups
                val visible = if (expanded) meaningful else listOf(meaningful.last())
                visible.map { FeedEntry.Activity(it) } + FeedEntry.WorkToggle(
                    groupId = id,
                    hiddenCount = meaningful.size - 1,
                    expanded = expanded,
                    onlyToolActivities = meaningful.all { it.isToolLike },
                    createdAt = meaningful.last().createdAt,
                )
            }
        }
    }
}
