package com.silverbullet.kode.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The twelve activity glyphs T3 Code's feed uses.
 *
 * Hand-built rather than pulled from an icon library: `material-icons-core` is
 * only published up to Compose 1.7.x, and mixing it with Compose 1.11 invites
 * version conflicts for twelve small shapes. T3 Code itself uses Tabler icons,
 * so these are shape-equivalent rather than pixel-identical.
 *
 * Each is a stroked 24dp vector, so `Icon(tint = …)` recolours it correctly.
 */
object KodeIcons {
    val Agent: ImageVector by lazy {
        strokeIcon("Agent") {
            // Head outline with two eyes and an antenna.
            moveTo(5f, 9f); lineTo(19f, 9f); lineTo(19f, 19f); lineTo(5f, 19f); close()
            moveTo(12f, 5f); lineTo(12f, 9f)
            moveTo(9f, 13f); lineTo(9f, 15f)
            moveTo(15f, 13f); lineTo(15f, 15f)
        }
    }

    val Alert: ImageVector by lazy {
        strokeIcon("Alert") {
            circle(12f, 12f, 9f)
            moveTo(12f, 7f); lineTo(12f, 13f)
            moveTo(12f, 16.5f); lineTo(12f, 16.6f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check") {
            moveTo(4f, 12.5f); lineTo(9f, 17.5f); lineTo(20f, 6.5f)
        }
    }

    /** A shell prompt: chevron plus a cursor rule. */
    val Command: ImageVector by lazy {
        strokeIcon("Command") {
            moveTo(5f, 7f); lineTo(10f, 12f); lineTo(5f, 17f)
            moveTo(12.5f, 17f); lineTo(19f, 17f)
        }
    }

    val Edit: ImageVector by lazy {
        strokeIcon("Edit") {
            moveTo(4f, 20f); lineTo(4f, 16f); lineTo(15f, 5f); lineTo(19f, 9f)
            lineTo(8f, 20f); close()
            moveTo(13f, 7f); lineTo(17f, 11f)
        }
    }

    val Eye: ImageVector by lazy {
        strokeIcon("Eye") {
            moveTo(2.5f, 12f)
            quadTo(12f, 4.5f, 21.5f, 12f)
            quadTo(12f, 19.5f, 2.5f, 12f)
            close()
            circle(12f, 12f, 3f)
        }
    }

    val Globe: ImageVector by lazy {
        strokeIcon("Globe") {
            circle(12f, 12f, 9f)
            moveTo(3f, 12f); lineTo(21f, 12f)
            // Meridian, drawn as two mirrored arcs.
            moveTo(12f, 3f)
            arcTo(5f, 9f, 0f, true, true, 12f, 21f)
            arcTo(5f, 9f, 0f, true, true, 12f, 3f)
        }
    }

    val Hammer: ImageVector by lazy {
        strokeIcon("Hammer") {
            moveTo(4f, 20f); lineTo(12f, 12f)
            moveTo(10f, 10f); lineTo(14f, 14f)
            moveTo(13f, 5f); lineTo(19f, 11f); lineTo(16f, 14f); lineTo(10f, 8f); close()
        }
    }

    val Message: ImageVector by lazy {
        strokeIcon("Message") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(9f, 16f)
            lineTo(5f, 20f); lineTo(5f, 16f); lineTo(4f, 16f); close()
        }
    }

