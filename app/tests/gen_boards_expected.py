"""Independent expectations for LicenceBoardsTest: per member (real save_27 dump) and per board for their two jobs,
the owned / open / total cell counts, computed here from the raw board files and licence bits.

Usage: gen_boards_expected.py <boards.json> <save_27.bin> <out.json>
"""
import json
import struct
import sys
from pathlib import Path

SAVE = 0x4E68C


def main():
    doc = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    d = Path(sys.argv[2]).read_bytes()
    out = []
    for m in range(6):
        r = d[SAVE + 8 + m * 0x1C8:SAVE + 8 + (m + 1) * 0x1C8]
        bits = r[0x194:0x194 + 0x2F]
        own = lambda n: n != 0xFFFF and bits[n >> 3] >> (n & 7) & 1
        for job in (r[0x1C3], r[0x1C4]):
            if job == 0xFF: continue
            b = doc['boards'][job]
            w, h = b['w'], b['h']
            c = struct.unpack(f'<{w * h}H', bytes.fromhex(b['cells']))
            at = lambda x, y: c[y * w + x] if 0 <= x < w and 0 <= y < h else 0xFFFF
            o = p = t = 0
            for y in range(h):
                for x in range(w):
                    n = at(x, y)
                    if n == 0xFFFF: continue
                    t += 1
                    if own(n): o += 1
                    elif any(own(at(x + dx, y + dy)) for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))): p += 1
            out.append({'member': m, 'job': job, 'owned': o, 'open': p, 'total': t})
    Path(sys.argv[3]).write_text(json.dumps(out), encoding='utf-8')
    print(f'{len(out)} member boards -> {sys.argv[3]}')


if __name__ == '__main__':
    main()
