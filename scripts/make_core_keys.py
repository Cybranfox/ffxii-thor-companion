"""Creates the two game-script signing keys, ONCE. Run it yourself in a terminal; it asks for two passphrases and never
prints or stores them.

    python scripts/make_core_keys.py

Writes:
  ~/.ffxii-companion/core-signing/release-epoch1.pem      release key (signs every game-script update), passphrase-encrypted
  ~/.ffxii-companion/RECOVERY-KEY-move-to-USB/recovery.pem recovery key (only for replacing a lost/leaked release key)
  game-script/keys/release-epoch1.pub, recovery.pub, min-sequence.txt   public halves, pinned into the loader stub

Then: copy the RECOVERY-KEY folder to two USB sticks kept in different places, check you can open it, and delete it
from this PC. Back up core-signing/ (encrypted) the same way. Losing BOTH the release key and the recovery key means every
user has to run install.cmd again; losing their passphrases is the same as losing the keys.
"""
import getpass
import hashlib
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import core_signing as cs  # noqa: E402

HOME = Path.home() / '.ffxii-companion'
RELEASE_PEM = HOME / 'core-signing' / 'release-epoch1.pem'
RECOVERY_PEM = HOME / 'RECOVERY-KEY-move-to-USB' / 'recovery.pem'
PUB = ROOT / 'game-script' / 'keys'


def ask(label):
    while True:
        a = getpass.getpass(f'{label} passphrase (12+ characters, not shown): ')
        if len(a) < 12:
            print('  too short, try again')
            continue
        if getpass.getpass(f'{label} passphrase again: ') != a:
            print('  they did not match, try again')
            continue
        return a.encode('utf-8')


def save(key, path, password):
    path.parent.mkdir(parents=True, exist_ok=True)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.BestAvailableEncryption(password))
    with open(path, 'xb') as f:   # never overwrites
        f.write(pem)


def main():
    for p in (RELEASE_PEM, RECOVERY_PEM, PUB / 'release-epoch1.pub', PUB / 'recovery.pub'):
        if p.exists():
            sys.exit(f'{p} already exists. Keys are made once; nothing was changed.')
    print('FFXII Thor Companion: creating the game-script signing keys.\n')
    rel_pw = ask('RELEASE key')
    rec_pw = ask('RECOVERY key')
    if rel_pw == rec_pw:
        sys.exit('Use a different passphrase for the recovery key. Nothing was created.')
    rel, rec = Ed25519PrivateKey.generate(), Ed25519PrivateKey.generate()
    save(rel, RELEASE_PEM, rel_pw)
    save(rec, RECOVERY_PEM, rec_pw)
    # prove both files open with their passphrases and sign
    for path, pw in ((RELEASE_PEM, rel_pw), (RECOVERY_PEM, rec_pw)):
        k = cs.load_private(path, pw)
        k.public_key().verify(k.sign(b'check'), b'check')
    PUB.mkdir(parents=True, exist_ok=True)
    (PUB / 'release-epoch1.pub').write_text(cs.raw_public(rel) + '\n', encoding='ascii', newline='\n')
    (PUB / 'recovery.pub').write_text(cs.raw_public(rec) + '\n', encoding='ascii', newline='\n')
    (PUB / 'min-sequence.txt').write_text('1\n', encoding='ascii', newline='\n')
    (RECOVERY_PEM.parent / 'README.txt').write_text(
        'FFXII Thor Companion RECOVERY key (passphrase-encrypted).\n'
        'Copy this folder to two USB sticks kept in different places, check one opens, then delete it from the PC.\n'
        'It is only needed if the release key is lost or leaked: it signs a key-rotation policy, never game scripts.\n',
        encoding='utf-8')
    for name, k in (('release', rel), ('recovery', rec)):
        raw = bytes.fromhex(cs.raw_public(k))
        print(f'{name:9} public key {raw.hex()}\n          fingerprint SHA-256 {hashlib.sha256(raw).hexdigest()}')
    print(f'\nRelease key:  {RELEASE_PEM}\nRecovery key: {RECOVERY_PEM}  <- move to USB now (see README.txt there)')
    print('Public keys written to game-script/keys/. Done.')


if __name__ == '__main__':
    main()
