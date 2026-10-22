"""Stub-API tests for the party-build family of ThorCompanion.lua 0.5.4 (lupa), on REAL data: the six member records
from the Thor (gambit-probe 0.2.0 final dump) and the deployed modded battle pack's licence nodes (section 12), placed
the way the Thor has them (pack base 0, descriptor data field = record address). Memory writes raise; disk is simulated.

Usage: python -I test_build_family.py <battle_pack.bin> <gambit-probe 0.2.0 history.jsonl> [save_27.bin]
"""
import json
import struct
import sys
from pathlib import Path

from lupa import LuaRuntime

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'builder'))
import gambit_names  # noqa: E402

SRC = Path(__file__).resolve().parents[1].joinpath('core', 'ThorCompanion-core.lua').read_text(encoding='utf-8')
IMAGE_BASE = 0x120000
SAVE = 0x2164280 + 0x4E68C
REGION, REGION_BYTES = 0x2164280, 0x56690
SEC = 0x0D0C2E50
SEC13 = 0x0D1C2E50     # equipment table (section 13), same Thor layout
EQ = 'D:/FFXII_companion/live/equipment.json'
LIC = 'D:/FFXII_companion/live/licence_nodes.json'
TRIGGER = 'D:/FFXII_companion/cmd/DUMP_SAVE'


def put(mem, a, data):
    for i, b in enumerate(data):
        mem[a + i] = b


