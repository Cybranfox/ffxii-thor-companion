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
    print(f'{ok}/3 PASS. Objectives builder: font rule for extra punctuation, unknown text left out, hint table order')


if __name__ == '__main__':
    main()
