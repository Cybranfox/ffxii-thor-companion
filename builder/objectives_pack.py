"""The story hint and the hunt journal, from the user's own game files (0.14.0; Objectives_Hunts_2026-10-22.md).

Never from fan guides, wikis or walkthroughs: only the user's VBF.

Story hint, the line at the bottom of the game's map screen: worldmap/menumap_common.bin ('cmap') holds 8-byte rows
  {u16 threshold, u16 gate (0xFFFF none), u8 guide file, u8 0, u16 index}; the game picks the LAST row whose threshold
  <= signed scenario variable 0 and whose gate is open (0x3BD950) and shows text `index` of menumap_guideNN.bin
  ('gmap': u16 text base +8, u16 count +0xE, 8-byte rows from +0x10 with u16 text offset +4; 0x3BD8F0).
Hunts: quest/menuquest.dat ('qstd') lists the menu's quests (u32 title ID, u32 group | quest << 8); group 1 = the 45
  hunts, detail rows in menuquest01.bin in menu order (0x37EE92). Each detail row: three narrative offsets (the third,
  split on 0x03, is the journal paragraph per phase; 0x578450) and a 28-byte phase table (bank at header +0xA):
  phase = nibble (raw // 5) of the table, raw = the live stage byte (0x37EF70). Titles from questtitle.bin ('st2e').
Text: the game's encoding (gambit_names.decode); bytes it doesn't cover are read from the user's own font00.dat, where
  glyph (byte - 0x20) holds the character as UTF-8 (all 68 known bytes agree). 0x0F 0x20 0x81 xx is formatting and is
  dropped, 0x02 is a line break. A text with any byte still unknown is left out rather than shown garbled.
"""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from gambit_names import decode  # noqa: E402
import menumap_icons  # noqa: E402

FORMAT = 'ffxii-companion-objectives-1'
CMAP = 'ps2data/image/ff12/myoshiok/us/worldmap/menumap_common.bin'
GUIDE = 'ps2data/image/ff12/myoshiok/us/worldmap/menumap_guide{:02d}.bin'
MENU = 'ps2data/image/ff12/myoshiok/us/quest/menuquest.dat'
DETAIL = 'ps2data/image/ff12/myoshiok/us/quest/menuquest{:02d}.bin'
TITLES = 'ps2data/image/ff12/test_battle/us/binaryfile/questtitle.bin'
FONT = 'gamedata/d3d11/artdata/font/us/font00.dat'
HUNT_GROUP = 1
MAX_BYTES = 1 << 20


def _u(fmt, d, o):
    if o < 0 or o + struct.calcsize(fmt) > len(d):
        raise ValueError(f'read {fmt} at {o:#x} outside {len(d):#x}')
    return struct.unpack_from(fmt, d, o)


def font_chars(data):
    """{byte: character} from font00.dat: glyph index byte - 0x20, packed UTF-8 in the record's second u32."""
    if not 40 <= len(data) <= 4 << 20:
        raise ValueError('font size')
    head = _u('<10I', data, 0)
    count = head[1]
    if head[0] != 1 or head[9] != 36 or not 1 <= count <= 16384 or len(data) != 40 + 36 * count:
        raise ValueError('font00 layout')
    out = {}
    for b in range(0x20, min(0x100, 0x20 + count)):
        index, packed = _u('<II', data, 40 + 36 * (b - 0x20))
        if index != b - 0x20:
            raise ValueError('font00 glyph order')
        try:
            ch = struct.pack('<I', packed).rstrip(b'\0').decode('utf-8')
        except UnicodeDecodeError:
            continue
        if len(ch) == 1 and ch.isprintable():
            out[b] = ch
    return out


def text(raw, chars):
    """Decoded text, or None when any byte is still unknown."""
    out, i = [], 0
    while i < len(raw):
        if raw[i:i + 3] == b'\x0f\x20\x81' and i + 4 <= len(raw):
            i += 4
            continue
        c = raw[i]
        if c == 0x02:
            out.append('\n')
        else:
            s, unknown = decode(bytes([c]))
            if unknown:
                if c not in chars:
                    return None
                s = chars[c]
            out.append(s)
        i += 1
    return ''.join(out).strip()


def cmap_rows(d):
    if not 16 <= len(d) <= MAX_BYTES or d[:4] != b'cmap':
        raise ValueError('not a cmap file')
    first, tail = _u('<HH', d, 8)
    if not 12 <= first <= tail < len(d):
        raise ValueError('cmap directories')
    m = _u('<H', d, tail)[0]
    if m > 8192:
        raise ValueError('cmap middle table')
    at = (tail + 2 + 2 * m + 3) & ~3
    count = _u('<I', d, at)[0]
    if count > 4096 or at + 4 + 8 * count > len(d):
        raise ValueError('cmap hint table')
    rows = [_u('<HHBBH', d, at + 4 + 8 * i) for i in range(count)]
    if any(a[0] > b[0] for a, b in zip(rows, rows[1:])):
        raise ValueError('cmap thresholds out of order')
    return [(threshold, gate, guide, index) for threshold, gate, guide, _zero, index in rows]


