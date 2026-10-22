"""Tests for the signed loader stub (game-script/loader/stub.lua, assembled by scripts/build_stub.py) in Lua 5.4 via lupa,
with throwaway keys made here (never the real ones) and a simulated file system. Checks that only a correctly signed,
not-older core ever runs, that the rollback journal and the recovery-key policy behave as specified, and that every
refusal leaves the core unrun with a clear reason in live/loader_status.json.
Needs: lupa, cryptography.
"""
import json
import sys
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from lupa.lua54 import LuaRuntime

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import build_stub  # noqa: E402
import core_signing as cs  # noqa: E402

D = 'D:/FFXII_companion/'
CORE, SIG, POLICY, STATUS = D + 'install/ThorCompanion-core.lua', D + 'install/ThorCompanion-core.sig', \
    D + 'install/ThorCompanion-policy.txt', D + 'live/loader_status.json'
STATE = 'scripts/ThorCompanion-state/'
DATE = '2026-10-22'


def core_text(tag, extra=''):
    return f'CORE_RAN = (CORE_RAN or 0) + 1\nCORE_TAG = "{tag}"\n{extra}'.encode()


class Thor:
    """One game install: a disk (shared + private), and a fresh Lua state per game start."""

    def __init__(self, stub, disk=None):
        self.stub, self.disk, self.readonly = stub, disk if disk is not None else {}, set()

    def start(self):
        lua = LuaRuntime(unpack_returned_tuples=True)
        g = lua.globals()
        disk, ro = self.disk, self.readonly

        def py_open(path, mode):
            if mode.startswith('r'):
                return disk.get(path)
            if any(path.startswith(p) for p in ro):
                return None
            return True

        g.py_open = py_open
        g.py_put = lambda path, data: disk.__setitem__(path, bytes(data, 'latin-1') if isinstance(data, str) else bytes(data))
        lua.execute('''
          local function rd(data) local pos = 1
            return { read = function(self, n) if pos > #data then return nil end
                                               local s = data:sub(pos, pos + n - 1); pos = pos + #s; return s end,
                     close = function() end } end
          io = { open = function(path, mode)
            mode = mode or "r"
            local r = py_open(path, mode)
            if r == nil then return nil end
            if mode:sub(1, 1) == "r" then return rd(r) end
            local buf = {}
            return { write = function(self, s) buf[#buf + 1] = s; return self end,
                     close = function() py_put(path, table.concat(buf)) end } end }
          memory = setmetatable({}, { __index = function() error("the loader must not touch game memory") end })
        ''')
        # lupa hands Lua strings to Python as bytes: decode the disk contents for the reader
        g.py_open = lambda path, mode: (disk[path].decode('latin-1') if path in disk else None) if mode.startswith('r') \
            else (None if any(path.startswith(p) for p in ro) else True)
        lua.execute(self.stub)
        st = json.loads(disk[STATUS]) if STATUS in disk else None
        return st, g.CORE_RAN, g.CORE_TAG

    def put_core(self, key, core, seq, version='0.11.0-beta', min_stub=1):
        self.disk[CORE] = core
        self.disk[SIG] = cs.sign_core(key, core, seq, version, min_stub, DATE)


