"""Stub-API tests for data.party (ThorCompanion.lua 0.5.9, lupa) on the REAL save_27 dump: field party slots, HP/MP and
the game's effective-max rules (Party_Vitals_Trace_2026-10-21.md). Memory writes raise; only the stub memory is changed.

Usage: python -I test_party_family.py <battle_pack.bin> <gambit-probe 0.2.0 history.jsonl> <save_27.bin>
"""
import json
import struct
import sys
from pathlib import Path

from test_build_family import World, SAVE, put


def party(w):
    return w.snapshot()['data']['party']


def main():
    pack = Path(sys.argv[1]).read_bytes()
    hist = [json.loads(x) for x in Path(sys.argv[2]).read_text(encoding='utf-8').splitlines() if x.strip()]
    probe = [x for x in hist if x.get('status') == 'ok' and x.get('stable')][-1]
    dump = Path(sys.argv[3]).read_bytes()
    ok = 0

    w = World(pack, probe, dump=dump)
    s = w.snapshot()
    p = s['data']['party']
    assert s['data']['hello']['capabilities']['party'] == 'on'
    assert p['contract'] == 'ffxii.party.v1' and p['status'] == 'ok', p
    got = [(m['slot'], m['name'], m['hp']['cur'], m['hp']['max'], m['mp']['cur'], m['mp']['max']) for m in p['members']]
    assert got == [(0, 'Vaan', 2747, 2747, 242, 242), (1, 'Balthier', 2479, 2479, 211, 211),
                   (2, 'Fran', 1948, 1948, 277, 277), (3, 'Penelo', 1410, 1410, 339, 339)], got
    assert p['observedClockUs'] == s['data']['session']['clockUs']
    if len(sys.argv) > 4:   # hand the real snapshot to the companion's ReceiverStateTest (end-to-end contract check)
        Path(sys.argv[4]).write_text(json.dumps(s), encoding='utf-8')
    ok += 1
    print('PASS field party from the real dump: Vaan, Balthier, Fran, Penelo with the trace\'s HP/MP; stamped with the snapshot clock')

    M = lambda i: SAVE + 8 + i * 0x1C8
    put(w.mem, M(0) + 0x48, struct.pack('<i', 1200))          # Vaan takes damage
    put(w.mem, M(3) + 0x4C, struct.pack('<h', 60))            # Balthier spends MP
    w.tick(600)
    m = {x['name']: x for x in party(w)['members']}
    assert m['Vaan']['hp'] == {'cur': 1200, 'max': 2747} and m['Balthier']['mp'] == {'cur': 60, 'max': 211}
    ok += 1
    print('PASS changes in the record show up on the next poll (Vaan HP 1200/2747, Balthier MP 60/211)')

    put(w.mem, M(0) + 0x64, struct.pack('<I', 0x10000000))    # Bubble-style bit28: max doubles
    put(w.mem, M(2) + 0x3C, struct.pack('<I', 0x00010000))    # Disease-style bit16: max = current
    put(w.mem, M(5) + 0x7C, b'\x80')                          # MP-zero flag
    w.tick(600)
    m = {x['name']: x for x in party(w)['members']}
    assert m['Vaan']['hp']['max'] == 2 * 2747 and m['Fran']['hp'] == {'cur': 1948, 'max': 1948} and m['Penelo']['mp']['max'] == 0
    put(w.mem, M(2) + 0x3C, struct.pack('<I', 0x10010000))    # both: bit16 wins
    w.tick(600)
    assert {x['name']: x for x in party(w)['members']}['Fran']['hp']['max'] == 1948
    ok += 1
    print('PASS effective max like the game: bit28 doubles, bit16 = current (and wins over bit28), +0x7C bit 0x80 zeroes max MP')

    put(w.mem, SAVE + 0x5A5A + 2, b'\xff\xff')                # slot 1 empty
    put(w.mem, SAVE + 0x5A5A + 6, struct.pack('<H', 4))       # Basch into slot 3
    w.tick(600)
    assert [(x['slot'], x['name']) for x in party(w)['members']] == [(0, 'Vaan'), (2, 'Fran'), (3, 'Basch')]
    put(w.mem, SAVE + 0x5A5A + 6, struct.pack('<H', 13))      # a guest ID (Larsa in the dump's roster): no name guessed
    w.tick(600)
    g = party(w)['members'][-1]
    assert g['slot'] == 3 and g['member'] == 13 and 'name' not in g
    ok += 1
    print('PASS slots: 0xFFFF skipped, member IDs mapped to the ESTABLISHED names, unknown IDs get no name')

    w = World(pack, probe, dump=dump, save_ready=False)
    assert party(w) == {'contract': 'ffxii.party.v1', 'status': 'save_not_ready'}
    assert w.lua.eval('syncRegs') == 0
    ok += 1
    print('PASS no save loaded -> save_not_ready, no values; no events registered; memory writes raise and none occurred')
    print(f'{ok}/5 PASS. Party vitals on the real dump; stubs only.')


if __name__ == '__main__':
    main()
