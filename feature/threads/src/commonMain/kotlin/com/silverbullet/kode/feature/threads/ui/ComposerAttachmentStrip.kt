package com.silverbullet.kode.feature.threads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.feature.threads.domain.DraftAttachment

/**
 * The horizontally scrollable row of staged images, shown above the input.
 *
 * Renders nothing when empty rather than collapsing to a zero-height box, so
 * the composer's vertical rhythm does not depend on whether images are
 * attached.
 *
 * Thumbnails are loaded from [DraftAttachment.previewUri] — the platform handle
 * — never from the data URL, which would make the image loader decode several
 * megabytes of base64 per frame.
 */
@Composable
fun ComposerAttachmentStrip(
    attachments: List<DraftAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    onPreview: ((DraftAttachment) -> Unit)? = null,
    thumbnailSize: Dp = 64.dp,
) {
    if (attachments.isEmpty()) return

    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            AttachmentThumbnail(
                previewUri = attachment.previewUri,
                contentDescription = attachment.name,
                size = thumbnailSize,
                onClick = onPreview?.let { preview -> { preview(attachment) } },
                onRemove = { onRemove(attachment.id) },
            )
        }
    }
}

/**
 * One thumbnail with its remove control.
 *
 * The remove button sits inside the image's top-right corner over a scrim
 * rather than in a gutter: at this size a gutter would cost more layout than
 * the thumbnail itself. The scrim is what keeps the glyph legible over a light
 * photo.
 */
@Composable
private fun AttachmentThumbnail(
    previewUri: String,
    contentDescription: String,
    size: Dp,
    onRemove: () -> Unit,
    onClick: (() -> Unit)?,
) {
    Box(modifier = Modifier.size(size)) {
        AsyncImage(
            model = previewUri,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .let { if (onClick == null) it else it.clickable(onClick = onClick) },
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(20.dp)
                // Clipped before the background so the ripple stays circular.
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = KodeIcons.Close,
                contentDescription = "Remove $contentDescription",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
