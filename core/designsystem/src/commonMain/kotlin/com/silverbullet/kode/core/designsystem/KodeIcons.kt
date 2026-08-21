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
 * Every glyph the app draws, as hand-owned [ImageVector]s.
 *
 * These are the Tabler outline icons — the same set T3 Code uses — converted
 * from their SVGs one-for-one, rather than pulled from an icon library. Two
 * reasons: `material-icons-core` is only published up to Compose 1.7.x, and
 * every icon pack on Maven ships thousands of icons that only shrink back down
 * if R8 runs, which the release build does not currently enable. Owning the 28
 * we use means the APK carries exactly 28.
 *
 * The SVG sources and the converter live in `core/designsystem/icons/`; see the
 * README there before adding a glyph.
 *
 * Each is a stroked 24dp vector, so `Icon(tint = …)` recolours it correctly.
 */
object KodeIcons {
    /** The agent itself: shown for thinking-tone activity. */
    val Agent: ImageVector by lazy {
        strokeIcon("Agent") { // tabler/robot
            moveTo(6f, 6f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f);
            horizontalLineToRelative(8f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f);
            verticalLineToRelative(4f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f);
            horizontalLineToRelative(-8f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f);
            lineToRelative(0f, -4f)
            moveTo(12f, 2f); verticalLineToRelative(2f)
            moveTo(9f, 12f); verticalLineToRelative(9f)
            moveTo(15f, 12f); verticalLineToRelative(9f)
            moveTo(5f, 16f); lineToRelative(4f, -2f)
            moveTo(15f, 14f); lineToRelative(4f, 2f)
            moveTo(9f, 18f); horizontalLineToRelative(6f)
            moveTo(10f, 8f); verticalLineToRelative(0.01f)
            moveTo(14f, 8f); verticalLineToRelative(0.01f)
        }
    }

    /** Error-tone activity. */
    val Alert: ImageVector by lazy {
        strokeIcon("Alert") { // tabler/alert-circle
            moveTo(3f, 12f); arcToRelative(9f, 9f, 0f, true, false, 18f, 0f);
            arcToRelative(9f, 9f, 0f, false, false, -18f, 0f)
            moveTo(12f, 8f); verticalLineToRelative(4f)
            moveTo(12f, 16f); horizontalLineToRelative(0.01f)
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check") { // tabler/check
            moveTo(5f, 12f); lineToRelative(5f, 5f); lineToRelative(10f, -10f)
        }
    }

    /** Shell commands, and command-approval requests. */
    val Command: ImageVector by lazy {
        strokeIcon("Command") { // tabler/terminal-2
            moveTo(8f, 9f); lineToRelative(3f, 3f); lineToRelative(-3f, 3f)
            moveTo(13f, 15f); lineToRelative(3f, 0f)
            moveTo(3f, 6f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f);
            horizontalLineToRelative(14f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f);
            verticalLineToRelative(12f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f);
            horizontalLineToRelative(-14f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f);
            lineToRelative(0f, -12f)
        }
    }

    /** File changes, and file-change approval requests. */
    val Edit: ImageVector by lazy {
        strokeIcon("Edit") { // tabler/pencil
            moveTo(4f, 20f); horizontalLineToRelative(4f); lineToRelative(10.5f, -10.5f);
            arcToRelative(2.828f, 2.828f, 0f, true, false, -4f, -4f);
            lineToRelative(-10.5f, 10.5f); verticalLineToRelative(4f)
            moveTo(13.5f, 6.5f); lineToRelative(4f, 4f)
        }
    }

    /** File reads, image views, and diff previews. */
    val Eye: ImageVector by lazy {
        strokeIcon("Eye") { // tabler/eye
            moveTo(10f, 12f); arcToRelative(2f, 2f, 0f, true, false, 4f, 0f);
            arcToRelative(2f, 2f, 0f, false, false, -4f, 0f)
            moveTo(21f, 12f); curveToRelative(-2.4f, 4f, -5.4f, 6f, -9f, 6f);
            curveToRelative(-3.6f, 0f, -6.6f, -2f, -9f, -6f);
            curveToRelative(2.4f, -4f, 5.4f, -6f, 9f, -6f);
            curveToRelative(3.6f, 0f, 6.6f, 2f, 9f, 6f)
        }
    }

