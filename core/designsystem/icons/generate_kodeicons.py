import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from svg_to_imagevector import convert

ICONS = [
    ("Agent",        "robot",          "The agent itself: shown for thinking-tone activity."),
    ("Alert",        "alert-circle",   "Error-tone activity."),
    ("Check",        "check",          None),
    ("Command",      "terminal-2",     "Shell commands, and command-approval requests."),
    ("Edit",         "pencil",         "File changes, and file-change approval requests."),
    ("Eye",          "eye",            "File reads, image views, and diff previews."),
    ("Globe",        "world",          "Web search."),
    ("Hammer",       "hammer",         "Dynamic tool calls."),
    ("Message",      "message",        "User-input requests and their resolutions."),
    ("Warning",      "alert-triangle", "Runtime warnings, and interrupted turns."),
    ("Wrench",       "tool",           "MCP tool calls."),
    ("ChevronDown",  "chevron-down",   None),
    ("ArrowUp",      "arrow-up",       None),
    ("Plus",         "plus",           None),
    ("Close",        "x",              None),
    ("Image",        "photo",          None),
    ("Zap",          "bolt",           "The fallback glyph for tool-tone activity."),
    ("Gear",         "settings",       None),
    ("Trash",        "trash",          None),
    ("Refresh",      "refresh",        None),
    ("QrCode",       "qrcode",         None),
    ("Monitor",      "device-desktop", None),
    ("ChevronRight", "chevron-right",  None),
    ("GitBranch",    "git-merge",      "Git actions: the glyph T3 Code maps its git control to."),
    ("ArrowDown",    "arrow-down",     None),
    ("ArrowUpRight", "arrow-up-right", None),
    ("Mic",          "microphone",     None),
]

SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
LIMIT = 94

def wrap(line, indent):
    """Re-flow a `;`-joined subpath so the generated source stays readable."""
    calls = line.split('; ')
    out, buf = [], ''
    for call in calls:
        cand = call if not buf else buf + '; ' + call
        if buf and len(indent) + len(cand) + 1 > LIMIT:
            out.append(indent + buf + ';')
            buf = call
        else:
            buf = cand
    if buf:
        out.append(indent + buf)
    return out

head = '''package com.silverbullet.kode.core.designsystem

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
 * if R8 runs, which the release build does not currently enable. Owning the 27
 * we use means the APK carries exactly 27.
 *
 * The SVG sources and the converter live in `core/designsystem/icons/`; see the
 * README there before adding a glyph.
 *
 * Each is a stroked 24dp vector, so `Icon(tint = …)` recolours it correctly.
 */
object KodeIcons {'''

body = []
for name, slug, doc in ICONS:
    lines = convert(open(os.path.join(SRC, slug + '.svg')).read())
    body.append('')
    if doc:
        body.append('    /** %s */' % doc)
    body.append('    val %s: ImageVector by lazy {' % name)
    body.append('        strokeIcon("%s") { // tabler/%s' % (name, slug))
    for l in lines:
        body.extend(wrap(l.strip(), ' ' * 12))
    body.append('        }')
    body.append('    }')

tail = '''}

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
'''

print(head + '\n'.join(body) + '\n' + tail, end='')
