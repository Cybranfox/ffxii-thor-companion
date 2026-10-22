"""Stub-API tests for the gated gambit editor of ThorCompanion.lua 0.5.0 (lupa), on REAL data: the last stable gambit-probe
0.2.0 dump from the Thor (stored sets, set indices, runtime AI lists of members 0-5) and the deployed modded battle_pack.bin
(condition records, section 7). Every expected runtime list comes from gambit_runtime.py, the offline replica of the
game's rebuild 0x327DE0 that matched the live game 36/36. Disk, clock, onFlip and memory are simulated; every memory write
is recorded in order. Checks script logic only, NOT the loader ABI or any runtime result.

Usage: python -I test_gambit_editor.py <battle_pack.bin> <gambit-probe 0.2.0 history.jsonl>
"""
import json
import struct
import sys
import time
from pathlib import Path

from lupa import LuaRuntime

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'builder'))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import gambit_names  # noqa: E402
import gambit_runtime  # noqa: E402

SRC = Path(__file__).resolve().parents[1].joinpath('core', 'ThorCompanion-core.lua').read_text(encoding='utf-8')
IMAGE_BASE = 0x120000
SAVE = 0x2164280 + 0x4E68C
SETS = SAVE + 0x5B10
RT = 0x2089378
SEC = 0x40000000
NODES = 0x0E000000
REGION = 0x2164280
CMD = 'D:/FFXII_companion/cmd/gambit_cmd.txt'
ARM = 'D:/FFXII_companion/cmd/ARMED'
LOG = 'D:/FFXII_companion/cmd/gambit_log.jsonl'
MANIFESTO_CFG = 'scripts/config/TheInsurgentsManifestoConfig.lua'


def put(mem, addr, data):
    for i, b in enumerate(data):
        mem[addr + i] = b