    /** Web search. */
    val Globe: ImageVector by lazy {
        strokeIcon("Globe") { // tabler/world
            moveTo(3f, 12f); arcToRelative(9f, 9f, 0f, true, false, 18f, 0f);
            arcToRelative(9f, 9f, 0f, false, false, -18f, 0f)
            moveTo(3.6f, 9f); horizontalLineToRelative(16.8f)
            moveTo(3.6f, 15f); horizontalLineToRelative(16.8f)
            moveTo(11.5f, 3f); arcToRelative(17f, 17f, 0f, false, false, 0f, 18f)
            moveTo(12.5f, 3f); arcToRelative(17f, 17f, 0f, false, true, 0f, 18f)
        }
    }

    /** Dynamic tool calls. */
    val Hammer: ImageVector by lazy {
        strokeIcon("Hammer") { // tabler/hammer
            moveTo(11.414f, 10f); lineToRelative(-7.383f, 7.418f);
            arcToRelative(2.091f, 2.091f, 0f, false, false, 0f, 2.967f);
            arcToRelative(2.11f, 2.11f, 0f, false, false, 2.976f, 0f);
            lineToRelative(7.407f, -7.385f)
            moveTo(18.121f, 15.293f); lineToRelative(2.586f, -2.586f);
            arcToRelative(1f, 1f, 0f, false, false, 0f, -1.414f);
            lineToRelative(-7.586f, -7.586f);
            arcToRelative(1f, 1f, 0f, false, false, -1.414f, 0f);
            lineToRelative(-2.586f, 2.586f);
            arcToRelative(1f, 1f, 0f, false, false, 0f, 1.414f);
            lineToRelative(7.586f, 7.586f);
            arcToRelative(1f, 1f, 0f, false, false, 1.414f, 0f)
        }
    }

    /** User-input requests and their resolutions. */
    val Message: ImageVector by lazy {
        strokeIcon("Message") { // tabler/message
            moveTo(8f, 9f); horizontalLineToRelative(8f)
            moveTo(8f, 13f); horizontalLineToRelative(6f)
            moveTo(18f, 4f); arcToRelative(3f, 3f, 0f, false, true, 3f, 3f);
            verticalLineToRelative(8f); arcToRelative(3f, 3f, 0f, false, true, -3f, 3f);
            horizontalLineToRelative(-5f); lineToRelative(-5f, 3f);
            verticalLineToRelative(-3f); horizontalLineToRelative(-2f);
            arcToRelative(3f, 3f, 0f, false, true, -3f, -3f); verticalLineToRelative(-8f);
            arcToRelative(3f, 3f, 0f, false, true, 3f, -3f); horizontalLineToRelative(12f)
        }
    }

    /** Runtime warnings, and interrupted turns. */
    val Warning: ImageVector by lazy {
        strokeIcon("Warning") { // tabler/alert-triangle
            moveTo(12f, 9f); verticalLineToRelative(4f)
            moveTo(10.363f, 3.591f); lineToRelative(-8.106f, 13.534f);
            arcToRelative(1.914f, 1.914f, 0f, false, false, 1.636f, 2.871f);
            horizontalLineToRelative(16.214f);
            arcToRelative(1.914f, 1.914f, 0f, false, false, 1.636f, -2.87f);
            lineToRelative(-8.106f, -13.536f);
            arcToRelative(1.914f, 1.914f, 0f, false, false, -3.274f, 0f)
            moveTo(12f, 16f); horizontalLineToRelative(0.01f)
        }
    }

    /** MCP tool calls. */
    val Wrench: ImageVector by lazy {
        strokeIcon("Wrench") { // tabler/tool
            moveTo(7f, 10f); horizontalLineToRelative(3f); verticalLineToRelative(-3f);
            lineToRelative(-3.5f, -3.5f); arcToRelative(6f, 6f, 0f, false, true, 8f, 8f);
            lineToRelative(6f, 6f); arcToRelative(2f, 2f, 0f, false, true, -3f, 3f);
            lineToRelative(-6f, -6f); arcToRelative(6f, 6f, 0f, false, true, -8f, -8f);
            lineToRelative(3.5f, 3.5f)
        }
    }

