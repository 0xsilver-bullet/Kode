import re
INV = {
 'moveTo':'M','moveToRelative':'m','lineTo':'L','lineToRelative':'l',
 'horizontalLineTo':'H','horizontalLineToRelative':'h','verticalLineTo':'V',
 'verticalLineToRelative':'v','curveTo':'C','curveToRelative':'c',
 'reflectiveCurveTo':'S','reflectiveCurveToRelative':'s','quadTo':'Q',
 'quadToRelative':'q','reflectiveQuadTo':'T','reflectiveQuadToRelative':'t',
 'arcTo':'A','arcToRelative':'a','close':'Z',
}
src = open('/Users/malakh/projects/Kode/core/designsystem/src/commonMain/kotlin/com/silverbullet/kode/core/designsystem/KodeIcons.kt').read()
icons = []
for m in re.finditer(r'strokeIcon\("(\w+)"\) \{ // tabler/([\w-]+)\n(.*?)\n        \}', src, re.S):
    name, slug, body = m.groups()
    d = []
    for call in re.finditer(r'(\w+)\(([^)]*)\)', body):
        fn, args = call.group(1), call.group(2)
        vals = [a.strip().rstrip('f') for a in args.split(',') if a.strip()]
        vals = ['1' if v == 'true' else '0' if v == 'false' else v for v in vals]
        d.append(INV[fn] + ' '.join(vals))
    icons.append((name, slug, ' '.join(d)))
assert len(icons) == 27, len(icons)
COLS, CELL = 7, 100
rows = (len(icons) + COLS - 1) // COLS
out = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d">' % (COLS*CELL, rows*CELL)]
out.append('<rect width="100%" height="100%" fill="#14151a"/>')
for i, (name, slug, d) in enumerate(icons):
    x, y = (i % COLS) * CELL, (i // COLS) * CELL
    out.append('<g transform="translate(%d,%d) scale(2.2)">' % (x + 17, y + 12))
    out.append('<path d="%s" fill="none" stroke="#e6e6ea" stroke-width="2" '
               'stroke-linecap="round" stroke-linejoin="round"/></g>' % d)
    out.append('<text x="%d" y="%d" fill="#8a8a96" font-family="Helvetica" '
               'font-size="10" text-anchor="middle">%s</text>' % (x + CELL//2, y + 88, name))
out.append('</svg>')
open('/tmp/sheet.svg','w').write('\n'.join(out))
print('\n'.join('%-13s %s' % (n, s) for n, s, _ in icons))
