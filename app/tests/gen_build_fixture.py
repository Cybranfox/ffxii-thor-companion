"""Builds data.build fixtures (as ThorCompanion.lua 0.5.5 publishes them) from REAL save-region dumps, plus expectations
computed independently here for BuildTest.java: owned gambit IDs and, per member, the magick/technick actions that are
owned by the party AND granted by one of that member's licences (live licence_nodes.json).

Usage: gen_build_fixture.py <licence_nodes.json> <out_dir> <name=save.bin> [<name=save.bin> ...]
"""
import json
import struct
import sys
from pathlib import Path

SAVE = 0x4E68C          # save-region offset of the party block (0x21B290C - 0x2164280)
LISTS = (('gambits', 0xBEE4, 256), ('technicks', 0xC0E4, 24), ('magicks', 0xC114, 81))


def build(d):
    members = []
    for m in range(6):
        r = d[SAVE + 8 + m * 0x1C8:SAVE + 8 + (m + 1) * 0x1C8]
        members.append('%02x%02x:%d:%s' % (r[0x1C3], r[0x1C4], struct.unpack_from('<I', r, 0x190)[0], r[0x194:0x194 + 0x2F].hex()))
    owned = {'status': 'ok'}
    for k, off, n in LISTS:
        owned[k] = ''.join('%04x' % v for v in struct.unpack_from('<%dH' % n, d, off) if v != 0xFFFF)
    stock = {'status': 'ok', 'words': ''.join('%04x' % v for v in struct.unpack_from('<64H', d, SAVE + 0x4748))}  # 0.5.8
    gear = {'status': 'ok', 'members': ','.join(''.join('%04x' % v for v in struct.unpack_from('<5H', d, SAVE + 8 + m * 0x1C8 + 0x50))
                                                for m in range(6))}  # 0.5.11
    keys = {'status': 'ok', 'words': ''.join('%04x' % v for v in struct.unpack_from('<4H', d, SAVE + 0x4F80))}  # 0.5.14
    roster = {'status': 'ok', 'members': ','.join('%02x:%s' % (d[SAVE + 8 + m * 0x1C8], d[SAVE + 8 + m * 0x1C8 + 0x194:SAVE + 8 + m * 0x1C8 + 0x194 + 0x2F].hex())
                                                   for m in range(7))}
    return {'contract': 'ffxii.build.v1', 'status': 'ok', 'stable': True, 'members': ','.join(members), 'owned': owned,
            'stock': stock, 'gear': gear, 'keyItems': keys, 'roster': roster}


def expected(d, nodes):
    raw = bytes.fromhex(nodes['records'])
    st = nodes['stride']
    own = {k: [v for v in struct.unpack_from('<%dH' % n, d, off) if v != 0xFFFF] for k, off, n in LISTS}
    w = struct.unpack_from('<64H', d, SAVE + 0x4748)
    out = {'gambits': own['gambits'], 'usable': [],   # item actions the game's gambit check allows: available > 0
           'items': [a for a in range(0x52, 0x92) if not 0x6F <= a <= 0x7B and (w[a - 0x52] & 0x3FF) - (w[a - 0x52] >> 10) > 0]}
    for m in range(6):
        r = d[SAVE + 8 + m * 0x1C8:SAVE + 8 + (m + 1) * 0x1C8]
        bits = r[0x194:0x194 + 0x2F]
        lic = set()
        for n in range(nodes['count']):
            if not bits[n >> 3] >> (n & 7) & 1:
                continue
            t = raw[n * st + 5]
            grants = [g for g in struct.unpack_from('<8H', raw, n * st + 8) if g != 0xFFFF]
            if 0x09 <= t <= 0x0D:
                lic.update(('m', g) for g in grants)
            elif t == 0x0E:
                lic.update(('t', g) for g in grants)
        acts = sorted([v - 0x3000 for v in own['magicks'] if ('m', v - 0x3000) in lic]
                      + [0x9E + v - 0x4000 for v in own['technicks'] if ('t', 0x9E + v - 0x4000) in lic])
        out['usable'].append(acts)
    return out


def main():
    nodes = json.loads(Path(sys.argv[1]).read_text(encoding='utf-8'))
    out = Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for spec in sys.argv[3:]:
        name, path = spec.split('=', 1)
        d = Path(path).read_bytes()
        (out / f'build_{name}.json').write_text(json.dumps(build(d)), encoding='utf-8')
        (out / f'build_{name}_expected.json').write_text(json.dumps(expected(d, nodes)), encoding='utf-8')
        sets = ','.join(d[SAVE + 0x5B10 + m * 0x9C:SAVE + 0x5B10 + (m + 1) * 0x9C].hex() for m in range(6))
        (out / f'gambits_{name}.json').write_text(json.dumps({'contract': 'ffxii.gambits.v1', 'status': 'ok', 'stable': True, 'sets': sets}), encoding='utf-8')
    (out / 'licence_nodes.json').write_text(json.dumps(nodes), encoding='utf-8')
    print(f'build fixtures from {len(sys.argv) - 3} real dumps -> {out}')


if __name__ == '__main__':
    main()
