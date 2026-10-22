"""Builds the companion's offline REFERENCE bundle from data the user's mods themselves produce (read-only).

Sources (paths relative to the game's x64 folder):
  gear     scripts/config/TheInsurgentsDescriptiveInventoryConfig/<lang>.lua
           written at game start by Dynamic Equipment Description from the live, mod-patched battle pack
           (sections 13/14): {item index, marked-up description}. Name = first segment of the description.
  bestiary scripts/config/FoedexClanPrimerTextReplacerConfig/Bestiary/<lang>.lua
           Foedex Clan Primer Text Replacer + More Bestiary Pages: {entry, page, text}; entry name = preceding
           "--Name" comment. Pages hold per-location Drop / Steal / Poach / Monograph / Canopic Jar tables.
  tips     scripts/config/FoedexClanPrimerTextReplacerConfig/TravelersTips/<lang>.lua (same format)

This is REFERENCE data, not live state: it reflects the mods as configured when the source files were written.
Markup is reduced to plain text lines; per-line colour roles the app can style are kept as a leading tag.

  gambitConditions  names of gambit conditions 0x6000+n from the deployed (modded) battle_pack.bin via gambit_names.py
           (section 7 record +0x14 -> section 61 text); all 11 names read off the Thor's Gambits screens match.

Usage: reference_pack.py <game x64 dir> <out.json> [lang=us]
"""
import hashlib
import json

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

SEP = ' · '
ENTRY = re.compile(r'\{\s*(\d+)\s*,\s*(\d+)\s*,\s*\[\[\r?\n?(.*?)\]\]\s*\}', re.S)
GEAR = re.compile(r'^\s*\{(\d+),\s*"((?:[^"\\]|\\.)*)"\},?\s*$', re.M)


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def plain_ded(text):
    """DED markup -> list of lines; {widespace} separates fields on a line."""
    text = text.replace('\\n', '\n').replace('\\"', '"')
    text = re.sub(r'\{widespace\}(\{widespace\})*', '\x1f', text)
    text = text.replace('{lowspace}', ' ')
    text = re.sub(r'\{(scale|rgb|vpos|icon|spacing):[^}]*\}', '', text)
    lines = [SEP.join(f for f in (re.sub(r'\s+', ' ', p).strip() for p in l.split('\x1f')) if f)
             for l in text.split('\n')]
    return [l for l in lines if l]


def plain_fctr(text):
    """FCTR markup -> lines. Coloured section headings (Drop/Steal/Poach/...) are prefixed with '## '."""
    out = []
    for raw in text.replace('\r', '').split('\n'):
        heading = raw.startswith('{rgb:') and raw.endswith('{color:none}')
        line = raw.replace('{wait}', '')
        line = re.sub(r'\{hpossetcur:\d+\}', '  ', line)
        line = re.sub(r'\{(color|rgb):[^}]*\}|\{alignright\}', '', line)
        line = line.rstrip()
        if heading and line:
            line = '## ' + line
        out.append(line)
    while out and not out[0]:
        out.pop(0)
    while out and not out[-1]:
        out.pop()
    return out


def parse_fctr(path):
    text = path.read_text(encoding='utf-8')
    entries = {}
    names = {}
    for m in ENTRY.finditer(text):
        before = text[:m.start()].rstrip().split('\n')
        # the nearest preceding "--Name" comment that is not the file header
        for line in reversed(before[-6:]):
            line = line.strip()
            if line.startswith('--') and not line.startswith('--{'):
                names.setdefault(int(m.group(1)), line[2:].strip())
                break
        e = entries.setdefault(int(m.group(1)), {})
        e[int(m.group(2))] = plain_fctr(m.group(3))
    return entries, names


def locations(pages):
    """Location headings on 'Items' pages: lines right after the {wait} marker in gold, before the first table."""
    locs = []
    for lines in pages.values():
        body = [l for l in lines[1:] if l.strip()] if lines and lines[0] == 'Items' else []
        if body and not body[0].startswith('##'):
            loc = re.sub(r'\[[^\]]*\]\s*:\s*', '', body[0]).strip()
            if loc and loc not in locs:
                locs.append(loc)
    return locs


