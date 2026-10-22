"""Builds the companion's local map atlas from the user's own FFXII_TZA.vbf (read-only, in memory).

Output folder (pushed to /sdcard/Download/FFXII_companion/atlas/ on the Thor; never bundled into the APK):
  index.json  format "ffxii-companion-atlas-1": one entry per menu-map sheet with mapId, region file, sheet
              index, width, height, the game's reveal stride, sheets-per-map count, 64-px tile occupancy and
              the PNG's sha256.
  <mapId>_<region>_<index>.png  8-bit grayscale coverage mask (sheet value 0..127 doubled), no colour or tint:
              the game's runtime palette/tint for these masks is still unknown.
  regions.json  format "ffxii-companion-regions-1": per region file, where each sheet sits in the game's region
              view, the connector lines and (0.12) the exit labels (menumap_names.py). Rule (evidence/map_assets/region_layout, 21 Oct 2026): a sheet's top-left
              corner is its menumap_location.bin ('lmap') point and 1 layout unit = 4 sheet pixels; overlaying that
              composite on the Thor's Zertinan Caverns region screen put every visited neighbour on the game's ink.
Each index.json entry also carries "slot" (reveal record slot from the game's navimapdata.bin, null = none) and
"origin" {x0, z0, width, depth}: the sheet's NPS2 record. The game draws the map in world units with 16 units per
64-px tile and the view translated by -(x0, z0), so sheet px = 4 * (x - x0), 4 * (z - z0)
(FFXII_Position_Transform_Static_2026-10-21.md).
The receiver verifies each PNG's sha256 and dimensions against index.json before use.

Usage: mapatlas_pack.py <FFXII_TZA.vbf> <out_dir>
"""
import hashlib
import io
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).parent))
import menumap_sloc  # noqa: E402
import menumap_names  # noqa: E402
import menumap_tables  # noqa: E402
import navimap_index  # noqa: E402
import menumap_tile_index  # noqa: E402
import reveal_state  # noqa: E402
import vbf_extract  # noqa: E402

FORMAT = 'ffxii-companion-atlas-1'
REGIONS_FORMAT = 'ffxii-companion-regions-1'
LOCATION = 'ps2data/image/ff12/myoshiok/us/worldmap/menumap_location.bin'
NAV = 'ps2data/image/ff12/in/bin/navimapdata.bin'
UNITS_PER_SHEET_PX = 0.25


def png_bytes(mask):
    buf = io.BytesIO()
    Image.fromarray(np.minimum(255, mask.astype(np.uint16) * 2).astype(np.uint8), 'L').save(buf, 'PNG', optimize=True)
    return buf.getvalue()


def main(vbf, out):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    f, index, blocks = vbf_extract.open_vbf(vbf)
    names = sorted(k for k in index if k.startswith(menumap_tile_index.PREFIX) and k.endswith('.bin'))
    _, nav = navimap_index.parse(vbf_extract.extract(f, index[NAV], blocks))
    lmap = menumap_tables.parse_location(vbf_extract.extract(f, index[LOCATION], blocks))
    labels = {}
    if menumap_names.PATH in index:                 # exit labels are optional: no file, no labels
        try:
            labels = menumap_names.parse(vbf_extract.extract(f, index[menumap_names.PATH], blocks))
        except ValueError as e:
            print('exit labels skipped:', e)

    def slot_of(map_id):
        if map_id >= len(nav):
            return None
        v = int.from_bytes(nav[map_id][:2], 'little') & 0xFFF
        return None if v == 0xFFF or v > 0x180 else v

    entries, regions = [], []
    for name in names:
        region = name.rsplit('/', 1)[1][len('menumap_'):-len('.bin')]
        for i, s in enumerate(menumap_sloc.parse(vbf_extract.extract(f, index[name], blocks))['sheets']):
            data = png_bytes(s['mask'])
            fn = f"{s['map_id']}_{region}_{i}.png"
            (out / fn).write_bytes(data)
            entries.append({'mapId': s['map_id'], 'region': region, 'sheet': i, 'file': fn, 'slot': slot_of(s['map_id']),
                            'width': s['width'], 'height': s['height'],
                            'stride': reveal_state.stride_for(s['width']),
                            'tiles': menumap_tile_index.occupancy(s['mask']),
                            'origin': {k: round(v, 4) for k, v in s['nps2'].items()},
                            'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()})
        regions.append(region_layout(region, [e for e in entries if e['region'] == region], lmap, labels))
    per_map = {}
    for e in entries:
        per_map[e['mapId']] = per_map.get(e['mapId'], 0) + 1
    for e in entries:
        e['sheetsForMap'] = per_map[e['mapId']]
    st = os.stat(vbf)
    doc = {'format': FORMAT, 'source': {'vbf': 'FFXII_TZA.vbf', 'vbfBytes': st.st_size, 'regionFiles': len(names)},
           'note': 'Coverage masks from the user\'s own game files, for personal use on their device. No tint.',
           'sheets': entries}
    (out / 'index.json').write_text(json.dumps(doc, separators=(',', ':')), encoding='utf-8')
    regions = [r for r in regions if r]
    (out / 'regions.json').write_text(json.dumps({'format': REGIONS_FORMAT, 'unitsPerSheetPx': UNITS_PER_SHEET_PX,
                                                  'regions': regions}, separators=(',', ':')), encoding='utf-8')
    print('regions with layout', len(regions), 'exit labels', sum(len(r['exits']) for r in regions))
    total = sum(e['bytes'] for e in entries)
    print(out, len(entries), 'sheets', f'{total / 1e6:.1f} MB PNG', 'multi-sheet maps',
          sum(1 for v in per_map.values() if v > 1))


def region_layout(region, sheets, lmap, names=None):
    """Pairs a region file's sheets with its lmap record (record k = menumap_subKK; the k-th sheet of a map ID
    takes that ID's k-th lmap entry). None when the file has no record or the map lists differ."""
    if not region.startswith('sub') or not region[3:].isdigit() or int(region[3:]) >= len(lmap):
        return None
    rec = lmap[int(region[3:])]
    if sorted(m['map_id'] for m in rec['maps']) != sorted(s['mapId'] for s in sheets):
        return None
    taken, placed = {}, []
    for s in sheets:
        k = taken.get(s['mapId'], 0)
        taken[s['mapId']] = k + 1
        m = [e for e in rec['maps'] if e['map_id'] == s['mapId']][k]
        placed.append({'mapId': s['mapId'], 'sheet': s['sheet'], 'file': s['file'], 'x': m['x'], 'y': m['y']})
    links = [{'a': l['map_a'], 'b': l['map_b'], 'ax': l['a'][0], 'ay': l['a'][1], 'bx': l['b'][0], 'by': l['b'][1]}
             for l in rec['links']]
    return {'region': region, 'sheets': placed, 'links': links,
            'exits': menumap_names.exit_labels(rec['exits'], names or {})}


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
