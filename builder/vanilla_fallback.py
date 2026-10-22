"""Vanilla-only prototype. Reads VBF entries in memory; never writes extracted game data.
build() returns the existing reference/boards JSON contracts plus a map-id/name dict.
CLI prints counts and deterministic hashes only. US decoder only: fail closed for other languages.
This is a partial reader: mod-generated descriptions and handbook prose are explicitly absent.
"""
import hashlib
import json
import re
import struct
import sys
from pathlib import Path
sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).resolve().parent))
from vbf_extract import open_vbf, extract
from gambit_names import sections, condition_names, decode
from boards_pack import JOBS
DEFAULT = Path('C:/Program Files (x86)/Steam/steamapps/common/FINAL FANTASY XII THE ZODIAC AGE')

def digest(value):
    if not isinstance(value, bytes):
        value = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode()
    return hashlib.sha256(value).hexdigest()

def build(install=DEFAULT, lang='us'):
    if lang != 'us':
        raise ValueError('Only US text encoding is validated by this prototype')
    f, index, blocks = open_vbf(Path(install) / 'FFXII_TZA.vbf')
    sources = {}
    def read(path):
        data = extract(f, index[path], blocks)
        sources[path] = {'sha256': digest(data), 'bytes': len(data)}
        return data
    try:
        pack_path = f'ps2data/image/ff12/test_battle/{lang}/binaryfile/battle_pack.bin'
        pack = read(pack_path)
        ss = sections(pack)
        texts = [sections(x) for x in sections(ss[61])]
        unknown = set()
        def text(tid):
            group, row = tid >> 11, tid & 2047
            if group >= len(texts) or row >= len(texts[group]):
                return None
            parts = [x for x in texts[group][row].split(b'\0') if x]
            if not parts: return None
            name, un = decode(parts[0])
            # Existing boards_pack substitutions, plus period for reference names.
            name = name.replace('{a0}', '&').replace('{81}', 'ú').replace('{a8}', '.')
            unknown.update(re.findall(r'\{[0-9a-f]{2}\}', name))
            return name
        def records(section):
            data = ss[section]
            magic, count, stride, offset = struct.unpack_from('<4I', data)
            assert magic == 0x65327473 and offset + count * stride <= len(data)
            return [data[offset+i*stride:offset+(i+1)*stride] for i in range(count)]
        gear = {}
        for i, row in enumerate(records(13)[:420]):
            name = text(struct.unpack_from('<H', row, 2)[0])
            if not name: continue
            slot = row[7] >> 5
            gear[0x1000+i] = {'id': 0x1000+i, 'name': name,
                'category': ['weapon','offHand','helm','armor','accessory'][slot] if slot < 5 else None,
                'tags': [], 'lines': []}
        # Inventory IDs come from the action record, not an assumed text-table ordering.
        for row in records(14):
            kind = row[0x1e]
            ident = struct.unpack_from('<H', row, 0x22)[0]
            if not ((kind == 1 and 0x3000 <= ident < 0x3051) or
                    (kind == 2 and 0x4000 <= ident < 0x4018)):
                continue
            name = text(struct.unpack_from('<H', row, 0x34)[0])
            if name:
                gear[ident] = {'id': ident, 'name': name, 'category': 'magicks' if kind==1 else 'technicks', 'tags': [], 'lines': []}
        for i, row in enumerate(records(18)):
            name = text(struct.unpack_from('<H', row, 8)[0])
            if name:
                gear[i] = {'id': i, 'name': name, 'category': 'items', 'tags': [], 'lines': []}
        # Gambit actions as the game's menu names them: a stored action u16 is a section-14 record index and the menu shows
        # that record's +0x34 text (Action_IDs_and_Set_Switch_2026-10-22). Only the ordinary party families are listed
        # (class 0 attack, 1 magick, 2 technick, 3 item), so the list doubles as the set of actions the editor may write.
        actions = {}
        for a, row in enumerate(records(14)):
            if row[0x1e] in (0, 1, 2, 3):
                name = text(struct.unpack_from('<H', row, 0x34)[0])
                if name:
                    actions[f'{a:04x}'] = name
        conditions, cond_unknown = condition_names(pack)
        ref = {'format': 'ffxii-companion-reference-1', 'kind': 'REFERENCE', 'language': lang,
            'note': 'Vanilla-only partial reference; descriptions, bestiary and tips are unavailable in this prototype.',
            'sources': {'vanillaBattlePack': {'file': pack_path, **sources[pack_path]}},
            'gear': [gear[k] for k in sorted(gear)], 'bestiary': [], 'tips': [],
            'gambitConditions': {f'{k:04x}': v for k,v in sorted(conditions.items())},
            'gambitActions': actions}
        nodes = records(12)
        names = {str(i): text(struct.unpack_from('<H', row)[0]) for i,row in enumerate(nodes)}
        boards = []
        for j, job in enumerate(JOBS):
            # Western-language VBF directories omit board_N. Explicit shared layout fallback;
            # byte equality against the deployed boards is separately checked by the test.
            path = f'ps2data/image/ff12/test_battle/{lang}/binaryfile/board_{j+1}.bin'
            if path not in index:
                path = f'ps2data/image/ff12/test_battle/in/binaryfile/board_{j+1}.bin'
            raw = read(path)
            assert raw[:4] == b'licd'
            w,h = struct.unpack_from('<HH',raw,4)
            assert len(raw) == 8+2*w*h
            cells = raw[8:]
            assert all(n == 65535 or n < len(nodes) for n in struct.unpack(f'<{w*h}H',cells))
            boards.append({'job':j,'name':job,'w':w,'h':h,'cells':cells.hex()})
        boarddoc = {'format':'ffxii-companion-boards-1','kind':'REFERENCE',
            'note':'Built in memory from vanilla files; not for redistribution.',
            'packSha256':digest(pack),'nodeCount':len(nodes),
            'names':{k:v for k,v in names.items() if v},'boards':boards}
        raw = read(f'ps2data/image/ff12/{lang}/bin/planmapname.bin')
        assert raw[:4] == b'PLMN'
        end = struct.unpack_from('<I',raw,4)[0]
        assert 16 <= end <= len(raw) and end % 2 == 0
        areas = {}
        area_unknown = 0
        for i,off in enumerate(struct.unpack_from(f'<{(end-16)//2}H',raw,16)):
            if not off: continue
            assert end <= off < len(raw)
            z = raw.index(0,off)
            name=''
            for c in raw[off:z]:
                if 0x20<=c<=0x39: name+=chr(c+0x21)
                elif 0x3a<=c<=0x53: name+=chr(c+0x27)
                elif 0x85<=c<=0x8e: name+=chr(c-0x85+48)
                else: name+={4:' ',0xa3:'_',0xa5:'-',0xa8:'.',0xac:"'"}.get(c,'?')
            if not re.match(r'^[a-z]{3}_',name):
                areas[str(i)] = name
                area_unknown += '?' in name
        coverage = {'gear':len(gear),'equipment':sum(0x1000<=i<0x2000 for i in gear),
            'magicks':sum(0x3000<=i<0x4000 for i in gear),'technicks':sum(0x4000<=i<0x5000 for i in gear),
            'items':sum(i<0x1000 for i in gear),'descriptions':0,'bestiary':0,'tips':0,
            'conditions':len(conditions),'boards':len(boards),'nodes':len(nodes),'namedNodes':len(boarddoc['names']),
            'areas':len(areas),'unknownNameGlyphs':len(unknown),'unknownConditionGlyphs':len(cond_unknown),'unknownAreas':area_unknown}
        return {'reference':ref,'boards':boarddoc,'areas':areas,'coverage':coverage,'sources':sources}
    finally:
        f.close()

if __name__ == '__main__':
    result=build(Path(sys.argv[1]) if len(sys.argv)>1 else DEFAULT)
    print(json.dumps({'counts':result['coverage'],'hashes':{k:digest(result[k]) for k in ['reference','boards','areas']},
        'sourceHashes':{p:x['sha256'] for p,x in result['sources'].items()}},sort_keys=True))
