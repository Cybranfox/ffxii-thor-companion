"""Stub-API tests for the reveal family of ThorCompanion.lua 0.4.x (lupa). Simulates LL 1.7.1's memory/event/call
APIs and captures every io.open write in memory, so nothing touches the PC's disks. Checks script logic and
read-only behaviour only, NOT the loader ABI or any runtime result.

Usage: python -I test_reveal_family.py
"""
import json
import struct
from pathlib import Path

from lupa import LuaRuntime

SRC = Path(__file__).resolve().parents[1].joinpath('core', 'ThorCompanion-core.lua').read_text(encoding='utf-8')
IMAGE_BASE = 0x120000
NAV = 0x30000000
ROOT = 0x2164280 + 0x8200
REC_585 = bytes.fromhex('7c00fc00fc01ff03ff03ff03ff01ff00ff00ff00ff007f003c00000000000000')


def put_f32(mem, addr, *vals):
    for k, v in enumerate(vals):
        for i, b in enumerate(struct.pack('<f', v)):
            mem[addr + 4 * k + i] = b


def make_memory(stamp=1573552087, nav=True, root=ROOT, record=REC_585, pos=(150.25, -3.5, 40.75, 1.0), navimap=True):
    mem = {IMAGE_BASE: 0x4D, IMAGE_BASE + 1: 0x5A}
    put_f32(mem, 0x2B45810, *pos)
    put_f32(mem, 0x2B45800, 134.0, 0.125, 148.0, 202.0)     # origin x0, z0 and extent of map 585's NPS2
    mem[0x2B45A00] = 0x03 if navimap else 0x02
    gb = 0x2164280 + 0x4E68C
    for i, b in enumerate(gb.to_bytes(8, 'little')):
        mem[0x2EBF190 + i] = b
    for i, b in enumerate((0x05071901).to_bytes(4, 'little')):   # the game's loaded-save marker at P (0x233C4D)
        mem[gb + i] = b
    for i, b in enumerate(struct.pack('<HH', 0x60B5, 0x0052) + b'\xff' * 44 + struct.pack('<HB', 0x0001, 12)):
        mem[gb + 0x5B10 + 3 * 0x9C + i] = b             # Balthier set 1 slot 1: Ally HP<50% -> Potion, on
    lfanew = 0x100
    for i, b in enumerate(lfanew.to_bytes(4, 'little')):
        mem[IMAGE_BASE + 0x3C + i] = b
    for i, b in enumerate(b'PE\0\0' + bytes(4) + stamp.to_bytes(4, 'little')):   # TimeDateStamp at PE+8
        mem[IMAGE_BASE + lfanew + i] = b
    if nav:
        for i, b in enumerate(NAV.to_bytes(8, 'little')):
            mem[0x2ADD100 + i] = b
        mem.update({NAV: 0x4E, NAV + 1: 0x41, NAV + 2: 0x56, NAV + 3: 2})
        for i, b in enumerate((1154).to_bytes(4, 'little')):
            mem[NAV + 4 + i] = b
        for mid, rec in ((585, b'\x2e\x00\x08'), (581, b'\x2c\x00\x08'), (100, b'\xff\x0f\x00')):
            for i, b in enumerate(rec):
                mem[NAV + 8 + 3 * mid + i] = b
    for i, b in enumerate(root.to_bytes(8, 'little')):
        mem[0x20927C0 + i] = b
    for i, b in enumerate(record):
        mem[ROOT + 0x224 + 0x2E * 32 + i] = b
    return mem


