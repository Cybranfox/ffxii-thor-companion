"""The loader's pure-Lua Ed25519/SHA-512 verifier (game-script/loader/verify.lua) in Lua 5.4 and 5.5: the five RFC 8032 section 7.1
vectors, SHA-512 against Python, 64 random valid signatures from the `cryptography` package, and rejection of every
single-bit change to a signature, a wrong key, wrong messages and malformed inputs. Needs: lupa, cryptography.
"""
import hashlib
import json
import os
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
import lupa.lua54
import lupa.lua55

LOADER = Path(__file__).resolve().parents[1] / 'loader'


def main(runtime):
    lua = runtime.LuaRuntime(encoding=None)
    version = lua.eval('_VERSION')
    v = lua.execute((LOADER / 'verify.lua').read_text(encoding='utf-8'))
    ok = 0
    vec = json.loads((LOADER / 'rfc8032_vectors.json').read_text(encoding='utf-8'))['vectors']
    for t in vec:
        pk, msg, sig = (bytes.fromhex(t[k]) for k in ('public', 'message', 'signature'))
        assert v.verify(pk, msg, sig) is True, t
        assert v.verify(pk, msg + b'x', sig) is False
        ok += 2
    for n in (0, 1, 111, 112, 127, 128, 129, 1000):
        m = os.urandom(n)
        assert bytes(v.sha512(m)) == hashlib.sha512(m).digest(), n
        ok += 1
    for i in range(64):
        k = Ed25519PrivateKey.generate()
        pk = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        msg = os.urandom(i * 7)
        sig = k.sign(msg)
        assert v.verify(pk, msg, sig) is True
        other = Ed25519PrivateKey.generate().public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        assert v.verify(other, msg, sig) is False
        ok += 2
    k = Ed25519PrivateKey.generate()
    pk = k.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    msg = b'FFXII companion core'
    sig = k.sign(msg)
    for bit in range(512):
        bad = bytearray(sig)
        bad[bit // 8] ^= 1 << (bit % 8)
        assert v.verify(pk, msg, bytes(bad)) is False, bit
        ok += 1
    for bad in (sig[:63], sig + b'\0', b'', b'\0' * 64):
        assert v.verify(pk, msg, bad) is False
        ok += 1
    for badpk in (pk[:31], b'\0' * 32, b'\x01' + b'\0' * 31):
        assert v.verify(badpk, msg, sig) is False
        ok += 1
    print(f'{ok}/{ok} PASS ({version.decode()}). Pure-Lua Ed25519 verify: 5 RFC 8032 vectors, SHA-512, 64 random keys, '
          '512 bit flips, malformed inputs.')


def other_lua_refused():
    lua = lupa.lua54.LuaRuntime(encoding=None)
    lua.execute('_VERSION = "Lua 5.3"')
    try:
        lua.execute((LOADER / 'verify.lua').read_text(encoding='utf-8'))
    except lupa.lua54.LuaError as e:
        assert b'requires Lua 5.4 or 5.5' in str(e).encode() or 'requires Lua 5.4 or 5.5' in str(e), e
        print('1/1 PASS. Any other Lua version is refused before the verifier is built.')
        return
    raise AssertionError('verify.lua loaded under a fake Lua 5.3')


if __name__ == '__main__':
    for rt in (lupa.lua54, lupa.lua55):
        main(rt)
    other_lua_refused()
