package com.silverbullet.kode.feature.threads.presentation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.silverbullet.kode.core.common.DispatcherProvider
import com.silverbullet.kode.core.common.IdGenerator
import com.silverbullet.kode.core.common.ImagePickOutcome
import com.silverbullet.kode.core.common.ImagePicker
import com.silverbullet.kode.core.model.AttachmentLimits
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.core.model.InteractionMode
import com.silverbullet.kode.core.model.MessageRole
import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.ProviderOptionDescriptor
import com.silverbullet.kode.core.model.RuntimeMode
import com.silverbullet.kode.core.model.ThreadId
import com.silverbullet.kode.feature.threads.domain.DraftAttachment
import com.silverbullet.kode.feature.threads.domain.FeedEntry
import com.silverbullet.kode.feature.threads.domain.ModelOption
import com.silverbullet.kode.feature.threads.domain.appendPicked
import com.silverbullet.kode.feature.threads.domain.attachmentCapReason
import com.silverbullet.kode.feature.threads.domain.remainingAttachmentSlots
import com.silverbullet.kode.feature.threads.domain.removeAttachment
import com.silverbullet.kode.feature.threads.domain.toUploads
import com.silverbullet.kode.feature.threads.domain.ProviderCatalog
import com.silverbullet.kode.feature.threads.domain.applyOptionSelection
import com.silverbullet.kode.feature.threads.domain.lockedDriver
import com.silverbullet.kode.feature.threads.domain.modelChangeBlockedReason
import com.silverbullet.kode.feature.threads.domain.resolveOptionDescriptors
import com.silverbullet.kode.feature.threads.domain.FeedExpansion
import com.silverbullet.kode.feature.threads.domain.PendingApproval
import com.silverbullet.kode.feature.threads.domain.PendingUserInput
import com.silverbullet.kode.feature.threads.domain.QuestionDraft
import com.silverbullet.kode.feature.threads.domain.UserInputAnswers
import com.silverbullet.kode.feature.threads.domain.buildUserInputAnswers
import com.silverbullet.kode.feature.threads.domain.toggleOption
import com.silverbullet.kode.feature.threads.domain.SyncStatus
import com.silverbullet.kode.feature.threads.domain.ThreadDetailState
import com.silverbullet.kode.feature.threads.domain.ThreadsRepository
import com.silverbullet.kode.feature.threads.domain.buildFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

