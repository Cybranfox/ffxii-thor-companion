"""Stub-API tests for data.target (ThorCompanion-core.lua 0.13.0, lupa): each field member's pending-action target,
resolved through the game's generational handles (Party_Target_Export_2026-10-22.md part B). Synthetic memory only, so
it runs anywhere; memory writes raise in the stub.

Usage: python test_target_family.py
"""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from test_reveal_family import make_memory, run

GB = 0x2164280 + 0x4E68C
M = lambda i: GB + 8 + i * 0x1C8
POOL, LIST, WRAP, ENEMY, STATS, SEC = 0x40000000, 0x41000000, 0x42000000, 0x43000000, 0x44000000, 0x45000000
MGR1 = 0x2098E10 + 1 * 0x288


def put(mem, a, data):
    for i, b in enumerate(data):
        mem[a + i] = b


def handle(cls, index, gen):
    return (gen << 20) | (cls << 16) | index


def world(**over):
    o = dict(gate=0x10, gen=5, wrap_gen=5, cls=1, bestiary=77, count=3, bubble=False, save=True, marker=True)
    o.update(over)
    mem = make_memory()
    if not o['save']:
        put(mem, 0x2EBF190, bytes(8))
    if not o['marker']:
        put(mem, GB, bytes(4))                     # P set at startup, no save loaded yet (title screen)
    put(mem, GB + 0x5A5A, struct.pack('<4H', 0, 3, 0xFFFF, 0xFFFF))     # field: Vaan, Balthier
    mem[GB + 0x5AA4] = 0                                                # leader: Vaan
    put(mem, 0x208E688, POOL.to_bytes(8, 'little'))
    put(mem, 0x208E6A0, struct.pack('<i', o['count']))
    put(mem, POOL + 0 * 0xF50 + 0x698, M(0).to_bytes(8, 'little'))     # actor 0: Vaan
    put(mem, POOL + 1 * 0xF50 + 0x698, (0x12345678).to_bytes(8, 'little'))
    put(mem, POOL + 2 * 0xF50 + 0x698, M(3).to_bytes(8, 'little'))     # actor 2: Balthier
    put(mem, POOL + 0x710, struct.pack('<IH', handle(o['cls'], 2, o['gen']), 0x96))          # Vaan: Attack -> enemy
    put(mem, POOL + 2 * 0xF50 + 0x710, struct.pack('<IH', handle(1, 3, 1), 0x3001))          # Balthier: Cure -> Vaan
    mem[0x2089340] = o['gate']
    put(mem, MGR1, (1).to_bytes(8, 'little') + LIST.to_bytes(8, 'little'))
    mem[MGR1 + 0x10] = 1
    put(mem, MGR1 + 0x20, struct.pack('<i', 10))
    put(mem, LIST, struct.pack('<i', 10))
    put(mem, LIST + 8 + 8 * 2, WRAP.to_bytes(8, 'little'))
    put(mem, WRAP + 0x16, struct.pack('<H', o['wrap_gen']))
    put(mem, WRAP + 0x30, ENEMY.to_bytes(8, 'little'))
    put(mem, LIST + 8 + 8 * 3, (WRAP + 0x100).to_bytes(8, 'little'))  # wrapper for Vaan's own actor
    put(mem, WRAP + 0x100 + 0x16, struct.pack('<H', 1))
    put(mem, WRAP + 0x100 + 0x30, POOL.to_bytes(8, 'little'))
    put(mem, ENEMY + 0x698, STATS.to_bytes(8, 'little'))
    put(mem, ENEMY + 0xE68, SEC.to_bytes(8, 'little'))
    put(mem, SEC + 0x52, struct.pack('<H', o['bestiary']))
    mem[STATS + 5] = 1
    put(mem, STATS + 0x24, struct.pack('<i', 1500))
    put(mem, STATS + 0x48, struct.pack('<i', 900))
    put(mem, STATS + 0x64, struct.pack('<I', 0x10000000 if o['bubble'] else 0))
    mem[STATS + 0x1C2] = 12
    put(mem, STATS + 0x40, bytes([0b00000101, 0b00001000, 0, 0b01000000, 0]))    # weak fire+ice, absorb earth, immune holy
    put(mem, M(0) + 0x24, struct.pack('<i', 2747))
    put(mem, M(0) + 0x48, struct.pack('<i', 2700))
    s, _ = run(mem)
    return s['data']


def by_member(t):
    return {e['member']: e for e in t['members']}


def main():
    ok = 0
    d = world()
    assert d['hello']['capabilities']['target'] == 'probe'
    t = d['target']
    assert t['contract'] == 'ffxii.target.v1' and t['status'] == 'ok', t
    v, b = by_member(t)[0], by_member(t)[3]
    assert v['leader'] is True and v['action'] == 0x96 and v['handle'] == handle(1, 2, 5)
    assert v['target'] == {'kind': 'enemy', 'hp': {'cur': 900, 'max': 1500}, 'level': 12, 'status': 0,
                           'weak': 5, 'absorb': 8, 'half': 0, 'immune': 64, 'potency': 0, 'bestiary': 77}, v['target']
    assert 'leader' not in b and b['action'] == 0x3001 and b['target']['kind'] == 'member' and b['target']['member'] == 0
    assert b['target']['hp'] == {'cur': 2700, 'max': 2747}
    ok += 1
    print('PASS enemy target: HP, level, live affinities, bestiary entry; an ally target names the member; leader marked')

    for over, why in ((dict(wrap_gen=6), 'generation mismatch'), (dict(gate=0), 'scene gate clear'),
                      (dict(cls=5), 'class >= 5')):
        v = by_member(world(**over)['target'])[0]
        assert 'target' not in v and v['handle'] != 0, (why, v)
    ok += 1
    print('PASS no target on a stale generation, a clear scene gate or a bad class (the handle itself is kept)')

    v = by_member(world(bestiary=0xFFFF)['target'])[0]
    assert v['target']['kind'] == 'enemy' and 'bestiary' not in v['target']
    v = by_member(world(bubble=True)['target'])[0]
    assert v['target']['hp'] == {'cur': 900, 'max': 3000}
    ok += 1
    print('PASS 0xFFFF means no bestiary entry; Bubble doubles max like the party rule')

    t = world(count=5000)['target']
    assert t['status'] == 'ok' and all('handle' not in e for e in t['members'])
    t = world(count=-1)['target']
    assert all('handle' not in e for e in t['members'])
    assert world(save=False)['target'] == {'contract': 'ffxii.target.v1', 'status': 'save_not_ready'}
    d = world(marker=False)
    assert d['target'] == {'contract': 'ffxii.target.v1', 'status': 'save_not_ready'}
    assert d['party'] == {'contract': 'ffxii.party.v1', 'status': 'save_not_ready'}
    ok += 1
    print('PASS implausible pool counts read nothing; no save loaded, or P set but no save marker yet -> save_not_ready')
    print(f'{ok}/4 PASS. Pending-action targets on synthetic memory; stubs only, no writes.')


if __name__ == '__main__':
    main()
