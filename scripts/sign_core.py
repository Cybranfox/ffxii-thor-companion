"""Signs a prepared game script for a release. Run it yourself; it asks for the release-key passphrase.

    python scripts/sign_core.py <version>          e.g. python scripts/sign_core.py 0.11.0-beta

It shows exactly what it is about to sign (version, sequence, SHA-256 of the script) and asks you to confirm, signs it,
then checks the signature twice (the `cryptography` library and the pure-Lua verifier the game uses) before writing
dist/<version>/signing/ThorCompanion-core.sig. Then run the release script again to finish the build.
"""
import getpass
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import core_signing as cs  # noqa: E402

RELEASE_PEM = Path.home() / '.ffxii-companion' / 'core-signing' / 'release-epoch1.pem'


def lua_verify(public_hex, core, envelope):
    from lupa.lua54 import LuaRuntime
    v = LuaRuntime(encoding=None).execute((ROOT / 'game-script' / 'loader' / 'verify.lua').read_text(encoding='utf-8'))
    return v.verify(bytes.fromhex(public_hex), envelope[:-129] + core, bytes.fromhex(envelope[-129:-1].decode()))


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sdir = ROOT / 'dist' / sys.argv[1] / 'signing'
    req = json.loads((sdir / 'request.json').read_text(encoding='utf-8'))
    core = (sdir / 'ThorCompanion-core.lua').read_bytes()
    sig_path = sdir / 'ThorCompanion-core.sig'
    if sig_path.exists():
        sys.exit(f'{sig_path} already exists; nothing to do.')
    digest = hashlib.sha256(core).hexdigest()
    if digest != req['sha256']:
        sys.exit('The prepared script changed since the release script made the request. Run the release script again.')
    pinned = (ROOT / 'game-script' / 'keys' / 'release-epoch1.pub').read_text().strip()
    print(f"About to sign the game script for {req['version']}\n  sequence {req['sequence']}\n  size {len(core)} bytes"
          f"\n  SHA-256 {digest}\n  (the release script printed the same SHA-256)")
    if input('Sign it? Type yes: ').strip().lower() != 'yes':
        sys.exit('Not signed.')
    key = cs.load_private(RELEASE_PEM, getpass.getpass('Release key passphrase (not shown): ').encode('utf-8'))
    if cs.raw_public(key) != pinned:
        sys.exit('This release key does not match game-script/keys/release-epoch1.pub. Not signed.')
    env = cs.sign_core(key, core, req['sequence'], req['version'], req['min_stub'], req['date'])
    cs.verify_core(pinned, core, env)
    if lua_verify(pinned, core, env) is not True:
        sys.exit('The game\'s Lua verifier did not accept the signature. Not written.')
    with open(sig_path, 'xb') as f:
        f.write(env)
    print(f'Signed and double-checked: {sig_path}\nNow run the release script again to finish.')


if __name__ == '__main__':
    main()
