package com.silverbullet.kode.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Provider driver kinds that have a vendor mark.
 *
 * These are the wire slugs — `ProviderDriverKind` in T3 Code's contracts, which
 * is deliberately an open string, so this is a list of the ones we can draw
 * rather than the set a server may report.
 */
object ProviderDriver {
    const val CLAUDE = "claudeAgent"
    const val OPENCODE = "opencode"
}

/**
 * Which agent is running a thread, as its vendor's logomark.
 *
 * Drawn with `Image` rather than `Icon` for the marks that have one: they carry
 * their own brand colours, and tinting them to the foreground would stop them
 * being the vendor's mark. Every other driver — the servers can report any slug
 * — falls back to the tinted generic agent glyph, which says "some agent" and is
 * honest about not knowing which.
 */
@Composable
fun ProviderMark(
    driver: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    val brandMark: ImageVector? = when (driver) {
        ProviderDriver.CLAUDE -> KodeBrandMarks.Claude
        ProviderDriver.OPENCODE ->
            if (KodeTheme.isDark) KodeBrandMarks.OpenCodeDark else KodeBrandMarks.OpenCodeLight

        else -> null
    }

    if (brandMark != null) {
        Image(imageVector = brandMark, contentDescription = contentDescription, modifier = modifier)
    } else {
        Icon(
            imageVector = KodeIcons.Agent,
            contentDescription = contentDescription,
            tint = KodeTheme.colors.muted,
            modifier = modifier,
        )
    }
}