def run(mem, map_id=585, mutate=False, steps=None):
    lua = LuaRuntime()
    g = lua.globals()
    files, reads = {}, {'n': 0}
    target = ROOT + 0x224 + 0x2E * 32

    def rd(width):
        def get(addr):
            if mutate and addr == target:
                reads['n'] += 1
                if reads['n'] % 2 == 0:
                    return 0xAA
            return int.from_bytes(bytes(mem.get(addr + i, 0) for i in range(width)), 'little')
        return get

    g.py_u8, g.py_u16, g.py_u32, g.py_u64 = rd(1), rd(2), rd(4), rd(8)
    g.py_set = lambda path, text: files.__setitem__(path, text)
    g.current_map = map_id
    lua.execute('''
      local function view(f) return setmetatable({}, {__index=function(_, a) return f(a) end,
        __newindex=function() error("WRITE ATTEMPT") end}) end
      memory = { u8=view(py_u8), u16=view(py_u16), u32=view(py_u32), u64=view(py_u64) }
      call = { getCurrentMap=function() return current_map end, getCurrentMapIndex=function() return 1 end,
               getPreviousMap=function() return 581 end, getPreviousMapIndex=function() return 0 end }
      handlers = {}; timers = {}; clock = 0
      event = { registerEventAsync=function(n,f) handlers[n]=f end,
                executeAfterMs=function(ms,f) timers[#timers+1]={at=clock+ms*1000, f=f} end,
                getClock=function() return clock end }
      function getLibVersion() return {1,7,1} end
      game = {}
      lfs = nil
      local realOpen = io.open
      io = { open=function(path, mode)
        if mode == nil or mode:sub(1,1) == "r" then return nil end   -- no PC file is ever read
        local buf = {}
        return { write=function(self, ...) for _, s in ipairs({...}) do buf[#buf+1]=s end end,
                 close=function() py_set(path, table.concat(buf)) end, seek=function() return 0 end }
      end }
      loadfile = function() return nil end
      function drive(ms) local target = clock + ms * 1000
        while true do
          table.sort(timers, function(a, b) return a.at < b.at end)
          local t = timers[1]
          if not t or t.at > target then break end
          table.remove(timers, 1); clock = t.at; t.f()
        end
        clock = target
      end
    ''')
    lua.execute(SRC)
    lua.execute("handlers['onInitDone'](); drive(1200)")
    for step in steps or []:
        step(lua, g, mem)
    snaps = [json.loads(v) for k, v in files.items() if 'state_' in k]
    return max(snaps, key=lambda s: s['seq']), files


