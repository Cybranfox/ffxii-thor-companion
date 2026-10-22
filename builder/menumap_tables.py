"""Bounded readers for worldmap/menumap_location.bin ('lmap') and worldmap/menumap_common.bin ('cmap').

lmap (established 2026-10-21 against all 48 records and the menumap sheet index):
  'lmap', u32, u8 ?, u8 record_count (48 = sublocations, same order as menumap_subNN.bin),
  u16 record offsets[record_count], then per record:
    u8 maps, u8 exits, u8 links, u8 0
    maps  x 24 B: u16 x, u16 y (region layout position), u8 neighbours[4] (0xFF = none), u32 0,
                  u16 map_id, u16 0xFFFF, 8 B zero
    exits x 24 B: u16 x, u16 y, u8 neighbours[4], u16 x2, u16 y2, u16 text_id, u16 0,
                  u8 ?, u8 dest_sublocation, u16 ?, u8 ?, u8 ?, u16 0xFFFF
    links x 20 B: u16 map_a, u16 map_b, u16 xa, u16 ya, u32 0, u16 xb, u16 yb, u16 0xFFFF, u16 0
    8-byte trailer (unknown). 7 of 48 records carry extra trailing bytes (unknown).
  Coordinates are schematic region-overview positions, NOT map units or sheet pixels: no scale/tint here.

cmap (layout partly established, meaning mostly unknown):
  'cmap', u32, u16 a, u16 table_offset, u32 count (48), count x {u16 v0, u16 v1 (0xFFFF none), u16 text_id, u16 0},
  at table_offset: u16 n, n x u16 (0xFFFE/0xFFFF only; unknown), then u16 m, u16 0, m x {u16 threshold,
  u16 text_id, u16 0, u16 index} with increasing thresholds (candidate: story-progress -> message text).
"""
import struct

MAX_BYTES = 1 << 20


def _u(fmt, d, o):
    if o < 0 or o + struct.calcsize(fmt) > len(d):
        raise ValueError(f'read {fmt} at {o:#x} outside {len(d):#x}')
    return struct.unpack_from(fmt, d, o)


def parse_location(d):
    if len(d) > MAX_BYTES or d[:4] != b'lmap':
        raise ValueError('not an lmap file')
    count = d[9]
    offs = list(_u(f'<{count}H', d, 0x0A))
    ends = offs[1:] + [len(d)]
    records = []
    for k, (a, b) in enumerate(zip(offs, ends)):
        if not 0 < a < b <= len(d):
            raise ValueError(f'record {k} out of range')
        nm, ne, nl = _u('<BBB', d, a)
        base_len = 4 + 24 * nm + 24 * ne + 20 * nl + 8
        if base_len > b - a:
            raise ValueError(f'record {k}: counts exceed record size')
        o = a + 4
        maps = []
        for _ in range(nm):
            x, y = _u('<HH', d, o)
            maps.append({'x': x, 'y': y, 'neighbours': list(d[o + 4:o + 8]), 'map_id': _u('<H', d, o + 12)[0]})
            o += 24
        exits = []
        for _ in range(ne):
            x, y = _u('<HH', d, o)
            x2, y2, text = _u('<HHH', d, o + 8)
            exits.append({'x': x, 'y': y, 'neighbours': list(d[o + 4:o + 8]), 'x2': x2, 'y2': y2,
                          'text_id': text, 'dest_sublocation': d[o + 17], 'raw_tail': d[o + 16:o + 24].hex()})
            o += 24
        links = []
        for _ in range(nl):
            ma, mb, xa, ya = _u('<HHHH', d, o)
            xb, yb = _u('<HH', d, o + 12)
            links.append({'map_a': ma, 'map_b': mb, 'a': (xa, ya), 'b': (xb, yb)})
            o += 20
        records.append({'index': k, 'maps': maps, 'exits': exits, 'links': links,
                        'trailer': d[o:o + 8].hex(), 'extra_bytes': (b - a) - base_len})
    return records


def parse_common(d):
    if len(d) > MAX_BYTES or d[:4] != b'cmap':
        raise ValueError('not a cmap file')
    _, a, table, count = _u('<IHHI', d, 4)
    subs = [dict(zip(('v0', 'v1', 'text_id'), _u('<HHH', d, 0x10 + 8 * i))) for i in range(count)]
    n = _u('<H', d, table)[0]
    flags = list(_u(f'<{n}H', d, table + 2))
    o = table + 2 + 2 * n
    m = _u('<H', d, o)[0]
    thresholds = [dict(zip(('threshold', 'text_id', 'zero', 'index'), _u('<HHHH', d, o + 4 + 8 * i))) for i in range(m)]
    return {'header_a': a, 'sublocations': subs, 'unknown_table': flags, 'thresholds': thresholds}
