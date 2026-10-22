"""Synthetic checks for builder/menumap_icons.py (no game files needed): symbol rows and threshold rows."""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import menumap_icons  # noqa: E402


def sloc(map_id, rows_by_group):
    """One-map sloc file: directory, then an info block holding map_id and a NAVIICN2 table."""
    tag_at = 0x40
    navi = bytearray(b'NAVIICN2' + struct.pack('<H', 10) + bytes(0x28))
    off = 0x32
    for g in range(1, 10):
        rows = rows_by_group.get(g, [])
        struct.pack_into('<HH', navi, 0xA + 4 * g, off if rows else 0, len(rows))
        off += 16 * len(rows)
    for g in range(1, 10):
        for r in rows_by_group.get(g, []):
            navi += struct.pack('<5hHI', *r)
    info = struct.pack('<H', map_id) + bytes(tag_at - 2) + bytes(navi)
    body_start = 22
    table = body_start + len(info)
    head = b'sloc' + bytes(6) + struct.pack('<H', table) + bytes(4) + struct.pack('<I', 1) + struct.pack('<H', body_start - 16)
    return head + info


def main():
    ok = 0
    data = sloc(578, {5: [(8, 8, 10, 20, 1, 0, 0), (-16, 40, 12, 22, 14, 0x401B, 7)], 9: [(0, 0, 3, 4, 2, 0xFFFF, 0)]})
    rows = menumap_icons.navi(data)
    assert rows == [(578, 5, 0, 1, 8, 8, 10, 20, 0), (578, 5, 1, 14, -16, 40, 12, 22, 0x401B),
                    (578, 9, 0, 2, 0, 0, 3, 4, 0xFFFF)], rows
    ok += 1
    for bad in (b'xxxx' + data[4:], data[:-8], data[:10] + struct.pack('<H', 4) + data[12:]):
        try:
            menumap_icons.navi(bad)
            raise AssertionError('accepted a malformed sloc file')
        except ValueError:
            pass
    ok += 1
    rom = b'FLAGROM1' + struct.pack('<H', 3) + struct.pack('<3H', 0, 16, 22) + struct.pack('<3H', 10, 20, 0x801E) \
        + struct.pack('<H', 0x8005)
    assert menumap_icons.flagrom(rom) == [None, [10, 20, 0x801E], [0x8005]]
    for bad in (b'FLAGROMX' + rom[8:], rom[:-2], rom[:12] + struct.pack('<H', 15) + rom[14:]):
        try:
            menumap_icons.flagrom(bad)
            raise AssertionError('accepted a malformed FLAGROM1')
        except ValueError:
            pass
    ok += 1
    print(f'{ok}/3 PASS. Map symbol rows (signed points, groups, gates) and threshold rows; malformed files refused')


if __name__ == '__main__':
    main()
