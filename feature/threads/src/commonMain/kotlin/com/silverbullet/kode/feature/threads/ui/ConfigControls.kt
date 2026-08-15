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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme

/**
 * A compact pill that opens a picker.
 *
 * Sized to sit in a row above the composer without crowding it, which is why
 * the value is allowed to ellipsise rather than wrap.
 */
@Composable
fun ConfigPill(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = KodeTheme.colors
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(999.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                colors.muted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (enabled) {
            Icon(
                imageVector = KodeIcons.ChevronDown,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

/** One selectable row inside a picker sheet. */
data class PickerEntry(
    val id: String,
    val label: String,
    val description: String? = null,
    val group: String? = null,
    val enabled: Boolean = true,
)

/**
 * A bottom-sheet picker.
 *
 * A sheet rather than a dropdown because the model list can be long and grouped
 * by provider, and a dropdown anchored to a pill above the keyboard would have
 * almost no room.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(
    title: String,
    entries: List<PickerEntry>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    val colors = KodeTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )

            note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.warning,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(entries, key = { it.id }, contentType = { "entry" }) { entry ->
                    // Group headers repeat only when the group changes, so a
                    // provider with many models is labelled once.
                    val index = entries.indexOf(entry)
                    val previousGroup = entries.getOrNull(index - 1)?.group
                    if (entry.group != null && entry.group != previousGroup) {
                        Text(
                            text = entry.group.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.muted,
                            modifier = Modifier.padding(
                                start = 20.dp,
                                end = 20.dp,
                                top = 12.dp,
                                bottom = 4.dp,
                            ),
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = entry.enabled) { onSelect(entry.id) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = KodeIcons.Check,
                            contentDescription = null,
                            tint = if (entry.id == selectedId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                            modifier = Modifier.size(18.dp),
                        )
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = entry.label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (entry.enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    colors.muted
                                },
                            )
                            entry.description?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.muted,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
