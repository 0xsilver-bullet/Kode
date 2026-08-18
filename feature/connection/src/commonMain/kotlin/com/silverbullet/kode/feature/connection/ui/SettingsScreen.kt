package com.silverbullet.kode.feature.connection.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.silverbullet.kode.core.designsystem.KodeIcons
import com.silverbullet.kode.core.designsystem.KodeTheme
import com.silverbullet.kode.core.session.EnvironmentFleet
import org.koin.compose.koinInject

/**
 * The settings root, mirroring T3 Code mobile's `SettingsRouteScreen` layout:
 * captioned sections of grouped rows. Only the parts that apply to this client
 * are ported — the Configuration section with the Environments row; cloud
 * account, notifications and Live Activities are relay features Kode does not
 * have.
 */
@Composable
fun SettingsRoute(
    onOpenEnvironments: () -> Unit,
    onOpenVoice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The fleet's state flows are already lifecycle-friendly; a view model here
    // would only forward one count.
    val fleet = koinInject<EnvironmentFleet>()
    val environments by fleet.environments.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        SettingsSection(title = "Configuration") {
            SettingsRow(
                icon = KodeIcons.Monitor,
                label = "Environments",
                value = environments?.size?.toString() ?: "",
                onClick = onOpenEnvironments,
            )
            SettingsRow(
                icon = KodeIcons.Mic,
                label = "Voice",
                onClick = onOpenVoice,
            )
        }
    }
}

/** A muted caption above a rounded card of rows, as in T3's `SettingsSection`. */
@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = KodeTheme.colors.muted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Card(shape = RoundedCornerShape(24.dp)) {
            Column { content() }
        }
    }
}

/** Icon, label, optional right-aligned value, disclosure chevron. */
@Composable
fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    value: String = "",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = KodeTheme.colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = KodeIcons.ChevronRight,
            contentDescription = null,
            tint = KodeTheme.colors.muted,
            modifier = Modifier.size(16.dp),
        )
    }
}
