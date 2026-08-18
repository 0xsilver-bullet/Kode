package com.silverbullet.kode.feature.threads.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.model.ChatAttachment

/**
 * The images on one sent message.
 *
 * Attachments carry ids, not bytes, so each one costs a round trip to turn into
 * a signed URL. That request is made per attachment and lazily — [produceState]
 * fires when the row first composes and the repository caches the answer, so
 * scrolling a long thread back and forth does not re-ask.
 */
@Composable
fun MessageAttachments(
    attachments: List<ChatAttachment>,
    resolveUrl: suspend (String) -> String?,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        attachments.forEach { attachment ->
            MessageAttachmentImage(
                attachment = attachment,
                resolveUrl = resolveUrl,
                onPreview = onPreview,
            )
        }
    }
}

@Composable
private fun MessageAttachmentImage(
    attachment: ChatAttachment,
    resolveUrl: suspend (String) -> String?,
    onPreview: (String) -> Unit,
) {
    // Keyed on the id: a recomposition for any other reason must not re-issue
    // the request, and a different attachment must not reuse this URL.
    //
    // Three states, not two: a null URL means the request has not answered yet,
    // while a *finished* request that produced nothing is a failure. Collapsing
    // them would leave a spinner running forever whenever the environment is
    // unreachable, which reads as a hung app rather than an offline one.
    val resolution by produceState<AttachmentUrl>(AttachmentUrl.Loading, attachment.id) {
        value = resolveUrl(attachment.id)
            ?.let { AttachmentUrl.Ready(it) }
            ?: AttachmentUrl.Unavailable
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // A fixed ratio rather than the image's own: the real aspect is
            // unknown until the bytes land, and letting the row resize
            // afterwards would shift everything above it in a reverse-laid-out
            // feed.
            .aspectRatio(1.3f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        when (val resolved = resolution) {
            AttachmentUrl.Loading -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )

            AttachmentUrl.Unavailable -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = KodeIcons.Image,
                    contentDescription = null,
                    tint = KodeTheme.colors.muted,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = attachment.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = KodeTheme.colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            is AttachmentUrl.Ready -> AsyncImage(
                model = resolved.url,
                contentDescription = attachment.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { onPreview(resolved.url) },
            )
        }
    }
}

/** Where one attachment's URL request has got to. */
private sealed interface AttachmentUrl {
    data object Loading : AttachmentUrl

    /** The request finished without a URL — offline, or the asset is gone. */
    data object Unavailable : AttachmentUrl

    data class Ready(val url: String) : AttachmentUrl
}

/**
 * Full-screen image preview.
 *
 * Deliberately plain — no pinch-zoom or swipe-to-close. Those need a gesture
 * library this client does not carry, and a tap-to-dismiss full-bleed view
 * already answers the question the thumbnail raises ("what is in that image?").
 */
@Composable
fun ImagePreviewDialog(model: Any, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(16.dp),
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(36.dp)
                    // Clipped before the background so the ripple stays circular.
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = KodeIcons.Close,
                    contentDescription = "Close preview",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
