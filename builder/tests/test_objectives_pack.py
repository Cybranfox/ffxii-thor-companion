"""Synthetic checks for builder/objectives_pack.py (no game files): text decoding with the font rule, the hint table."""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import objectives_pack as O  # noqa: E402


def font(chars):
    """font00.dat shape: 40-byte header, 36-byte records; glyph i holds UTF-8 for byte i + 0x20."""
    count = 0x80
    head = struct.pack('<10I', 1, count, 512, 512, 0, 0, 0, 0, 0, 36)
    recs = b''
    for i in range(count):
        ch = chars.get(i + 0x20, '')
        recs += struct.pack('<II', i, int.from_bytes(ch.encode('utf-8').ljust(4, b'\0'), 'little')) + bytes(28)
    return head + recs


def main():
    ok = 0
    chars = O.font_chars(font({0x99: '!', 0x9A: '?', 0x8F: '—'}))
    assert chars == {0x99: '!', 0x9A: '?', 0x8F: '—'}, chars
    ok += 1
    hello = bytes([0x27, 0x3E, 0x45, 0x45, 0x48])                    # "Hello" in the game's encoding
    assert O.text(hello + b'\x99', chars) == 'Hello!'
    assert O.text(b'\x0f\x20\x81\x94' + hello + b'\x02' + hello + b'\x9a', chars) == 'Hello\nHello?'
    assert O.text(hello + b'\x01', chars) is None                      # still unknown: left out, never garbled
    ok += 1
    rows = [(0, 0xFFFF, 0, 0, 0), (100, 0x4001, 0, 0, 1)]
    cmap = b'cmap' + bytes(4) + struct.pack('<HH', 16, 24) + bytes(4) + struct.pack('<I', 0) + bytes(4) \
        + struct.pack('<H', 0) + bytes(2) + struct.pack('<I', 2) + b''.join(struct.pack('<HHBBH', *r) for r in rows)
    assert O.cmap_rows(cmap) == [(0, 0xFFFF, 0, 0), (100, 0x4001, 0, 1)]
    bad = cmap[:-8] + struct.pack('<HHBBH', 50, 0, 0, 0, 1)            # thresholds out of order
    try:
        O.cmap_rows(bad[:len(cmap) - 16] + struct.pack('<HHBBH', 100, 0, 0, 0, 0) + struct.pack('<HHBBH', 50, 0, 0, 0, 1))
        raise AssertionError('accepted unordered thresholds')
    except ValueError:
        pass
    ok += 1
    # hunt map helper (QHD0): header, then a record {count, kind} and 18-byte entries
    entry = struct.pack('<BBH', 16, 3, 291) + bytes.fromhex('3ffcffffffff07') * 2
    qhd = b'QHD0' + struct.pack('<II', 1, 12) + struct.pack('<BB', 1, 1) + entry
    assert O.map_hints(b'xx' + qhd, 2) == {'kind': 1, 'entries': [{'showMask': '3ffcffffffff07', 'markerMask': '3ffcffffffff07',
                                                                    'mapId': 291, 'markerIndex': 3}]}
    for bad in (qhd[:-1], b'QHDX' + qhd[4:], qhd[:4] + struct.pack('<II', 2, 12) + qhd[12:]):
        try:
            O.map_hints(bad, 0)
            raise AssertionError('accepted a malformed hunt map helper')
        except ValueError:
            pass
    ok += 1
    print(f'{ok}/4 PASS. Objectives builder: font rule for extra punctuation, unknown text left out, hint table order, hunt maps')


if __name__ == '__main__':
    main()
