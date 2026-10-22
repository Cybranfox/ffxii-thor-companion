"""Synthetic checks for builder/menumap_names.py (no game files needed): layout, bounds, and the exit join."""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import menumap_names  # noqa: E402


def enc(s):
    out = bytearray()
    for ch in s:
        if 'A' <= ch <= 'Z':
            out.append(ord(ch) - 0x21)
        elif 'a' <= ch <= 'z':
            out.append(ord(ch) - 0x27)
        elif ch == ' ':
            out.append(0x04)
        else:
            out.append(0xF0)                          # not a known glyph
    return bytes(out)


def st2e(rows):
    """rows: [(text_id, encoded bytes)] -> a file shaped like menu_mapname.bin."""
    n = len(rows)
    table = b''.join(struct.pack('<HH', tid, 0) for tid, _ in rows)
    root = 0x20 + len(table)
    strings, offs = b'', []
    for _, b in rows:
        offs.append(4 + 4 * n + len(strings))
        strings += b + b'\0'
    group = struct.pack('<I', n) + b''.join(struct.pack('<I', o) for o in offs) + strings
    head = b'st2e' + struct.pack('<IIIII', n, 4, 0x20, 0, root) + b'\0' * 8
    return head + table + struct.pack('<II', 1, 8) + group


def main():
    ok = 0
    names = menumap_names.parse(st2e([(60201, enc('To Lowtown')), (60203, enc('To the Estersand')), (7, enc('Bad?'))]))
    assert names == {60201: 'To Lowtown', 60203: 'To the Estersand'}, names
    ok += 1
    for bad in (b'xxxx' + b'\0' * 40, st2e([(1, enc('A'))])[:-3], b'st2e' + struct.pack('<III', 9999, 4, 0x20) + b'\0' * 20):
        try:
            menumap_names.parse(bad)
            raise AssertionError('accepted a malformed file')
        except ValueError:
            pass
    ok += 1
    exits = [{'x': 425, 'y': 530, 'x2': 444, 'y2': 528, 'text_id': 60201, 'raw_tail': '080effff0102ffff'},
             {'x': 593, 'y': 410, 'x2': 568, 'y2': 430, 'text_id': 60203, 'raw_tail': '00151b000611ffff'},
             {'x': 1, 'y': 2, 'x2': 3, 'y2': 4, 'text_id': 99, 'raw_tail': '0' * 16},
             {'x': 65533, 'y': 29, 'x2': 65521, 'y2': 10, 'text_id': 60201, 'raw_tail': '0000430000000000'}]
    lab = menumap_names.exit_labels(exits, names)
    assert lab == [{'x': 425, 'y': 530, 'lx': 444, 'ly': 528, 'text': 'To Lowtown', 'gate': 0xFFFF},
                   {'x': 593, 'y': 410, 'lx': 568, 'ly': 430, 'text': 'To the Estersand', 'gate': 0x1B},
                   {'x': -3, 'y': 29, 'lx': -15, 'ly': 10, 'text': 'To Lowtown', 'gate': 0x43}], lab
    ok += 1
    print(f'{ok}/3 PASS. menu_mapname reader: decoding, malformed files refused, exit join (label at the second point, signed points)')


if __name__ == '__main__':
    main()
