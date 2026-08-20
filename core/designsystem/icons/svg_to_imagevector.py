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

def convert(svg):
    lines = []
    for d in re.findall(r'<path[^>]*\sd="([^"]+)"', svg):
        calls = [emit(c, a) for c, a in tokens(d)]
        # One source line per subpath keeps the generated code diffable.
        buf = []
        for call in calls:
            if call.startswith(('moveTo(', 'moveToRelative(')) and buf:
                lines.append('; '.join(buf)); buf = []
            buf.append(call)
        if buf:
            lines.append('; '.join(buf))
    return lines

if __name__ == '__main__':
  for path in sys.argv[1:]:
    print('### ' + os.path.basename(path)[:-4])
    for l in convert(open(path).read()):
        print('    ' + l)
