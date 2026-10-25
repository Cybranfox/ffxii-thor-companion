"""Stub-API tests for the raw copy (data.rawcopy, ThorCompanion-core.lua 0.15.0, lupa, Lua 5.4 and 5.5): the app's
request -> stored game-file bytes copied unchanged to raw/<job>/, 4 KiB per 50 ms tick. Uses a small synthetic SRYK
archive with the real layout (no game data) and real files in a temp folder; D:/ and A:/ are mapped into it.
Checks: exact copies, receipts/done, resume after a quit, re-check against the archive's own directory (size and
name), pauses (fight, app away), bad requests refused, exit stops it, finished jobs are not recopied, the copier adds
no memory reads, and nothing is written outside raw/ and live/.

Usage: python test_rawcopy_family.py
"""
import json
import os
import struct
import sys
import tempfile
import zlib
from pathlib import Path

import lupa.lua54
import lupa.lua55

sys.path.insert(0, str(Path(__file__).parent))
from test_reveal_family import SRC, make_memory  # noqa: E402

BASE_T = 1_800_000_000
BATTLE_FLAGS = 0x22C1F48
CONFIG = 'scripts/config/TheInsurgentsDescriptiveInventoryConfig/us.lua'


def make_archive(path, rng_seed=5, names=('a/first.bin', 'b/second.bin', 'c/third.bin')):
    """SRYK: 'SRYK', u32 header_len, u64 n; n x 16 hash; n x 32 {u32 first, u32 0, u64 size, u64 off, u64 name_off};
    u32 names_len (incl. itself) + names; u16 block codes (0 = raw 64 KiB, == logical size = raw, else zlib); data.
    names: the three entries' paths (test_rawcopy_app uses the app's recipe paths); the contents are synthetic."""
    import random
    rnd = random.Random(rng_seed)
    files = [(names[0], bytes(rnd.getrandbits(8) for _ in range(70000))),               # 2 blocks, zlib + raw tail
             (names[1], bytes(range(256)) * 300),                                         # 76800 B, compresses
             (names[2], bytes(rnd.getrandbits(8) for _ in range(65536 * 2)))]            # 2 x 64 KiB raw (code 0)
    files += [(n, bytes([i]) * 1000) for i, n in enumerate(names[3:])]                    # extras: one zlib block each
    names = b''
    name_offs = []
    for n, _ in files:
        name_offs.append(len(names))
        names += n.encode() + b'\0'
    blocks, stored_by_file = [], []
    for _, data in files:
        sb = []
        for i in range(0, len(data), 65536):
            chunk = data[i:i + 65536]
            z = zlib.compress(chunk, 9)
            if len(z) < len(chunk):
                sb.append((len(z), z))
            elif len(chunk) == 65536:
                sb.append((0, chunk))
            else:
                sb.append((len(chunk), chunk))
        stored_by_file.append(sb)
    n = len(files)
    nblocks = sum(len(s) for s in stored_by_file)
    hlen = 16 + 16 * n + 32 * n + 4 + len(names) + 2 * nblocks
    hlen += (-hlen) % 16
    entries, codes, body, first = b'', b'', b'', 0
    stored = []
    for (name, data), sb, noff in zip(files, stored_by_file, name_offs):
        off = hlen + len(body)
        blob = b''.join(b for _, b in sb)
        entries += struct.pack('<IIQQQ', first, 0, len(data), off, noff)
        codes += b''.join(struct.pack('<H', c) for c, _ in sb)
        body += blob
        stored.append((name, blob))
        first += len(sb)
    head = b'SRYK' + struct.pack('<IQ', hlen, n) + bytes(16 * n) + entries + struct.pack('<I', 4 + len(names)) + names + codes
    head += bytes(hlen - len(head))
    Path(path).write_bytes(head + body)
    return head, stored


