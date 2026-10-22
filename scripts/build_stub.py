"""Assembles the installed loader stub (ThorCompanion.lua) from game-script/loader/stub.lua, the pinned public keys and the
verifier. The output is the only file install.cmd puts in the game's scripts folder.

    python scripts/build_stub.py --out <ThorCompanion.lua>                      # pins from game-script/keys/
    python scripts/build_stub.py --out <file> --release-key <hex> --recovery-key <hex> --min-sequence 1   # tests
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOADER = ROOT / 'game-script' / 'loader'
KEYS = ROOT / 'game-script' / 'keys'


def read_key(path):
    h = path.read_text(encoding='ascii').strip().lower()
    if not re.fullmatch(r'[0-9a-f]{64}', h):
        sys.exit(f'{path}: expected 64 hex characters (a raw Ed25519 public key)')
    return h


def build(release_key, recovery_key, min_sequence):
    if release_key == recovery_key:
        sys.exit('the release and recovery keys must be different')
    stub = (LOADER / 'stub.lua').read_text(encoding='utf-8')
    verify = (LOADER / 'verify.lua').read_text(encoding='utf-8')
    pins = ('local PINS = {\n'
            f'  release_key = "{release_key}",   --epoch 1 release key (signs cores)\n'
            f'  recovery_key = "{recovery_key}",  --recovery key (signs key-rotation policies only)\n'
            f'  min_sequence = {int(min_sequence)},\n'
            '}')
    assert stub.count('--@@PINS@@') == 1 and stub.count('--@@VERIFY@@') == 1
    out = stub.replace('--@@PINS@@', pins).replace('--@@VERIFY@@', verify.rstrip('\n'))
    out = out.replace('\r\n', '\n')
    if not out.endswith('\n'):
        out += '\n'
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', required=True)
    ap.add_argument('--release-key')
    ap.add_argument('--recovery-key')
    ap.add_argument('--min-sequence', type=int)
    a = ap.parse_args()
    rel = a.release_key or read_key(KEYS / 'release-epoch1.pub')
    rec = a.recovery_key or read_key(KEYS / 'recovery.pub')
    seq = a.min_sequence if a.min_sequence is not None else int((KEYS / 'min-sequence.txt').read_text().strip())
    Path(a.out).write_text(build(rel.lower(), rec.lower(), seq), encoding='utf-8', newline='\n')
    print(f'stub -> {a.out}')


if __name__ == '__main__':
    main()
