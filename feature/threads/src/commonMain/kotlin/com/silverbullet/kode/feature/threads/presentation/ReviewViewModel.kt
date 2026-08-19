package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.common.DispatcherProvider
import com.silverbullet.kode.core.model.CheckpointStatus
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.OrchestrationCheckpointSummary
import com.silverbullet.kode.core.model.ReviewDiffPreviewSource
import com.silverbullet.kode.core.model.ReviewDiffSourceKind
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.ThreadDetailState
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import com.silverbullet.kode.feature.threads.domain.git.GitRepository
import com.silverbullet.kode.feature.threads.domain.git.gitFailureMessage
import com.silverbullet.kode.feature.threads.domain.review.ReviewRow
import com.silverbullet.kode.feature.threads.domain.review.ReviewRowData
import com.silverbullet.kode.feature.threads.domain.review.buildReviewRows
import com.silverbullet.kode.feature.threads.domain.review.parseUnifiedDiff
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One entry of the section switcher. */
@Immutable
data class ReviewSectionUi(
    val id: String,
    val title: String,
    val subtitle: String?,
)

@Immutable
data class ReviewUiState(
    val sections: List<ReviewSectionUi> = emptyList(),
    val selectedId: String? = null,
    /** The visible rows: file headers always, bodies of expanded files only. */
    val rows: List<ReviewRow> = emptyList(),
    /** The longest content line of the selected diff, for the pan surface. */
    val maxLineLength: Int = 0,
    val collapsedFileIds: Set<String> = emptySet(),
    val viewedFileIds: Set<String> = emptySet(),
    val fileCount: Int = 0,
    /** True while the selected section's diff is being fetched or parsed. */
    val loading: Boolean = true,
    /** Amber banner: the server hit its 120 KB patch cap. */
    val truncated: Boolean = false,
    val error: String? = null,
    /** Set when the selected section parsed clean but holds no changes. */
    val emptyMessage: String? = null,
)

