"""Offline replica of the game's runtime-gambit-list builder 0x327DE0, checked against live Thor dumps.

For member m: take stored set k = u32 save+0x7370+m*4; for each slot i (12) with enabled bit (+0x30) set and condition/
action != 0xFFFF, append a 0x18-byte entry at runtime+0x20+j*0x18 (runtime = 0x2089378 + m*0x170) with the action and
fields copied from the condition's battle-pack section 7 record; the count byte at runtime+0 = number of entries.
Only the fields 0x327DE0 writes are compared (+0x22/+0x23 are left untouched by the game).

21 Oct 2026: replica == live runtime list for 24/24 member-snapshots, including a list rebuilt after an in-menu toggle.

Usage: gambit_runtime.py <battle_pack.bin> <gambit-probe 0.2.0 history.jsonl>
"""
import json
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import gambit_names  # noqa: E402

# runtime entry offset -> (record offset, width) ; action is special-cased
FIELDS = {0x24: (0x0E, 2), 0x26: (0x10, 1), 0x28: (0x08, 2), 0x2A: (0x18, 2), 0x2C: (0x11, 1),
          0x2E: (0x0A, 2), 0x30: (0x1A, 2), 0x32: (0x12, 1), 0x34: (0x0C, 2), 0x36: (0x1C, 2)}


def condition_table(pack):
    s7 = gambit_names.sections(pack)[7]
    magic, count, stride, data = struct.unpack_from('<4I', s7, 0)
    return lambda c: s7[data + (c - 0x6000) * stride:data + (c - 0x6000 + 1) * stride]


def build(stored_set, rec):
    """Entries (list of {offset: value}) the game would write for one stored set."""
    bits = struct.unpack_from('<H', stored_set, 0x30)[0]
    out = []
    for i in range(12):
        c, a = struct.unpack_from('<HH', stored_set, 4 * i)
        if not (bits >> i & 1) or c == 0xFFFF or a == 0xFFFF:
            continue
        r = rec(c)
        e = {0x20: a}
        for off, (ro, w) in FIELDS.items():
            e[off] = r[ro] if w == 1 else struct.unpack_from('<H', r, ro)[0]
        out.append(e)
    return out


def matches(runtime, entries):
    if runtime[0] != len(entries):
        return False
    return all(struct.unpack_from('<H', runtime, 0x20 - 0x20 + j * 0x18 + off)[0] == v
               for j, e in enumerate(entries) for off, v in e.items())


if __name__ == '__main__':
    rec = condition_table(Path(sys.argv[1]).read_bytes())
    snaps = [json.loads(l) for l in Path(sys.argv[2]).read_text(encoding='utf-8').splitlines() if l.strip()]
    snaps = [s for s in snaps if s.get('status') == 'ok' and s.get('stable')]
    total = good = 0
    for s in snaps:
        st, rt = s['stored'].split(','), s['runtime'].split(',')
        idx = [int(x) for x in s['setidx'].split(',')]
        for m in range(len(st)):
            S, R = bytes.fromhex(st[m]), bytes.fromhex(rt[m])
            total += 1
            good += matches(R, build(S[idx[m] * 0x34:(idx[m] + 1) * 0x34], rec))
    print(f'{good}/{total} member-snapshots: replica == game runtime list')
    sys.exit(0 if good == total else 1)
