"""Builds boards.json for the companion's Licences tab from the user's OWN game files (never shipped in the APK):
the 12 licence boards (board_N.bin, 'licd' + u16 w,h + w*h u16 node ids, 0xFFFF empty) and each licence node's name
(battle-pack section 12 record +0 = name text id -> section 61), from the deployed (modded) battle pack.
Job j uses board_(j+1) (Gambit_Autobuilder_2026-10-21.md). Costs, types, grants and ownership stay live (logger).

Usage: python -B boards_pack.py <boards_dir> <battle_pack.bin> <out boards.json>
"""
import hashlib
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from gambit_names import sections, decode  # noqa: E402

JOBS = ["White Mage", "Uhlan", "Machinist", "Red Battlemage", "Knight", "Monk", "Time Battlemage",
        "Foebreaker", "Archer", "Black Mage", "Bushi", "Shikari"]


def table(d):
    n = struct.unpack_from('<I', d)[0]
    o = list(struct.unpack_from(f'<{n}I', d, 4)) + [len(d)]
    return [d[o[i]:o[i + 1]] for i in range(n)]


def main():
    bdir, pack_path, out = Path(sys.argv[1]), Path(sys.argv[2]), Path(sys.argv[3])
    pack = pack_path.read_bytes()
    ss = sections(pack)
    texts = [table(x) for x in table(ss[61])]

    def name(tid):
        try:
            z = [x for x in texts[tid >> 11][tid & 2047].split(b'\0') if x]
            return decode(z[0])[0] if z else None
        except Exception:
            return None

    s12 = ss[12]
    count, stride = struct.unpack_from('<II', s12, 4)
    names = {}
    for n in range(count):
        nm = name(struct.unpack_from('<H', s12, 0x20 + n * stride)[0])
        if nm: nm = nm.replace('{a0}', '&').replace('{81}', 'ú')   # two glyphs gambit_names.decode doesn't map
        if nm: names[str(n)] = nm
    boards = []
    for j, job in enumerate(JOBS):
        raw = (bdir / f'board_{j + 1}.bin').read_bytes()
        assert raw[:4] == b'licd'
        w, h = struct.unpack_from('<HH', raw, 4)
        cells = raw[8:8 + 2 * w * h]
        assert len(cells) == 2 * w * h
        boards.append({'job': j, 'name': job, 'w': w, 'h': h, 'cells': cells.hex()})
    doc = {'format': 'ffxii-companion-boards-1', 'kind': 'REFERENCE',
           'note': "Built on the PC from the user's own game files; not for redistribution.",
           'packSha256': hashlib.sha256(pack).hexdigest(), 'nodeCount': count, 'names': names, 'boards': boards}
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, ensure_ascii=False), encoding='utf-8')
    print(f'{len(boards)} boards, {len(names)} named nodes -> {out}')


if __name__ == '__main__':
    main()