    val Warning: ImageVector by lazy {
        strokeIcon("Warning") {
            moveTo(12f, 4f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close()
            moveTo(12f, 10f); lineTo(12f, 15f)
            moveTo(12f, 17.5f); lineTo(12f, 17.6f)
        }
    }

    val Wrench: ImageVector by lazy {
        strokeIcon("Wrench") {
            moveTo(15.5f, 3.5f)
            quadTo(20.5f, 5f, 19f, 10f)
            quadTo(17.5f, 12f, 14.5f, 11.5f)
            lineTo(5.5f, 20.5f)
            lineTo(3.5f, 18.5f)
            lineTo(12.5f, 9.5f)
            quadTo(12f, 6.5f, 14f, 5f)
            close()
        }
    }

    /** Points down when collapsed; rotate 180° to point up. */
    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown") {
            moveTo(6f, 9.5f); lineTo(12f, 15.5f); lineTo(18f, 9.5f)
        }
    }

    /** Send: an upward arrow, as used inside a composer field. */
    val ArrowUp: ImageVector by lazy {
        strokeIcon("ArrowUp") {
            moveTo(12f, 19f); lineTo(12f, 5f)
            moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("Plus") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(5f, 12f); lineTo(19f, 12f)
        }
    }

    /** Dismiss: removing a staged attachment, closing the image preview. */
    val Close: ImageVector by lazy {
        strokeIcon("Close") {
            moveTo(6f, 6f); lineTo(18f, 18f)
            moveTo(18f, 6f); lineTo(6f, 18f)
        }
    }

    /** Attachments: a framed photo with a sun and a horizon fold. */
    val Image: ImageVector by lazy {
        strokeIcon("Image") {
            moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 19f); lineTo(4f, 19f); close()
            moveTo(8.5f, 9.5f); lineTo(8.6f, 9.5f)
            moveTo(4f, 16f); lineTo(9f, 11f); lineTo(14f, 16f)
            moveTo(13f, 15f); lineTo(16f, 12f); lineTo(20f, 16f)
        }
    }

    val Zap: ImageVector by lazy {
        strokeIcon("Zap") {
            moveTo(13.5f, 3f); lineTo(5f, 13.5f); lineTo(11.5f, 13.5f)
            lineTo(10.5f, 21f); lineTo(19f, 10.5f); lineTo(12.5f, 10.5f); close()
        }
    }

    /**
     * Settings: Tabler's `settings` gear — eight lobes around a hub.
     *
     * Two concentric circles with four stubby ticks, which is what this was,
     * reads as a crosshair at 24dp, not a gear. The lobed outline is the shape
     * people actually recognise, so it is transcribed from Tabler (T3 Code's
     * icon set) rather than approximated: each lobe is a cubic pushing out from
     * the hub, joined by the small arc that rounds its shoulder.
     */
    val Gear: ImageVector by lazy {
        strokeIcon("Gear") {
            moveTo(10.325f, 4.317f)
            curveToRelative(0.426f, -1.756f, 2.924f, -1.756f, 3.35f, 0f)
            arcToRelative(1.724f, 1.724f, 0f, false, false, 2.573f, 1.066f)
            curveToRelative(1.543f, -0.94f, 3.31f, 0.826f, 2.37f, 2.37f)
            arcToRelative(1.724f, 1.724f, 0f, false, false, 1.065f, 2.572f)
            curveToRelative(1.756f, 0.426f, 1.756f, 2.924f, 0f, 3.35f)
            arcToRelative(1.724f, 1.724f, 0f, false, false, -1.066f, 2.573f)
            curveToRelative(0.94f, 1.543f, -0.826f, 3.31f, -2.37f, 2.37f)
            arcToRelative(1.724f, 1.724f, 0f, false, false, -2.572f, 1.065f)
            curveToRelative(-0.426f, 1.756f, -2.924f, 1.756f, -3.35f, 0f)
            arcToRelative(1.724f, 1.724f, 0f, false, false, -2.573f, -1.066f)
            curveToRelative(-1.543f, 0.94f, -3.31f, -0.826f, -2.37f, -2.37f)
            arcToRelative(1.724f, 1.724f, 0f, false, false, -1.065f, -2.572f)
            curveToRelative(-1.756f, -0.426f, -1.756f, -2.924f, 0f, -3.35f)
            arcToRelative(1.724f, 1.724f, 0f, false, false, 1.066f, -2.573f)
            curveToRelative(-0.94f, -1.543f, 0.826f, -3.31f, 2.37f, -2.37f)
            curveToRelative(1f, 0.608f, 2.296f, 0.07f, 2.572f, -1.065f)
            close()
            circle(12f, 12f, 3f)
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
            moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f)
            moveTo(10f, 10.5f); lineTo(10f, 16.5f)
            moveTo(14f, 10.5f); lineTo(14f, 16.5f)
        }
    }

    /** Reconnect: a circular arrow. */
    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") {
            moveTo(19f, 12f)
            arcTo(7f, 7f, 0f, true, true, 17f, 7f)
            moveTo(17f, 3f); lineTo(17f, 7f); lineTo(21f, 7f)
        }
    }

    val QrCode: ImageVector by lazy {
        strokeIcon("QrCode") {
            // Three finder squares plus a data dot.
            moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
            moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
            moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
            moveTo(14f, 14f); lineTo(16.5f, 14f)
            moveTo(20f, 14f); lineTo(20f, 16.5f)
            moveTo(14f, 17.5f); lineTo(14f, 20f); lineTo(17f, 20f)
            moveTo(20f, 20f); lineTo(20f, 20.1f)
        }
    }

    /** A desktop environment: monitor plus stand. */
    val Monitor: ImageVector by lazy {
        strokeIcon("Monitor") {
            moveTo(3.5f, 5f); lineTo(20.5f, 5f); lineTo(20.5f, 16f); lineTo(3.5f, 16f); close()
            moveTo(12f, 16f); lineTo(12f, 19.5f)
            moveTo(8f, 19.5f); lineTo(16f, 19.5f)
        }
    }

    /** Row disclosure: points into the target screen. */
    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
        }
    }

    /**
     * Git actions: Tabler's `git-merge` layout — two nodes on a trunk with a
     * branch curving into a third, matching the glyph T3 Code maps its git
     * control to on non-SF platforms.
     */
    val GitBranch: ImageVector by lazy {
        strokeIcon("GitBranch") {
            circle(7f, 6f, 2.5f)
            circle(7f, 18f, 2.5f)
            circle(17f, 12f, 2.5f)
            moveTo(7f, 8.5f); lineTo(7f, 15.5f)
            moveTo(7f, 8.5f)
            quadTo(7f, 12f, 14.5f, 12f)
        }
    }

    /** Pull: a straight-down arrow, mirroring [ArrowUp]. */
    val ArrowDown: ImageVector by lazy {
        strokeIcon("ArrowDown") {
            moveTo(12f, 5f); lineTo(12f, 19f)
            moveTo(6f, 13f); lineTo(12f, 19f); lineTo(18f, 13f)
        }
    }

    /** Open externally: an arrow escaping to the top-right. */
    val ArrowUpRight: ImageVector by lazy {
        strokeIcon("ArrowUpRight") {
            moveTo(7f, 17f); lineTo(17f, 7f)
            moveTo(9f, 7f); lineTo(17f, 7f); lineTo(17f, 15f)
        }
    }

    /** Voice prompt: a capsule mic on its cradle. */
    val Mic: ImageVector by lazy {
        strokeIcon("Mic") {
            moveTo(9f, 5f)
            arcTo(3f, 3f, 0f, false, true, 15f, 5f)
            lineTo(15f, 11f)
            arcTo(3f, 3f, 0f, false, true, 9f, 11f)
            close()
            moveTo(5f, 11f)
            arcTo(7f, 7f, 0f, false, false, 19f, 11f)
            moveTo(12f, 18f); lineTo(12f, 21f)
            moveTo(8f, 21f); lineTo(16f, 21f)
        }
    }
}

/** Approximates a circle with two half arcs, which is all `PathBuilder` offers. */
private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
    moveTo(centerX - radius, centerY)
    arcTo(radius, radius, 0f, true, true, centerX + radius, centerY)
    arcTo(radius, radius, 0f, true, true, centerX - radius, centerY)
}

private fun strokeIcon(name: String, build: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            // Black is a placeholder: `Icon` applies a tint filter over the
            // whole vector, so the stroke takes the caller's colour.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
    }.build()