class ThreadDetailViewModel(
    private val environmentId: EnvironmentId,
    private val threadId: ThreadId,
    private val repository: ThreadsRepository,
    private val imagePicker: ImagePicker,
    private val idGenerator: IdGenerator,
    dispatchers: DispatcherProvider,
) : ViewModel() {

    private val expansion = MutableStateFlow(FeedExpansion())
    private val _composer = MutableStateFlow(ComposerState())
    private val userInputForm = MutableStateFlow(UserInputFormState())
    private val approvalForm = MutableStateFlow(ApprovalFormState())
    private val _interrupting = MutableStateFlow(false)

    /**
     * The screen's one thread subscription, shared by [approval], [userInput]
     * and [feed]. Collecting `repository.thread` in each `combine` opened three
     * `orchestration.subscribeThread` streams per screen — tripling socket
     * traffic, chunk decoding and reducer work for identical data. `flowOn`
     * keeps the decode/reduce fold off the main thread.
     */
    private val detail: StateFlow<ThreadDetailState> =
        repository.thread(environmentId, threadId)
            .flowOn(dispatchers.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ThreadDetailState(),
            )

    /** True while a stop request is in flight. */
    val interrupting: StateFlow<Boolean> = _interrupting.asStateFlow()

    /**
     * Whether this host can pick images at all. A constant, not a flow: the
     * binding is chosen at DI time and cannot change while the app runs.
     */
    val canAttachImages: Boolean get() = imagePicker.isAvailable

    /**
     * The thread's project directory on the environment's machine — the worktree when the
     * thread runs in one, else the project's workspace root. Consumed by the voice prompt
     * slot, which forwards it to the voice server for glossary/refinement context.
     */
    val projectDir: StateFlow<String?> =
        combine(detail, repository.shells) { detail, shells ->
            detail.thread?.worktreePath?.takeIf { it.isNotBlank() }
                ?: detail.thread?.projectId?.let { projectId ->
                    shells.firstOrNull { it.environmentId == environmentId }
                        ?.shell?.projects?.get(projectId)?.workspaceRoot
                }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = null,
        )

    /**
     * A snapshot of the newest conversation turns as (role, text) pairs, oldest first —
     * refinement context for a voice prompt. A snapshot, not a flow: it is read once at
     * refine time, and the voice dialog must not become another thread subscriber.
     */
    fun recentMessagesSnapshot(limit: Int = 6): List<Pair<String, String>> =
        detail.value.messages
            .filter { it.text.isNotBlank() }
            .takeLast(limit)
            .map { message ->
                val role = if (message.role == MessageRole.USER) "user" else "assistant"
                role to message.text
            }

    /**
     * Dispatches externally produced text (an accepted voice prompt) as a turn without
     * touching the composer draft.
     */
    suspend fun sendExternal(text: String): Result<Unit> {
        val current = feed.value
        // Images staged in the composer ride along. This is what makes "attach
        // first, then talk" work: the voice dialog never owns attachments, it
        // just triggers the same send the composer would have, and the pending
        // images are consumed by it.
        val attachments = _composer.value.attachments
        return repository.sendMessage(
            environmentId = environmentId,
            threadId = threadId,
            text = text,
            runtimeMode = current.runtimeMode ?: RuntimeMode.APPROVAL_REQUIRED,
            interactionMode = current.interactionMode ?: InteractionMode.DEFAULT,
            attachments = attachments.toUploads(),
        ).onSuccess {
            // Cleared only on success, and only the images this send carried:
            // anything attached while the dispatch was in flight survives.
            _composer.value = _composer.value.copy(
                attachments = _composer.value.attachments.filterNot { staged ->
                    attachments.any { it.id == staged.id }
                },
            )
        }.map { }
    }

    /** A signed URL for one sent attachment, or null when it cannot be resolved. */
    suspend fun attachmentUrl(attachmentId: String): String? =
        repository.attachmentUrl(environmentId, attachmentId)


    /**
     * Exposed separately from [feed] on purpose.
     *
     * Folding the composer into the same state object meant every keystroke
     * produced a new feed state, which rebuilt the lazy list's item provider and
     * re-ran every visible item's content lambda. Conversely, every streamed
     * token recomposed the text field. Keeping them apart breaks both directions.
     */
    val composer: StateFlow<ComposerState> = _composer.asStateFlow()

    /**
     * The approval card's state.
     *
     * Approvals win over questions in the footer: an approval gates one concrete
     * action the agent is part-way through, and answering it is a single tap.
     */
    val approval: StateFlow<ApprovalUiState> =
        combine(detail, approvalForm) { detail, form ->
            val pending = detail.activeApproval
                ?: return@combine ApprovalUiState()

            ApprovalUiState(
                pending = pending,
                collapsed = form.collapsedRequestIds.contains(pending.requestId),
                decidingWith = form.decisionFor(pending.requestId),
                error = form.error,
                otherPendingCount = (detail.pendingApprovals.size - 1).coerceAtLeast(0),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = ApprovalUiState(),
        )

    /**
     * The question card's state, kept out of [feed] for the same reason the
     * composer is: answering must not rebuild the feed on every keystroke, and a
     * streamed token must not recompose the card the user is typing into.
     */
    val userInput: StateFlow<UserInputUiState> =
        combine(detail, userInputForm) { detail, form ->
            val pending = detail.activeUserInput
                ?: return@combine UserInputUiState()

            // Drafts are keyed by request: a new request must not inherit the
            // previous one's answers.
            val drafts = form.draftsFor(pending.requestId)
            UserInputUiState(
                pending = pending,
                drafts = drafts,
                answers = buildUserInputAnswers(pending.questions, drafts),
                collapsed = form.collapsedRequestIds.contains(pending.requestId),
                isSubmitting = form.submittingRequestId == pending.requestId,
                error = form.error,
                highlightedQuestionId = form.highlightedQuestionId,
                otherPendingCount = (detail.pendingUserInputs.size - 1).coerceAtLeast(0),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UserInputUiState(),
        )

    /**
     * The presented feed.
     *
     * `flowOn` moves the fold/collapse pipeline off the main thread: it walks
     * every message and activity, and re-runs on every streamed delta.
     */
    val feed: StateFlow<ThreadFeedUiState> =
        combine(
            detail,
            expansion,
            repository.catalog(environmentId),
        ) { detail, expanded, catalog ->
            val selection = detail.thread?.modelSelection
            val selected = catalog.optionFor(selection)
            // Started means a turn, a message, or a session — matching
            // `threadHasStarted`. Using the session alone let a thread with
            // messages be re-pointed at another provider.
            val hasStarted = detail.thread?.latestTurn != null ||
                detail.messages.isNotEmpty() ||
                detail.session != null
            val locked = lockedDriver(
                hasStarted = hasStarted,
                sessionProviderName = detail.session?.providerName,
                currentDriver = selected?.driver,
            )
            ThreadFeedUiState(
                catalog = catalog,
                modelSelection = selection,
                selectedModel = selected,
                lockedDriver = locked,
                optionDescriptors = selected?.model
                    ?.resolveOptionDescriptors(selection?.options)
                    .orEmpty(),
                entries = buildFeed(
                    messages = detail.messages,
                    activities = detail.activities,
                    latestTurn = detail.thread?.latestTurn,
                    expansion = expanded,
                ),
                title = detail.thread?.title,
                isBusy = detail.isBusy,
                hasThread = detail.thread != null,
                syncStatus = detail.status,
                error = detail.error,
                streamingMessageId = detail.messages.lastOrNull { it.streaming }?.id,
                runtimeMode = detail.thread?.runtimeMode,
                interactionMode = detail.thread?.interactionMode,
                hasStartedSession = hasStarted,
            )
        }.flowOn(dispatchers.default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = ThreadFeedUiState(),
            )

    /**
     * Expansion lives here rather than in the rows.
     *
     * A row's `remember` dies when the lazy list scrolls it away, so per-row
     * state would silently reset. Hoisting it also keeps the rows skippable.
     */
    fun toggleTurn(turnId: String) {
        expansion.value = expansion.value.let { it.copy(turns = it.turns.toggle(turnId)) }
    }

    fun toggleWorkGroup(groupId: String) {
        expansion.value = expansion.value.let {
            it.copy(workGroups = it.workGroups.toggle(groupId))
        }
    }

    /**
     * Stops the running turn.
     *
     * No turn id: the contract makes it optional and omitting it interrupts
     * whichever turn is running, which is what a Stop button means. The
     * indicator clears when the session leaves `running`, not when this
     * returns — a dispatched interrupt is a request, not a completed stop.
     */
    fun interruptTurn() {
        if (_interrupting.value) return

        viewModelScope.launch {
            _interrupting.value = true
            val result = repository.interruptTurn(environmentId, threadId = threadId, turnId = null)
            _interrupting.value = false

            result.exceptionOrNull()?.let { failure ->
                _composer.value = _composer.value.copy(
                    error = failure.message ?: "Could not stop the turn.",
                )
            }
        }
    }

    /**
     * Changes the thread's model, or reports why it cannot change.
     *
     * The refusal is not "no changes after the first message" — it is that some
     * providers cannot switch mid-conversation, so the block depends on which
     * provider you are coming from and going to.
     */
    fun selectModel(option: ModelOption) {
        val state = feed.value
        val blocked = modelChangeBlockedReason(
            current = state.modelSelection,
            next = option.selection,
            catalog = state.catalog,
            hasStartedSession = state.hasStartedSession,
            lockedDriver = state.lockedDriver,
        )
        if (blocked != null) {
            _composer.value = _composer.value.copy(error = blocked)
            return
        }

        viewModelScope.launch {
            repository.setModelSelection(environmentId, threadId, option.selection)
                .exceptionOrNull()
                ?.let { failure ->
                    _composer.value = _composer.value.copy(
                        error = failure.message ?: "Could not change the model.",
                    )
                }
        }
    }

    /**
     * Changes one per-model option, such as reasoning effort.
     *
     * The whole selection list is rewritten each time: the contract stores
     * options on the model selection, not as a patch.
     */
    fun selectModelOption(id: String, value: JsonPrimitive) {
        val state = feed.value
        val selection = state.modelSelection ?: return
        val next = state.optionDescriptors.applyOptionSelection(id, value) ?: return

        viewModelScope.launch {
            repository.setModelSelection(environmentId, threadId, selection.copy(options = next))
                .exceptionOrNull()
                ?.let { failure ->
                    _composer.value = _composer.value.copy(
                        error = failure.message ?: "Could not change that option.",
                    )
                }
        }
    }

    fun selectRuntimeMode(mode: String) {
        viewModelScope.launch {
            repository.setRuntimeMode(environmentId, threadId, mode).exceptionOrNull()?.let { failure ->
                _composer.value = _composer.value.copy(
                    error = failure.message ?: "Could not change permissions.",
                )
            }
        }
    }

    fun selectInteractionMode(mode: String) {
        viewModelScope.launch {
            repository.setInteractionMode(environmentId, threadId, mode).exceptionOrNull()?.let { failure ->
                _composer.value = _composer.value.copy(
                    error = failure.message ?: "Could not change the mode.",
                )
            }
        }
    }

    fun toggleApprovalCollapsed() {
        val requestId = approval.value.pending?.requestId ?: return
        approvalForm.value = approvalForm.value.let { form ->
            form.copy(
                collapsedRequestIds = if (requestId in form.collapsedRequestIds) {
                    form.collapsedRequestIds - requestId
                } else {
                    form.collapsedRequestIds + requestId
                },
            )
        }
    }

    /**
     * Sends a decision.
     *
     * The card stays put afterwards: the request closes when its
     * `approval.resolved` activity arrives, not when the dispatch returns.
     * Clearing it optimistically would hide a request that the provider may
     * still be waiting on.
     */
    fun decideApproval(decision: String) {
        val pending = approval.value.pending ?: return
        if (approval.value.isDeciding) return

        viewModelScope.launch {
            approvalForm.value = approvalForm.value.copy(
                decisions = approvalForm.value.decisions + (pending.requestId to decision),
                error = null,
            )

            val result = repository.respondToApproval(
                environmentId = environmentId,
                threadId = threadId,
                requestId = pending.requestId,
                decision = decision,
            )

            val failure = result.exceptionOrNull()
            if (failure != null) {
                approvalForm.value = approvalForm.value.copy(
                    decisions = approvalForm.value.decisions - pending.requestId,
                    error = failure.message ?: "Could not send the decision.",
                )
            }
        }
    }

    fun toggleUserInputCollapsed() {
        val requestId = userInput.value.pending?.requestId ?: return
        userInputForm.value = userInputForm.value.let { form ->
            form.copy(
                collapsedRequestIds = if (requestId in form.collapsedRequestIds) {
                    form.collapsedRequestIds - requestId
                } else {
                    form.collapsedRequestIds + requestId
                },
            )
        }
    }

    fun onOptionToggled(questionId: String, label: String) {
        val state = userInput.value
        val pending = state.pending ?: return
        val question = pending.questions.firstOrNull { it.id == questionId } ?: return

        updateDraft(pending.requestId, questionId) { it.toggleOption(question, label) }
    }

    fun onCustomAnswerChanged(questionId: String, value: String) {
        val pending = userInput.value.pending ?: return
        updateDraft(pending.requestId, questionId) { it.copy(customAnswer = value) }
    }

    /**
     * Submits, or points at the first gap.
     *
     * The button is never disabled: T3 Code disables it while any question is
     * unanswered, which leaves a dead control and no clue which one is missing.
     * Here an incomplete form highlights the first unanswered question instead,
     * and the UI scrolls to it.
     */
    fun submitUserInput() {
        val state = userInput.value
        val pending = state.pending ?: return
        if (state.isSubmitting) return

        val missing = state.answers.missingQuestionIds.firstOrNull()
        if (missing != null) {
            userInputForm.value = userInputForm.value.copy(
                highlightedQuestionId = missing,
                error = null,
            )
            return
        }

        viewModelScope.launch {
            userInputForm.value = userInputForm.value.copy(
                submittingRequestId = pending.requestId,
                error = null,
                highlightedQuestionId = null,
            )

            val result = repository.respondToUserInput(
                environmentId = environmentId,
                threadId = threadId,
                requestId = pending.requestId,
                answers = state.answers.answers,
            )

            val failure = result.exceptionOrNull()
            userInputForm.value = if (failure == null) {
                // Drafts are dropped only on success. The request itself closes
                // when its `user-input.resolved` activity arrives, not here.
                userInputForm.value.clearRequest(pending.requestId)
            } else {
                userInputForm.value.copy(
                    submittingRequestId = null,
                    error = failure.message ?: "Could not send the answers.",
                )
            }
        }
    }

    private fun updateDraft(
        requestId: String,
        questionId: String,
        transform: (QuestionDraft) -> QuestionDraft,
    ) {
        userInputForm.value = userInputForm.value.let { form ->
            val drafts = form.draftsFor(requestId)
            val next = transform(drafts[questionId] ?: QuestionDraft())
            form.copy(
                drafts = form.drafts + (requestId to (drafts + (questionId to next))),
                // Touching the highlighted question clears its marker.
                highlightedQuestionId = form.highlightedQuestionId.takeIf { it != questionId },
                error = null,
            )
        }
    }

    fun onDraftChanged(value: String) {
        _composer.value = _composer.value.copy(draft = value, error = null)
    }

    /**
     * Opens the system picker and stages what comes back.
     *
     * The cap is checked before the picker opens as well as after: opening a
     * picker that can only produce rejections is worse than saying so up front.
     */
    fun onPickImages() {
        val staged = _composer.value.attachments
        staged.attachmentCapReason()?.let { reason ->
            _composer.value = _composer.value.copy(error = reason)
            return
        }

        viewModelScope.launch {
            val outcome = imagePicker.pick(
                maxCount = staged.remainingAttachmentSlots(),
                maxBytes = AttachmentLimits.MAX_IMAGE_BYTES,
                supportedMimeTypes = AttachmentLimits.SUPPORTED_IMAGE_MIME_TYPES,
            )
            _composer.value = when (outcome) {
                is ImagePickOutcome.Picked -> _composer.value.copy(
                    attachments = _composer.value.attachments
                        .appendPicked(outcome.images, idGenerator::newId),
                    error = outcome.warning,
                )

                ImagePickOutcome.Cancelled -> _composer.value
                is ImagePickOutcome.Failed -> _composer.value.copy(error = outcome.message)
            }
        }
    }

    fun onRemoveAttachment(attachmentId: String) {
        _composer.value = _composer.value.copy(
            attachments = _composer.value.attachments.removeAttachment(attachmentId),
            error = null,
        )
    }

    /**
     * Dispatches the draft as a new turn.
     *
     * The draft is cleared optimistically: the server echoes the user message
     * back over the subscription within the same round trip, so keeping it in
     * the composer would show it twice.
     */
    fun send() {
        val text = _composer.value.draft.trim()
        val attachments = _composer.value.attachments
        // Images alone are a valid message — T3 Code sends a turn with empty
        // text when only attachments are staged.
        if ((text.isEmpty() && attachments.isEmpty()) || _composer.value.isSending) return

        // Inherit the thread's modes rather than forcing defaults, so we do not
        // strand the turn behind an approval this client cannot answer.
        val current = feed.value
        val runtimeMode = current.runtimeMode ?: RuntimeMode.APPROVAL_REQUIRED
        val interactionMode = current.interactionMode ?: InteractionMode.DEFAULT

        viewModelScope.launch {
            _composer.value = _composer.value.copy(
                isSending = true,
                draft = "",
                attachments = emptyList(),
                error = null,
            )

            val result = repository.sendMessage(
                environmentId = environmentId,
                threadId = threadId,
                text = text,
                runtimeMode = runtimeMode,
                interactionMode = interactionMode,
                attachments = attachments.toUploads(),
            )

            _composer.value = result.fold(
                onSuccess = { ComposerState() },
                onFailure = { failure ->
                    // Restore the draft *and* the images so a failed send loses
                    // neither — re-picking eight photos is not a recovery path.
                    ComposerState(
                        draft = text,
                        attachments = attachments,
                        error = failure.message ?: "Could not send the message.",
                    )
                },
            )
        }
    }

    private fun Set<String>.toggle(value: String): Set<String> =
        if (value in this) this - value else this + value

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

@Immutable
data class ThreadFeedUiState(
    val entries: List<FeedEntry> = emptyList(),
    val catalog: ProviderCatalog = ProviderCatalog(),
    val modelSelection: ModelSelection? = null,
    val selectedModel: ModelOption? = null,
    val hasStartedSession: Boolean = false,
    /** The driver this thread is pinned to once started, if any. */
    val lockedDriver: String? = null,
    val optionDescriptors: List<ProviderOptionDescriptor> = emptyList(),
    val title: String? = null,
    val isBusy: Boolean = false,
    val hasThread: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.Empty,
    val error: String? = null,
    /** The message currently growing, if any — it renders on the streaming path. */
    val streamingMessageId: String? = null,
    val runtimeMode: String? = null,
    val interactionMode: String? = null,
) {
    val isLoading: Boolean get() = !hasThread && error == null
}

@Immutable
data class ComposerState(
    val draft: String = "",
    /** Images staged for the next turn, in the order they were picked. */
    val attachments: List<DraftAttachment> = emptyList(),
    val isSending: Boolean = false,
    val error: String? = null,
) {
    /** Images with no text are a valid message; nothing at all is not. */
    val canSend: Boolean
        get() = (draft.isNotBlank() || attachments.isNotEmpty()) && !isSending

    val canAttach: Boolean get() = attachments.size < AttachmentLimits.MAX_ATTACHMENTS
}

/** Per-request answering state, held across collapse and scrolling. */
@Immutable
private data class UserInputFormState(
    val drafts: Map<String, Map<String, QuestionDraft>> = emptyMap(),
    val collapsedRequestIds: Set<String> = emptySet(),
    val submittingRequestId: String? = null,
    val highlightedQuestionId: String? = null,
    val error: String? = null,
) {
    fun draftsFor(requestId: String): Map<String, QuestionDraft> = drafts[requestId].orEmpty()

    fun clearRequest(requestId: String) = copy(
        drafts = drafts - requestId,
        collapsedRequestIds = collapsedRequestIds - requestId,
        submittingRequestId = null,
        highlightedQuestionId = null,
        error = null,
    )
}

@Immutable
data class UserInputUiState(
    val pending: PendingUserInput? = null,
    val drafts: Map<String, QuestionDraft> = emptyMap(),
    val answers: UserInputAnswers = UserInputAnswers(emptyMap(), emptyList()),
    val collapsed: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
    /** The question to scroll to and mark after an incomplete submit. */
    val highlightedQuestionId: String? = null,
    /** Requests queued behind this one, so the user knows more are coming. */
    val otherPendingCount: Int = 0,
) {
    val isActive: Boolean get() = pending != null
    val questionCount: Int get() = pending?.questions?.size ?: 0
    val answeredCount: Int get() = answers.answeredCount
}

/** Per-request approval state, held across collapse and scrolling. */
@Immutable
private data class ApprovalFormState(
    val collapsedRequestIds: Set<String> = emptySet(),
    /** The decision in flight per request, so the tapped button can show it. */
    val decisions: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    fun decisionFor(requestId: String): String? = decisions[requestId]
}

@Immutable
data class ApprovalUiState(
    val pending: PendingApproval? = null,
    val collapsed: Boolean = false,
    /** Non-null while a decision is being dispatched. */
    val decidingWith: String? = null,
    val error: String? = null,
    val otherPendingCount: Int = 0,
) {
    val isActive: Boolean get() = pending != null
    val isDeciding: Boolean get() = decidingWith != null
}