    val ChevronDown: ImageVector by lazy {
        strokeIcon("ChevronDown") { // tabler/chevron-down
            moveTo(6f, 9f); lineToRelative(6f, 6f); lineToRelative(6f, -6f)
        }
    }

    val ArrowUp: ImageVector by lazy {
        strokeIcon("ArrowUp") { // tabler/arrow-up
            moveTo(12f, 5f); lineToRelative(0f, 14f)
            moveTo(18f, 11f); lineToRelative(-6f, -6f)
            moveTo(6f, 11f); lineToRelative(6f, -6f)
        }
    }

    val Plus: ImageVector by lazy {
        strokeIcon("Plus") { // tabler/plus
            moveTo(12f, 5f); lineToRelative(0f, 14f)
            moveTo(5f, 12f); lineToRelative(14f, 0f)
        }
    }

    val Close: ImageVector by lazy {
        strokeIcon("Close") { // tabler/x
            moveTo(18f, 6f); lineToRelative(-12f, 12f)
            moveTo(6f, 6f); lineToRelative(12f, 12f)
        }
    }

    val Image: ImageVector by lazy {
        strokeIcon("Image") { // tabler/photo
            moveTo(15f, 8f); horizontalLineToRelative(0.01f)
            moveTo(3f, 6f); arcToRelative(3f, 3f, 0f, false, true, 3f, -3f);
            horizontalLineToRelative(12f); arcToRelative(3f, 3f, 0f, false, true, 3f, 3f);
            verticalLineToRelative(12f); arcToRelative(3f, 3f, 0f, false, true, -3f, 3f);
            horizontalLineToRelative(-12f); arcToRelative(3f, 3f, 0f, false, true, -3f, -3f);
            verticalLineToRelative(-12f)
            moveTo(3f, 16f); lineToRelative(5f, -5f);
            curveToRelative(0.928f, -0.893f, 2.072f, -0.893f, 3f, 0f); lineToRelative(5f, 5f)
            moveTo(14f, 14f); lineToRelative(1f, -1f);
            curveToRelative(0.928f, -0.893f, 2.072f, -0.893f, 3f, 0f); lineToRelative(3f, 3f)
        }
    }

    /** The fallback glyph for tool-tone activity. */
    val Zap: ImageVector by lazy {
        strokeIcon("Zap") { // tabler/bolt
            moveTo(13f, 3f); lineToRelative(0f, 7f); lineToRelative(6f, 0f);
            lineToRelative(-8f, 11f); lineToRelative(0f, -7f); lineToRelative(-6f, 0f);
            lineToRelative(8f, -11f)
        }
    }

    val Gear: ImageVector by lazy {
        strokeIcon("Gear") { // tabler/settings
            moveTo(10.325f, 4.317f);
            curveToRelative(0.426f, -1.756f, 2.924f, -1.756f, 3.35f, 0f);
            arcToRelative(1.724f, 1.724f, 0f, false, false, 2.573f, 1.066f);
            curveToRelative(1.543f, -0.94f, 3.31f, 0.826f, 2.37f, 2.37f);
            arcToRelative(1.724f, 1.724f, 0f, false, false, 1.065f, 2.572f);
            curveToRelative(1.756f, 0.426f, 1.756f, 2.924f, 0f, 3.35f);
            arcToRelative(1.724f, 1.724f, 0f, false, false, -1.066f, 2.573f);
            curveToRelative(0.94f, 1.543f, -0.826f, 3.31f, -2.37f, 2.37f);
            arcToRelative(1.724f, 1.724f, 0f, false, false, -2.572f, 1.065f);
            curveToRelative(-0.426f, 1.756f, -2.924f, 1.756f, -3.35f, 0f);
            arcToRelative(1.724f, 1.724f, 0f, false, false, -2.573f, -1.066f);
            curveToRelative(-1.543f, 0.94f, -3.31f, -0.826f, -2.37f, -2.37f);
            arcToRelative(1.724f, 1.724f, 0f, false, false, -1.065f, -2.572f);
            curveToRelative(-1.756f, -0.426f, -1.756f, -2.924f, 0f, -3.35f);
            arcToRelative(1.724f, 1.724f, 0f, false, false, 1.066f, -2.573f);
            curveToRelative(-0.94f, -1.543f, 0.826f, -3.31f, 2.37f, -2.37f);
            curveToRelative(1f, 0.608f, 2.296f, 0.07f, 2.572f, -1.065f)
            moveTo(9f, 12f); arcToRelative(3f, 3f, 0f, true, false, 6f, 0f);
            arcToRelative(3f, 3f, 0f, false, false, -6f, 0f)
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") { // tabler/trash
            moveTo(4f, 7f); lineToRelative(16f, 0f)
            moveTo(10f, 11f); lineToRelative(0f, 6f)
            moveTo(14f, 11f); lineToRelative(0f, 6f)
            moveTo(5f, 7f); lineToRelative(1f, 12f);
            arcToRelative(2f, 2f, 0f, false, false, 2f, 2f); horizontalLineToRelative(8f);
            arcToRelative(2f, 2f, 0f, false, false, 2f, -2f); lineToRelative(1f, -12f)
            moveTo(9f, 7f); verticalLineToRelative(-3f);
            arcToRelative(1f, 1f, 0f, false, true, 1f, -1f); horizontalLineToRelative(4f);
            arcToRelative(1f, 1f, 0f, false, true, 1f, 1f); verticalLineToRelative(3f)
        }
    }

