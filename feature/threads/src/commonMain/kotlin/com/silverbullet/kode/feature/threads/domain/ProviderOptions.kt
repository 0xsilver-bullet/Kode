package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ProviderOptionChoice
import com.silverbullet.kode.core.model.ProviderOptionDescriptor
import com.silverbullet.kode.core.model.ProviderOptionSelection
import com.silverbullet.kode.core.model.ServerProviderModel
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Per-model tunables — reasoning effort, thinking budget, fast mode — resolved
 * against whatever the thread has already chosen.
 *
 * Port of `getProviderOptionDescriptors`: the model advertises the descriptors,
 * the thread's `modelSelection.options` overrides their current values.
 */
fun ServerProviderModel.resolveOptionDescriptors(
    selections: List<ProviderOptionSelection>?,
): List<ProviderOptionDescriptor> {
    val descriptors = capabilities?.optionDescriptors.orEmpty()
    if (descriptors.isEmpty()) return emptyList()

    return descriptors.map { descriptor ->
        val chosen = selections?.firstOrNull { it.id == descriptor.id }?.value
        if (chosen == null) descriptor else descriptor.copy(currentValue = chosen)
    }
}

/**
 * The choices worth offering for a select.
 *
 * Port of `selectableChoices`: prompt-injected values are set by the provider
 * itself, and `ultracode` is a workflow trigger rather than a reasoning level.
 * A value set elsewhere still displays — it just is not offered.
 */
fun ProviderOptionDescriptor.selectableChoices(): List<ProviderOptionChoice> {
    val injected = promptInjectedValues.toSet()
    return options.filterNot { it.id in injected || it.id in HIDDEN_OPTION_IDS }
}

private val HIDDEN_OPTION_IDS = setOf("ultracode")

/**
 * The value in effect, falling back to the descriptor's own default.
 *
 * Port of `getProviderOptionCurrentValue`.
 */
fun ProviderOptionDescriptor.currentValueOrDefault(): JsonPrimitive? {
    if (isBoolean) return currentValue
    currentValue?.let { return it }
    return options.firstOrNull { it.isDefault }?.let { JsonPrimitive(it.id) }
}

/** The label to show for the value in effect, if any. */
fun ProviderOptionDescriptor.currentLabel(): String? {
    if (isBoolean) return label.takeIf { currentValue?.booleanOrNull == true }
    val value = currentValueOrDefault()?.contentOrNull() ?: return null
    return options.firstOrNull { it.id == value }?.label
}

/**
 * A short summary of everything currently switched on, for the composer pill.
 *
 * Port of `providerOptionValueLabels`.
 */
fun List<ProviderOptionDescriptor>.activeOptionLabels(): List<String> =
    mapNotNull { it.currentLabel() }

/**
 * Applies one change and returns the full selection list to store.
 *
 * Returns null when the change does not match an advertised descriptor or
 * choice — the same rejection `applyProviderOptionSelection` performs, so a
 * stale picker cannot write a value the model does not accept.
 */
fun List<ProviderOptionDescriptor>.applyOptionSelection(
    id: String,
    value: JsonPrimitive,
): List<ProviderOptionSelection>? {
    val descriptor = firstOrNull { it.id == id } ?: return null

    val valid = when {
        descriptor.isBoolean -> value.booleanOrNull != null
        descriptor.isSelect ->
            value.contentOrNull()?.let { chosen -> descriptor.options.any { it.id == chosen } } == true

        else -> false
    }
    if (!valid) return null

    return map { candidate ->
        if (candidate.id == id) candidate.copy(currentValue = value) else candidate
    }.mapNotNull { candidate ->
        candidate.currentValueOrDefault()?.let { ProviderOptionSelection(candidate.id, it) }
    }
}

/** JSON strings carry quotes in `toString`; this is the unquoted content. */
private fun JsonPrimitive.contentOrNull(): String? = if (isString) content else null
