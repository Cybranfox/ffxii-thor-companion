"""Per-sheet 64-px tile occupancy for every US menu-map region file, read in memory from the VBF (read-only).

For each sheet: region file, sheet index, map ID, width, height, the game's reveal row stride for that width
(reveal_state.stride_for) and a row list where '#' = the tile has any drawn mask pixel, '.' = blank.
Lets reveal_table_report.py judge any live reveal record without extracting 270 MB of region files.

Usage: menumap_tile_index.py <FFXII_TZA.vbf> <out.json>
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import menumap_sloc  # noqa: E402
import reveal_state  # noqa: E402
import vbf_extract  # noqa: E402

PREFIX = 'ps2data/image/ff12/myoshiok/us/worldmap/menumap_sub'
T = reveal_state.TILE


def occupancy(mask):
    h, w = mask.shape
    return [''.join('#' if mask[y * T:(y + 1) * T, x * T:(x + 1) * T].max() > 0 else '.' for x in range(w // T))
            for y in range(h // T)]


def main(vbf, out):
    f, index, blocks = vbf_extract.open_vbf(vbf)
    names = sorted(k for k in index if k.startswith(PREFIX) and k.endswith('.bin'))
    sheets = []
    for name in names:
        parsed = menumap_sloc.parse(vbf_extract.extract(f, index[name], blocks))
        for i, s in enumerate(parsed['sheets']):
            sheets.append({'file': name.rsplit('/', 1)[1], 'index': i, 'map_id': s['map_id'],
                           'width': s['width'], 'height': s['height'],
                           'stride': reveal_state.stride_for(s['width']), 'tiles': occupancy(s['mask'])})
    Path(out).write_text(json.dumps({'source': 'FFXII_TZA.vbf (read-only, in memory)', 'files': len(names),
                                     'sheets': sheets}, indent=1), encoding='utf-8')
    widths = {}
    for s in sheets:
        widths[s['width']] = widths.get(s['width'], 0) + 1
    print(out, len(names), 'files', len(sheets), 'sheets', 'widths', widths)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
