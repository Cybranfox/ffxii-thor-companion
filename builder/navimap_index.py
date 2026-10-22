"""Reader for ps2data/image/ff12/<lang>/bin/navimapdata.bin ('NAV', version 2).

Established 2026-10-21: 8-byte header ('NAV', u8 version, u32 count) + count x 3-byte records, and the record
INDEX is the game map ID (records are not looked up by contents). Evidence: 379 of 380 map IDs that own a
menumap sheet have a non-placeholder record, versus 28 % of the other indices; maps of one region get
consecutive first bytes. The 3 bytes are too small for bounds or a transform; their meaning is unknown
(candidate: 12-bit index + 12-bit group, untested).

Usage: navimap_index.py <navimapdata.bin> [map_id ...]
"""
import struct
import sys
from pathlib import Path


def parse(data):
    if data[:3] != b'NAV' or len(data) < 8:
        raise ValueError('not a NAV file')
    version, count = data[3], struct.unpack_from('<I', data, 4)[0]
    if 8 + 3 * count != len(data):
        raise ValueError(f'size {len(data)} does not match {count} records')
    return version, [data[8 + 3 * i:11 + 3 * i] for i in range(count)]


def describe(rec):
    v = int.from_bytes(rec, 'little')
    return {'hex': rec.hex(), 'placeholder': rec[0] == 0xFF, 'low12': v & 0xFFF, 'high12': v >> 12}


if __name__ == '__main__':
    version, recs = parse(Path(sys.argv[1]).read_bytes())
    print(f'NAV v{version}, {len(recs)} records (index = map ID)')
    for mid in map(int, sys.argv[2:]):
        print(mid, describe(recs[mid]) if mid < len(recs) else 'out of range')