class World:
    def __init__(self, pack, probe, save_ready=True, lic=True, dump=None):
        self.mem, self.disk, self.writes_to_lic = {}, {}, 0
        m = self.mem
        if dump is not None:                              # the real save region from the Thor (save_27.bin)
            put(m, REGION, dump)
        put(m, IMAGE_BASE, b'MZ')
        put(m, IMAGE_BASE + 0x3C, (0x100).to_bytes(4, 'little'))
        put(m, IMAGE_BASE + 0x100, b'PE\0\0' + bytes(4) + (1573552087).to_bytes(4, 'little'))
        put(m, 0x2EBF190, (SAVE if save_ready else 0).to_bytes(8, 'little'))
        self.records = [bytes.fromhex(x) for x in probe['record'].split(',')]
        for k in range(6):
            put(m, SAVE + 8 + k * 0x1C8, self.records[k])
        s12 = gambit_names.sections(pack)[12]
        self.count, self.stride = struct.unpack_from('<II', s12, 4)[0], struct.unpack_from('<I', s12, 8)[0]
        self.nodes = s12[0x20:0x20 + self.count * self.stride]
        put(m, SEC, s12[:0x20])
        put(m, SEC + 0xC, (SEC + 0x20).to_bytes(4, 'little'))      # Thor layout: base 0, data field = address
        put(m, SEC + 0x20, self.nodes)
        put(m, 0x2EBF038, (SEC if lic else 0).to_bytes(8, 'little'))
        s13 = gambit_names.sections(pack)[13]
        self.eq_count, self.eq_stride = struct.unpack_from('<II', s13, 4)
        self.eq = s13[0x20:0x20 + self.eq_count * self.eq_stride]
        put(m, SEC13, s13[:0x20])
        put(m, SEC13 + 0xC, (SEC13 + 0x20).to_bytes(4, 'little'))
        put(m, SEC13 + 0x20, self.eq)
        put(m, 0x2EBF0F8, SEC13.to_bytes(8, 'little'))
        put(m, 0x1F83530, bytes(8))
        self.lua = self._boot()

    def _boot(self):
        lua = LuaRuntime()
        g = lua.globals()
        mem, disk, world = self.mem, self.disk, self

        def rd(w):
            return lambda a: int.from_bytes(bytes(mem.get(a + i, 0) for i in range(w)), 'little')

        def wr(path, text, append):
            if path == LIC:
                world.writes_to_lic += 1
            disk[path] = (disk.get(path, '') if append else '') + text
        g.py_u8, g.py_u16, g.py_u32, g.py_u64 = rd(1), rd(2), rd(4), rd(8)
        g.py_read = lambda p: disk.get(p)
        g.py_write = wr
        g.py_remove = lambda p: disk.pop(p, None) is not None
        lua.execute('''
          local function view(f) return setmetatable({}, {__index=function(_, a) return f(a) end,
            __newindex=function() error("WRITE ATTEMPT") end}) end
          memory = { u8=view(py_u8), u16=view(py_u16), u32=view(py_u32), u64=view(py_u64) }
          call = { getCurrentMap=function() return 581 end, getCurrentMapIndex=function() return 1 end,
                   getPreviousMap=function() return 580 end, getPreviousMapIndex=function() return 0 end }
          handlers = {}; timers = {}; clock = 1000000; syncRegs = 0
          event = { registerEventAsync=function(n,f) handlers[n]=f end,
                    registerEventSync=function() syncRegs = syncRegs + 1; return true end,
                    executeAfterMs=function(ms,f) timers[#timers+1]={at=clock+ms*1000, f=f} end,
                    getClock=function() return clock end }
          function getLibVersion() return {1,7,1} end
          game = {}; lfs = nil
          io = { open=function(path, mode)
            mode = mode or "r"
            if mode:sub(1,1) == "r" then
              local t = py_read(path)
              if t == nil then return nil end
              return { read=function() return t end, close=function() end, seek=function() return #t end }
            end
            local buf = {}
            local binary = mode:find("b") ~= nil   -- binary data crosses to Python as hex (not valid UTF-8 text)
            return { write=function(self, ...) for _, s in ipairs({...}) do buf[#buf+1]=s end end,
                     close=function()
                       local t = table.concat(buf)
                       if binary then t = "HEX:" .. t:gsub(".", function(c) return string.format("%02x", c:byte()) end) end
                       py_write(path, t, mode:sub(1,1) == "a") end }
          end }
          os = { time=os.time, remove=function(p) return py_remove(p) or nil end }
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
        return lua

    def tick(self, ms=600):
        self.lua.execute(f'drive({ms})')

    def snapshot(self):
        snaps = [json.loads(v) for k, v in self.disk.items() if 'state_' in k]
        return max(snaps, key=lambda s: s['seq'])


def main():
    pack = Path(sys.argv[1]).read_bytes()
    hist = [json.loads(x) for x in Path(sys.argv[2]).read_text(encoding='utf-8').splitlines() if x.strip()]
    probe = [x for x in hist if x.get('status') == 'ok' and x.get('stable')][-1]
    ok = 0

    w = World(pack, probe)
    b = w.snapshot()['data']['build']
    assert b['contract'] == 'ffxii.build.v1' and b['status'] == 'ok' and b['stable'], b
    parts = b['members'].split(',')
    assert len(parts) == 6
    for k, p in enumerate(parts):
        jobs, lp, bits = p.split(':')
        r = w.records[k]
        assert jobs == '%02x%02x' % (r[0x1C3], r[0x1C4]), (k, jobs)
        assert int(lp) == struct.unpack_from('<I', r, 0x190)[0]
        assert bits == r[0x194:0x194 + 0x2F].hex()
    jobs = [p.split(':')[0] for p in parts]
    assert sorted(int(j[i:i + 2], 16) for j in jobs for i in (0, 2)) == list(range(12)), jobs
    assert w.snapshot()['data']['hello']['capabilities']['build'] == 'probe'
    ok += 1
    print('PASS build: jobs/LP/licence bits per member == the real records; the 12 jobs used once each')

    assert w.writes_to_lic == 1 and b['licenceNodes']['status'] == 'ok' and b['licenceNodes']['count'] == 361, b['licenceNodes']
    ln = json.loads(w.disk[LIC])
    assert ln['contract'] == 'ffxii.licnodes.v1' and ln['count'] == w.count and ln['stride'] == w.stride
    assert bytes.fromhex(ln['records']) == w.nodes
    w.tick(5000)
    assert w.writes_to_lic == 1, 'rewritten without a change'
    w.mem[SEC + 0x20 + 5] ^= 0x10                     # Manifesto-style change to node 0 at run time
    w.tick(2100)
    assert w.writes_to_lic == 2 and bytes.fromhex(json.loads(w.disk[LIC])['records'])[5] == w.nodes[5] ^ 0x10
    ok += 1
    print('PASS licence nodes: live table (Thor layout) written once == section 12; rewritten only when it changes')

    # licence coverage on the real data: every magick/technick action in each member's gambits is licensed
    nodes = [(w.nodes[n * 24 + 5], [x for x in struct.unpack_from('<8H', w.nodes, n * 24 + 8) if x != 0xFFFF])
             for n in range(w.count)]
    stored = [bytes.fromhex(x) for x in probe['stored'].split(',')]
    tot = cov = 0
    for k, p in enumerate(parts):
        bits = bytes.fromhex(p.split(':')[2])
        for s in range(3):
            for i in range(12):
                c, a = struct.unpack_from('<HH', stored[k], s * 0x34 + 4 * i)
                if a == 0xFFFF or not (a <= 0x50 or 0x9E <= a <= 0xB5):
                    continue
                tot += 1
                cov += any((0x09 <= t <= 0x0D or t == 0x0E) and a in cs and bits[n >> 3] >> (n & 7) & 1
                           for n, (t, cs) in enumerate(nodes))
    assert tot > 0 and cov == tot, (cov, tot)
    ok += 1
    print(f'PASS licence semantics: {cov}/{tot} magick/technick gambit actions covered by an owned licence (published bits)')

    w = World(pack, probe, lic=False)
    b = w.snapshot()['data']['build']
    assert b['status'] == 'ok' and b['licenceNodes']['status'] == 'desc_null' and LIC not in w.disk
    w = World(pack, probe, save_ready=False)
    b = w.snapshot()['data']['build']
    assert b['status'] == 'save_not_ready' and 'members' not in b
    ok += 1
    print('PASS gates: no licence table -> desc_null (build still ok); no save loaded -> save_not_ready, no data')

    w = World(pack, probe)
    w.disk[TRIGGER] = ''
    w.tick(3000)
    assert 'dump' not in w.snapshot()['data']['build'] and not [k for k in w.disk if '/dump/' in k]
    ok += 1
    print('PASS release build has no save dump: the old trigger file is ignored, nothing is copied out')
    w = World(pack, probe, save_ready=False)
    w.tick()
    assert w.lua.eval('syncRegs') == 0
    ok += 1
    print('PASS no event registered (not armed); memory writes raise and none occurred')
    # 0.5.5 owned lists, from the real dump (records too come from the dump here)
    dump = Path(sys.argv[3]).read_bytes() if len(sys.argv) > 3 else None
    total = 6
    if dump:
        total += 5
        w = World(pack, probe, dump=dump)
        o = w.snapshot()['data']['build']['owned']
        ids = lambda h: [int(h[i:i + 4], 16) for i in range(0, len(h), 4)]
        exp = lambda off, n: [v for v in struct.unpack_from('<%dH' % n, dump, off) if v != 0xFFFF]
        assert o['status'] == 'ok', o
        assert ids(o['gambits']) == exp(0xBEE4, 256) and len(ids(o['gambits'])) == 255
        assert ids(o['magicks']) == exp(0xC114, 81) and len(ids(o['magicks'])) == 28
        assert ids(o['technicks']) == exp(0xC0E4, 24) and len(ids(o['technicks'])) == 8
        assert w.snapshot()['data']['hello']['capabilities']['build.owned'] == 'probe'
        ok += 1
        print('PASS owned lists from the real dump: 255 gambits, 28 magicks, 8 technicks, list order kept, capability probe')
        st = w.snapshot()['data']['build']['stock']
        words = [int(st['words'][i:i + 4], 16) for i in range(0, len(st['words']), 4)]
        assert st['status'] == 'ok' and words == list(struct.unpack_from('<64H', dump, 0x52DD4))
        assert words == list(struct.unpack_from('<64H', dump, 0x6948))      # the save copy agrees in this dump
        assert [words[i] & 0x3FF for i in (0, 1, 6, 0xD)] == [14, 2, 3, 0]  # Potion, Hi-Potion, Phoenix Down, Chronos Tear
        assert w.snapshot()['data']['hello']['capabilities']['inventory'] == 'probe'
        ok += 1
        print('PASS stock: 64 words from the party copy (P+0x4748) == the real dump; Potion 14, Phoenix Down 3, Chronos Tear 0')
        gear = w.snapshot()['data']['build']['gear']
        rows = [[int(m[i:i + 4], 16) for i in range(0, 20, 4)] for m in gear['members'].split(',')]
        assert gear['status'] == 'ok' and len(rows) == 6
        for k in range(6):
            assert rows[k] == list(struct.unpack_from('<5H', dump, 0x4E68C + 8 + k * 0x1C8 + 0x50)), k
        assert rows[0] == [0x1076, 0x10CE, 0x10E9, 0x1148, 0x117E]          # Vaan: Platinum Dagger, Golden Shield, ...
        assert rows[1][1] == 0x1184 and rows[2][1] == 0xFFFF               # Ashe's Onion Arrows share the shield slot; Fran none
        ok += 1
        print('PASS gear: five slots per member == the real dump (Vaan Platinum Dagger/Golden Shield..., Ashe Onion Arrows, Fran empty slot 2)')
        es = w.snapshot()['data']['build']['equipStock']
        ew = [int(es['words'][i:i + 4], 16) for i in range(0, len(es['words']), 4)]
        assert es['status'] == 'ok' and ew == list(struct.unpack_from('<420H', dump, 0x4E68C + 0x47C8))
        g = lambda i: (ew[i - 0x1000] & 0x3FF, ew[i - 0x1000] >> 10)
        assert g(0x10CE) == (2, 1) and g(0x10FE) == (8, 4) and g(0x1133) == (3, 3)      # trace table: total, equipped
        eqf = json.loads(w.disk[EQ])
        assert eqf['contract'] == 'ffxii.equipment.v1' and eqf['count'] == 557 and eqf['stride'] == 0x34
        assert bytes.fromhex(eqf['records']) == w.eq and w.snapshot()['data']['build']['equipment']['status'] == 'ok'
        ok += 1
        ki = w.snapshot()['data']['build']['keyItems']
        kw = [int(ki['words'][i:i + 4], 16) for i in range(0, 16, 4)]
        assert ki['status'] == 'ok' and kw == list(struct.unpack_from('<4H', dump, 0x4E68C + 0x4F80))
        assert [i for i in range(32) if kw[i >> 4] >> (i & 15) & 1] == [0, 3, 4, 6, 7, 9, 12, 13, 15, 16, 17, 18, 20, 31]  # trace's list
        ro = w.snapshot()['data']['build']['roster']
        parts = ro['members'].split(',')
        assert ro['status'] == 'ok' and len(parts) == 7
        for k, p in enumerate(parts):
            M = 0x4E68C + 8 + k * 0x1C8
            assert p == '%02x:%s' % (dump[M], dump[M + 0x194:M + 0x194 + 0x2F].hex()), k
        print('PASS key items == the trace list (C000+0,3,4,...,31); records 0..6 flag byte + licence bits == the dump')
        print('PASS equipment: stock words == the dump (Golden Shield 2/1, Gaia Hat 8/4, Chanter\'s Djellaba 3/3); live table 557 x 0x34 written')
        bad = bytearray(dump)
        struct.pack_into('<H', bad, 0xC114 + 2 * 5, 0x3000)   # duplicate magick id
        w = World(pack, probe, dump=bytes(bad))
        b = w.snapshot()['data']['build']
        assert b['owned'] == {'status': 'implausible_magicks'} and b['status'] == 'ok', b['owned']
        struct.pack_into('<H', bad, 0xC114 + 2 * 5, 0x4001)   # technick id in the magick list
        w = World(pack, probe, dump=bytes(bad))
        assert w.snapshot()['data']['build']['owned'] == {'status': 'implausible_magicks'}
        ok += 1
        print('PASS implausible lists (duplicate / wrong range) published as a status with no IDs; build data unaffected')
    print(f'{ok}/{total} PASS. Real Thor records + deployed battle pack' + (' + real save dump' if dump else '') + '; stubs only.')


if __name__ == '__main__':
    main()
