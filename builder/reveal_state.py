"""Decode an FFXII TZA menu-map reveal record and gate a sheet mask with it (no game access).

Established from the PC exe (see reveal_static_evidence.py):
  * record = 32 bytes at save-region offset 0x8424 + slot*32 (VA 0x216C6A4 + slot*32), slot <= 0x180;
    slot = low 12 bits of navimapdata.bin's 3-byte record indexed by map ID (0xFFF = none).
  * a cell is revealed when bit (x & 7) of byte [y*stride + x//8] is set; the draw loop tests one cell per
    64x64-pixel tile of the sheet and colours revealed and unrevealed tiles differently.
  * a map counts as visited when any byte of its record is non-zero.
  * row stride = (width/64) >> 3 bytes, as the game's descriptor initialiser (VA 0x3C5410) computes it:
    512-px-wide sheets -> 1, 1024-px -> 2 (FFXII_Reveal_Descriptor_Followup_2026-10-21.md). stride_for()
    mirrors that. Runtime: the live record of map 581 (512 px wide) matched the in-game map only at stride 1
    (Thor_Reveal_Runtime_2026-10-21.md); 1024-px sheets are not yet checked live.
  * one renderer path (VA 0x3C4070 -> 0x3C6420) passes this save record as the descriptor's bitmap.
STILL UNVERIFIED: that the descriptor's dimensions always come from the drawn sheet's texture (writers of
VA 0x2B457F8/0x2B457FC untraced), other renderer/mod paths, and coherent sampling during updates.

Usage: reveal_state.py <sloc file> <map_id> <out.png> <hex record (64 hex chars)> [stride|auto] [source label]
       Writes a preview labelled with the record's source (default: SYNTHETIC). This tool never reads game
       memory or saves itself; pass a source label only for bytes captured elsewhere (e.g. the reveal probe).
"""
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
import menumap_sloc  # noqa: E402

TILE = 64
RECORD_BYTES = 32
MAX_SLOT = 0x180


def record_va(slot, save_base=0x2164280):
    if not 0 <= slot <= MAX_SLOT:
        raise ValueError('slot out of range')
    return save_base + 0x8200 + 0x224 + slot * RECORD_BYTES


def cell(record, x, y, cols, rows, stride):
    """Mirror of the game's bitmap test (bounds-checked; out of range = not revealed)."""
    if len(record) != RECORD_BYTES:
        raise ValueError('record must be 32 bytes')
    if not (0 <= x < cols and 0 <= y < rows):
        return False
    i = y * stride + (x >> 3)
    return i < RECORD_BYTES and bool(record[i] >> (x & 7) & 1)


def stride_for(width):
    """Row bytes exactly as the game's descriptor initialiser computes them: (width/64) >> 3."""
    return (width // TILE) >> 3


def tile_grid(record, width, height, stride=None):
    cols, rows = width // TILE, height // TILE
    if stride is None:
        stride = stride_for(width)
    return np.array([[cell(record, x, y, cols, rows, stride) for x in range(cols)] for y in range(rows)])


def visited(record):
    return any(record)


def gate(mask, record, stride=None):
    """Mask with unrevealed 64-px tiles zeroed (how the companion would honour fog; tint not applied).
    stride=None uses the game's rule for the mask's width."""
    h, w = mask.shape
    g = tile_grid(record, w, h, stride)
    out = mask.copy()
    for y in range(g.shape[0]):
        for x in range(g.shape[1]):
            if not g[y, x]:
                out[y * TILE:(y + 1) * TILE, x * TILE:(x + 1) * TILE] = 0
    return out, g


if __name__ == '__main__':
    from PIL import Image, ImageDraw, ImageFont
    src, mid, dst, rec_hex = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    source = sys.argv[6] if len(sys.argv) > 6 else None
    record = bytes.fromhex(rec_hex)
    sheet = next(s for s in menumap_sloc.parse(Path(src).read_bytes())['sheets'] if s['map_id'] == mid)
    arg = sys.argv[5] if len(sys.argv) > 5 else 'auto'
    stride = stride_for(sheet['width']) if arg == 'auto' else int(arg)
    gated, grid = gate(sheet['mask'], record, stride)
    a = np.minimum(255, gated.astype(np.uint16) * 2).astype(np.uint8)
    img = Image.fromarray(np.dstack([np.full_like(a, 230)] * 3 + [a]), 'RGBA')
    bg = Image.new('RGBA', img.size, (28, 30, 38, 255))
    d = ImageDraw.Draw(bg)
    for y in range(grid.shape[0]):
        for x in range(grid.shape[1]):
            d.rectangle([x * TILE, y * TILE, (x + 1) * TILE - 1, (y + 1) * TILE - 1], outline=(60, 64, 76, 255))
    out = Image.alpha_composite(bg, img)
    panel = Image.new('RGB', (max(out.width, 980), out.height + 110), (214, 92, 214))
    panel.paste(out.convert('RGB'), (0, 110))
    t = ImageDraw.Draw(panel)
    try:
        f = ImageFont.truetype('C:/Windows/Fonts/segoeuib.ttf', 17)
    except OSError:
        f = ImageFont.load_default()
    head = ('REVEAL RECORD SOURCE: ' + source) if source else 'SYNTHETIC REVEAL RECORD - not read from any save or game memory'
    for i, s in enumerate([head,
                           f'map {mid}: tiles hidden where record bits are clear (64 px tiles, stride {stride}'
                           + (' = game rule)' if stride == stride_for(sheet['width']) else ' OVERRIDE)'),
                           'Established: record location/size, bit test, 64 px tiles. Tint/colours of unrevealed tiles: not applied']):
        t.text((8, 6 + i * 34), s, fill=(14, 17, 23), font=f)
    panel.save(dst)
    print(dst, 'revealed tiles', int(grid.sum()), 'of', grid.size)
