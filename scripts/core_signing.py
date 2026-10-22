"""Signed-core envelope and key-rotation policy formats (Secure_Loader_Design, version 1), shared by the release flow,
the signing command and the tests. Needs the `cryptography` package.

Envelope (ThorCompanion-core.sig): seven header lines, each ending in LF, then the 128-hex Ed25519 signature + LF.
The signature covers the header lines (including the last LF) followed by the exact core bytes.
Policy (ThorCompanion-policy.txt): five header lines + signature, signed by the RECOVERY key.
"""
import re
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

VERSION_RE = re.compile(r'\d+\.\d+\.\d+(-[a-z0-9.]+)?')
MAX_CORE = 524288


def raw_public(key):
    """64 lowercase hex characters of a raw Ed25519 public key (from a private or public key object)."""
    if isinstance(key, Ed25519PrivateKey):
        key = key.public_key()
    return key.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw).hex()


def key_id(public_hex):
    return public_hex[:16]


def check_core(core):
    if not core or len(core) > MAX_CORE:
        raise ValueError('core must be 1..524288 bytes')
    if core.startswith(b'\xef\xbb\xbf') or b'\r' in core or b'\0' in core or not core.endswith(b'\n') or core[:1] == b'\x1b':
        raise ValueError('core must be LF text with a final LF, no BOM, CR, NUL or bytecode')


def header(sequence, version, min_stub, date, public_hex, length):
    if not VERSION_RE.fullmatch(version) or len(version) > 64:
        raise ValueError(f'bad version {version!r}')
    for n in (sequence, min_stub, length):
        if not (1 <= int(n) <= 2147483647):
            raise ValueError('numbers must be 1..2147483647')
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', date):
        raise ValueError('date must be YYYY-MM-DD')
    return (f'FFXII-CORE-SIG-V1\nsequence={int(sequence)}\nversion={version}\nmin-stub={int(min_stub)}\n'
            f'build-date={date}\nkey-id={key_id(public_hex)}\nlength={int(length)}\n').encode('ascii')


def sign_core(private_key, core, sequence, version, min_stub, date):
    check_core(core)
    h = header(sequence, version, min_stub, date, raw_public(private_key), len(core))
    return h + private_key.sign(h + core).hex().encode('ascii') + b'\n'


def verify_core(public_hex, core, envelope):
    """Returns the parsed header fields, or raises ValueError."""
    check_core(core)
    text = envelope.decode('ascii')
    m = re.fullmatch(r'FFXII-CORE-SIG-V1\nsequence=([1-9]\d*)\nversion=([0-9a-z.\-]+)\nmin-stub=([1-9]\d*)\n'
                     r'build-date=(\d{4}-\d{2}-\d{2})\nkey-id=([0-9a-f]{16})\nlength=([1-9]\d*)\n([0-9a-f]{128})\n', text)
    if not m or int(m.group(6)) != len(core) or m.group(5) != key_id(public_hex):
        raise ValueError('envelope format, length or key id mismatch')
    h = envelope[:-129]
    try:
        Ed25519PublicKey.from_public_bytes(bytes.fromhex(public_hex)).verify(bytes.fromhex(m.group(7)), h + core)
    except InvalidSignature as e:
        raise ValueError('signature does not verify') from e
    return {'sequence': int(m.group(1)), 'version': m.group(2), 'min_stub': int(m.group(3)), 'date': m.group(4)}


def sign_policy(recovery_key, epoch, release_public_hex, min_sequence, date):
    h = (f'FFXII-ROOT-POLICY-V1\nepoch={int(epoch)}\nrelease-key={release_public_hex}\nmin-sequence={int(min_sequence)}\n'
         f'build-date={date}\n').encode('ascii')
    return h + recovery_key.sign(h).hex().encode('ascii') + b'\n'


def load_private(path, password):
    key = serialization.load_pem_private_key(path.read_bytes(), password=password)
    if not isinstance(key, Ed25519PrivateKey):
        raise ValueError(f'{path} is not an Ed25519 private key')
    return key
