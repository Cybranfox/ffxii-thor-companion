"""Bounded reader for test_battle/us/binaryfile/menu_mapname.bin ('st2e'): the text of the region map's exit labels.

Layout (established 22 Oct 2026, Map_Detail report, all 111 lmap exits / 42 text IDs resolve):
  'st2e', u32 count @+4, u32 stride (4) @+8, u32 rows offset @+0xC, ..., u32 text section offset @+0x14
  (= end of the rows); rows: u16 text_id, u16 0; text section: u32 groups (1), u32 relative group offset,
  group: u32 count (= rows), count x u32 string offset relative to the group, 0-terminated strings in the
  game's menu encoding (gambit_names.decode).
lmap exit +0xC holds the text ID. The game draws the label at the exit's second point (x2, y2), in region layout
units (exit+8/+0xA copied to the map component, drawn by 0x4044D0), and draws it dimmed while the exit's gate
(exit+0x12, a generic story flag) is off: it never hides it.

Usage: menumap_names.py <menu_mapname.bin>
"""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from gambit_names import decode  # noqa: E402

PATH = 'ps2data/image/ff12/test_battle/us/binaryfile/menu_mapname.bin'
MAX_BYTES = 1 << 20


def _u(fmt, d, o):
    if o < 0 or o + struct.calcsize(fmt) > len(d):
        raise ValueError(f'read {fmt} at {o:#x} outside {len(d):#x}')
    return struct.unpack_from(fmt, d, o)


def parse(d):
    """{text_id: label} for every row that decodes completely; rows with unknown glyphs are left out."""
    if len(d) > MAX_BYTES or d[:4] != b'st2e':
        raise ValueError('not a menu_mapname file')
    count, stride, rows = _u('<3I', d, 4)
    if count > 4096 or stride != 4 or rows + count * stride > len(d):
        raise ValueError('bad row table')
    root = _u('<I', d, 0x14)[0]
    if root != rows + count * stride:
        raise ValueError('unexpected text section offset')
    groups, rel = _u('<2I', d, root)
    if groups != 1:
        raise ValueError('unexpected text group count')
    base = root + rel
    if _u('<I', d, base)[0] != count:
        raise ValueError('text count does not match the rows')
    out = {}
    for i in range(count):
        tid, zero = _u('<HH', d, rows + i * stride)
        if zero:
            raise ValueError(f'row {i}: non-zero tail')
        at = base + _u('<I', d, base + 4 + 4 * i)[0]
        if at < base + 4 + 4 * count or at >= len(d):
            raise ValueError(f'row {i}: text offset out of range')
        stop = d.find(b'\0', at, min(len(d), at + 512))
        if stop < 0:
            raise ValueError(f'row {i}: unterminated text')
        text, unknown = decode(d[at:stop])
        if text and not unknown:
            out[tid] = text
    return out


def s16(v):
    """Exit points are signed: an exit just left of a region's first area sits at x = -3 (sub37)."""
    return v - 0x10000 if v >= 0x8000 else v


def exit_labels(exits, names):
    """The region layout's exits with a label: first point, label anchor (second point), text, story gate."""
    out = []
    for e in exits:
        text = names.get(e['text_id'])
        if not text:
            continue
        tail = bytes.fromhex(e['raw_tail'])
        out.append({'x': s16(e['x']), 'y': s16(e['y']), 'lx': s16(e['x2']), 'ly': s16(e['y2']), 'text': text,
                    'gate': int.from_bytes(tail[2:4], 'little')})
    return out


if __name__ == '__main__':
    labels = parse(Path(sys.argv[1]).read_bytes())
    print(len(labels), 'labels')