    val Refresh: ImageVector by lazy {
        strokeIcon("Refresh") { // tabler/refresh
            moveTo(20f, 11f); arcToRelative(8.1f, 8.1f, 0f, false, false, -15.5f, -2f)
            moveToRelative(-0.5f, -4f); verticalLineToRelative(4f);
            horizontalLineToRelative(4f)
            moveTo(4f, 13f); arcToRelative(8.1f, 8.1f, 0f, false, false, 15.5f, 2f)
            moveToRelative(0.5f, 4f); verticalLineToRelative(-4f);
            horizontalLineToRelative(-4f)
        }
    }

    val QrCode: ImageVector by lazy {
        strokeIcon("QrCode") { // tabler/qrcode
            moveTo(4f, 5f); arcToRelative(1f, 1f, 0f, false, true, 1f, -1f);
            horizontalLineToRelative(4f); arcToRelative(1f, 1f, 0f, false, true, 1f, 1f);
            verticalLineToRelative(4f); arcToRelative(1f, 1f, 0f, false, true, -1f, 1f);
            horizontalLineToRelative(-4f); arcToRelative(1f, 1f, 0f, false, true, -1f, -1f);
            lineToRelative(0f, -4f)
            moveTo(7f, 17f); lineToRelative(0f, 0.01f)
            moveTo(14f, 5f); arcToRelative(1f, 1f, 0f, false, true, 1f, -1f);
            horizontalLineToRelative(4f); arcToRelative(1f, 1f, 0f, false, true, 1f, 1f);
            verticalLineToRelative(4f); arcToRelative(1f, 1f, 0f, false, true, -1f, 1f);
            horizontalLineToRelative(-4f); arcToRelative(1f, 1f, 0f, false, true, -1f, -1f);
            lineToRelative(0f, -4f)
            moveTo(7f, 7f); lineToRelative(0f, 0.01f)
            moveTo(4f, 15f); arcToRelative(1f, 1f, 0f, false, true, 1f, -1f);
            horizontalLineToRelative(4f); arcToRelative(1f, 1f, 0f, false, true, 1f, 1f);
            verticalLineToRelative(4f); arcToRelative(1f, 1f, 0f, false, true, -1f, 1f);
            horizontalLineToRelative(-4f); arcToRelative(1f, 1f, 0f, false, true, -1f, -1f);
            lineToRelative(0f, -4f)
            moveTo(17f, 7f); lineToRelative(0f, 0.01f)
            moveTo(14f, 14f); lineToRelative(3f, 0f)
            moveTo(20f, 14f); lineToRelative(0f, 0.01f)
            moveTo(14f, 14f); lineToRelative(0f, 3f)
            moveTo(14f, 20f); lineToRelative(3f, 0f)
            moveTo(17f, 17f); lineToRelative(3f, 0f)
            moveTo(20f, 17f); lineToRelative(0f, 3f)
        }
    }

