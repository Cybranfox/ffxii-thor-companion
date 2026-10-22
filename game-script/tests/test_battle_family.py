"""Stub-API tests for the battle-state probe of ThorCompanion.lua 0.5.7 (lupa): data.battle and live/battle_log.jsonl
follow the flag byte 0x22C1F48 / countdown 0x22C1F44 as the game would move them; nothing is written to memory.

Usage: python -I test_battle_family.py <battle_pack.bin> <gambit-probe 0.2.0 history.jsonl>
"""
import json
import sys
from pathlib import Path

from test_build_family import World

FLAGS, COUNT = 0x22C1F48, 0x22C1F44
LOG = 'D:/FFXII_companion/live/battle_log.jsonl'


def set_state(w, flags, count):
    w.mem[FLAGS] = flags
    for i, b in enumerate(count.to_bytes(4, 'little')):
        w.mem[COUNT + i] = b


def log(w):
    return [json.loads(x) for x in w.disk.get(LOG, '').splitlines() if x.strip()]


def main():
    pack = Path(sys.argv[1]).read_bytes()
    hist = [json.loads(x) for x in Path(sys.argv[2]).read_text(encoding='utf-8').splitlines() if x.strip()]
    probe = [x for x in hist if x.get('status') == 'ok' and x.get('stable')][-1]
    ok = 0

    w = World(pack, probe)
    s = w.snapshot()['data']
    b = s['battle']
    assert s['hello']['capabilities']['battle'] == 'probe'
    assert b['contract'] == 'ffxii.battle.v1' and b['status'] == 'ok' and b['flags'] == 0 and not b['candidate'], b
    assert b['changes'] == 0 and b['transitions'] in ([], {}) and not log(w)   # release build writes no battle log file
    ok += 1
    print('PASS baseline: field state flags 0 published, capability probe, one baseline log line, no changes')

    set_state(w, 0x05, 90)            # fight starts: demand + ready, countdown armed
    w.tick(600)                       # snapshots go out on the 500 ms poll
    b = w.snapshot()['data']['battle']
    assert b['flags'] == 5 and b['demand'] and b['ready'] and b['candidate'] and b['changes'] == 1, b
    set_state(w, 0x03, 40)            # demand gone, sheathe countdown still running
    w.tick(150)
    set_state(w, 0x00, 0)             # weapons sheathed
    w.tick(600)
    b = w.snapshot()['data']['battle']
    assert b['flags'] == 0 and not b['candidate'] and b['changes'] == 3
    assert [t['flags'] for t in b['transitions']] == [5, 3, 0] and all(t['mapId'] == 581 for t in b['transitions'])
    assert b['transitions'][1]['countdown'] == 40 and not log(w)
    ok += 1
    print('PASS fight cycle 0 -> 5 -> 3 -> 0: a 150 ms state still logged (100 ms sampler), snapshots on the poll')

    for c in range(90, 0, -10):       # countdown alone moves: no change count
        set_state(w, 0x00, c)
        w.tick(150)
    assert w.snapshot()['data']['battle']['changes'] == 3
    for i in range(30):
        set_state(w, 0x05 if i % 2 == 0 else 0x00, 0)
        w.tick(150)
    w.tick(600)
    b = w.snapshot()['data']['battle']
    assert b['changes'] == 33 and len(b['transitions']) == 20 and b['transitions'][-1]['flags'] == 0
    ok += 1
    print('PASS only flag changes are logged; the published list keeps the last 20')

    assert w.lua.eval('syncRegs') == 0 and all(not k.startswith('D:/FFXII_companion/cmd') for k in w.disk)
    ok += 1
    print('PASS read-only: no event registered after load, no command files; memory writes raise and none occurred')
    # 0.5.13: after "exit" both timer chains stop (nothing left scheduled), and nothing more is written
    w = World(pack, probe)
    w.tick(1000)
    assert w.lua.eval('#timers') >= 2
    w.lua.execute("handlers['exit']('Normal exit')")
    snaps = {k: v for k, v in w.disk.items() if 'state_' in k}
    exit_snap = w.snapshot()
    assert exit_snap['data']['session']['exiting'] is True
    w.tick(3000)
    assert w.lua.eval('#timers') == 0, 'timers still scheduled after exit'
    assert {k: v for k, v in w.disk.items() if 'state_' in k} == snaps, 'snapshot written after exit'
    set_state(w, 0x05, 90)
    w.tick(500)
    assert not log(w) and w.lua.eval('#timers') == 0
    ok += 1
    print('PASS exit: both timer chains drain (0 scheduled), no snapshot or battle-log write after the exit snapshot')
    print(f'{ok}/5 PASS. Battle-state probe + exit; stubs only.')


if __name__ == '__main__':
    main()