def mod_text(x64, lang='us'):
    """Text the user's optional mods generated: Dynamic Equipment Description / Descriptive Inventory gear descriptions and
    Foedex Clan Primer bestiary + traveler's tips. Each family is an empty list when its mod's file is missing."""
    x64 = Path(x64)
    gear_p = x64 / f'scripts/config/TheInsurgentsDescriptiveInventoryConfig/{lang}.lua'
    best_p = x64 / f'scripts/config/FoedexClanPrimerTextReplacerConfig/Bestiary/{lang}.lua'
    tips_p = x64 / f'scripts/config/FoedexClanPrimerTextReplacerConfig/TravelersTips/{lang}.lua'
    gear, bestiary, tips, sources = [], [], [], {}
    if gear_p.is_file():
        for m in GEAR.finditer(gear_p.read_text(encoding='utf-8')):
            lines = plain_ded(m.group(2))
            if not lines:
                continue
            head = lines[0].split(SEP)
            cat = next((p.split(':', 1)[1].strip() for p in head if p.startswith('Category:')), None)
            gear.append({'id': int(m.group(1)), 'name': head[0], 'category': cat,
                         'tags': [p for p in head[1:] if not p.startswith('Category:')], 'lines': lines[1:]})
    if best_p.is_file():
        entries, names = parse_fctr(best_p)
        bestiary = [{'entry': k, 'name': names.get(k, f'Entry {k}'), 'locations': locations(v),
                     'pages': [v[p] for p in sorted(v)]} for k, v in sorted(entries.items())]
    if tips_p.is_file():
        tips_e, tips_n = parse_fctr(tips_p)
        tips = [{'entry': k, 'name': tips_n.get(k, f'Tip {k}'), 'pages': [v[p] for p in sorted(v)]}
                for k, v in sorted(tips_e.items())]
    for k, p in (('gear', gear_p), ('bestiary', best_p), ('tips', tips_p)):
        if p.is_file():
            sources[k] = {'file': p.relative_to(x64).as_posix(), 'sha256': sha(p), 'bytes': p.stat().st_size}
    return {'gear': gear, 'bestiary': bestiary, 'tips': tips, 'sources': sources}


def main(x64, out, lang='us'):
    x64 = Path(x64)
    gear_p = x64 / f'scripts/config/TheInsurgentsDescriptiveInventoryConfig/{lang}.lua'
    best_p = x64 / f'scripts/config/FoedexClanPrimerTextReplacerConfig/Bestiary/{lang}.lua'
    tips_p = x64 / f'scripts/config/FoedexClanPrimerTextReplacerConfig/TravelersTips/{lang}.lua'

    gear = []
    for m in GEAR.finditer(gear_p.read_text(encoding='utf-8')):
        lines = plain_ded(m.group(2))
        if not lines:
            continue
        head = lines[0].split(SEP)
        cat = next((p.split(':', 1)[1].strip() for p in head if p.startswith('Category:')), None)
        gear.append({'id': int(m.group(1)), 'name': head[0], 'category': cat,
                     'tags': [p for p in head[1:] if not p.startswith('Category:')], 'lines': lines[1:]})

    entries, names = parse_fctr(best_p)
    bestiary = [{'entry': k, 'name': names.get(k, f'Entry {k}'), 'locations': locations(v),
                 'pages': [v[p] for p in sorted(v)]} for k, v in sorted(entries.items())]
    tips_e, tips_n = parse_fctr(tips_p)
    tips = [{'entry': k, 'name': tips_n.get(k, f'Tip {k}'), 'pages': [v[p] for p in sorted(v)]}
            for k, v in sorted(tips_e.items())]

    import gambit_names
    pack = x64.parent / f'mods/deploy/ff12data/ps2data/image/ff12/test_battle/{lang}/binaryfile/battle_pack.bin'
    conds = {}
    if pack.is_file():
        names, _ = gambit_names.condition_names(pack.read_bytes())
        conds = {f'{k:04x}': v for k, v in sorted(names.items())}
    doc = {'format': 'ffxii-companion-reference-1', 'kind': 'REFERENCE', 'language': lang,
           'note': 'Built from files written by the user\'s own mods (personal use). Not live game state.',
           'sources': {k: {'file': str(p.relative_to(x64)).replace('\\', '/'), 'sha256': sha(p),
                           'bytes': p.stat().st_size}
                       for k, p in (('gear', gear_p), ('bestiary', best_p), ('tips', tips_p))},
           'gear': gear, 'bestiary': bestiary, 'tips': tips, 'gambitConditions': conds}
    if pack.is_file():
        doc['sources']['gambitConditions'] = {'file': 'mods/deploy/.../battle_pack.bin', 'sha256': sha(pack),
                                              'bytes': pack.stat().st_size}
    Path(out).write_text(json.dumps(doc, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
    cats = {}
    for g in gear:
        cats[g['category']] = cats.get(g['category'], 0) + 1
    print(out, f'{Path(out).stat().st_size / 1e6:.2f} MB', 'gear', len(gear), 'bestiary', len(bestiary),
          'pages', sum(len(b['pages']) for b in bestiary), 'tips', len(tips))
    print('gear categories', sorted(cats.items(), key=lambda x: -x[1])[:16])


if __name__ == '__main__':
    main(*sys.argv[1:4])