class World:
    def __init__(self, pack, probe, field=True, thirty_six=False, armed=True, drop=None, table=True, base0=False, dump=None, manifesto=True):
        self.mem, self.writes, self.disk, self.drop = {}, [], {}, drop or set()
        m = self.mem
        if dump is not None:                              # the real save region (owned lists, records, stored sets)
            put(m, REGION, dump)
        put(m, IMAGE_BASE, b'MZ')
        put(m, IMAGE_BASE + 0x3C, (0x100).to_bytes(4, 'little'))
        put(m, IMAGE_BASE + 0x100, b'PE\0\0' + bytes(4) + (1573552087).to_bytes(4, 'little'))
        put(m, 0x2EBF190, SAVE.to_bytes(8, 'little'))
        m[0x2B45A00] = 0x03 if field else 0x02
        if dump is None:
            self.stored = [bytes.fromhex(x) for x in probe['stored'].split(',')]
            self.runtime = [bytes.fromhex(x) for x in probe['runtime'].split(',')]
            idx = [int(x) for x in probe['setidx'].split(',')]
        else:
            self.stored = [dump[SETS - REGION + k * 0x9C:SETS - REGION + (k + 1) * 0x9C] for k in range(6)]
            idx = [struct.unpack_from('<I', dump, SAVE - REGION + 0x7370 + 4 * k)[0] for k in range(6)]
            rec = gambit_runtime.condition_table(pack)
            self.runtime = []
            for k in range(6):                            # the game's own rebuild of each member's active set
                rt = bytearray(0x170)
                for j, e in enumerate(gambit_runtime.build(self.stored[k][idx[k] * 0x34:(idx[k] + 1) * 0x34], rec)):
                    for off, v in e.items():
                        struct.pack_into('<H', rt, j * 0x18 + off, v)
                rt[0] = len(gambit_runtime.build(self.stored[k][idx[k] * 0x34:(idx[k] + 1) * 0x34], rec))
                self.runtime.append(bytes(rt))
            s12 = gambit_names.sections(pack)[12]         # live licence table, Thor-style descriptor
            put(m, NODES, s12)
            put(m, 0x2EBF038, NODES.to_bytes(8, 'little'))
            put(m, NODES + 0xC, ((NODES + 0x20 - (0 if base0 else SEC)) & 0xFFFFFFFF).to_bytes(4, 'little'))
        for k in range(6):
            put(m, SETS + k * 0x9C, self.stored[k])
            put(m, RT + k * 0x170, self.runtime[k])
            put(m, SAVE + 0x7370 + 4 * k, idx[k].to_bytes(4, 'little'))
        s7 = gambit_names.sections(pack)[7]
        put(m, SEC, s7)                                   # descriptor = section header (count +4, stride +8, data +0xC)
        put(m, 0x2EBF0D8, (SEC if table else 0).to_bytes(8, 'little'))
        put(m, 0x1F83530, (0 if base0 else SEC).to_bytes(8, 'little'))
        if base0:                                         # as on the Thor: base 0, descriptor +0xC = record address
            put(m, SEC + 0xC, (SEC + struct.unpack_from('<I', s7, 0xC)[0]).to_bytes(4, 'little'))
        self.rec = gambit_runtime.condition_table(pack)
        if armed:
            self.disk[ARM] = 'armed for the test'
        if manifesto:                                     # the Insurgent's Manifesto is installed (its config is read)
            self.disk['scripts/TheInsurgentsManifesto.lua'] = '--manifesto'
        self.thirty_six = thirty_six
        self.lua = self._boot()

    def _boot(self):
        lua = LuaRuntime()
        g = lua.globals()
        mem, writes, disk, drop = self.mem, self.writes, self.disk, self.drop

        def rd(w):
            return lambda a: int.from_bytes(bytes(mem.get(a + i, 0) for i in range(w)), 'little')

        def wr(w):
            def f(a, v):
                writes.append((a, w, v))
                if a in drop:
                    return                                  # simulates the game overwriting / a lost write
                for i in range(w):
                    mem[a + i] = (v >> (8 * i)) & 0xFF
            return f

        g.py_u8, g.py_u16, g.py_u32, g.py_u64 = rd(1), rd(2), rd(4), rd(8)
        g.py_w8, g.py_w16, g.py_w32, g.py_w64 = wr(1), wr(2), wr(4), wr(8)
        g.py_read = lambda p: disk.get(p)
        g.py_write = lambda p, t, append: disk.__setitem__(p, (disk.get(p, '') if append else '') + t)
        g.py_remove = lambda p: disk.pop(p, None) is not None
        g.thirty_six = self.thirty_six
        lua.execute('''
          local function view(r, w) return setmetatable({}, {__index=function(_, a) return r(a) end,
            __newindex=function(_, a, v) w(a, v) end}) end
          memory = { u8=view(py_u8, py_w8), u16=view(py_u16, py_w16), u32=view(py_u32, py_w32), u64=view(py_u64, py_w64) }
          call = { getCurrentMap=function() return 581 end, getCurrentMapIndex=function() return 1 end,
                   getPreviousMap=function() return 580 end, getPreviousMapIndex=function() return 0 end }
          handlers = {}; timers = {}; syncs = {}; clock = 1000000
          loading = true; syncRegs = {}     -- every registerEventSync call: {name, oneShot, during script load?}
          event = { registerEventAsync=function(n,f) handlers[n]=f end,
                    registerEventSync=function(n,f,once)
                      syncRegs[#syncRegs+1]={n=n, once=once and true or false, load=loading}
                      syncs[#syncs+1]={n=n,f=f,once=once}; return true end,
                    executeAfterMs=function(ms,f) timers[#timers+1]={at=clock+ms*1000, f=f} end,
                    getClock=function() return clock end }
          function getLibVersion() return {1,7,1} end
          game = {}
          lfs = nil
          io = { open=function(path, mode)
            mode = mode or "r"
            if mode:sub(1,1) == "r" then
              local t = py_read(path)
              if t == nil then return nil end
              return { read=function() return t end, close=function() end, seek=function() return #t end }
            end
            local buf = {}
            return { write=function(self, ...) for _, s in ipairs({...}) do buf[#buf+1]=s end end,
                     close=function() py_write(path, table.concat(buf), mode:sub(1,1) == "a") end }
          end }
          os = { time=os.time, remove=function(p) return py_remove(p) or nil end }
          loadfile = function(path)
            if path ~= "scripts/config/TheInsurgentsManifestoConfig.lua" then return nil end
            return function() return { thirtySixGambitsAndOneSet = thirty_six }, {} end
          end
          function flip()   -- one frame: run onFlip sync handlers; drop only one-shots (as the loader documents)
            local keep = {}
            for _, h in ipairs(syncs) do
              if h.n == "onFlip" then h.f() end
              if not h.once then keep[#keep+1] = h end
            end
            syncs = keep
          end
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
        lua.execute("loading = false; handlers['onInitDone'](); drive(1200)")
        return lua

    def sync_regs(self):
        return [dict(r) for r in self.lua.eval('syncRegs').values()]

    # ---- helpers ----
    def set_bytes(self, m, k):
        a = SETS + m * 0x9C + k * 0x34
        return bytes(self.mem.get(a + i, 0) for i in range(0x34))

    def runtime_bytes(self, m):
        return bytes(self.mem.get(RT + m * 0x170 + i, 0) for i in range(0x170))

    def slot(self, m, k, i):
        s = self.set_bytes(m, k)
        c, a = struct.unpack_from('<HH', s, 4 * i)
        return c, a, struct.unpack_from('<H', s, 0x30)[0]

    def command(self, m, k, i, op, cid, issued=None, **over):
        c, a, bits = self.slot(m, k, i)
        f = dict(id=cid, issued=str(int(time.time()) if issued is None else issued), member=m, set=k, slot=i, op=op,
                 cond=f'{c:04x}', action=f'{a:04x}', bits=f'{bits:04x}')
        f.update(over)
        self.disk[CMD] = 'ffxii.gambitcmd.v1\n' + ''.join(f'{key}={v}\n' for key, v in f.items())

    def tick(self, ms=600):
        self.lua.execute(f'drive({ms})')

    def flip(self):
        self.lua.execute('flip()')

    def snapshot(self):
        snaps = [json.loads(v) for k, v in self.disk.items() if 'state_' in k]
        return max(snaps, key=lambda s: s['seq'])

    def last(self):
        return self.snapshot()['data']['gambitEdit']['last']

    def log(self):
        return [json.loads(x) for x in self.disk.get(LOG, '').splitlines() if x.strip()]

    def replica_ok(self, m):
        k = struct.unpack('<I', bytes(self.mem[SAVE + 0x7370 + 4 * m + i] for i in range(4)))[0]
        return gambit_runtime.matches(self.runtime_bytes(m), gambit_runtime.build(self.set_bytes(m, k), self.rec))


def main():
    pack = Path(sys.argv[1]).read_bytes()
    hist = [json.loads(x) for x in Path(sys.argv[2]).read_text(encoding='utf-8').splitlines() if x.strip()]
    probe = [x for x in hist if x.get('status') == 'ok' and x.get('stable')][-1]
    ok = 0

    w = World(pack, probe)
    assert w.slot(0, 0, 6) == (0x607E, 0x0052, 0x003F), w.slot(0, 0, 6)       # Vaan set I slot 7: OFF, Self HP<40% -> Potion
    assert all(w.replica_ok(m) for m in range(6))
    e = w.snapshot()['data']['gambitEdit']
    assert e['armed'] is True and 'last' not in e and w.snapshot()['data']['hello']['capabilities']['gambits.write'] == 'probe'
    ok += 1
    print('PASS setup: real dump, replica == runtime for all 6 members, editor armed, no edit yet')

    # 1. not armed: consumed, rejected, nothing written
    w = World(pack, probe, armed=False)
    w.command(0, 0, 6, 'enable', 'test-not-armed-1')
    w.tick(); w.flip()
    assert CMD not in w.disk and w.writes == [] and w.last()['reason'] == 'not_armed', w.last()
    assert w.snapshot()['data']['gambitEdit']['armed'] is False
    ok += 1
    print('PASS not armed: command consumed and rejected, zero memory writes')

    # 2. the proposed test: Vaan set I slot 7 ON (append; entries 1-6 untouched; count 6 -> 7 written last)
    w = World(pack, probe)
    before_rt = w.runtime_bytes(0)
    w.command(0, 0, 6, 'enable', 'test-vaan-s7-on')
    w.tick()
    assert w.writes == [] and w.snapshot()['data']['gambitEdit']['pending'] == 'test-vaan-s7-on'
    w.flip()
    w.tick()
    r = w.last()
    assert r['status'] == 'applied' and r['after'] == '607e:0052:007f' and r['before'] == '607e:0052:003f', r
    assert r['runtimeCountBefore'] == 6 and r['runtimeCountAfter'] == 7 and r['activeSet'] == 0, r
    assert w.slot(0, 0, 6) == (0x607E, 0x0052, 0x007F) and w.replica_ok(0)
    rt = w.runtime_bytes(0)
    assert rt[1:0x20 + 6 * 0x18] == before_rt[1:0x20 + 6 * 0x18], 'entries 1-6 must not change'
    assert w.writes[0] == (SETS + 0x30, 2, 0x7F), w.writes[0]
    assert w.writes[-1] == (RT, 1, 7) and all(a != RT for a, _, _ in w.writes[:-1]), 'count written once, last'
    assert all(RT + 0x20 + 6 * 0x18 <= a < RT + 0x20 + 7 * 0x18 for a, _, _ in w.writes[1:-1]), 'only entry 7 written'
    assert all(SETS <= a < SETS + 0x9C or RT <= a < RT + 0x170 for a, _, _ in w.writes), 'writes outside Vaan'
    w.tick(2600)
    assert w.last()['persisted'] is True, w.last()
    assert [x['status'] for x in w.log()] == ['applied', 'applied'] and w.log()[-1]['persisted'] is True
    ok += 1
    print('PASS Vaan slot 7 ON: stored bit 0x3F->0x7F, runtime == replica, entries 1-6 untouched, count last, persisted')

    # 3. and back OFF: count lowered first, stored set byte-identical to the original, runtime prefix identical
    w.command(0, 0, 6, 'disable', 'test-vaan-s7-off')
    n0 = len(w.writes)
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied' and w.set_bytes(0, 0) == w.stored[0][:0x34] and w.replica_ok(0)
    assert w.runtime_bytes(0)[:0x20 + 6 * 0x18] == before_rt[:0x20 + 6 * 0x18]
    assert w.writes[n0:] == [(SETS + 0x30, 2, 0x3F), (RT, 1, 6)], w.writes[n0:]
    ok += 1
    print('PASS Vaan slot 7 OFF again: stored set back to the original bytes, only bits + count written')

    # 4. a middle slot: count lowered to the unchanged prefix, later entries rewritten, final count last
    w = World(pack, probe)
    w.command(0, 0, 2, 'disable', 'test-vaan-s3-off')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied' and w.replica_ok(0) and w.runtime_bytes(0)[0] == 5
    counts = [v for a, wd, v in w.writes if a == RT]
    assert counts == [2, 5] and w.writes[-1] == (RT, 1, 5), counts
    first_count = next(n for n, x in enumerate(w.writes) if x[0] == RT)
    assert all(not (RT + 0x20 <= a < RT + 0x20 + 2 * 0x18) for a, _, _ in w.writes), 'prefix entries untouched'
    assert all(a == SETS + 0x30 for a, _, _ in w.writes[:first_count])
    ok += 1
    print('PASS middle slot OFF: count 6 -> 2 (valid prefix) -> entries 3-5 -> count 5; replica exact')

    # 5. inactive set (Vaan set II): stored only, runtime untouched
    w = World(pack, probe)
    rt0 = w.runtime_bytes(0)
    i2 = next(i for i in range(12) if w.slot(0, 1, i)[0] != 0xFFFF)
    w.command(0, 1, i2, 'disable' if w.slot(0, 1, i2)[2] >> i2 & 1 else 'enable', 'test-vaan-set2')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied' and w.runtime_bytes(0) == rt0 and all(a < RT or a >= RT + 0x170 for a, _, _ in w.writes)
    ok += 1
    print('PASS inactive set: stored set written, runtime list untouched')

    # 6. gates, each consumed and rejected with no memory writes
    cases = [
        (dict(field=False), {}, 'not_in_field'),
        (dict(thirty_six=True), {}, 'manifesto_36_slot_on_or_unknown'),
        ({}, dict(bits='0001'), 'stale_expected'),
        ({}, dict(op='disable'), 'already_off'),
        ({}, dict(issued=str(int(time.time()) - 600)), 'command_too_old'),
        ({}, dict(op='set', newCond='6100', newAction='0052'), 'bad_condition'),
        ({}, dict(op='set', newCond='6000', newAction='0051'), 'bad_action'),
        ({}, dict(op='jump'), 'bad_op'),
        ({}, dict(member='6'), 'missing_member'),
    ]
    for kw, over, reason in cases:
        w = World(pack, probe, **kw)
        w.command(0, 0, 6, over.pop('op', 'enable'), 'test-gate-' + reason.replace('_', '-')[:20], **over)
        w.tick(); w.flip(); w.tick()
        assert w.writes == [] and CMD not in w.disk and w.last()['status'] == 'rejected' and w.last()['reason'] == reason, (reason, w.last())
    ok += 1
    print(f'PASS {len(cases)} gates (field, Manifesto 36, stale, already off, old, bad IDs, bad op, range): rejected, no writes')

    # 6b. no Manifesto installed (vanilla game: three sets) -> the 36-slot gate passes even if a stale config says on
    w = World(pack, probe, manifesto=False, thirty_six=True)
    w.command(0, 0, 6, 'enable', 'test-vanilla-no-manifesto')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied', w.last()
    ok += 1
    print('PASS no Manifesto installed: editor works on the normal three sets')

    # 7. runtime list not what the replica builds (model check) -> rejected
    w = World(pack, probe)
    w.mem[RT + 0x20 + 0x24] ^= 1
    w.command(0, 0, 6, 'enable', 'test-model-mismatch')
    w.tick(); w.flip(); w.tick()
    assert w.writes == [] and w.last()['reason'] == 'runtime_mismatch'
    ok += 1
    print('PASS live runtime list differing from the replica: rejected before any write')

    # 8. replay: same id again (re-dropped file) is rejected
    w = World(pack, probe)
    w.command(0, 0, 6, 'enable', 'test-replay-0001')
    w.tick(); w.flip(); w.tick()
    n = len(w.writes)
    w.command(0, 0, 6, 'disable', 'test-replay-0001')
    w.tick(); w.flip(); w.tick()
    assert len(w.writes) == n and w.last()['reason'] == 'duplicate_id'
    ok += 1
    print('PASS replay of a used command id: rejected, no writes')

    # 9. a change between check and frame (menu edit): re-check on the frame thread rejects
    w = World(pack, probe)
    w.command(0, 0, 6, 'enable', 'test-race-menu')
    w.tick()
    w.mem[SETS + 0x30] = 0x3E                         # player toggles slot 1 in the menu before the frame
    w.flip(); w.tick()
    assert w.writes == [] and w.last()['reason'] == 'stale_expected', w.last()
    ok += 1
    print('PASS stored set changed before the frame: re-check rejects, no writes')

    # 10. frame never runs: expired, and a late onFlip does nothing
    w = World(pack, probe)
    w.command(0, 0, 6, 'enable', 'test-no-frame')
    w.tick(); w.tick(3500)
    assert w.last()['status'] == 'expired'
    w.flip(); w.tick()
    assert w.writes == [] and w.last()['status'] == 'expired'
    ok += 1
    print('PASS no frame within 3 s: expired, late onFlip writes nothing')

    # 11. read-back mismatch (count write lost): old bytes restored exactly
    w = World(pack, probe, drop={RT})
    st0, rt0 = w.set_bytes(0, 0), w.runtime_bytes(0)
    w.command(0, 0, 6, 'enable', 'test-readback-lost')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'restored' and w.last()['reason'] == 'readback_mismatch', w.last()
    assert w.set_bytes(0, 0) == st0 and w.runtime_bytes(0)[1:] == rt0[1:] and w.runtime_bytes(0)[0] == rt0[0]
    ok += 1
    print('PASS read-back mismatch: stored set and runtime list restored byte for byte')

    # 12. set op: replace Vaan slot 7's condition (Self HP<40% -> Ally HP<50%), still OFF: stored only
    w = World(pack, probe)
    w.command(0, 0, 6, 'set', 'test-set-cond-01', newCond='60b5', newAction='0052')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied' and w.slot(0, 0, 6) == (0x60B5, 0x0052, 0x003F) and w.replica_ok(0)
    assert all(a < RT or a >= RT + 0x170 for a, _, _ in w.writes)
    w.command(0, 0, 3, 'set', 'test-set-cond-02', newCond='6000', newAction='0096')   # an ON slot: runtime rebuilt
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied' and w.replica_ok(0)
    ok += 1
    print('PASS set op: OFF slot changes stored only; ON slot rebuilds the runtime list == replica')

    # 13. live table unavailable (desc pointer 0, as seen on the Thor) and no copy yet: rejected with the reason
    w = World(pack, probe, table=False)
    w.command(0, 0, 6, 'enable', 'test-no-table-01')
    w.tick(); w.flip(); w.tick()
    t = w.snapshot()['data']['gambitEdit']['table']
    assert w.writes == [] and w.last()['reason'] == 'condition_table_unavailable' and w.last()['table']['why'] == 'desc_null', w.last()
    assert t['source'] == 'none' and t['why'] == 'desc_null', t
    ok += 1
    print('PASS no live table and no copy: rejected, reason desc_null reported in the result and data.gambitEdit.table')

    # 14. the game had the table earlier this session (copy taken), then not: the copy is used, model check still on
    w = World(pack, probe)
    assert w.snapshot()['data']['gambitEdit']['table']['source'] == 'live'
    put(w.mem, 0x2EBF0D8, bytes(8))
    w.command(0, 0, 6, 'enable', 'test-cached-table')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied' and w.last()['tableSource'] == 'cache' and w.replica_ok(0), w.last()
    assert w.snapshot()['data']['gambitEdit']['table']['source'] == 'cache'
    ok += 1
    print('PASS live table gone after a copy was taken: edit applied from the copy == replica')

    # 15. Thor layout (0.5.1 diagnostics): pack base 0, descriptor data field = absolute record address
    w = World(pack, probe, base0=True)
    assert w.snapshot()['data']['gambitEdit']['table']['source'] == 'live', w.snapshot()['data']['gambitEdit']['table']
    w.command(0, 0, 6, 'enable', 'test-base-zero-01')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied' and w.last()['tableSource'] == 'live' and w.replica_ok(0), w.last()
    ok += 1
    print('PASS pack base 0 (Thor layout): records at the descriptor offset, edit applied == replica')

    # 16. 0.5.3 deadlock rule: the only sync registration is ONE permanent onFlip at script load, and only when armed
    w = World(pack, probe)
    for op, cid in (('enable', 'test-reg-rule-on'), ('disable', 'test-reg-rule-off')):
        w.command(0, 0, 6, op, cid)
        w.tick(); w.flip(); w.tick(); w.flip(); w.tick()
        assert w.last()['status'] == 'applied', w.last()
    w.tick(3000); w.flip()
    regs = w.sync_regs()
    assert regs == [{'n': 'onFlip', 'once': False, 'load': True}], regs
    assert w.snapshot()['data']['gambitEdit']['hooked'] is True
    ok += 1
    print('PASS registration rule: exactly one permanent sync onFlip, registered during script load; two edits add none')

    # 17. not armed at load: no hook at all; arming later is refused until the game restarts
    w = World(pack, probe, armed=False)
    assert w.sync_regs() == [] and w.snapshot()['data']['gambitEdit']['hooked'] is False
    w.disk[ARM] = 'armed later'
    w.command(0, 0, 6, 'enable', 'test-armed-late-1')
    w.tick(); w.flip(); w.tick()
    assert w.writes == [] and w.last()['reason'] == 'editor_not_started' and w.sync_regs() == [], w.last()
    ok += 1
    print('PASS not armed at load: no sync registration; a later ARMED is refused (editor_not_started), zero writes')

    total = 19
    # 18-23. 0.5.6 setslot on the REAL save dump (owned lists, licences, member records from a real game)
    if len(sys.argv) > 5:
        dump = Path(sys.argv[5]).read_bytes()
        def fresh(**kw):
            return World(pack, probe, dump=dump, **kw)
        w = fresh()
        assert all(w.replica_ok(k) for k in range(6))
        c, a, bits = w.slot(0, 0, 7)
        assert (c, a) == (0xFFFF, 0xFFFF), 'Vaan set I slot 8 empty in the dump'
        w.command(0, 0, 7, 'setslot', 'test-setslot-empty', newCond='60b5', newAction='0052')
        w.tick(); w.flip(); w.tick()
        assert w.last()['status'] == 'applied' and w.slot(0, 0, 7) == (0x60B5, 0x0052, bits | 0x80) and w.replica_ok(0), w.last()
        ok += 1; total += 1
        print('PASS setslot fills an empty slot (owned condition + item) and switches it ON; runtime == replica')
        owned_m = {v for v in struct.unpack_from('<81H', dump, 0xC114) if v != 0xFFFF}
        not_bought = next(a for a in range(0x51) if 0x3000 + a not in owned_m)
        cases = [(dict(newCond='60b5', newAction='%04x' % not_bought), 'action_not_owned'),  # a magick nobody bought
                 (dict(newCond='60b5', newAction='0000'), 'action_not_licensed'),           # Cure: owned, Vaan has no licence
                 (dict(newCond='60ff', newAction='0052'), 'condition_not_owned'),           # the one gambit not owned
                 (dict(newCond='60b5', newAction='0051'), 'bad_action'),
                 (dict(newCond='60b5', newAction='0070'), 'bad_action')]
        for over, reason in cases:
            w = fresh()
            w.command(0, 0, 7, 'setslot', 'test-setslot-' + reason.replace('_', '-')[:18], **over)
            w.tick(); w.flip(); w.tick()
            assert w.writes == [] and w.last()['reason'] == reason, (reason, w.last())
        ok += 1; total += 1
        print('PASS setslot refused, zero writes: magick not bought; owned but not licensed (Vaan/Cure); gambit not owned; bad id')
        w = fresh()
        w.command(0, 0, 7, 'setslot', 'test-setslot-steal', newCond='602a', newAction='00a9')
        w.tick(); w.flip(); w.tick()
        assert w.last()['status'] == 'applied' and w.slot(0, 0, 7)[:2] == (0x602A, 0x00A9), w.last()
        ok += 1; total += 1
        print('PASS setslot accepts a technick the member owns and is licensed for (Vaan: Steal)')
        bad = bytearray(dump)
        bad[SETS - REGION + 0x32] = 6                     # Vaan set I has only 6 slots unlocked
        w = World(pack, probe, dump=bytes(bad))
        w.command(0, 0, 7, 'setslot', 'test-setslot-locked', newCond='60b5', newAction='0052')
        w.tick(); w.flip(); w.tick()
        assert w.writes == [] and w.last()['reason'] == 'slot_locked', w.last()
        bad = bytearray(dump)
        struct.pack_into('<H', bad, 0xC114 + 2, 0x3000)  # duplicate in the magick list -> ownership unknown
        w = World(pack, probe, dump=bytes(bad))
        w.command(0, 0, 7, 'setslot', 'test-setslot-noown', newCond='60b5', newAction='0052')
        w.tick(); w.flip(); w.tick()
        assert w.writes == [] and w.last()['reason'] == 'ownership_unknown', w.last()
        ok += 1; total += 1
        print('PASS setslot refused past the unlocked slot count and when the ownership lists are implausible')
    if len(sys.argv) > 3:                             # the receiver's own command text (GambitEditTest.java)
        total += 1
        w = World(pack, probe)
        w.disk[CMD] = Path(sys.argv[3]).read_text(encoding='ascii')
        w.tick(); w.flip(); w.tick()
        assert w.last()['status'] == 'applied' and w.last()['after'] == '607e:0052:007f' and w.replica_ok(0), w.last()
        ok += 1
        print('PASS command built by the receiver (Java) parsed and applied by the Lua editor')
    if len(sys.argv) > 4:                             # the receiver's change (op set) command
        total += 1
        w = World(pack, probe)
        rt0 = w.runtime_bytes(0)
        w.disk[CMD] = Path(sys.argv[4]).read_text(encoding='ascii')
        w.tick(); w.flip(); w.tick()
        assert w.last()['status'] == 'applied' and w.last()['op'] == 'set' and w.last()['after'] == '60b5:0052:003f', w.last()
        assert w.slot(0, 0, 6) == (0x60B5, 0x0052, 0x003F) and w.runtime_bytes(0) == rt0 and w.replica_ok(0)
        ok += 1
        print('PASS change command built by the receiver (op set) applied: slot 7 condition swapped, OFF slot so AI list untouched')
    if len(sys.argv) > 6:                             # the receiver's own setslot command (ApplyTest.java), same dump
        total += 1
        w = World(pack, probe, dump=Path(sys.argv[5]).read_bytes())
        txt = Path(sys.argv[6]).read_text(encoding='ascii')
        w.disk[CMD] = txt
        w.tick(); w.flip(); w.tick()
        f = dict(l.split('=', 1) for l in txt.splitlines()[1:])
        assert w.last()['status'] == 'applied' and w.last()['op'] == 'setslot', w.last()
        assert w.slot(0, 0, int(f['slot']))[:2] == (int(f['newCond'], 16), int(f['newAction'], 16)) and w.replica_ok(0)
        ok += 1
        print('PASS setslot command built by the receiver (ApplyTest) applied by the Lua editor on the same real dump')
    # ---- 0.10.0 setactive: switch a member's active gambit set (Action_IDs_and_Set_Switch_2026-10-22, Part B) ----
    def setactive(w, m, target, cid, frm=None, stored=None, **over):
        k = struct.unpack('<I', bytes(w.mem[SAVE + 0x7370 + 4 * m + i] for i in range(4)))[0]
        f = dict(id=cid, issued=str(int(time.time())), member=m, set=target, op='setactive',
                 **{'from': k if frm is None else frm},
                 stored=(stored if stored is not None else bytes(w.mem.get(SETS + m * 0x9C + i, 0) for i in range(0x9C)).hex()))
        f.update(over)
        w.disk[CMD] = 'ffxii.gambitcmd.v1' + chr(10) + ''.join(f'{key}={v}' + chr(10) for key, v in f.items())
    def active(w, m):
        return struct.unpack('<I', bytes(w.mem[SAVE + 0x7370 + 4 * m + i] for i in range(4)))[0]
    total += 6
    w = World(pack, probe)
    idx = [active(w, m) for m in range(6)]
    assert w.snapshot()['data']['gambits']['active'] == ','.join(map(str, idx))
    ok += 1
    print('PASS active-set telemetry: data.gambits.active == the six active indices')

    n = 0
    for m in range(6):
        for target in range(3):
            if target == idx[m]:
                continue
            w = World(pack, probe)
            before_sets = bytes(w.mem.get(SETS + m * 0x9C + i, 0) for i in range(0x9C))
            others = [w.runtime_bytes(o) for o in range(6) if o != m]
            setactive(w, m, target, f'test-sa-{m}-{target}')
            w.tick(); w.flip(); w.tick()
            r = w.last()
            assert r['status'] == 'applied' and active(w, m) == target and w.replica_ok(m), (m, target, r)
            assert bytes(w.mem.get(SETS + m * 0x9C + i, 0) for i in range(0x9C)) == before_sets
            assert [w.runtime_bytes(o) for o in range(6) if o != m] == others
            rt, kaddr = RT + m * 0x170, SAVE + 0x7370 + 4 * m
            seq = [(a, wd) for a, wd, _ in w.writes]
            assert seq[0] == (rt, 1) and w.writes[0][2] == 0, 'count 0 first'
            assert seq[1] == (kaddr, 4), 'index second'
            assert seq[-1] == (rt, 1), 'final count last'
            assert all(rt <= a < rt + 0x170 or a == kaddr for a, _ in seq), 'nothing else written'
            w.tick(3000)
            assert w.last()['persisted'] is True
            n += 1
    ok += 1
    print(f'PASS setactive: {n} member/set switches applied; count 0 -> index -> entries -> count; stored sets and other members untouched; persisted')

    cases = [(dict(field=False), {}, 'not_in_field'),
             (dict(thirty_six=True), {}, 'manifesto_36_slot_on_or_unknown'),
             (dict(table=False), {}, 'condition_table_unavailable'),
             ({}, dict(frm='bad'), 'stale_expected'),
             ({}, dict(stored='00' * 0x9C), 'stale_expected'),
             ({}, dict(menu=True), 'gambits_menu_open'),
             ({}, dict(rtmismatch=True), 'runtime_mismatch')]
    for kw, over, reason in cases:
        w = World(pack, probe)
        if over.pop('menu', False):
            put(w.mem, 0x2CA96F8, (0x12345678).to_bytes(8, 'little'))
        if over.pop('rtmismatch', False):
            w.mem[RT + 0x20 + 0x24] ^= 1
        if kw:
            w = World(pack, probe, **kw)
        frm = over.pop('frm', None)
        tgt = (idx[0] + 1) % 3
        setactive(w, 0, tgt, 'test-sa-gate-' + reason.replace('_', '-')[:16],
                  frm=((idx[0] + 2) % 3 if frm == 'bad' else None), **over)
        w.tick(); w.flip(); w.tick()
        assert w.writes == [] and w.last()['status'] == 'rejected' and w.last()['reason'] == reason, (reason, w.last())
        assert active(w, 0) == idx[0]
    ok += 1
    print(f'PASS setactive gates, zero writes each: {", ".join(c[2] for c in cases)}')

    w = World(pack, probe)
    setactive(w, 0, idx[0], 'test-sa-noop')
    w.tick(); w.flip(); w.tick()
    assert w.writes == [] and w.last()['status'] == 'applied' and w.last()['reason'] == 'already_active'
    ok += 1
    print('PASS setactive to the already-active set: success, zero writes')

    tgt = (idx[0] + 1) % 3
    w = World(pack, probe, drop={SAVE + 0x7370})
    before = w.runtime_bytes(0)
    setactive(w, 0, tgt, 'test-sa-dropped')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'restored' and active(w, 0) == idx[0] and w.runtime_bytes(0) == before, w.last()
    ok += 1
    print('PASS setactive index write lost -> old count, entries and index put back exactly (restored)')

    w = World(pack, probe)
    setactive(w, 0, tgt, 'test-sa-late-change')
    w.tick(); w.flip(); w.tick()
    assert w.last()['status'] == 'applied'
    put(w.mem, SAVE + 0x7370, idx[0].to_bytes(4, 'little'))   # the player switches back in the menu
    w.tick(3000)
    assert w.last()['persisted'] is False
    ok += 1
    print('PASS setactive persistence re-reads the index: a later native switch is reported, not overwritten')
    # ---- 0.11.0 command-channel hardening ----
    total += 3
    w = World(pack, probe)
    w.disk[CMD] = 'ffxii.gambitcmd.v1' + chr(10) + 'id=test-too-large' + chr(10) + ('x' * 5000)
    w.tick(); w.flip(); w.tick()
    assert w.writes == [] and w.last()['reason'] == 'too_large' and CMD not in w.disk, w.last()
    ok += 1
    print('PASS command file over 4 KB: refused unread, removed, no writes')
    for extra, reason in (('evil=1', 'unknown_field'), ('member=1', 'duplicate_field')):
        w = World(pack, probe)
        w.command(0, 0, 6, 'enable', 'test-grammar-' + reason.replace('_', '-'))
        w.disk[CMD] += extra + chr(10)
        w.tick(); w.flip(); w.tick()
        assert w.writes == [] and w.last()['reason'] == reason, w.last()
    ok += 1
    print('PASS unknown or repeated command fields refused, no writes')
    w = World(pack, probe, field=False)               # valid requests, refused later (not in the field)
    def resend(n):
        w.command(0, 0, 6, 'enable', f'test-seen-{n:04d}')
        w.tick(600)
        return w.last()['reason']
    for n in range(300):
        resend(n)
    assert resend(299) == 'duplicate_id' and resend(0) != 'duplicate_id'
    ok += 1
    print('PASS replay memory is bounded: after 300 requests the oldest id is forgotten, recent ones still refused')
    jf = Path(__file__).resolve().parents[2] / 'app/tests/out/java_setactive_cmd.txt'
    if jf.is_file():                                   # the app's command uses exactly the fields this script reads
        lines = jf.read_text(encoding='ascii').splitlines()
        assert lines[0] == 'ffxii.gambitcmd.v1'
        assert [x.split('=', 1)[0] for x in lines[1:]] == ['id', 'issued', 'member', 'set', 'op', 'from', 'stored']
        assert dict(x.split('=', 1) for x in lines[1:])['op'] == 'setactive'
        total += 1
        ok += 1
        print('PASS the app setactive command (SetActiveTest) has exactly the fields the game script parses')
    print(f'{ok}/{total} PASS. Real Thor dump + deployed battle pack; stubs only; every write recorded and checked.')


if __name__ == '__main__':
    main()
