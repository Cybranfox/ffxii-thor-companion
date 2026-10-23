"""FFXII Thor Companion data builder: makes the companion's game-data pack from YOUR OWN copy of the game (and, when
installed, your optional text mods). Nothing here is shipped with the app; the pack is for your personal use only.

    python build_data_pack.py [--game "<FINAL FANTASY XII THE ZODIAC AGE folder>"] [--out <folder>] [--no-map]

Output: <out>/FFXII_companion/ with
  reference/reference.json  item/gear/magick names (+ descriptions and bestiary from optional mods), gambit names
  reference/boards.json     the 12 licence boards and licence names
  reference/areas.json      area names
  atlas/                    map sheets (grayscale masks) and their index
  BUILD_INFO.json           builder version, what was included, source file hashes (no personal paths)
Copy the FFXII_companion folder to the Thor's Download folder (merge with the existing one).

Reads the game's FFXII_TZA.vbf (read-only, in memory) and, when present, the text files that Dynamic Equipment
Description / Descriptive Inventory and Foedex Clan Primer write under x64/scripts/config. No network access.
"""
import argparse
import json
import os
import shutil
import sys
import tempfile
from pathlib import Path

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))

BUILDER_VERSION = '0.14.0-beta'
GAME_FOLDER = 'FINAL FANTASY XII THE ZODIAC AGE'


def steam_libraries():
    """Steam library folders from the usual install places and libraryfolders.vdf (Windows)."""
    roots = []
    for base in (os.environ.get('ProgramFiles(x86)'), os.environ.get('ProgramFiles')):
        if base:
            roots.append(Path(base) / 'Steam')
    libs = []
    for root in roots:
        vdf = root / 'steamapps' / 'libraryfolders.vdf'
        libs.append(root)
        if vdf.is_file():
            for line in vdf.read_text(encoding='utf-8', errors='replace').splitlines():
                parts = line.strip().split('"')
                if len(parts) >= 4 and parts[1] == 'path':
                    libs.append(Path(parts[3].replace('\\\\', '\\')))
    return libs


def find_game(given):
    if given:
        g = Path(given)
        return g if (g / 'FFXII_TZA.vbf').is_file() else None
    for lib in steam_libraries():
        g = lib / 'steamapps' / 'common' / GAME_FOLDER
        if (g / 'FFXII_TZA.vbf').is_file():
            return g
    return None


def merge_reference(vanilla, mods):
    """Vanilla names everywhere; a mod's richer entry replaces the vanilla one with the same id."""
    ref = dict(vanilla)
    by_id = {g['id']: g for g in vanilla['gear']}
    for g in mods['gear']:
        by_id[g['id']] = g
    ref['gear'] = [by_id[k] for k in sorted(by_id)]
    ref['bestiary'] = mods['bestiary']
    ref['tips'] = mods['tips']
    ref['note'] = 'Built by the FFXII Thor Companion data builder from your own game files. Personal use only.'
    ref['sources'] = dict(vanilla.get('sources') or {}, **mods['sources'])
    return ref


def write_json(path, doc):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(doc, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')


def build(game, out, with_map=True, log=print):
    import vanilla_fallback
    import reference_pack
    pack = Path(out) / 'FFXII_companion'
    log(f'Reading {game / "FFXII_TZA.vbf"} ...')
    v = vanilla_fallback.build(game)
    mods = reference_pack.mod_text(game / 'x64')
    ref = merge_reference(v['reference'], mods)
    write_json(pack / 'reference' / 'reference.json', ref)
    write_json(pack / 'reference' / 'boards.json', v['boards'])
    write_json(pack / 'reference' / 'areas.json', {'format': 'ffxii-companion-areas-1', 'names': v['areas']})
    objectives = None                          # 0.14.0: story hint + hunt journal (optional: skipped if unreadable)
    try:
        import objectives_pack
        import vbf_extract
        f, index, blocks = vbf_extract.open_vbf(str(game / 'FFXII_TZA.vbf'))
        try:
            objectives = objectives_pack.build(lambda p: vbf_extract.extract(f, index[p], blocks))
        finally:
            f.close()
        write_json(pack / 'reference' / 'objectives.json', objectives)
    except (KeyError, ValueError, OSError) as e:
        log(f'Story hint and hunts skipped: {e}')
    atlas = 0
    if with_map:
        import mapatlas_pack
        log('Building map sheets (this takes a minute) ...')
        with tempfile.TemporaryDirectory() as tmp:
            mapatlas_pack.main(str(game / 'FFXII_TZA.vbf'), tmp)
            dest = pack / 'atlas'
            if dest.exists():
                shutil.rmtree(dest)
            shutil.copytree(tmp, dest)
        atlas = sum(1 for p in (pack / 'atlas').glob('*.png'))
    info = {
        'builder': BUILDER_VERSION,
        'atlasFormat': 2 if atlas else None,     # 0.15.0: pixel hashes + atlas/files.json; needs app 0.15.0 or newer
        'included': {
            'names': len(ref['gear']), 'gambitConditions': len(ref.get('gambitConditions') or {}),
            'licenceBoards': len(v['boards']['boards']), 'areas': len(v['areas']), 'mapSheets': atlas,
            'gearDescriptions': sum(1 for g in ref['gear'] if g.get('lines')),
            'bestiary': len(ref['bestiary']), 'tips': len(ref['tips']),
            'storyHints': len(objectives['hints']) if objectives else 0,
            'hunts': len(objectives['hunts']) if objectives else 0,
        },
        'optionalMods': {
            'Dynamic Equipment Description / Descriptive Inventory (gear descriptions)': 'gear' in mods['sources'],
            'Foedex Clan Primer Text Replacer (bestiary)': 'bestiary' in mods['sources'],
        },
        'sources': {k: x['sha256'] for k, x in sorted(v['sources'].items())},
        'note': 'Made from your own game files for your personal use. Do not share this folder.',
    }
    write_json(pack / 'BUILD_INFO.json', info)
    return pack, info


def main(argv=None):
    ap = argparse.ArgumentParser(description='Build the FFXII Thor Companion data pack from your own game files.')
    ap.add_argument('--game', help=f'the "{GAME_FOLDER}" folder (found automatically in Steam libraries)')
    ap.add_argument('--out', default='.', help='where to create the FFXII_companion folder (default: here)')
    ap.add_argument('--no-map', action='store_true', help='skip the map sheets')
    a = ap.parse_args(argv)
    game = find_game(a.game)
    if game is None:
        print('Could not find FINAL FANTASY XII THE ZODIAC AGE (FFXII_TZA.vbf). Pass its folder with --game "<folder>".')
        return 2
    pack, info = build(game, Path(a.out), with_map=not a.no_map)
    print(f'\nDone: {pack}')
    for k, n in info['included'].items():
        print(f'  {k:18} {n}')
    for name, present in info['optionalMods'].items():
        print(f'  {"found" if present else "not installed (optional)":24} {name}')
    print('\nCopy the FFXII_companion folder into the Thor\'s Download folder (merge with the existing one).')
    return 0


if __name__ == '__main__':
    sys.exit(main())