    val Monitor: ImageVector by lazy {
        strokeIcon("Monitor") { // tabler/device-desktop
            moveTo(3f, 5f); arcToRelative(1f, 1f, 0f, false, true, 1f, -1f);
            horizontalLineToRelative(16f); arcToRelative(1f, 1f, 0f, false, true, 1f, 1f);
            verticalLineToRelative(10f); arcToRelative(1f, 1f, 0f, false, true, -1f, 1f);
            horizontalLineToRelative(-16f); arcToRelative(1f, 1f, 0f, false, true, -1f, -1f);
            verticalLineToRelative(-10f)
            moveTo(7f, 20f); horizontalLineToRelative(10f)
            moveTo(9f, 16f); verticalLineToRelative(4f)
            moveTo(15f, 16f); verticalLineToRelative(4f)
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") { // tabler/chevron-right
            moveTo(9f, 6f); lineToRelative(6f, 6f); lineToRelative(-6f, 6f)
        }
    }

    /** Git actions: the glyph T3 Code maps its git control to. */
    val GitBranch: ImageVector by lazy {
        strokeIcon("GitBranch") { // tabler/git-merge
            moveTo(5f, 18f); arcToRelative(2f, 2f, 0f, true, false, 4f, 0f);
            arcToRelative(2f, 2f, 0f, true, false, -4f, 0f)
            moveTo(5f, 6f); arcToRelative(2f, 2f, 0f, true, false, 4f, 0f);
            arcToRelative(2f, 2f, 0f, true, false, -4f, 0f)
            moveTo(15f, 12f); arcToRelative(2f, 2f, 0f, true, false, 4f, 0f);
            arcToRelative(2f, 2f, 0f, true, false, -4f, 0f)
            moveTo(7f, 8f); lineToRelative(0f, 8f)
            moveTo(7f, 8f); arcToRelative(4f, 4f, 0f, false, false, 4f, 4f);
            horizontalLineToRelative(4f)
        }
    }

    val ArrowDown: ImageVector by lazy {
        strokeIcon("ArrowDown") { // tabler/arrow-down
            moveTo(12f, 5f); lineToRelative(0f, 14f)
            moveTo(18f, 13f); lineToRelative(-6f, 6f)
            moveTo(6f, 13f); lineToRelative(6f, 6f)
        }
    }

    val ArrowUpRight: ImageVector by lazy {
        strokeIcon("ArrowUpRight") { // tabler/arrow-up-right
            moveTo(17f, 7f); lineToRelative(-10f, 10f)
            moveTo(8f, 7f); lineToRelative(9f, 0f); lineToRelative(0f, 9f)
        }
    }

    val Mic: ImageVector by lazy {
        strokeIcon("Mic") { // tabler/microphone
            moveTo(9f, 5f); arcToRelative(3f, 3f, 0f, false, true, 3f, -3f);
            arcToRelative(3f, 3f, 0f, false, true, 3f, 3f); verticalLineToRelative(5f);
            arcToRelative(3f, 3f, 0f, false, true, -3f, 3f);
            arcToRelative(3f, 3f, 0f, false, true, -3f, -3f); lineToRelative(0f, -5f)
            moveTo(5f, 10f); arcToRelative(7f, 7f, 0f, false, false, 14f, 0f)
            moveTo(8f, 21f); lineToRelative(8f, 0f)
            moveTo(12f, 17f); lineToRelative(0f, 4f)
        }
    }

    /** A project, in the thread list. */
    val Folder: ImageVector by lazy {
        strokeIcon("Folder") { // tabler/folder
            moveTo(5f, 4f); horizontalLineToRelative(4f); lineToRelative(3f, 3f);
            horizontalLineToRelative(7f); arcToRelative(2f, 2f, 0f, false, true, 2f, 2f);
            verticalLineToRelative(8f); arcToRelative(2f, 2f, 0f, false, true, -2f, 2f);
            horizontalLineToRelative(-14f); arcToRelative(2f, 2f, 0f, false, true, -2f, -2f);
            verticalLineToRelative(-11f); arcToRelative(2f, 2f, 0f, false, true, 2f, -2f)
        }
    }
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
            // whole vector, so the stroke takes the caller's colour. The stroke
            // values mirror Tabler's own SVG attributes.
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
    }.build()