def gmap_texts(d):
    """[raw bytes or None] per gmap row."""
    if not 16 <= len(d) <= MAX_BYTES or d[:4] != b'gmap':
        raise ValueError('not a gmap file')
    base, image, count = _u('<H', d, 8)[0], _u('<H', d, 10)[0], _u('<H', d, 14)[0]
    if count > 4096 or not 16 + 8 * count <= base <= len(d):
        raise ValueError('gmap rows')
    end = image if image < len(d) and d[image:image + 4] == b'TIM2' else len(d)
    out = []
    for i in range(count):
        off = _u('<H', d, 16 + 8 * i + 4)[0]
        if off == 0xFFFF:
            out.append(None)
            continue
        start = base + off
        stop = d.find(b'\0', start, end)
        if not base <= start < end or stop < 0:
            raise ValueError('gmap text')
        out.append(d[start:stop])
    return out


def st2e_sparse(d):
    """{u32 id: raw bytes} for questtitle.bin (sparse IDs)."""
    if not 32 <= len(d) <= MAX_BYTES or d[:4] != b'st2e':
        raise ValueError('not a st2e file')
    count, stride, start = _u('<III', d, 4)
    if not 0 < count <= 65536 or stride != 4 or start + 4 * count > len(d):
        raise ValueError('st2e rows')
    ids = [_u('<I', d, start + 4 * i)[0] for i in range(count)]
    outer = _u('<I', d, 20)[0]
    if _u('<I', d, outer)[0] != 1:
        raise ValueError('st2e text table')
    first, last = _u('<II', d, outer + 4)
    base, end = outer + first, outer + last
    if not base < end <= len(d) or _u('<I', d, base)[0] != count:
        raise ValueError('st2e text extent')
    out = {}
    for i, key in enumerate(ids):
        s = base + _u('<I', d, base + 4 + 4 * i)[0]
        stop = d.find(b'\0', s, end)
        if not base <= s < end or stop < 0:
            raise ValueError('st2e text')
        out[key] = d[s:stop]
    return out


def hunts(menu, detail, titles, chars):
    if not 12 <= len(menu) <= MAX_BYTES or menu[:4] != b'qstd':
        raise ValueError('not a qstd file')
    groups, count = _u('<HH', menu, 8)
    if not 1 <= groups <= 16 or count > 256 or len(menu) != 12 + 8 * count:
        raise ValueError('qstd rows')
    if not 16 <= len(detail) <= MAX_BYTES:
        raise ValueError('detail file')
    rows = _u('<H', detail, 14)[0]
    banks = _u('<6H', detail, 0)
    if rows > 256 or 16 + 20 * rows > len(detail) or banks[5] + 28 * rows > len(detail):
        raise ValueError('detail rows')
    out, ordinal = [], 0
    for i in range(count):
        title_id, packed = _u('<II', menu, 12 + 8 * i)
        if packed & 0xFF != HUNT_GROUP:
            continue
        quest = (packed >> 8) & 0x3FF
        if ordinal >= rows or quest > 255:
            raise ValueError('hunt detail row')
        rel = _u('<H', detail, 16 + 20 * ordinal + 4)[0]          # third narrative: paragraph per phase
        paragraphs = []
        if banks[2] != 0xFFFF and rel != 0xFFFF:
            s = banks[2] + rel
            stop = detail.find(b'\0', s)
            if not 16 + 20 * rows <= s < len(detail) or stop < 0:
                raise ValueError('hunt narrative')
            paragraphs = [text(p, chars) for p in detail[s:stop].split(b'\x03')]
        out.append({'questId': quest, 'title': text(titles.get(title_id, b''), chars),
                    'phases': detail[banks[5] + 28 * ordinal:banks[5] + 28 * (ordinal + 1)].hex(),
                    'paragraphs': paragraphs})
        ordinal += 1
    return out


def build(read):
    """read(path) -> bytes. Returns the objectives document (hints + hunts) or raises ValueError/KeyError."""
    chars = font_chars(read(FONT))
    rows = cmap_rows(read(CMAP))
    guides = {g: gmap_texts(read(GUIDE.format(g))) for g in sorted({r[2] for r in rows})}
    hints = []
    for threshold, gate, guide, index in rows:
        raw = guides[guide][index] if index < len(guides[guide]) else None
        hints.append({'threshold': threshold, 'gate': gate, 'text': text(raw, chars) if raw else None})
    hunt_rows = hunts(read(MENU), read(DETAIL.format(HUNT_GROUP)), st2e_sparse(read(TITLES)), chars)
    return {'format': FORMAT, 'hints': hints, 'hunts': hunt_rows,
            'thresholds': menumap_icons.flagrom(read(menumap_icons.FLAGROM)),   # the hints' bit14 gates
            'note': 'Built from your own game files for your personal use. Do not share.'}
