package com.silverbullet.kode.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * One button in a [SwipeReveal] panel: a filled circle above a caption, filling
 * the column's full height so the whole strip is tappable.
 *
 * The proportions are T3 Code mobile's `SwipeActionButton` — a 36dp circle with
 * a 15dp glyph and a caption beneath. The circle carries the colour rather than
 * the column, so the list's own background stays behind the panel and the row
 * reads as sliding over the top of it instead of out of a coloured well.
 *
 * The caption only repeats the icon, so it is hidden from accessibility and
 * [contentDescription] speaks for the button as a whole.
 */
@Composable
fun SwipeRevealAction(
    icon: ImageVector,
    label: String,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Closing the row is part of performing the action, not the caller's job.
    val click = rememberSwipeRevealAction(onClick)

    Column(
        modifier = modifier
            .width(SwipeRevealDefaults.ActionsWidth)
            .fillMaxHeight()
            .clickable(role = Role.Button, onClick = click)
            .semantics { this.contentDescription = contentDescription },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(CircleSize)
                .background(containerColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(IconSize),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = KodeTheme.colors.muted,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .padding(top = LabelSpacing)
                .clearAndSetSemantics { },
        )
    }
}

private val CircleSize = 36.dp
private val IconSize = 15.dp
private val LabelSpacing = 4.dp
