"""Bounded readers for the map's symbols (0.13.0, Map_Icons_Flags_2026-10-22.md; all from the user's own VBF):

NAVIICN2 rows, inside each map's info block of worldmap/menumap_subNN.bin ('sloc'): header u16 +8 == 10; for groups
  1..9, u16 relative row offset at tag+0xA+4*g and u16 count at +0xC+4*g; 16-byte rows: s16 source x, y; s16 display
  x, y; s16 type; u16 gate; u32 flags. Drawn at 4 x display (sheet px); the fog test uses source / 16 (reveal cell).
FLAGROM1 (bin/mapjumpgroupflagrom.bin): the bit14 gates' threshold rows, read by 0x377970 (parity of thresholds reached).
Symbol images (tm2_menu/loca_8_c.tm2): one 8-bit TIM2 with an 'eXt' table; type t uses UV row 9 + t (0x400485) and
  palette bank paletteTable[selector >> 4] + (selector & 15) (0x3C6DEA). Alpha is the PS2 0..128 scale doubled.

The companion draws the game's own symbol where and when the game would, and never names it: what each type means
(save crystal, shop...) is not established from the game's code (Map_Icons_Flags part B).
"""
import struct

ICON_ATLAS = 'ps2data/image/ff12/myoshiok/us/tm2_menu/loca_8_c.tm2'
FLAGROM = 'ps2data/image/ff12/us/bin/mapjumpgroupflagrom.bin'
ICON_TYPES = 29
MAX_SLOC = 64 << 20
MAX_ATLAS = 4 << 20


def _u(fmt, d, o, end=None):
    end = len(d) if end is None else min(end, len(d))
    if o < 0 or o + struct.calcsize(fmt) > end:
        raise ValueError(f'read {fmt} at {o:#x} outside {end:#x}')
    return struct.unpack_from(fmt, d, o)


def navi(data):
    """[(map_id, group, index, type, sx, sy, dx, dy, gate)] for one region file, in file order."""
    if not 20 <= len(data) <= MAX_SLOC or data[:4] != b'sloc':
        raise ValueError('not a sloc file')
    table, count = _u('<H', data, 10)[0], _u('<I', data, 16)[0]
    if count > 64 or 20 + 2 * count > table or table > len(data):
        raise ValueError('sloc directory')
    offsets = _u(f'<{count}H', data, 20)
    rows = []
    for i, rel in enumerate(offsets):
        start, end = 16 + rel, (16 + offsets[i + 1] if i + 1 < count else table)
        if not 20 + 2 * count <= start < end <= table:
            raise ValueError('sloc info extent')
        map_id = _u('<H', data, start, end)[0]
        tag = data.find(b'NAVIICN2', start, end)
        if tag < 0:
            continue
        if tag + 0x32 > end or _u('<H', data, tag + 8, end)[0] != 10:
            raise ValueError('NAVIICN2 header')
        for group in range(1, 10):
            off, n = _u('<HH', data, tag + 0xA + 4 * group, end)
            if n > 4096 or (n and (off < 0x32 or tag + off + 16 * n > end)):
                raise ValueError('NAVIICN2 rows')
            for index in range(n):
                sx, sy, dx, dy, typ, gate, _flags = _u('<5hHI', data, tag + off + 16 * index, end)
                rows.append((map_id, group, index, typ, sx, sy, dx, dy, gate))
    return rows


def flagrom(data):
    """Threshold rows (lists of u16 words, the last with bit 15), or None for an empty row."""
    if not 10 <= len(data) <= 65536 or data[:8] != b'FLAGROM1':
        raise ValueError('not a FLAGROM1 file')
    count = _u('<H', data, 8)[0]
    if count > 4096 or 10 + 2 * count > len(data):
        raise ValueError('FLAGROM1 directory')
    rows = []
    for i in range(count):
        o = _u('<H', data, 10 + 2 * i)[0]
        if not o:
            rows.append(None)
            continue
        if o < 10 + 2 * count or o & 1:
            raise ValueError('FLAGROM1 row offset')
        row = []
        for _ in range(64):
            w = _u('<H', data, o)[0]
            o += 2
            row.append(w)
            if w & 0x8000:
                break
        else:
            raise ValueError('FLAGROM1 row too long')
        rows.append(row)
    return rows


def symbols(data):
    """[(type, width, height, rgba bytes)] for types 0..28 from loca_8_c.tm2."""
    if len(data) > MAX_ATLAS or data[:4] != b'TIM2' or _u('<H', data, 6)[0] != 1:
        raise ValueError('unsupported TIM2')
    total, clut_size, image_size, hs, colors, fmt, mip, ct, it, w, h = _u('<IIIHHBBBBHH', data, 16)
    if total != len(data) - 16 or hs < 48 or 16 + hs > len(data) or (fmt, mip, ct, it) != (0, 1, 3, 5):
        raise ValueError('unsupported picture')
    if not (0 < w <= 2048 and 0 < h <= 2048) or image_size != w * h or clut_size != colors * 4 or colors % 256 \
            or colors > 4096 or hs + image_size + clut_size != total:
        raise ValueError('inconsistent picture sizes')
    ext = 0x40
    head = data[:16 + hs]
    if head[ext:ext + 4] != b'eXt\0':
        raise ValueError('missing eXt')
    uv, pal, group = (ext + _u('<H', head, ext + 0x10)[0], ext + _u('<H', head, ext + 0x12)[0],
                      _u('<H', head, ext + 0x14)[0])
    if group > 16 or _u('<H', head, ext + 0x16 + 2 * group)[0] != 9:
        raise ValueError('unexpected symbol group')
    pixels = data[16 + hs:16 + hs + image_size]
    clut = data[16 + hs + image_size:16 + hs + image_size + clut_size]
    out = []
    for typ in range(ICON_TYPES):
        x, y, packed = _u('<HHI', head, uv + 8 * (9 + typ))
        cw, ch, sel = packed & 4095, (packed >> 12) & 4095, packed >> 24
        bank = _u('<B', head, pal + (sel >> 4))[0] + (sel & 15)
        if not 0 < cw <= w or not 0 < ch <= h or x + cw > w or y + ch > h or bank >= colors // 256:
            raise ValueError('symbol rectangle or palette out of range')
        rgba = bytearray()
        for yy in range(y, y + ch):
            for index in pixels[yy * w + x:yy * w + x + cw]:
                k = (index & ~24) | ((index & 8) << 1) | ((index & 16) >> 1)   # PS2 CSM1 CLUT order
                r, g, b, a = clut[bank * 1024 + k * 4:bank * 1024 + k * 4 + 4]
                rgba.extend((r, g, b, min(255, a * 2)))
        out.append((typ, cw, ch, bytes(rgba)))
    return out
