"""Release for the FFXII Thor Companion: the release script prepares, YOU sign the game script, the release script finishes.

    python scripts/release.py 0.11.0-beta            # tests, builds, prepares dist/<v>/signing/, then stops
    python scripts/sign_core.py 0.11.0-beta          # you: passphrase, signs the prepared game script
    python scripts/release.py 0.11.0-beta            # again: verifies the signature, builds the APK and the zips
    python scripts/release.py 0.11.0-beta --publish  # ...and commits, tags, pushes and creates the GitHub pre-release


    python scripts/release.py 0.9.1-beta            # bump versions, test, build, package into dist/<version>/
    python scripts/release.py 0.9.1-beta --publish  # ...then commit, tag v<version>, push, create the GitHub release

Signing: the permanent release key from ~/.ffxii-companion/release.env (RELEASE_KEYSTORE, RELEASE_KEYSTORE_PASS,
RELEASE_KEY_ALIAS). It never enters the repo. Without it the script stops: a debug-signed APK cannot update installs.
Private real-game test inputs are used when their environment variables are set (see app/tests/run_tests.sh and
game-script/tests/run_tests.sh); without them those tests skip, as on CI.
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NAME = 'FFXII-Thor-Companion'
BASH = shutil.which('bash') or r'C:\Program Files\Git\bin\bash.exe'


def run(cmd, **kw):
    print('>', ' '.join(str(c) for c in cmd), flush=True)
    subprocess.run(cmd, check=True, cwd=kw.pop('cwd', ROOT), **kw)


def sub(path, pattern, repl, count=1):
    p = ROOT / path
    t = p.read_text(encoding='utf-8')
    t2, n = re.subn(pattern, repl, t, count=count)
    if n != count:
        sys.exit(f'version bump: pattern not found in {path}: {pattern}')
    p.write_text(t2, encoding='utf-8', newline='')


def bump(version):
    m = (ROOT / 'app/AndroidManifest.xml').read_text(encoding='utf-8')
    code = int(re.search(r'android:versionCode="(\d+)"', m).group(1))
    old = re.search(r'android:versionName="([^"]+)"', m).group(1)
    if old != version:
        code += 1
    sub('app/AndroidManifest.xml', r'android:versionCode="\d+"', f'android:versionCode="{code}"')
    sub('app/AndroidManifest.xml', r'android:versionName="[^"]+"', f'android:versionName="{version}"')
    sub('game-script/core/ThorCompanion-core.lua', r'(local PRODUCER = \{ name = "ThorCompanion-lua", version = ")[^"]+(" \})', rf'\g<1>{version}\g<2>')
    sub('game-script/tests/test_reveal_family.py', r"(\['producer'\]\['version'\] == ')[^']+(')", rf'\g<1>{version}\g<2>')
    sub('builder/build_data_pack.py', r"BUILDER_VERSION = '[^']+'", f"BUILDER_VERSION = '{version}'")
    return code


def load_release_env():
    env = dict(os.environ)
    f = Path.home() / '.ffxii-companion' / 'release.env'
    if not f.is_file():
        sys.exit(f'release key settings not found: {f}')
    for line in f.read_text().splitlines():
        if '=' in line:
            k, v = line.split('=', 1)
            env[k.strip()] = v.strip()
    return env


def notes(version):
    log = (ROOT / 'CHANGELOG.md').read_text(encoding='utf-8')
    m = re.search(rf'^## {re.escape(version)}\b.*?$(.*?)(?=^## |\Z)', log, re.M | re.S)
    if not m:
        sys.exit(f'CHANGELOG.md has no "## {version}" section')
    return m.group(1).strip()


def sha256(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


SIGNING_KEYS = ROOT / 'game-script' / 'keys'


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def prepare_signing(version):
    """dist/<v>/signing/: the exact game script to sign and its request (sequence, version, date, hash)."""
    import datetime
    import json
    sys.path.insert(0, str(ROOT / 'scripts'))
    import core_signing as cs
    sdir = ROOT / 'dist' / version / 'signing'
    sdir.mkdir(parents=True, exist_ok=True)
    core = (ROOT / 'game-script/core/ThorCompanion-core.lua').read_bytes().replace(b'\r\n', b'\n')
    cs.check_core(core)
    digest = sha256_bytes(core)
    req_path = sdir / 'request.json'
    if req_path.exists():
        req = json.loads(req_path.read_text(encoding='utf-8'))
        if req['sha256'] == digest and (sdir / 'ThorCompanion-core.lua').read_bytes() == core:
            return sdir, req
        (sdir / 'ThorCompanion-core.sig').unlink(missing_ok=True)   # the script changed: the old signature is void
    last_file = SIGNING_KEYS / 'last-sequence.txt'
    last = int(last_file.read_text().strip()) if last_file.exists() else 0
    seq = max(last + 1, int((SIGNING_KEYS / 'min-sequence.txt').read_text().strip()))
    req = {'version': version, 'sequence': seq, 'min_stub': 1, 'date': datetime.date.today().isoformat(), 'sha256': digest}
    (sdir / 'ThorCompanion-core.lua').write_bytes(core)
    req_path.write_text(json.dumps(req, indent=1), encoding='utf-8')
    return sdir, req


def check_signature(sdir):
    sys.path.insert(0, str(ROOT / 'scripts'))
    import core_signing as cs
    import sign_core
    core, env = (sdir / 'ThorCompanion-core.lua').read_bytes(), (sdir / 'ThorCompanion-core.sig').read_bytes()
    pub = (SIGNING_KEYS / 'release-epoch1.pub').read_text().strip()
    info = cs.verify_core(pub, core, env)
    if sign_core.lua_verify(pub, core, env) is not True:
        sys.exit("The game's Lua verifier rejects this signature.")
    return info


def package(version, stage):
    dist = ROOT / 'dist' / version
    for old in dist.glob('*'):
        if old.is_file():
            old.unlink()
    apk = dist / f'{NAME}-{version}.apk'
    shutil.copy(ROOT / 'app/build/ffxii-companion.apk', apk)
    names = zipfile.ZipFile(apk).namelist()
    want = {f'assets/install/{f}' for f in ('ThorCompanion.lua', 'ThorCompanion-core.lua', 'ThorCompanion-core.sig',
                                            'install.cmd', 'uninstall.cmd', 'README.txt')}
    if not want <= set(names) or any(chr(92) in n for n in names):   # chr(92) = backslash
        sys.exit(f'APK assets wrong (Android needs forward slashes): {[n for n in names if "assets" in n]}')
    gs = dist / f'{NAME}-game-script-{version}.zip'
    with zipfile.ZipFile(gs, 'w', zipfile.ZIP_DEFLATED) as z:
        for f in sorted(stage.iterdir()):
            z.write(f, f.name)
    bd = dist / f'{NAME}-data-builder-{version}.zip'
    with zipfile.ZipFile(bd, 'w', zipfile.ZIP_DEFLATED) as z:
        for f in sorted((ROOT / 'builder').iterdir()):
            if f.is_file() and f.suffix in ('.py', '.txt', '.cmd'):
                z.write(f, f'data-builder/{f.name}')
        z.write(ROOT / 'LICENSE', 'data-builder/LICENSE')
    (dist / 'SHA256SUMS.txt').write_text(''.join(f'{sha256(p)}  {p.name}\n' for p in (apk, gs, bd)), encoding='utf-8',
                                          newline='\n')   # LF, so `sha256sum -c` works everywhere
    (dist / 'RELEASE_NOTES.md').write_text(notes(version) + '\n', encoding='utf-8')
    return dist


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('version', help='e.g. 0.9.1-beta')
    ap.add_argument('--publish', action='store_true', help='commit, tag, push and create the GitHub pre-release')
    a = ap.parse_args()
    if not re.fullmatch(r'\d+\.\d+\.\d+(-[a-z0-9.]+)?', a.version):
        sys.exit('version must look like 0.9.1-beta')
    notes(a.version)                       # fail early if the changelog section is missing
    env = load_release_env()
    code = bump(a.version)
    run([BASH, 'app/tests/run_tests.sh'])
    run([BASH, 'game-script/tests/run_tests.sh'])
    run([sys.executable, '-B', '-c', 'import sys; sys.path.insert(0, "builder"); import build_data_pack'])
    sdir, req = prepare_signing(a.version)
    if not (sdir / 'ThorCompanion-core.sig').exists():
        print(f"\nReady to sign the game script for {a.version}: sequence {req['sequence']}, SHA-256 {req['sha256']}"
              f"\nRun this yourself (it asks for your release-key passphrase):\n  python scripts/sign_core.py {a.version}"
              f"\nthen run this release command again.")
        return
    info = check_signature(sdir)
    if 'KEY-FINGERPRINT-PENDING' in (ROOT / 'SECURITY.md').read_text(encoding='utf-8'):
        sys.exit('SECURITY.md still shows placeholder key fingerprints; fill in the real ones first.')
    stage = ROOT / 'dist' / a.version / 'stage'
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    run([sys.executable, '-B', 'scripts/build_stub.py', '--out', str(stage / 'ThorCompanion.lua')])
    for f in ('ThorCompanion-core.lua', 'ThorCompanion-core.sig'):
        shutil.copy(sdir / f, stage / f)
    if (SIGNING_KEYS / 'policy.txt').exists():
        shutil.copy(SIGNING_KEYS / 'policy.txt', stage / 'ThorCompanion-policy.txt')
    for f in ('install.cmd', 'uninstall.cmd', 'README.txt'):
        shutil.copy(ROOT / 'game-script/container' / f, stage / f)
    run([BASH, 'app/build.sh'], env=dict(env, INSTALL_STAGE=str(stage)))
    dist = package(a.version, stage)
    (SIGNING_KEYS / 'last-sequence.txt').write_text(f"{info['sequence']}\n", encoding='ascii', newline='\n')
    print(f'\nBuilt {a.version} (versionCode {code}, game script sequence {info["sequence"]}) in {dist}')
    print((dist / 'SHA256SUMS.txt').read_text())
    if a.publish:
        tag = f'v{a.version}'
        run(['git', 'add', '-A'])
        run(['git', 'commit', '-m', f'Release {a.version}'])
        run(['git', 'tag', '-a', tag, '-m', f'Release {a.version}'])
        run(['git', 'push', 'origin', 'HEAD', tag])
        run(['gh', 'release', 'create', tag, '--prerelease', '--title', f'{a.version}', '--notes-file',
             str(dist / 'RELEASE_NOTES.md')] + [str(p) for p in sorted(dist.iterdir()) if p.suffix in ('.apk', '.zip', '.txt')])


if __name__ == '__main__':
    main()
