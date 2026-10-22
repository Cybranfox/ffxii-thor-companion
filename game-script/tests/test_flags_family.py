"""Stub-API tests for data.flags (ThorCompanion-core.lua 0.13.0, lupa): the game's story flags, scenario variable 0 and
the current exit, read for the map (Map_Icons_Flags_2026-10-22.md). Synthetic memory only, so it runs anywhere.

Usage: python test_flags_family.py
"""
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from test_reveal_family import make_memory, run  # noqa: E402

GB = 0x2164280 + 0x4E68C
FLAGS = 0x2164280 + 0x16F4
SCEN, EXITP = 0x46000000, 0x47000000


def put(mem, a, data):
    for i, b in enumerate(data):
        mem[a + i] = b


def world(gate=1, scen_ptr=True, exit_ptr=True, marker=True):
    mem = make_memory()
    flags = bytearray(64)
    flags[0x1B // 8] |= 1 << (0x1B & 7)            # flag 0x1B (an exit's gate) set
    flags[63] = 0x80                               # flag 511
    put(mem, FLAGS, flags)
    mem[0x2098E20] = gate
    put(mem, 0x2098E58, (SCEN if scen_ptr else 0).to_bytes(8, 'little'))
    put(mem, SCEN, struct.pack('<H', 4100))
    put(mem, 0x2098E10, (EXITP if exit_ptr else 0).to_bytes(8, 'little'))
    put(mem, EXITP + 0x7E, struct.pack('<h', 3))
    if not marker:
        put(mem, GB, bytes(4))
    s, _ = run(mem)
    return s['data']


def main():
    ok = 0
    d = world()
    assert d['hello']['capabilities']['flags'] == 'probe'
    f = d['flags']
    want = bytearray(64)
    want[3] = 0x08
    want[63] = 0x80
    assert f == {'contract': 'ffxii.flags.v1', 'status': 'ok', 'generic': want.hex(), 'scenario': 4100, 'currentExit': 3}, f
    ok += 1
    print('PASS 64 flag bytes as hex, scenario variable 0 and the current exit')

    for kw in (dict(gate=0), dict(scen_ptr=False, exit_ptr=False)):
        f = world(**kw)['flags']
        assert f['status'] == 'ok' and f['scenario'] == 0 and f['currentExit'] == -1, (kw, f)
    ok += 1
    print('PASS script manager inactive or pointers null -> scenario 0 and current exit -1, as the game reads them')

    assert world(marker=False)['flags'] == {'contract': 'ffxii.flags.v1', 'status': 'save_not_ready'}
    ok += 1
    print('PASS no save marker -> save_not_ready, nothing read')
    print(f'{ok}/3 PASS. Story flags on synthetic memory; stubs only, no writes.')


if __name__ == '__main__':
    main()
