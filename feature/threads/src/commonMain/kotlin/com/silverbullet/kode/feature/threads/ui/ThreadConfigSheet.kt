package com.silverbullet.kode.feature.threads.ui

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.ProviderOptionDescriptor
import com.silverbullet.kode.feature.threads.domain.INTERACTION_MODE_CHOICES
import com.silverbullet.kode.feature.threads.domain.ModelOption
import com.silverbullet.kode.feature.threads.domain.ProviderCatalog
import com.silverbullet.kode.feature.threads.domain.RUNTIME_MODE_CHOICES
import com.silverbullet.kode.feature.threads.domain.currentLabel
import com.silverbullet.kode.feature.threads.domain.currentValueOrDefault
import com.silverbullet.kode.feature.threads.domain.runtimeModeLabel
import com.silverbullet.kode.feature.threads.domain.selectableChoices
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Everything the config sheet renders and mutates. */
@androidx.compose.runtime.Immutable
data class ThreadConfig(
    val catalog: ProviderCatalog,
    val selectedModel: ModelOption?,
    val lockedDriver: String?,
    val optionDescriptors: List<ProviderOptionDescriptor>,
    val runtimeMode: String?,
    val interactionMode: String?,
)

/**
 * One sheet for the whole agent configuration: which model, its tunables, and
 * how much it is allowed to do.
 *
 * Deliberately a single sheet rather than a pill per setting. Reasoning,
 * context window and fast mode belong to the chosen model, so splitting them
 * across separate pickers both crowded the composer and hid that relationship.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadConfigSheet(
    config: ThreadConfig,
    onModelSelected: (ModelOption) -> Unit,
    onOptionSelected: (String, JsonPrimitive) -> Unit,
    onRuntimeModeSelected: (String) -> Unit,
    onInteractionModeSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = KodeTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showLegacy by remember { mutableStateOf(false) }
    // Which sub-list is open, if any. A drill-in rather than a second sheet so
    // the choice lands back in context.
    var detail by remember { mutableStateOf<String?>(null) }
    // One scroll state per pane, hoisted above the pane swap. The root list is
    // far taller than any drill-in, so sharing a single state let the shrinking
    // content clamp the offset to zero and the model list always came back from
    // the top. While a drill-in is showing the root state is simply detached,
    // and therefore never re-clamped.
    val rootScroll = rememberScrollState()
    val detailScroll = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        val open = detail
        // A fresh drill-in always starts at its own top.
        LaunchedEffect(open) {
            if (open != null) detailScroll.scrollTo(0)
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(if (open == null) rootScroll else detailScroll),
            ) {
                when (open) {
                    null -> ConfigRoot(
                        config = config,
                        showLegacy = showLegacy,
                        onToggleLegacy = { showLegacy = !showLegacy },
                        onModelSelected = onModelSelected,
                        onOptionSelected = onOptionSelected,
                        onOpenDetail = { detail = it },
                    )

                    RUNTIME_DETAIL -> DetailList(
                        title = "Runtime",
                        entries = RUNTIME_MODE_CHOICES.map {
                            Triple(it.mode, it.label, it.description)
                        },
                        selectedId = config.runtimeMode,
                        onBack = { detail = null },
                        onSelect = {
                            onRuntimeModeSelected(it)
                            detail = null
                        },
                    )

                    MODE_DETAIL -> DetailList(
                        title = "Mode",
                        entries = INTERACTION_MODE_CHOICES.map {
                            Triple(it.mode, it.label, it.description)
                        },
                        selectedId = config.interactionMode,
                        onBack = { detail = null },
                        onSelect = {
                            onInteractionModeSelected(it)
                            detail = null
                        },
                    )

                    else -> {
                        val descriptor = config.optionDescriptors.firstOrNull { it.id == open }
                        if (descriptor == null) {
                            // The option vanished under us (model switched while
                            // drilled in); fall back to the root on the next frame.
                            LaunchedEffect(open) { detail = null }
                        } else {
                            DetailList(
                                title = descriptor.label,
                                entries = descriptor.selectableChoices().map {
                                    Triple(it.id, it.label, it.description)
                                },
                                selectedId = descriptor.currentValueOrDefault()
                                    ?.takeIf { it.isString }?.content,
                                onBack = { detail = null },
                                onSelect = {
                                    onOptionSelected(descriptor.id, JsonPrimitive(it))
                                    detail = null
                                },
                            )
                        }
                    }
                }
            }

            // Outside the scroller: reaching Done used to mean scrolling past
            // the entire model list.
            Button(
                onClick = onDismiss,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
            ) {
                Text("Done")
            }
        }
    }
}

private const val RUNTIME_DETAIL = "__runtime"
private const val MODE_DETAIL = "__mode"

@Composable
private fun ConfigRoot(
    config: ThreadConfig,
    showLegacy: Boolean,
    onToggleLegacy: () -> Unit,
    onModelSelected: (ModelOption) -> Unit,
    onOptionSelected: (String, JsonPrimitive) -> Unit,
    onOpenDetail: (String) -> Unit,
) {
    val colors = KodeTheme.colors
    val offered = config.catalog.offered(config.lockedDriver, showLegacy)

    if (config.catalog.hasLegacyModels) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = if (showLegacy) "Hide legacy models" else "Show legacy models",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceContainerHigh,
                        RoundedCornerShape(999.dp),
                    )
                    .clickable(onClick = onToggleLegacy)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }

    // Grouped by provider, exactly as the desktop and mobile pickers present it.
    offered.groupBy { it.providerLabel }.forEach { (providerLabel, models) ->
        Text(
            text = providerLabel.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = colors.muted,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )

        models.forEach { option ->
            val selected = option.instanceId == config.selectedModel?.instanceId &&
                option.model.slug == config.selectedModel.model.slug

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            androidx.compose.ui.graphics.Color.Transparent
                        },
                        RoundedCornerShape(12.dp),
                    )
                    .clickable { onModelSelected(option) }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Icon(
                        imageVector = KodeIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    if (config.optionDescriptors.isNotEmpty() || config.runtimeMode != null) {
        HorizontalDivider(
            color = colors.divider,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }

    config.optionDescriptors.forEach { descriptor ->
        when {
            descriptor.isBoolean -> ToggleRow(
                label = descriptor.label,
                checked = descriptor.currentValue?.booleanOrNull == true,
                onCheckedChange = { onOptionSelected(descriptor.id, JsonPrimitive(it)) },
            )

            descriptor.isSelect && descriptor.selectableChoices().isNotEmpty() -> ValueRow(
                label = descriptor.label,
                value = descriptor.currentLabel() ?: "Default",
                onClick = { onOpenDetail(descriptor.id) },
            )
        }
    }

    config.runtimeMode?.let {
        ValueRow(
            label = "Runtime",
            value = runtimeModeLabel(it),
            onClick = { onOpenDetail(RUNTIME_DETAIL) },
        )
    }

    config.interactionMode?.let { mode ->
        ValueRow(
            label = "Mode",
            value = INTERACTION_MODE_CHOICES.firstOrNull { it.mode == mode }?.label ?: mode,
            onClick = { onOpenDetail(MODE_DETAIL) },
        )
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = KodeTheme.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            imageVector = KodeIcons.ChevronDown,
            contentDescription = null,
            tint = KodeTheme.colors.muted,
            modifier = Modifier
                .padding(start = 6.dp)
                .size(16.dp)
                .graphicsLayer { rotationZ = -90f },
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DetailList(
    title: String,
    entries: List<Triple<String, String, String?>>,
    selectedId: String?,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = KodeIcons.ChevronDown,
            contentDescription = "Back",
            tint = KodeTheme.colors.muted,
            modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = 90f },
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp),
        )
    }

    entries.forEach { (id, label, description) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(id) }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                description?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = KodeTheme.colors.muted,
                    )
                }
            }
            if (id == selectedId) {
                Icon(
                    imageVector = KodeIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