/**
 * The Review Changes screen: per-turn checkpoint sections (from the thread
 * subscription's `checkpoints`) plus the two git sections from
 * `review.getDiffPreview`, each parsed into renderable rows off the main
 * thread and cached per diff.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModel(
    private val environmentId: EnvironmentId,
    private val threadId: ThreadId,
    repository: ThreadsRepository,
    private val gitRepository: GitRepository,
    dispatchers: DispatcherProvider,
) : ViewModel() {

    private val detail = repository.thread(environmentId, threadId)
        .flowOn(dispatchers.default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ThreadDetailState(),
        )

    /** The thread's working directory — same derivation as the thread screen. */
    private val projectDir: StateFlow<String?> =
        combine(detail, repository.shells) { detail, shells ->
            detail.thread?.worktreePath?.takeIf { it.isNotBlank() }
                ?: detail.thread?.projectId?.let { projectId ->
                    shells.firstOrNull { it.environmentId == environmentId }
                        ?.shell?.projects?.get(projectId)?.workspaceRoot
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    private val readyCheckpoints: StateFlow<List<OrchestrationCheckpointSummary>> = detail
        .map { state ->
            state.checkpoints
                .filter { it.status == CheckpointStatus.READY }
                .sortedByDescending { it.checkpointTurnCount }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val preview = MutableStateFlow<PreviewState>(PreviewState.Loading)
    private val turnDiffs = MutableStateFlow<Map<String, TurnDiffState>>(emptyMap())
    private val selected = MutableStateFlow<String?>(null)
    private val collapsed = MutableStateFlow<Set<String>>(emptySet())
    private val viewed = MutableStateFlow<Set<String>>(emptySet())

    /** Parsed rows per section, keyed by the diff's identity — parse each once. */
    private val rowCache = HashMap<String, ReviewRowData>()

    init {
        // Fetch the git preview once the directory is known, and refetch when a
        // new checkpoint lands — a completed turn changed the working tree, so
        // both git sections are stale. This is the `thread.turn-diff-completed`
        // freshness path.
        viewModelScope.launch {
            combine(
                projectDir.filterNotNull(),
                readyCheckpoints.map { checkpoints -> checkpoints.firstOrNull()?.turnId },
            ) { dir, _ -> dir }
                .collect { dir -> fetchPreview(dir) }
        }

        // Fetch a turn section's diff when it is first selected — including the
        // *default* selection: with no explicit choice, the first section is
        // the newest turn, so its diff must load without a tap.
        viewModelScope.launch {
            combine(selected, readyCheckpoints) { id, checkpoints ->
                val effective = id ?: checkpoints.firstOrNull()?.let { turnSectionId(it) }
                checkpoints.firstOrNull { turnSectionId(it) == effective }
            }.collect { checkpoint ->
                if (checkpoint != null) fetchTurnDiffIfNeeded(checkpoint)
            }
        }
    }

    val state: StateFlow<ReviewUiState> = combine(
        readyCheckpoints,
        preview,
        selected,
        turnDiffs,
        combine(collapsed, viewed) { c, v -> c to v },
    ) { checkpoints, preview, selectedId, turnDiffs, folding ->
        buildState(checkpoints, preview, selectedId, turnDiffs, folding.first, folding.second)
    }
        .flowOn(dispatchers.default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ReviewUiState(),
        )

    fun selectSection(id: String) {
        selected.value = id
    }

    fun toggleFileCollapsed(fileId: String) {
        collapsed.value = collapsed.value.toggle(fileId)
    }

    /** Marking viewed auto-collapses; unmarking expands — t3code's rule. */
    fun toggleFileViewed(fileId: String) {
        val nowViewed = fileId !in viewed.value
        viewed.value = viewed.value.toggle(fileId)
        collapsed.value = if (nowViewed) collapsed.value + fileId else collapsed.value - fileId
    }

    fun refresh() {
        val dir = projectDir.value ?: return
        viewModelScope.launch { fetchPreview(dir) }
        // A turn's checkpoint diff is immutable (pinned by checkpointRef), so
        // only the git sections refetch.
    }

    // ------------------------------------------------------------------ internals

    private suspend fun fetchPreview(dir: String) {
        preview.value = when (val current = preview.value) {
            is PreviewState.Ready -> current // keep showing data while refreshing
            else -> PreviewState.Loading
        }
        gitRepository.diffPreview(environmentId, dir).fold(
            onSuccess = { result -> preview.value = PreviewState.Ready(result.sources) },
            onFailure = { failure ->
                val existing = preview.value
                preview.value = if (existing is PreviewState.Ready) {
                    existing // a failed refresh keeps the last good preview
                } else {
                    PreviewState.Failed(gitFailureMessage(failure, "Could not load the diff."))
                }
            },
        )
    }

    private fun fetchTurnDiffIfNeeded(checkpoint: OrchestrationCheckpointSummary) {
        val id = turnSectionId(checkpoint)
        if (turnDiffs.value[id] != null) return
        turnDiffs.value += id to TurnDiffState.Loading
        viewModelScope.launch {
            gitRepository.checkpointDiff(environmentId, threadId, checkpoint.checkpointTurnCount)
                .fold(
                    onSuccess = { turnDiffs.value += id to TurnDiffState.Ready(it.diff) },
                    onFailure = { failure ->
                        turnDiffs.value += id to TurnDiffState.Failed(
                            gitFailureMessage(failure, "Could not load this turn's diff."),
                        )
                    },
                )
        }
    }

    private fun buildState(
        checkpoints: List<OrchestrationCheckpointSummary>,
        preview: PreviewState,
        selectedId: String?,
        turnDiffs: Map<String, TurnDiffState>,
        collapsedIds: Set<String>,
        viewedIds: Set<String>,
    ): ReviewUiState {
        val sections = buildSections(checkpoints, preview)
        val effectiveId = selectedId?.takeIf { id -> sections.any { it.id == id } }
            ?: sections.firstOrNull()?.id

        val diff: DiffLookup = when {
            // No sections at all: a failed preview must surface as the error
            // rather than an eternal spinner.
            effectiveId == null -> when (preview) {
                is PreviewState.Failed -> DiffLookup.Failed(preview.message)
                else -> DiffLookup.Loading
            }
            effectiveId.startsWith(TURN_SECTION_PREFIX) -> when (val turn = turnDiffs[effectiveId]) {
                null, TurnDiffState.Loading -> DiffLookup.Loading
                is TurnDiffState.Failed -> DiffLookup.Failed(turn.message)
                is TurnDiffState.Ready -> DiffLookup.Ready(turn.diff, truncated = false)
            }

            else -> when (preview) {
                PreviewState.Loading -> DiffLookup.Loading
                is PreviewState.Failed -> DiffLookup.Failed(preview.message)
                is PreviewState.Ready -> {
                    val source = preview.sources.firstOrNull { gitSectionId(it) == effectiveId }
                    if (source == null) {
                        DiffLookup.Failed("This section is no longer available.")
                    } else {
                        DiffLookup.Ready(source.diff, source.truncated)
                    }
                }
            }
        }

        return when (diff) {
            DiffLookup.Loading -> ReviewUiState(
                sections = sections,
                selectedId = effectiveId,
                collapsedFileIds = collapsedIds,
                viewedFileIds = viewedIds,
                loading = true,
            )

            is DiffLookup.Failed -> ReviewUiState(
                sections = sections,
                selectedId = effectiveId,
                collapsedFileIds = collapsedIds,
                viewedFileIds = viewedIds,
                loading = false,
                error = diff.message,
            )

            is DiffLookup.Ready -> {
                val cacheKey = "$effectiveId:${diff.text.length}:${diff.text.hashCode()}"
                // Bounded eviction: entries go stale as previews refresh, and a
                // long thread accumulates turn sections.
                if (rowCache.size > ROW_CACHE_LIMIT && cacheKey !in rowCache) rowCache.clear()
                val data = rowCache.getOrPut(cacheKey) {
                    buildReviewRows(parseUnifiedDiff(diff.text))
                }
                val rows = ArrayList<ReviewRow>(data.blocks.sumOf { it.body.size + 1 })
                var maxLineLength = 0
                for (block in data.blocks) {
                    rows.add(block.header)
                    if (block.header.fileId !in collapsedIds) {
                        rows.addAll(block.body)
                        if (block.maxLineLength > maxLineLength) maxLineLength = block.maxLineLength
                    }
                }
                ReviewUiState(
                    sections = sections,
                    selectedId = effectiveId,
                    rows = rows,
                    maxLineLength = maxLineLength,
                    collapsedFileIds = collapsedIds,
                    viewedFileIds = viewedIds,
                    fileCount = data.blocks.size,
                    loading = false,
                    truncated = diff.truncated || data.truncated,
                    emptyMessage = if (data.isEmpty) "No changes in this section." else null,
                )
            }
        }
    }

    private fun buildSections(
        checkpoints: List<OrchestrationCheckpointSummary>,
        preview: PreviewState,
    ): List<ReviewSectionUi> {
        val sections = ArrayList<ReviewSectionUi>(checkpoints.size + 2)
        for (checkpoint in checkpoints) {
            val additions = checkpoint.files.sumOf { it.additions }
            val deletions = checkpoint.files.sumOf { it.deletions }
            val files = checkpoint.files.size
            sections.add(
                ReviewSectionUi(
                    id = turnSectionId(checkpoint),
                    title = "Turn ${checkpoint.checkpointTurnCount}",
                    subtitle = buildString {
                        append(if (files == 1) "1 file" else "$files files")
                        if (additions > 0 || deletions > 0) append(" · +$additions −$deletions")
                    },
                ),
            )
        }

        val sources = (preview as? PreviewState.Ready)?.sources.orEmpty()
        for (source in sources) {
            sections.add(
                ReviewSectionUi(
                    id = gitSectionId(source),
                    title = source.title,
                    subtitle = when (source.kind) {
                        ReviewDiffSourceKind.WORKING_TREE ->
                            "Tracked, staged, and untracked worktree changes"

                        ReviewDiffSourceKind.BRANCH_RANGE ->
                            source.baseRef?.let { base -> "$base ... ${source.headRef ?: "HEAD"}" }
                                ?: "Base branch unavailable"

                        else -> null
                    },
                ),
            )
        }
        return sections
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    private sealed interface PreviewState {
        data object Loading : PreviewState
        data class Ready(val sources: List<ReviewDiffPreviewSource>) : PreviewState
        data class Failed(val message: String) : PreviewState
    }

    private sealed interface TurnDiffState {
        data object Loading : TurnDiffState
        data class Ready(val diff: String) : TurnDiffState
        data class Failed(val message: String) : TurnDiffState
    }

    private sealed interface DiffLookup {
        data object Loading : DiffLookup
        data class Ready(val text: String, val truncated: Boolean) : DiffLookup
        data class Failed(val message: String) : DiffLookup
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        const val TURN_SECTION_PREFIX = "turn:"
        const val ROW_CACHE_LIMIT = 24

        fun turnSectionId(checkpoint: OrchestrationCheckpointSummary): String =
            "$TURN_SECTION_PREFIX${checkpoint.checkpointTurnCount}"

        fun gitSectionId(source: ReviewDiffPreviewSource): String = "git:${source.kind}"
    }
}
