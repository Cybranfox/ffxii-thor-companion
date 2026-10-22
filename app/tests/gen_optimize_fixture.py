"""Optimize cross-check fixtures (companion 0.19.0). The expected picks come from the other session's Python replica of
the game's Optimize (draft/tools/gear_optimize/equipment_decode.py, wrapped here as a function, logic unchanged), run on
the REAL save_gear_1918 dump and on modified copies of it: all spares, no spares, empty weapon/ammo, random spares.
The Java Optimize must give the same five IDs for every member in every case.

Usage: gen_optimize_fixture.py <battle_pack.bin> <licence_nodes.json> <save_gear_1918.bin> <out_dir>
"""
import json
import random
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'builder'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
from gambit_names import sections  # noqa: E402
from gen_build_fixture import build, SAVE  # noqa: E402

STOCK = SAVE + 0x47C8


def load(pack, nodes_path):
    ss = sections(pack)
    s = ss[13]
    _, count, stride, base = struct.unpack_from('<4I', s)
    eq = {}
    for i in range(count):
        r = s[base + i * stride:base + (i + 1) * stride]
        eq[i + 0x1000] = {'slot': r[7] >> 5, 'no_license': bool(r[7] & 16), 'category': r[9], 'offhand_category': r[0x11],
                          'optimization_tiebreaker': r[6], 'range_evade_defense': r[0x18],
                          'formula_magic_evade_resist': r[0x19], 'attack_or_augment': r[0x1a]}
    nodes = bytes.fromhex(json.loads(Path(nodes_path).read_text())['records'])
    lic = {}
    for j in range(len(nodes) // 24):
        for x in struct.unpack_from('<8H', nodes, 24 * j + 8):
            if 0x1000 <= x < 0x2000:
                lic.setdefault(x, []).append(j)
    table = {'contract': 'ffxii.equipment.v1', 'count': count, 'stride': stride, 'records': s[base:base + count * stride].hex()}
    return eq, lic, ss[16], table


def optimize(d, m, eq, lic, s16):
    """equipment_decode.py lines 27-77, as a function of the dump and member."""
    words = struct.unpack_from('<420H', d, STOCK)
    available = lambda g: (words[g - 0x1000] & 1023) - (words[g - 0x1000] >> 10) if 0x1000 <= g < 0x11a4 else 0
    o = SAVE + 8 + m * 0x1c8
    orig = list(struct.unpack_from('<5H', d, o + 0x50))
    bits = d[o + 0x194:o + 0x194 + 0x2f]
    restr = struct.unpack_from('<I', s16, 32 + m * 128)[0]
    assert restr == 0

    def licensed(g): return eq[g]['no_license'] or any(bits[j // 8] >> (j % 8) & 1 for j in lic.get(g, []))

    def valid(g, slot, gear):
        if g == ([0x1000] + [0xffff] * 4)[slot]: return True
        if g == 0xffff or g not in eq or available(g) < 1: return False
        r = eq[g]
        if r['slot'] != slot: return False
        if slot == 1 and r['category'] != 0x12 and gear[0] != 0xffff and r['category'] != eq[gear[0]]['offhand_category']: return False
        return not (restr >> r['category'] & 1) and licensed(g)

    def pick(cur, cand, mode):
        if eq[cand]['slot'] in (4, 5): return cur
        if cur == 0xffff: return cand
        a, b = eq[cur], eq[cand]
        if mode in (1, 2, 3):
            ka = (a['formula_magic_evade_resist'], a['range_evade_defense']) if mode == 2 else (a['range_evade_defense'], a['formula_magic_evade_resist'])
            kb = (b['formula_magic_evade_resist'], b['range_evade_defense']) if mode == 2 else (b['range_evade_defense'], b['formula_magic_evade_resist'])
            if ka != kb: return cur if ka > kb else cand
        elif mode == 0:
            sa, sb = score(cur), score(cand)
            if sa != sb: return cur if sa > sb else cand
            if orig[0] != 0xffff:
                if orig[0] == cur: return cur
                if orig[0] == cand: return cand
                if a['category'] != b['category']:
                    c = eq[orig[0]]['category']
                    if c == b['category']: return cand
                    if c == a['category']: return cur
            if a['category'] != b['category']: return cur if a['category'] < b['category'] else cand
        else:
            return cur
        return max((cur, cand), key=lambda x: (eq[x]['optimization_tiebreaker'], x))

    def score(g):
        if g in (0x1000, 0xffff): return 0
        r = eq[g]
        if not 0x17 <= r['offhand_category'] <= 0x1a: return r['attack_or_augment']
        best = orig[1] if orig[1] != 0xffff and eq[orig[1]]['category'] == r['offhand_category'] else 0xffff
        for ammo in range(0x1184, 0x11a4):
            if valid(ammo, 1, [0xffff] * 5) and eq[ammo]['category'] == r['offhand_category']: best = pick(best, ammo, 0)
        return -1 if best == 0xffff else r['attack_or_augment'] + eq[best]['attack_or_augment']

    chosen = orig[:]
    for slot, (lo, hi) in enumerate([(0x1000, 0x10c8), (0x10c8, 0x11a4), (0x10dc, 0x1118), (0x1118, 0x1154)]):
        for g in range(lo, hi):
            if not valid(g, slot, chosen): continue
            if slot == 1 and chosen[0] != 0xffff and eq[g]['category'] != eq[chosen[0]]['offhand_category']: continue
            mode = 0 if 0x1184 <= g < 0x11a4 else slot
            chosen[slot] = pick(chosen[slot], g, mode)
        if slot == 0:
            if chosen[0] == 0xffff: chosen[0] = 0x1000
            if chosen[1] != 0xffff and chosen[0] != 0xffff and eq[chosen[1]]['category'] != eq[chosen[0]]['offhand_category']: chosen[1] = 0xffff
    return chosen


def set_words(d, fn):
    d = bytearray(d)
    w = list(struct.unpack_from('<420H', d, STOCK))
    for i in range(420):
        tot, eqd = w[i] & 0x3FF, w[i] >> 10
        tot = fn(i, tot, eqd)
        w[i] = (eqd << 10) | min(max(tot, eqd), 0x3FF)
    struct.pack_into('<420H', d, STOCK, *w)
    return bytes(d)


def main():
    pack = Path(sys.argv[1]).read_bytes()
    eq, lic, s16, table = load(pack, sys.argv[2])
    real = Path(sys.argv[3]).read_bytes()
    out = Path(sys.argv[4]); out.mkdir(parents=True, exist_ok=True)
    cases = {'real': real,
             'allspare': set_words(real, lambda i, t, e: e + 1),
             'nospare': set_words(real, lambda i, t, e: e)}
    d = bytearray(cases['allspare'])
    struct.pack_into('<H', d, SAVE + 8 + 0 * 0x1C8 + 0x50, 0xFFFF)      # Vaan: no weapon
    struct.pack_into('<H', d, SAVE + 8 + 1 * 0x1C8 + 0x52, 0xFFFF)      # Ashe: no ammo
    cases['emptyslots'] = bytes(d)
    for seed in range(4):
        rnd = random.Random(seed)
        cases['random%d' % seed] = set_words(real, lambda i, t, e: e + (1 if rnd.random() < 0.35 else 0))
    res = []
    changed = 0
    for name, dd in cases.items():
        b = build(dd)
        b['gear'] = {'status': 'ok', 'members': ','.join(''.join('%04x' % v for v in struct.unpack_from('<5H', dd, SAVE + 8 + m * 0x1C8 + 0x50))
                                                         for m in range(6))}
        b['equipStock'] = {'status': 'ok', 'words': ''.join('%04x' % v for v in struct.unpack_from('<420H', dd, STOCK))}
        exp = [optimize(dd, m, eq, lic, s16) for m in range(6)]
        cur = [list(struct.unpack_from('<5H', dd, SAVE + 8 + m * 0x1C8 + 0x50)) for m in range(6)]
        changed += sum(1 for m in range(6) for k in range(5) if exp[m][k] != cur[m][k])
        res.append({'name': name, 'build': b, 'expected': exp})
    (out / 'equipment.json').write_text(json.dumps(table), encoding='utf-8')
    (out / 'optimize_cases.json').write_text(json.dumps(res), encoding='utf-8')
    print(f'{len(res)} optimize cases ({changed} changed slots across them) -> {out}')


if __name__ == '__main__':
    main()
