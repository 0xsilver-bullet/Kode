package com.silverbullet.kode.feature.threads.domain

import androidx.compose.runtime.Immutable
import com.silverbullet.kode.core.model.InteractionMode
import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.RuntimeMode
import com.silverbullet.kode.core.model.ServerProvider
import com.silverbullet.kode.core.model.ServerProviderModel

/**
 * The models a thread can actually be pointed at.
 *
 * Built from `ServerConfig.providers`: a provider instance is offerable only
 * when it is both enabled and available, matching `isProviderAvailable` plus
 * the `enabled` check in `serverSettings.ts`. Everything else is filtered out
 * rather than shown disabled — a model that cannot be chosen is noise.
 */
@Immutable
data class ProviderCatalog(
    val options: List<ModelOption> = emptyList(),
) {
    val isEmpty: Boolean get() = options.isEmpty()

    fun optionFor(selection: ModelSelection?): ModelOption? {
        if (selection == null) return null
        return options.firstOrNull {
            it.instanceId == selection.instanceId && it.model.slug == selection.model
        }
    }

    /**
     * What a new thread should start on: the provider's own default, else the
     * first offered model.
     */
    fun defaultSelection(): ModelSelection? {
        val offered = options.filterNot { it.model.isLegacy }
        return (offered.firstOrNull { it.model.isDefault } ?: offered.firstOrNull())?.selection
    }

    /** Models offered for a thread, honouring the driver lock and legacy toggle. */
    fun offered(lockedDriver: String?, includeLegacy: Boolean): List<ModelOption> = options
        .filter { includeLegacy || !it.model.isLegacy }
        .filter { lockedDriver == null || it.driver == lockedDriver }

    val hasLegacyModels: Boolean get() = options.any { it.model.isLegacy }

    /** Grouped for rendering, preserving server order within each provider. */
    fun byProvider(): List<ProviderGroup> = options
        .groupBy { it.instanceId }
        .map { (instanceId, group) ->
            ProviderGroup(
                instanceId = instanceId,
                providerLabel = group.first().providerLabel,
                options = group,
            )
        }
}

@Immutable
data class ModelOption(
    val instanceId: String,
    val providerLabel: String,
    /** The driver kind (`claude`, `opencode`, …) — what a started thread locks to. */
    val driver: String,
    val continuationGroupKey: String? = null,
    val model: ServerProviderModel,
    /** True when switching to or from this provider needs a fresh thread. */
    val requiresNewThreadForModelChange: Boolean,
) {
    val selection: ModelSelection get() = ModelSelection(instanceId = instanceId, model = model.slug)
    val label: String get() = model.label
}

@Immutable
data class ProviderGroup(
    val instanceId: String,
    val providerLabel: String,
    val options: List<ModelOption>,
)

fun List<ServerProvider>.toCatalog(): ProviderCatalog = ProviderCatalog(
    options = filter { it.isUsable }.flatMap { provider ->
        // Legacy models are kept so the sheet can offer them behind a toggle,
        // matching T3 Code. They are excluded from defaults below.
        provider.models
            .map { model ->
                ModelOption(
                    instanceId = provider.instanceId,
                    providerLabel = provider.label,
                    driver = provider.driver,
                    continuationGroupKey = provider.continuation?.groupKey,
                    model = model,
                    requiresNewThreadForModelChange = provider.requiresNewThreadForModelChange,
                )
            }
    },
)

/**
 * The driver a started thread is pinned to, or null while it is still free.
 *
 * Port of `deriveLockedProvider`. A thread counts as started once it has a
 * turn, a message, or a session — not merely a session, which was too narrow
 * and let a thread with messages be re-pointed at another provider.
 *
 * The lock is the **driver kind**, not the instance: a Claude thread cannot be
 * handed to OpenCode, because the provider owns the conversation state.
 */
fun lockedDriver(
    hasStarted: Boolean,
    sessionProviderName: String?,
    currentDriver: String?,
): String? {
    if (!hasStarted) return null
    return sessionProviderName?.takeIf { it.isNotBlank() } ?: currentDriver
}

/**
 * Why a model change is refused, or null if it is allowed.
 *
 * Three separate rules, in the order T3 Code applies them:
 *  1. a started thread is locked to its driver — `deriveLockedProvider`;
 *  2. within that driver, instances in different continuation groups cannot
 *     take over each other's conversations;
 *  3. some providers refuse any mid-conversation model change at all
 *     (`requiresNewThreadForModelChange`).
 */
fun modelChangeBlockedReason(
    current: ModelSelection?,
    next: ModelSelection,
    catalog: ProviderCatalog,
    hasStartedSession: Boolean,
    lockedDriver: String? = null,
): String? {
    if (!hasStartedSession) return null
    if (current != null && current.instanceId == next.instanceId && current.model == next.model) {
        return null
    }

    val nextOption = catalog.options.firstOrNull { it.instanceId == next.instanceId }

    if (lockedDriver != null && nextOption != null && nextOption.driver != lockedDriver) {
        return "This thread is running on $lockedDriver. " +
            "Start a new thread to use ${nextOption.providerLabel}."
    }

    val currentOption = current?.let { sel ->
        catalog.options.firstOrNull { it.instanceId == sel.instanceId }
    }

    // Different continuation groups cannot resume one another's conversation.
    val currentGroup = currentOption?.continuationGroupKey
    val nextGroup = nextOption?.continuationGroupKey
    if (currentGroup != null && nextGroup != null && currentGroup != nextGroup) {
        return "${nextOption.providerLabel} cannot continue a conversation started by " +
            "${currentOption.providerLabel}. Start a new thread instead."
    }

    if (current == null) return null

    val currentBlocks = currentOption?.requiresNewThreadForModelChange == true
    val nextBlocks = nextOption?.requiresNewThreadForModelChange == true
    if (!currentBlocks && !nextBlocks) return null

    return "Start a new thread to change models. " +
        "This provider does not allow switching models after a conversation has started."
}

/** Runtime modes with the labels T3 Code's phone picker uses. */
@Immutable
data class RuntimeModeChoice(
    val mode: String,
    val label: String,
    val description: String,
)

val RUNTIME_MODE_CHOICES: List<RuntimeModeChoice> = listOf(
    RuntimeModeChoice(
        mode = RuntimeMode.APPROVAL_REQUIRED,
        label = "Supervised",
        description = "Ask before commands and file changes.",
    ),
    RuntimeModeChoice(
        mode = RuntimeMode.AUTO_ACCEPT_EDITS,
        label = "Auto-accept edits",
        description = "Auto-approve edits, ask before other actions.",
    ),
    RuntimeModeChoice(
        mode = RuntimeMode.AUTO,
        label = "Auto",
        description = "Supported providers approve routine actions; others still ask.",
    ),
    RuntimeModeChoice(
        mode = RuntimeMode.FULL_ACCESS,
        label = "Full access",
        description = "Allow commands and edits without prompts.",
    ),
)

fun runtimeModeLabel(mode: String): String =
    RUNTIME_MODE_CHOICES.firstOrNull { it.mode == mode }?.label ?: mode

val INTERACTION_MODE_CHOICES: List<RuntimeModeChoice> = listOf(
    RuntimeModeChoice(
        mode = InteractionMode.DEFAULT,
        label = "Build",
        description = "The agent edits and runs things directly.",
    ),
    RuntimeModeChoice(
        mode = InteractionMode.PLAN,
        label = "Plan",
        description = "The agent proposes a plan before acting.",
    ),
)
