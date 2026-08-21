import os, re, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from generate_brandmarks import MARKS
from generate_kodeicons import ICONS

INV = {
 'moveTo':'M','moveToRelative':'m','lineTo':'L','lineToRelative':'l',
 'horizontalLineTo':'H','horizontalLineToRelative':'h','verticalLineTo':'V',
 'verticalLineToRelative':'v','curveTo':'C','curveToRelative':'c',
 'reflectiveCurveTo':'S','reflectiveCurveToRelative':'s','quadTo':'Q',
 'quadToRelative':'q','reflectiveQuadTo':'T','reflectiveQuadToRelative':'t',
 'arcTo':'A','arcToRelative':'a','close':'Z',
}
GENERATED = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    '..', 'src', 'commonMain', 'kotlin', 'com', 'silverbullet', 'kode',
    'core', 'designsystem',
)


def path_data(body):
    """A `PathBuilder` block back into an SVG `d`."""
    d = []
    for call in re.finditer(r'(\w+)\(([^)]*)\)', body):
        fn, args = call.group(1), call.group(2)
        vals = [a.strip().rstrip('f') for a in args.split(',') if a.strip()]
        vals = ['1' if v == 'true' else '0' if v == 'false' else v for v in vals]
        d.append(INV[fn] + ' '.join(vals))
    return ' '.join(d)


src = open(os.path.join(GENERATED, 'KodeIcons.kt')).read()
STROKE_ICON = re.compile(r'strokeIcon\("(\w+)"\) \{ // tabler/([\w-]+)\n(.*?)\n        \}', re.S)
icons = [(m.group(1), path_data(m.group(3))) for m in STROKE_ICON.finditer(src)]
assert len(icons) == len(ICONS), (len(icons), len(ICONS))

src = open(os.path.join(GENERATED, 'KodeBrandMarks.kt')).read()
marks = []
for m in re.finditer(r'brandMark\( //[^\n]*\n(.*?)\n        \)\ \{\n(.*?)\n        \}', src, re.S):
    args, block = m.groups()
    fields = dict(re.findall(r'(\w+) = ([^,\n]+),', args))
    marks.append((
        fields['name'].strip('"'),
        float(fields['viewport'].rstrip('f')),
        float(fields.get('offsetX', '0f').rstrip('f')),
        float(fields.get('offsetY', '0f').rstrip('f')),
        [
            (p.group(1), 'evenodd' if 'EvenOdd' in p.group(0) else 'nonzero', path_data(p.group(2)))
            for p in re.finditer(
                r'path\(fill = SolidColor\(Color\(0x(\w{8})\)\)[^)]*\) \{\n(.*?)\n            \}',
                block, re.S)
        ],
    ))
assert len(marks) == len(MARKS), (len(marks), len(MARKS))

COLS, CELL = 7, 100
cells = len(icons) + len(marks)
rows = (cells + COLS - 1) // COLS
out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d">' % (COLS*CELL, rows*CELL)]
out.append('<rect width="100%" height="100%" fill="#14151a"/>')


def label(i, name):
    x, y = (i % COLS) * CELL, (i // COLS) * CELL
    out.append('<text x="%d" y="%d" fill="#8a8a96" font-family="Helvetica" '
               'font-size="10" text-anchor="middle">%s</text>' % (x + CELL//2, y + 88, name))
    return x, y


for i, (name, d) in enumerate(icons):
    x, y = label(i, name)
    out.append('<g transform="translate(%d,%d) scale(2.2)">' % (x + 17, y + 12))
    out.append('<path d="%s" fill="none" stroke="#e6e6ea" stroke-width="2" '
               'stroke-linecap="round" stroke-linejoin="round"/></g>' % d)

for i, (name, viewport, dx, dy, paths) in enumerate(marks, start=len(icons)):
    x, y = label(i, name)
    # Same 52.8px drawn box as a 24-unit glyph at scale 2.2, so a brand mark is
    # eyeballed at the size the rows actually draw it.
    scale = 52.8 / viewport
    out.append('<g transform="translate(%d,%d) scale(%f) translate(%f,%f)"'
               % (x + 17, y + 12, scale, dx, dy))
    out.append('>')
    for fill, rule, d in paths:
        out.append('<path d="%s" fill="#%s" fill-rule="%s"/>' % (d, fill[2:], rule))
    out.append('</g>')

out.append('</svg>')
open('/tmp/sheet.svg', 'w').write('\n'.join(out))
print('\n'.join('%-14s stroke' % n for n, _ in icons))
print('\n'.join('%-14s brand (%d paths)' % (n, len(p)) for n, _, _, _, p in marks))