class World:
    def __init__(self, runtime, root, mem=None):
        self.root, self.reads = root, {'n': 0}
        self.lua = runtime.LuaRuntime(unpack_returned_tuples=True)
        g = self.lua.globals()
        mem = mem if mem is not None else make_memory()
        self.mem = mem

        def rd(width):
            def get(addr):
                self.reads['n'] += 1
                return int.from_bytes(bytes(mem.get(addr + i, 0) for i in range(width)), 'little')
            return get

        g.py_u8, g.py_u16, g.py_u32, g.py_u64 = rd(1), rd(2), rd(4), rd(8)
        g.py_map = self.map_path
        g.BASE_T = BASE_T
        self.lua.execute('''
          local function view(f) return setmetatable({}, {__index=function(_, a) return f(a) end,
            __newindex=function() error("WRITE ATTEMPT") end}) end
          memory = { u8=view(py_u8), u16=view(py_u16), u32=view(py_u32), u64=view(py_u64) }
          call = { getCurrentMap=function() return 585 end, getCurrentMapIndex=function() return 1 end,
                   getPreviousMap=function() return 581 end, getPreviousMapIndex=function() return 0 end }
          handlers = {}; timers = {}; clock = 0
          event = { registerEventAsync=function(n,f) handlers[n]=f end,
                    executeAfterMs=function(ms,f) timers[#timers+1]={at=clock+ms*1000, f=f} end,
                    getClock=function() return clock end }
          function getLibVersion() return {1,7,1} end
          game = {}
          OPENS = {}
          local realOpen, realRename, realRemove, realTime = io.open, os.rename, os.remove, os.time
          io.open = function(path, mode)
            OPENS[#OPENS + 1] = path .. "|" .. (mode or "r")
            local p = py_map(path); if not p then return nil end
            return realOpen(p, mode)
          end
          os.rename = function(a, b) return realRename(py_map(a), py_map(b)) end
          os.remove = function(a) local p = py_map(a); if p then return realRemove(p) end end
          os.time = function(t) if t then return realTime(t) end return BASE_T + clock // 1000000 end
          lfs = { mkdir = function(p) local q = py_map(p); if q then py_mkdir(q) end return true end,
                  attributes = function() return nil end }
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
        g.py_mkdir = lambda p: os.makedirs(p, exist_ok=True)
        self.lua.execute(SRC)
        self.lua.execute("handlers['onInitDone']()")

    def map_path(self, path):
        path = str(path)
        if path.startswith('D:/'):
            return str(self.root / 'D' / path[3:])
        if path == 'A:/FFXII_TZA.vbf':
            return str(self.root / 'A' / 'FFXII_TZA.vbf')
        if path.startswith('scripts/'):
            return str(self.root / 'A' / 'x64' / path)
        return None

    def drive(self, ms):
        self.lua.execute(f'drive({ms})')

    def now(self):
        return BASE_T + int(self.lua.eval('clock')) // 1_000_000

    def alive(self, age=0):
        p = self.root / 'D' / 'FFXII_companion' / 'raw'
        p.mkdir(parents=True, exist_ok=True)
        (p / 'alive.txt').write_text(str(self.now() - age))

    def snapshot(self):
        live = self.root / 'D' / 'FFXII_companion' / 'live'
        snaps = [json.loads(p.read_text()) for p in live.glob('state_*.json')]
        return max(snaps, key=lambda s: s['seq'])['data']

    def opens(self):
        return list(self.lua.eval('OPENS').values())

    def close(self):
        """Game exit: the copier closes its files (Windows can't delete open files)."""
        self.lua.execute("handlers['exit']('Normal exit'); collectgarbage(); collectgarbage()")


def setup(tmp):
    (tmp / 'A' / 'x64' / 'scripts' / 'config' / 'TheInsurgentsDescriptiveInventoryConfig').mkdir(parents=True)
    head, stored = make_archive(tmp / 'A' / 'FFXII_TZA.vbf')
    cfg = ('return {\r\n  -- synthetic config\r\n}\r\n' * 50).encode()
    (tmp / 'A' / 'x64' / CONFIG).write_bytes(cfg)
    (tmp / 'D' / 'FFXII_companion' / 'live').mkdir(parents=True)
    return head, stored, cfg


def request(tmp, job, items):
    raw = tmp / 'D' / 'FFXII_companion' / 'raw'
    raw.mkdir(parents=True, exist_ok=True)
    (raw / 'request.txt').write_bytes(('contract=ffxii.rawcopy.v1\njob=' + job + '\n'
                                       + ''.join('item=' + i + '\n' for i in items)).encode())


def full_items(stored):
    return ['header'] + [f'entry {i} {len(b)} {n}' for i, (n, b) in enumerate(stored)] + ['config 1']


def run_suite(runtime):
    ok = 0
    ver = runtime.LuaRuntime().eval('_VERSION')

    # 1. a full job: header, 3 entries, 1 config -> exact bytes, receipts, done
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        head, stored, cfg = setup(tmp)
        w = World(runtime, tmp)
        w.alive()
        request(tmp, 'j1', full_items(stored))
        for _ in range(200):
            w.alive()
            w.drive(2000)
            if w.snapshot()['rawcopy']['status'] == 'done':
                break
        d = tmp / 'D' / 'FFXII_companion' / 'raw' / 'j1'
        want = [head] + [b for _, b in stored] + [cfg]
        got = [(d / f'{i + 1:03d}.bin').read_bytes() for i in range(len(want))]
        assert got == want, [len(x) for x in got]
        rec = (d / 'receipts.txt').read_text().split()
        assert rec == sum(([f'{i + 1:03d}', str(len(b))] for i, b in enumerate(want)), [])
        assert (d / 'done.txt').exists() and not list(d.glob('*.part'))
        st = w.snapshot()['rawcopy']
        assert st['status'] == 'done' and st['job'] == 'j1' and st['contract'] == 'ffxii.rawcopy.v1'
        writes = [o for o in w.opens() if '|w' in o or '|a' in o or '|r+' in o]
        assert all(o.startswith('D:/FFXII_companion/raw/') or o.startswith('D:/FFXII_companion/live/') for o in writes), writes
        reads = [o for o in w.opens() if o.endswith('|rb') and not o.startswith('D:/')]
        assert reads and all(o.split('|')[0] in ('A:/FFXII_TZA.vbf', CONFIG) for o in reads), reads
        ok += 1
        print(f'PASS ({ver}) full job: header + 3 entries + config copied byte for byte, receipts, done; '
              'game files only opened rb, writes only under raw/ and live/')

        # 9. same job id again after done: nothing recopied
        before = (d / 'receipts.txt').read_bytes()
        w2 = World(runtime, tmp)
        w2.alive()
        w2.drive(4000)
        assert w2.snapshot()['rawcopy']['status'] == 'done' and (d / 'receipts.txt').read_bytes() == before
        ok += 1
        print(f'PASS ({ver}) a finished job is not copied again (new game start, same job id)')

    # 2. resume after a quit mid-entry
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        head, stored, cfg = setup(tmp)
        request(tmp, 'j2', full_items(stored))
        w = World(runtime, tmp)
        w.alive()
        w.drive(2500)
        mid = w.snapshot()['rawcopy']
        assert mid['status'] == 'running' and mid['bytes'] > 0, mid
        w.lua.execute("handlers['exit']('Normal exit')")                 # quit
        w.drive(2000)
        d = tmp / 'D' / 'FFXII_companion' / 'raw' / 'j2'
        cursor = (d / 'cursor.txt').read_text()
        w.close()
        w = World(runtime, tmp)                                           # next game start
        for _ in range(200):
            w.alive()
            w.drive(2000)
            if w.snapshot()['rawcopy']['status'] == 'done':
                break
        want = [head] + [b for _, b in stored] + [cfg]
        assert [(d / f'{i + 1:03d}.bin').read_bytes() for i in range(len(want))] == want
        ok += 1
        print(f'PASS ({ver}) quit mid-copy (cursor "{cursor.strip()}"), next start resumes, every file exact')

    # 3. request disagrees with the archive's own directory -> refused, nothing copied for it
    for bad, why in ((lambda s: f'entry 1 {len(s[1][1]) + 1} {s[1][0]}', 'entry_mismatch'),
                     (lambda s: f'entry 1 {len(s[1][1])} x/other.bin', 'entry_mismatch'),
                     (lambda s: 'entry 9 100 a/first.bin', 'entry_directory')):
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            head, stored, cfg = setup(tmp)
            request(tmp, 'j3', [bad(stored)])
            w = World(runtime, tmp)
            w.alive()
            w.drive(3000)
            st = w.snapshot()['rawcopy']
            assert st['status'] == 'error' and st['error'] == why, st
            assert not list((tmp / 'D' / 'FFXII_companion' / 'raw' / 'j3').glob('*.bin'))
    ok += 1
    print(f'PASS ({ver}) wrong stored size, wrong name, entry out of range: refused before copying')

    # 4/5. pauses: fight, app away
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        head, stored, cfg = setup(tmp)
        request(tmp, 'j4', full_items(stored) * 6)                       # long enough to pause mid-copy
        mem = make_memory()
        mem[BATTLE_FLAGS] = 0x04                                         # weapon demand: fight
        w = World(runtime, tmp, mem)
        w.alive()
        w.drive(3000)
        st = w.snapshot()['rawcopy']
        assert st['status'] == 'running' and st['paused'] == 'fight' and st['bytes'] == 0, st
        mem[BATTLE_FLAGS] = 0x00
        w.alive()
        w.drive(1000)
        st = w.snapshot()['rawcopy']
        assert st['status'] == 'running' and st['bytes'] > 0 and st.get('paused') is None, st
        mem[BATTLE_FLAGS] = 4  # preserve copy while unchanged-token lease expires
        w.drive(32000)
        mem[BATTLE_FLAGS] = 0
        w.drive(3000)
        b1 = w.snapshot()['rawcopy']
        w.drive(3000)
        b2 = w.snapshot()['rawcopy']
        assert b1['paused'] == 'app_away' and b2['bytes'] == b1['bytes'], (b1, b2)
        w.close()
    ok += 1
    print(f'PASS ({ver}) pauses during a fight and while the app is away; resumes after')

    # 6/7. bad requests
    for text, why in (('contract=ffxii.rawcopy.v1\njob=../x\nitem=header\n', 'job_id'),
                      ('contract=other\njob=a\nitem=header\n', 'request'),
                      ('contract=ffxii.rawcopy.v1\njob=a\nitem=config 9\n', 'item'),
                      ('contract=ffxii.rawcopy.v1\njob=a\nitem=entry 1 2 ../../etc\n', 'item'),
                      ('contract=ffxii.rawcopy.v1\njob=a\nitem=header\nrm=-rf\n', 'line'),
                      ('contract=ffxii.rawcopy.v1\njob=a\n' + 'item=header\n' * 129, 'too_many_items'),
                      ('x' * 50000, 'request_size')):
        with tempfile.TemporaryDirectory() as td:
            tmp = Path(td)
            setup(tmp)
            raw = tmp / 'D' / 'FFXII_companion' / 'raw'
            raw.mkdir(parents=True)
            (raw / 'request.txt').write_text(text, newline='')
            w = World(runtime, tmp)
            w.alive()
            w.drive(3000)
            st = w.snapshot()['rawcopy']
            assert st['status'] == 'error' and st['error'] == why, (text[:40], st)
            assert [p.name for p in raw.iterdir()] == sorted(['alive.txt', 'request.txt', 'request_error.txt']) or \
                sorted(p.name for p in raw.iterdir()) == ['alive.txt', 'request.txt', 'request_error.txt'], list(raw.iterdir())
    ok += 1
    print(f'PASS ({ver}) bad requests refused (job id, contract, config index, unsafe name, unknown line, '
          'too many items, oversized), refusal marker only')

    # 8 + 10. exit stops the copy; the copier adds no game-memory reads
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        head, stored, cfg = setup(tmp)
        w0 = World(runtime, tmp)                                          # no request: baseline reads
        w0.alive()
        w0.drive(1000)
        r0 = w0.reads['n']
        w0.drive(10000)
        base = w0.reads['n'] - r0
        request(tmp, 'j8', full_items(stored) * 8)                       # still copying after 11 s
        w = World(runtime, tmp)
        w.alive()
        w.drive(1000)
        r1 = w.reads['n']
        w.drive(10000)
        busy = w.reads['n'] - r1
        w0.close()
        assert w.snapshot()['rawcopy']['status'] == 'running'
        assert busy == base, (busy, base)
        w.lua.execute("handlers['exit']('Normal exit')")
        n1 = len(w.opens())
        w.drive(5000)
        assert len(w.opens()) == n1, 'no file activity after exit'
        w.close()
    ok += 1
    print(f'PASS ({ver}) copying adds no game-memory reads ({base} reads per 10 s either way); exit stops it')
    return ok


def main():
    total = 0
    for rt in (lupa.lua54, lupa.lua55):
        total += run_suite(rt)
    print(f'{total}/{total} PASS. Raw copy on a synthetic archive in a temp folder; Lua 5.4 and 5.5; no game data.')


if __name__ == '__main__':
    main()
