"""Bounded reader for FFXII TZA menu-map region files (worldmap/menumap_subNN.bin, magic 'sloc').

Read-only on an extracted copy; never touches the VBF. Layout established 2026-10-21 from sub00 (Rabanastre)
and sub16 (Zertinan Caverns); established from the game files (project research notes).

  0x00  'sloc', u32 0x64
  0x08  u16 ?, u16 table_offset
  0x10  u32 info_count, then info_count x u16 info offsets (relative to 0x10)
  info  u16 map_id (low 16 bits), u16 0x10, ... 'LCMPOS1' (0x70-byte header; float4 rows x,y,z,0)
        'NAVIICN2' (16-byte icon records, coordinates in the same map units; semantics partly unknown)
  table u16 sheet_count, u16 rel; sheet_count x u32 sheet ids at table+rel (low 16 bits = game map ID,
        high bits = unknown flag)
  then  sheet_count TIM2 records (v4, 1 picture, PSMT8, 48-byte picture header). Each declares a 1024-byte
        CLUT but none is stored: the next record starts right after the image. Pixel values are a 7-bit
        coverage mask (0..127, 0 = transparent), not palette indices.
  tail  sheet_count x 'NPS2' records: 16-byte tag + 4 floats (x0, z0, width, depth) in map units.
"""
import struct

import numpy as np

MAX_FILE = 64 * 1024 * 1024
MAX_DIM = 4096
MAX_SHEETS = 64


class SlocError(ValueError):
    pass


def _u(fmt, d, o):
    if o < 0 or o + struct.calcsize(fmt) > len(d):
        raise SlocError(f'read {fmt} at {o:#x} outside file ({len(d):#x})')
    return struct.unpack_from(fmt, d, o)


def parse(data):
    """Returns {'sheets': [...], 'nps2': [...], 'info': {map_id: {...}}} or raises SlocError."""
    if len(data) > MAX_FILE:
        raise SlocError('file too large')
    if data[:4] != b'sloc':
        raise SlocError('not a sloc file')
    table = _u('<H', data, 10)[0]
    count, rel = _u('<HH', data, table)
    if not 0 < count <= MAX_SHEETS:
        raise SlocError(f'implausible sheet count {count}')
    raw_ids = _u(f'<{count}I', data, table + rel)

    info_n = _u('<I', data, 0x10)[0]
    if info_n > MAX_SHEETS:
        raise SlocError('implausible info count')
    offs = _u(f'<{info_n}H', data, 0x14)
    info = {}
    for k, off in enumerate(offs):
        start = 0x10 + off
        end = 0x10 + offs[k + 1] if k + 1 < info_n else table
        if not 0 < start < end <= len(data):
            raise SlocError(f'info block {k} out of range')
        blk = data[start:end]
        mid = _u('<H', blk, 0)[0]
        lp, ni = blk.find(b'LCMPOS1'), blk.find(b'NAVIICN2')
        points = []
        if lp >= 0:
            stop = ni if ni > lp else len(blk)
            for q in range(lp + 0x70, stop - 15, 16):
                x, y, z, w = struct.unpack_from('<4f', blk, q)
                if w == 0 and (x, y, z) != (0, 0, 0) and max(abs(x), abs(y), abs(z)) < 1e5:
                    points.append((x, y, z))
        icons = []
        if ni >= 0:
            for q in range(ni + 0x30, len(blk) - 15, 16):
                icons.append(blk[q:q + 16].hex())
        info[mid] = {'block_offset': start, 'lcmpos_points': points, 'naviicn2_raw': icons}

    sheets, o = [], table
    for k in range(count):
        o = data.find(b'TIM2', o)
        if o < 0:
            raise SlocError(f'sheet {k}: TIM2 not found')
        total, clut, img, hdr, cols, fmt, mip, ctype, itype, w, h = _u('<IIIHHBBBBHH', data, o + 16)
        tex0 = _u('<Q', data, o + 16 + 24)[0]
        start = o + 16 + hdr
        if itype != 5 or not (0 < w <= MAX_DIM and 0 < h <= MAX_DIM) or img != w * h or start + img > len(data):
            raise SlocError(f'sheet {k}: unsupported or truncated TIM2 at {o:#x}')
        mask = np.frombuffer(data, np.uint8, img, start).reshape(h, w)
        # The declared CLUT would follow the image; in these files the next record tag (TIM2/NPS2) is there.
        after = data[start + img:start + img + 4]
        sheets.append({
            'index': k, 'raw_id': raw_ids[k], 'map_id': raw_ids[k] & 0xFFFF, 'id_flags': raw_ids[k] >> 16,
            'offset': o, 'width': w, 'height': h, 'declared_clut_bytes': clut,
            'clut_stored': after not in (b'TIM2', b'NPS2') and start + img + clut <= len(data),
            'tex0_psm': (tex0 >> 20) & 0x3F, 'mask_max': int(mask.max()), 'mask': mask})
        o = start + img

    nps2 = []
    p = data.find(b'NPS2', o)
    while p >= 0 and len(nps2) < count:
        nps2.append(_u('<4f', data, p + 16))
        p = data.find(b'NPS2', p + 32)
    if len(nps2) != count:
        raise SlocError(f'{len(nps2)} NPS2 records for {count} sheets')
    for s, r in zip(sheets, nps2):
        s['nps2'] = {'x0': r[0], 'z0': r[1], 'width': r[2], 'depth': r[3]}
    return {'sheets': sheets, 'info': info}


def mask_rgba(mask, color=(230, 230, 230)):
    """Diagnostic rendering: value v -> alpha min(255, 2v) over a neutral colour. The game's tint is unknown."""
    a = np.minimum(255, mask.astype(np.uint16) * 2).astype(np.uint8)
    rgba = np.zeros(mask.shape + (4,), np.uint8)
    rgba[..., 0], rgba[..., 1], rgba[..., 2] = color
    rgba[..., 3] = a
    return rgba
