import re, sys, os

# SVG path command -> (PathBuilder method, arg count). Compose's PathBuilder mirrors
# the SVG grammar exactly, so every command maps without any geometry rewriting.
CMD = {
    'M': ('moveTo', 2),               'm': ('moveToRelative', 2),
    'L': ('lineTo', 2),               'l': ('lineToRelative', 2),
    'H': ('horizontalLineTo', 1),     'h': ('horizontalLineToRelative', 1),
    'V': ('verticalLineTo', 1),       'v': ('verticalLineToRelative', 1),
    'C': ('curveTo', 6),              'c': ('curveToRelative', 6),
    'S': ('reflectiveCurveTo', 4),    's': ('reflectiveCurveToRelative', 4),
    'Q': ('quadTo', 4),               'q': ('quadToRelative', 4),
    'T': ('reflectiveQuadTo', 2),     't': ('reflectiveQuadToRelative', 2),
    'A': ('arcTo', 7),                'a': ('arcToRelative', 7),
    'Z': ('close', 0),                'z': ('close', 0),
}
NUM = re.compile(r'[-+]?(?:\d*\.\d+|\d+\.?)(?:[eE][-+]?\d+)?')

def fmt(v):
    s = ('%f' % v).rstrip('0').rstrip('.')
    return (s if s not in ('', '-0') else '0') + 'f'

def tokens(d):
    """Yield (command, [numbers]) honouring implicit command repetition."""
    for cmd, blob in re.findall(r'([MmLlHhVvCcSsQqTtAaZz])([^MmLlHhVvCcSsQqTtAaZz]*)', d):
        name, argc = CMD[cmd]
        nums = [float(n) for n in NUM.findall(blob)]
        if argc == 0:
            yield cmd, []
            continue
        if len(nums) % argc:
            raise ValueError('bad arg count for %s: %r' % (cmd, nums))
        for i in range(0, len(nums), argc):
            chunk = nums[i:i + argc]
            # An implicit repeat of M/m continues as a line, per the SVG spec.
            yield ('L' if cmd == 'M' else 'l' if cmd == 'm' else cmd) if i else cmd, chunk

def emit(cmd, args):
    name, argc = CMD[cmd]
    if argc == 0:
        return 'close()'
    if argc == 7:  # arcTo: rx ry rotation large-arc-flag sweep-flag x y
        rx, ry, rot, large, sweep, x, y = args
        return '%s(%s, %s, %s, %s, %s, %s, %s)' % (
            name, fmt(rx), fmt(ry), fmt(rot),
            'true' if large else 'false', 'true' if sweep else 'false', fmt(x), fmt(y))
    return '%s(%s)' % (name, ', '.join(fmt(a) for a in args))

def build(d):
    """One path's `d` as `PathBuilder` source lines, one line per subpath."""
    lines, buf = [], []
    for call in (emit(c, a) for c, a in tokens(d)):
        if call.startswith(('moveTo(', 'moveToRelative(')) and buf:
            lines.append('; '.join(buf)); buf = []
        buf.append(call)
    if buf:
        lines.append('; '.join(buf))
    return lines

def convert(svg):
    """Every `<path>` folded into one stroked path — the Tabler outline case."""
    lines = []
    for d in re.findall(r'<path[^>]*\sd="([^"]+)"', svg):
        lines.extend(build(d))
    return lines

# A `<rect>` is the one non-`<path>` shape the brand marks use. Expressed as
# explicit line segments rather than approximated, so the geometry survives.
def rect_path(attrs):
    if 'rx' in attrs or 'ry' in attrs:
        raise ValueError('rounded rect is not supported: %r' % attrs)
    x, y = float(attrs.get('x', 0)), float(attrs.get('y', 0))
    w, h = float(attrs['width']), float(attrs['height'])
    return 'M%g %gH%gV%gH%gZ' % (x, y, x + w, y + h, x)

ATTR = re.compile(r'([a-zA-Z-]+)="([^"]*)"')

def shapes(svg):
    """
    Every filled shape in document order, as `(fill, even_odd, lines)`.

    Document order is the paint order, so the caller must keep it: the brand
    marks layer a shade under a mark.
    """
    out = []
    for tag, body in re.findall(r'<(path|rect)\b([^>]*)>', svg):
        attrs = dict(ATTR.findall(body))
        d = attrs['d'] if tag == 'path' else rect_path(attrs)
        out.append((
            attrs.get('fill'),
            attrs.get('fill-rule') == 'evenodd',
            build(d),
        ))
    return out

if __name__ == '__main__':
  for path in sys.argv[1:]:
    print('### ' + os.path.basename(path)[:-4])
    for l in convert(open(path).read()):
        print('    ' + l)