def main():
    ok = 0
    s, files = run(make_memory())
    assert all(k.startswith('D:/FFXII_companion/live/') for k in files), files.keys()
    r = dict(s['data']['reveal'])
    assert r.pop('liveStatus') == 'map_mismatch'  # fixture has no bound live map
    assert 'live' not in r
    assert r == {'contract': 'ffxii.reveal.v1', 'mapId': 585, 'slot': 0x2E, 'status': 'ok', 'stable': True,
                 'record': REC_585.hex(), 'observedClockUs': r['observedClockUs']}, r
    assert s['data']['hello']['capabilities']['map.reveal'] == 'on'
    assert s['data']['hello']['producer']['version'] == '0.16.0-beta'
    t = s['data']['revealTable']
    assert t['status'] == 'ok' and t['stable'] and t['records'] == '46:' + REC_585.hex(), t
    ok += 1
    print('PASS ok path: contract, slot from NAV, record hex, stable, capability on, table has the one non-zero slot')

    mem = make_memory()
    for i, v in enumerate(bytes([9, 0, 0, 0x80])):
        mem[ROOT + 0x224 + 0x180 * 32 + 28 + i] = v
    t = run(mem)[0]['data']['revealTable']['records'].split(',')
    assert t == ['46:' + REC_585.hex(), '384:' + '00' * 28 + '09000080'], t
    ok += 1
    print('PASS table covers slot 0x180, little-endian u32 words -> bytes, sorted by slot')

    for kw, status in ((dict(stamp=1), 'unsupported_build'), (dict(nav=False), 'nav_not_loaded'),
                       (dict(root=ROOT + 8), 'root_unexpected')):
        d = run(make_memory(**kw))[0]['data']
        r, t = d['reveal'], d['revealTable']
        assert r['status'] == status and 'record' not in r, (status, r)
        if status != 'nav_not_loaded':
            assert t['status'] == status and 'records' not in t, (status, t)
        ok += 1
        print(f'PASS {status}: no record bytes')

    r = run(make_memory(), map_id=100)[0]['data']['reveal']
    assert r['status'] == 'no_slot' and 'record' not in r and 'slot' not in r, r
    r = run(make_memory(), map_id=5000)[0]['data']['reveal']
    assert r['status'] == 'no_slot', r
    ok += 1
    print('PASS map without slot (0xFFF) or beyond NAV count: no_slot')

    r = run(make_memory(), mutate=True)[0]['data']['reveal']
    assert r['status'] == 'unstable' and 'record' not in r, r
    ok += 1
    print('PASS record changing between the two reads: unstable, no bytes')

    def change_record(lua, g, mem):
        mem[ROOT + 0x224 + 0x2E * 32 + 28] = 0xFF   # byte 28 is 0 in the live record
        lua.execute('drive(600)')   # one poll, well inside the 2 s heartbeat
    s1 = run(make_memory())[0]
    s2 = run(make_memory(), steps=[change_record])[0]
    assert s2['seq'] == s1['seq'] + 1 and s2['data']['reveal']['record'][56:58] == 'ff', (s1['seq'], s2['seq'])
    ok += 1
    print('PASS a record change publishes on the next poll')

    def idle(lua, g, mem):
        lua.execute('drive(600)')   # stays inside the 2 s heartbeat
    s3 = run(make_memory(), steps=[idle])[0]
    assert s3['seq'] == s1['seq'], 'observedClockUs alone must not force a republish'
    ok += 1
    print('PASS unchanged record: no republish before the heartbeat')
    def jump_after_change(lua, g, mem):
        mem[ROOT + 0x224 + 3 * 32] = 0x01        # another slot changes; no table refresh until a jump or 10 s
        lua.execute('drive(600)')
        g.current_map = 585
        lua.execute("handlers['onMapJump'](585, 1, 0, 0)")
    s4 = run(make_memory(), steps=[jump_after_change])[0]
    assert s4['data']['revealTable']['records'].startswith('3:01'), s4['data']['revealTable']
    def no_jump(lua, g, mem):
        mem[ROOT + 0x224 + 3 * 32] = 0x01
        lua.execute('drive(600)')
    s5 = run(make_memory(), steps=[no_jump])[0]
    assert s5['data']['revealTable']['records'] == '46:' + REC_585.hex(), 'table re-read before 10 s'
    ok += 1
    print('PASS table refreshes on a map jump, stays cached otherwise (10 s)')
    pz = run(make_memory())[0]['data']['position']
    assert pz['status'] == 'ok' and pz['contract'] == 'ffxii.mappos.v1' and pz['navimapActive'], pz
    assert (pz['x'], pz['y'], pz['z']) == (150.25, -3.5, 40.75) and (pz['originX'], pz['originZ']) == (134.0, 0.125), pz
    assert (pz['extentX'], pz['extentZ']) == (148.0, 202.0), pz
    assert run(make_memory())[0]['data']['hello']['capabilities']['map.position'] == 'probe'
    ok += 1
    print('PASS position: static map copy x/y/z, origin and extent, capability probe')

    assert run(make_memory(navimap=False))[0]['data']['position']['status'] == 'navimap_inactive'
    assert run(make_memory(pos=(1.0, 2.0, 3.0, 0.0)))[0]['data']['position']['status'] == 'bad_w'
    d = run(make_memory(stamp=1))[0]['data']['position']
    assert d['status'] == 'unsupported_build' and 'x' not in d, d
    ok += 1
    print('PASS position gates: navimap inactive, w != 1, unsupported build (no coordinates)')

    def move(lua, g, mem):
        put_f32(mem, 0x2B45810, 151.0)
        lua.execute('drive(600)')
    sm = run(make_memory(), steps=[move])[0]
    assert sm['seq'] == s1['seq'] + 1 and sm['data']['position']['x'] == 151.0, sm['data']['position']
    ok += 1
    print('PASS moving publishes on the next poll')
    gm = run(make_memory())[0]['data']
    g = gm['gambits']
    sets = g['sets'].split(',')
    assert g['status'] == 'ok' and g['stable'] and len(sets) == 6 and all(len(x) == 0x9C * 2 for x in sets), g.get('status')
    assert sets[3][:8] == 'b5605200' and sets[3][0x60:0x66] == '01000c', sets[3][:8]
    assert gm['hello']['capabilities']['gambits'] == 'probe'
    ok += 1
    print('PASS gambits: 6 members x 3 stored sets, byte order, capability probe')

    def toggle(lua, g, mem):
        mem[0x2164280 + 0x4E68C + 0x5B10 + 3 * 0x9C + 0x30] = 0x00
        lua.execute('drive(2100)')
    st = run(make_memory(), steps=[toggle])[0]['data']['gambits']['sets'].split(',')[3]
    assert st[0x60:0x64] == '0000', st[0x60:0x64]
    ok += 1
    print('PASS a gambit toggle is published within one 2 s read')
    print(f'{ok}/15 PASS. Stub APIs only; memory writes raise in the stub and none occurred; no PC file was opened.')


if __name__ == '__main__':
    main()
