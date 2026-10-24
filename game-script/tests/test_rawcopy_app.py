"""The app's copy job (app/src/.../data/CopyJob.java) against the real game script's raw copy (ThorCompanion-core.lua
0.15.0 under lupa, Lua 5.4 and 5.5), on a synthetic archive named like the app's recipe (no game data). The Java
side runs as a fresh JVM for every step (app/tests/CopyJobDriver.java), the way an app restart would.
Checks: header job -> data job by directory row and stored size -> one job per mod config, a missing one just absent
-> DONE, request removed; every copied entry inflates to its original bytes and nothing outside the recipe is asked
for; the app's heartbeat pauses and resumes the copy; a game update between the header and data jobs stops the build
with the game script's reason; keepOnlyThis leaves only the latest set.
Needs a JDK (JDK or JAVA_HOME, or javac on PATH).

Usage: python test_rawcopy_app.py
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

import lupa.lua54
import lupa.lua55

sys.path.insert(0, str(Path(__file__).parent))
from test_rawcopy_family import World, make_archive, CONFIG  # noqa: E402

APP = Path(__file__).resolve().parents[2] / 'app'
RECIPE_NAMES = ('ps2data/image/ff12/us/bin/planmapname.bin',
                'ps2data/image/ff12/test_battle/us/binaryfile/menu_command.bin',
                'ps2data/image/ff12/myoshiok/us/worldmap/menumap_sub01.bin',
                'x/not_in_recipe.bin',
                'ps2data/image/ff12/myoshiok/us/worldmap/menumap_sub02.bin')
IN_RECIPE = [n for n in RECIPE_NAMES if not n.startswith('x/')]


def jdk_bin():
    for home in (os.environ.get('JDK'), os.environ.get('JAVA_HOME'), 'C:/Program Files/Eclipse Adoptium/jdk-17.0.16.8-hotspot'):
        if home and (Path(home) / 'bin').is_dir():
            return Path(home) / 'bin'
    j = shutil.which('javac')
    return Path(j).parent if j else None


class App:
    def __init__(self, classes, java, base):
        self.cmd, self.base = [str(java), '-cp', str(classes), 'local.thor.ffxiicompanion.data.CopyJobDriver'], base

    def __call__(self, *args):
        r = subprocess.run(self.cmd + [args[0], str(self.base)] + [str(a) for a in args[1:]], capture_output=True, text=True)
        if r.returncode:
            raise AssertionError(r.stderr)
        return json.loads(r.stdout)


def setup(tmp, names=RECIPE_NAMES):
    (tmp / 'A' / 'x64' / 'scripts' / 'config' / 'TheInsurgentsDescriptiveInventoryConfig').mkdir(parents=True, exist_ok=True)
    head, stored = make_archive(tmp / 'A' / 'FFXII_TZA.vbf', names=names)
    cfg = ('return {\r\n  -- synthetic config\r\n}\r\n' * 50).encode()
    (tmp / 'A' / 'x64' / CONFIG).write_bytes(cfg)                       # config 1 only; 2 and 3 absent
    (tmp / 'D' / 'FFXII_companion' / 'live').mkdir(parents=True, exist_ok=True)
    return head, stored, cfg


def run(w, app, until, steps=60, beat=True):
    seen = []
    for _ in range(steps):
        if beat:
            w.alive()
        w.drive(4000)
        st = w.snapshot()['rawcopy']
        if st.get('job') and st['job'] not in seen:
            seen.append(st['job'])
        r = app('advance')
        if r['phase'] in until:
            return r, seen
    raise AssertionError('copy job did not finish: ' + json.dumps(r))


def originals():
    """Each entry's original (inflated) bytes, from the synthetic generator's own record."""
    import random
    rnd = random.Random(5)
    data = [bytes(rnd.getrandbits(8) for _ in range(70000)), bytes(range(256)) * 300,
            bytes(rnd.getrandbits(8) for _ in range(65536 * 2))] + [bytes([i]) * 1000 for i in range(2)]
    return dict(zip(RECIPE_NAMES, data))


def run_suite(runtime, classes, java):
    ok = 0
    ver = runtime.LuaRuntime().eval('_VERSION')

    # 1. a whole build's copy: header, data by row, configs one by one (2 and 3 missing), DONE
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        head, stored, cfg = setup(tmp)
        base = tmp / 'D' / 'FFXII_companion'
        app = App(classes, java, base)
        w = World(runtime, tmp)
        r = app('start', 'g1')
        assert r['phase'] == 'HEADER' and r['job'] == 'hdr-g1', r
        r, seen = run(w, app, ('DONE', 'FAILED'))
        assert r['phase'] == 'DONE' and r['error'] is None, r
        assert seen == ['hdr-g1', 'dat-g1', 'cf1-g1', 'cf2-g1', 'cf3-g1'], seen
        req = (base / 'raw' / 'dat-g1').exists() and not (base / 'raw' / 'request.txt').exists()
        assert req, 'request left behind'
        ok += 1
        print(f'PASS ({ver}) header job -> data job by row/size -> one job per config (2 and 3 missing = absent) -> DONE; '
              'request removed')

        got = app('inputs')
        want = originals()
        assert sorted(got['entries']) == sorted(IN_RECIPE), got['entries']
        for n in IN_RECIPE:
            assert got['entries'][n] == hashlib.sha256(want[n]).hexdigest(), n
        assert got['configs'] == {CONFIG: hashlib.sha256(cfg).hexdigest()}, got['configs']
        ok += 1
        print(f'PASS ({ver}) RawInputs: the 4 recipe entries inflate to their original bytes, the other entry was never '
              'asked for; config 1 exact, 2 and 3 absent')

        w2 = World(runtime, tmp)                                            # next game start: nothing to do
        w2.alive()
        w2.drive(6000)
        assert w2.snapshot()['rawcopy']['status'] == 'idle'
        ok += 1
        print(f'PASS ({ver}) after DONE the next game start copies nothing (no request)')

        # keepOnlyThis: a second build's copy, then the first one's files go
        r = app('start', 'g2')
        r, _ = run(w2, app, ('DONE', 'FAILED'))
        assert r['phase'] == 'DONE'
        app('keep')
        left = sorted(p.name for p in (base / 'raw').iterdir() if p.is_dir())
        assert left == ['cf1-g2', 'cf2-g2', 'cf3-g2', 'dat-g2', 'hdr-g2'], left
        ok += 1
        print(f'PASS ({ver}) keepOnlyThis: only the latest build\'s copied files remain')

    # 2. heartbeat from the app: a stale one pauses the copy, a fresh one resumes it
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        setup(tmp)
        base = tmp / 'D' / 'FFXII_companion'
        app = App(classes, java, base)
        w = World(runtime, tmp)
        app('start', 'hb')
        app('beat', w.now())
        w.drive(3000)
        r, _ = run(w, app, ('DATA',), beat=False)
        app('beat', w.now() - 60)
        w.drive(5000)
        st = w.snapshot()['rawcopy']
        assert st.get('paused') == 'app_away' and st['job'] == 'dat-hb', st
        before = st['bytes']
        w.drive(5000)
        assert w.snapshot()['rawcopy']['bytes'] == before
        app('beat', w.now())
        w.drive(5000)
        st = w.snapshot()['rawcopy']
        assert st.get('paused') is None and (st['bytes'] > before or st['item'] > 1 or st['status'] == 'done'), st
        ok += 1
        print(f'PASS ({ver}) the app\'s heartbeat: stale -> paused "app_away" (no bytes), fresh -> copying again')

    # 3. game update between the header and data jobs: the game script refuses, the build stops with its reason
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        setup(tmp)
        base = tmp / 'D' / 'FFXII_companion'
        app = App(classes, java, base)
        w = World(runtime, tmp)
        app('start', 'up')
        r, _ = run(w, app, ('DATA',))
        w.close()
        setup(tmp, names=RECIPE_NAMES[1:] + RECIPE_NAMES[:1])              # "updated" archive: entries moved
        w = World(runtime, tmp)
        r, _ = run(w, app, ('DONE', 'FAILED'))
        assert r['phase'] == 'FAILED' and 'entry_mismatch' in r['error'], r
        assert not (base / 'raw' / 'request.txt').exists()
        ok += 1
        print(f'PASS ({ver}) archive changed after the header job: FAILED "{r["error"]}", request removed')
        w.close()
    return ok


def main():
    jb = jdk_bin()
    if not jb:
        print('SKIP test_rawcopy_app: no JDK (set JDK or JAVA_HOME)')
        return
    with tempfile.TemporaryDirectory() as cd:
        classes = Path(cd)
        src = sorted(str(p) for p in (APP / 'src' / 'local' / 'thor' / 'ffxiicompanion' / 'data').glob('*.java'))
        subprocess.run([str(jb / 'javac'), '-source', '11', '-target', '11', '-Xlint:-options', '-encoding', 'UTF-8',
                        '-d', str(classes)] + src + [str(APP / 'tests' / 'CopyJobDriver.java')], check=True)
        n = run_suite(lupa.lua54, classes, jb / 'java') + run_suite(lupa.lua55, classes, jb / 'java')
    print(f'{n}/12 PASS. The app\'s copy job against the real game script; synthetic archive; Lua 5.4 and 5.5; no game data.')


if __name__ == '__main__':
    main()