def main():
    rel, rec, other, rel2 = (Ed25519PrivateKey.generate() for _ in range(4))
    stub = build_stub.build(cs.raw_public(rel), cs.raw_public(rec), 5)
    ok = 0

    def check(cond, what):
        nonlocal ok
        if not cond:
            raise AssertionError(what)
        ok += 1
        print('PASS', what)

    t = Thor(stub)
    t.put_core(rel, core_text('a'), 5)
    st, ran, tag = t.start()
    check(st['result'] == 'ok' and ran == 1 and tag == 'a' and st['sequence'] == 5, 'signed core runs; status ok')
    check(any(k.startswith(STATE) for k in t.disk), 'first start writes the private rollback journal')
    gen = {k: v for k, v in t.disk.items() if k.startswith(STATE)}
    st, ran, _ = t.start()
    check(st['result'] == 'ok' and ran == 1 and {k: v for k, v in t.disk.items() if k.startswith(STATE)} == gen,
          'same release again: runs, journal unchanged')

    t.put_core(rel, core_text('b'), 7)
    st, ran, tag = t.start()
    check(st['result'] == 'ok' and tag == 'b', 'newer sequence accepted')
    t.put_core(rel, core_text('old'), 6)
    st, ran, _ = t.start()
    check(st['result'] == 'rollback' and ran is None, 'older signed release refused (rollback)')
    t.put_core(rel, core_text('other7'), 7)
    st, ran, _ = t.start()
    check(st['result'] == 'rollback' and ran is None, 'same sequence, different signed content refused')

    t2 = Thor(stub)
    t2.put_core(rel, core_text('x'), 4)
    check(t2.start()[0]['result'] == 'rollback', 'below the stub\'s minimum sequence refused on a fresh install')

    def fresh(mutate, expect, what):
        u = Thor(stub)
        u.put_core(rel, core_text('m'), 9)
        mutate(u)
        st, ran, _ = u.start()
        check(st['result'] == expect and ran is None, what)

    fresh(lambda u: u.disk.__setitem__(CORE, u.disk[CORE].replace(b'"m"', b'"n"')), 'tampered', 'one changed core byte refused')
    fresh(lambda u: u.disk.__setitem__(SIG, u.disk[SIG].replace(b'version=0.11.0-beta', b'version=0.11.1-beta')),
          'tampered', 'changed signed header refused')
    fresh(lambda u: u.disk.__setitem__(SIG, cs.sign_core(other, u.disk[CORE], 9, '0.11.0-beta', 1, DATE)),
          'unknown_key', 'core signed by another key refused (unknown key)')
    forged = cs.sign_core(other, core_text('m'), 9, '0.11.0-beta', 1, DATE).replace(
        b'key-id=' + cs.key_id(cs.raw_public(other)).encode(), b'key-id=' + cs.key_id(cs.raw_public(rel)).encode())
    fresh(lambda u: u.disk.__setitem__(SIG, forged), 'tampered', 'another key\'s signature under our key id refused')
    fresh(lambda u: u.disk.pop(SIG), 'missing', 'missing signature: nothing runs')
    fresh(lambda u: u.disk.pop(CORE), 'missing', 'missing core: nothing runs')
    fresh(lambda u: u.disk.__setitem__(SIG, u.disk[SIG] + b'x'), 'bad_format', 'trailing bytes after the signature refused')
    fresh(lambda u: u.disk.__setitem__(CORE, u.disk[CORE].replace(b'\n', b'\r\n')), 'bad_format', 'CRLF core refused (no newline rewriting)')
    fresh(lambda u: u.disk.__setitem__(CORE, b'\x1bLua' + u.disk[CORE]), 'bad_format', 'bytecode refused')
    fresh(lambda u: u.disk.__setitem__(CORE, b'-' * 524289), 'bad_format', 'oversized core refused without running')
    fresh(lambda u: u.put_core(rel, core_text('m'), 9, min_stub=2), 'stub_old', 'core needing a newer loader refused')
    fresh(lambda u: [u.disk.__setitem__(STATE + 'journal_a.txt', b'junk\n'), u.disk.__setitem__(STATE + 'journal_b.txt', b'junk\n')],
          'state_corrupt', 'corrupt rollback journal: fail closed, no silent reset')
    fresh(lambda u: u.readonly.add(STATE), 'state_unwritable', 'journal cannot be saved: core does not run')

    u = Thor(stub)
    u.put_core(rel, core_text('m'), 9)
    u.start()
    good = next(k for k in u.disk if k.startswith(STATE + 'journal'))
    other_slot = STATE + ('journal_b.txt' if good.endswith('a.txt') else 'journal_a.txt')
    u.disk[other_slot] = b'torn write'
    check(u.start()[0]['result'] == 'ok', 'one torn journal slot: the valid slot is used')

    u = Thor(stub)
    u.put_core(rel, core_text('err', 'error("boom")\n'), 9)
    st, ran, _ = u.start()
    check(st['result'] == 'core_failed' and ran == 1, 'signed core that errors at start: reported as core_failed')
    u = Thor(stub)
    u.put_core(rel, b'this is not lua (\n', 9)
    check(u.start()[0]['result'] == 'compile_failed', 'signed core that does not compile: compile_failed')

    # key rotation with the recovery key
    u = Thor(stub)
    u.put_core(rel, core_text('m'), 9)
    u.start()
    u.disk[POLICY] = cs.sign_policy(rel, 2, cs.raw_public(rel2), 20, DATE)       # signed by the RELEASE key
    u.put_core(rel2, core_text('k2'), 20)
    check(u.start()[0]['result'] == 'unknown_key', 'policy signed by a release key is ignored')
    u.disk[POLICY] = cs.sign_policy(rec, 2, cs.raw_public(rel2), 20, DATE)
    st, ran, tag = u.start()
    check(st['result'] == 'ok' and tag == 'k2' and st['epoch'] == 2, 'recovery-signed policy: the new release key is accepted')
    check(STATE + 'policy.txt' in u.disk, 'accepted policy kept privately')
    del u.disk[POLICY]
    u.put_core(rel, core_text('oldkey'), 30)
    check(u.start()[0]['result'] == 'unknown_key', 'after rotation the old key stays revoked, even with the staged policy gone')
    u.put_core(rel2, core_text('k2b'), 19)
    check(u.start()[0]['result'] == 'rollback', 'below the new policy\'s minimum sequence refused')
    u.disk[POLICY] = cs.sign_policy(rec, 1, cs.raw_public(rel), 1, DATE)
    u.put_core(rel, core_text('back'), 40)
    check(u.start()[0]['result'] == 'unknown_key', 'an older-epoch policy cannot bring the old key back')

    status_text = Thor(stub)
    status_text.put_core(rel, core_text('m'), 9)
    st, _, _ = status_text.start()
    check(set(st) <= {'contract', 'stub', 'result', 'version', 'sequence', 'keyId', 'epoch', 'detail', 'wallTime'}
          and st['contract'] == 'ffxii.loader.v1' and st['stub'] == 1, 'status receipt has only the fixed fields')
    print(f'{ok}/{ok} PASS. Signed loader stub; throwaway keys; simulated files; the stub never touched game memory.')


if __name__ == '__main__':
    main()
