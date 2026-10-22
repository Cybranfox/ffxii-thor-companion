"""Check a game script's signature yourself, outside the game.

    python scripts/verify_core.py <ThorCompanion-core.lua> <ThorCompanion-core.sig> [public key hex or .pub file]

With no key argument it uses game-script/keys/release-epoch1.pub from this repository. Compare that key's SHA-256
fingerprint with the one in SECURITY.md (from a copy of this repository you trust); a key sitting next to a download
proves nothing. Needs the `cryptography` package.
"""
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import core_signing as cs  # noqa: E402


def main():
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    core, env = Path(sys.argv[1]).read_bytes(), Path(sys.argv[2]).read_bytes()
    key = sys.argv[3] if len(sys.argv) == 4 else str(Path(__file__).resolve().parents[1] / 'game-script/keys/release-epoch1.pub')
    pub = Path(key).read_text().strip() if Path(key).is_file() else key.strip()
    print(f'public key fingerprint (SHA-256): {hashlib.sha256(bytes.fromhex(pub)).hexdigest()}')
    try:
        info = cs.verify_core(pub, core, env)
    except ValueError as e:
        sys.exit(f'NOT VALID: {e}')
    print(f"VALID: version {info['version']}, sequence {info['sequence']}, signed {info['date']}, "
          f'core SHA-256 {hashlib.sha256(core).hexdigest()}')


if __name__ == '__main__':
    main()
