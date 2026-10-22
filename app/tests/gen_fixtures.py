# Generates SYNTHETIC receiver fixtures. None of this is game data: names are labels, numbers are made up,
# and every file carries top-level "synthetic": true plus a "fixture" name. The party block follows the
# PROPOSED family in draft/android/PROPOSAL_party_family.md, which no producer implements yet.
import copy, json, os, sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'fixtures')

HELLO = {
    'protocol': 1, 'transport': 'file-ab',
    'producer': {'name': 'synthetic-fixture', 'version': 'fixture-1'},
    'loader': {'lua': '1.7.1'},
    'build': {'exeLengthSupported': True, 'imageBase': 1179648, 'pe': {'timeDateStamp': 1573552087},
              'patches': {'gn.595520.displaymodes': 'applied'}},
    'mods': [{'id': 'insurgents.companions', 'kind': 'lua', 'file_present': {'value': True}, 'config_observed': {}},
             {'id': 'insurgents.manifesto', 'kind': 'lua', 'file_present': {'value': True}}],
    'capabilities': {'map.id': 'probe', 'map.index': 'probe', 'map.jumpEvent': 'probe', 'party': 'off'},
}
MAP = {'mapId': 581, 'mapIndex': 3, 'previousMapId': 578, 'previousMapIndex': 4, 'jumps': 3}

def member(slot, name, hp, mp, role='active', leader=False, ko=None):
    m = {'slot': slot, 'name': name, 'role': role, 'leader': leader, 'hp': hp, 'mp': mp}
    if ko is not None: m['ko'] = ko
    return m

def v(cur, mx): return {'cur': cur, 'max': mx}

FOUR = [member(0, 'Vaan', v(1840, 2210), v(310, 402), leader=True),
        member(1, 'Balthier', v(2012, 2012), v(288, 350)),
        member(2, 'Fran', v(905, 1980), v(512, 530)),
        member(3, 'Larsa', v(640, 800), v(150, 200), role='guest')]

def snap(name, seq, clock_us=50_000_000, party=None, party_clock=None, include_map=True, exiting=False,
         synthetic=True, caps_party='off', session_id=None, map_override=None, reveal=None, hello_patch=None, table=None,
         position=None, party_block=None):
    hello = copy.deepcopy(HELLO)
    hello['capabilities']['party'] = caps_party
    if hello_patch: hello_patch(hello)
    if exiting:
        data = {'hello': hello, 'session': {'exiting': True, 'exitType': 'Normal exit', 'wallTime': 0}}
    else:
        data = {'hello': hello, 'session': {'clockUs': clock_us, 'wallTime': 0}}
        if include_map: data['map'] = dict(map_override or MAP)
        if reveal is not None: data['reveal'] = reveal
        if table is not None: data['revealTable'] = table
        if position is not None: data['position'] = position
        if party is not None:
            data['party'] = {'observedClockUs': clock_us if party_clock is None else party_clock,
                             'source': 'synthetic', 'members': party}
        if party_block is not None: data['party'] = party_block
    if session_id: data['session']['id'] = session_id
    o = {'v': 1, 'type': 'snapshot', 'seq': seq, 'data': data}
    if synthetic:
        o['synthetic'] = True
        o['fixture'] = name
    return o

FIXTURES = {
    'party_four_live': snap('party_four_live', 12, party=FOUR),
    'party_ko_missing_mp': snap('party_ko_missing_mp', 13, party=[
        member(0, 'Vaan', v(1840, 2210), v(310, 402), leader=True),
        member(1, 'Basch', v(0, 2400), v(0, 180), ko=True),
        member(2, 'Penelo', v(1500, 1700), None),
        member(3, 'Vossler', v(2100, 2300), v(500, 120), role='guest')]),
    'party_stale_location_fresh': snap('party_stale_location_fresh', 14, clock_us=80_000_000,
                                       party=FOUR, party_clock=70_000_000),
    'party_five_members': snap('party_five_members', 15, party=FOUR + [member(4, 'Ashe', v(1700, 1900), v(400, 410))]),
    'party_unverified_live_shape': snap('party_unverified_live_shape', 16, party=FOUR, synthetic=False, caps_party='on'),
    # logger 0.5.9 shape (contract ffxii.party.v1), still made-up numbers
    'party_logger_probe': snap('party_logger_probe', 17, synthetic=False, caps_party='probe', party_block={
        'contract': 'ffxii.party.v1', 'status': 'ok', 'observedClockUs': 50_000_000, 'members': [
            {'slot': 0, 'member': 0, 'name': 'Vaan', 'role': 'active', 'hp': v(1200, 2747), 'mp': v(242, 242)},
            {'slot': 1, 'member': 3, 'name': 'Balthier', 'role': 'active', 'hp': v(2479, 2479), 'mp': v(60, 211)},
            {'slot': 3, 'member': 13, 'role': 'active', 'hp': v(640, 800), 'mp': v(150, 200)}]}),
    'party_logger_not_ready': snap('party_logger_not_ready', 18, synthetic=False, caps_party='probe',
                                   party_block={'contract': 'ffxii.party.v1', 'status': 'save_not_ready'}),
    'party_logger_wrong_build': snap('party_logger_wrong_build', 19, synthetic=False, caps_party='probe',
                                     hello_patch=lambda h: h['build']['pe'].update(timeDateStamp=1),
                                     party_block={'contract': 'ffxii.party.v1', 'status': 'ok', 'observedClockUs': 50_000_000,
                                                  'members': {}}),
    'location_only': snap('location_only', 20),
    'no_map': snap('no_map', 21, include_map=False),
    'session2_first': snap('session2_first', 1, include_map=False),
    'session2_with_map': snap('session2_with_map', 2),
    'game_closed': snap('game_closed', 22, exiting=True),
    # Layout stress: longest real area names (map 430 from 511) and long member names, KO + guest, all four rows.
    'long_text_four': snap('long_text_four', 17, party=[
        member(0, 'Basch fon Ronsenburg of Landis', v(9999, 9999), v(999, 999), leader=True),
        member(1, 'Vossler York Azelas', v(0, 8800), v(0, 650), ko=True),
        member(2, "Princess Ashelia B'nargin Dalmasca", v(7123, 9050), None),
        member(3, 'Larsa Ferrinas Solidor', v(4480, 5120), v(812, 870), role='guest')],
        map_override={'mapId': 430, 'mapIndex': 12, 'previousMapId': 511, 'previousMapIndex': 7, 'jumps': 128}),
}
# Map fog (PROPOSED in ThorCompanion.lua 0.4.0, contract ffxii.reveal.v1). Records here are MADE UP patterns.
def rv(map_id, record, status='ok', stable=True, contract='ffxii.reveal.v1', slot=1):
    r = {'contract': contract, 'mapId': map_id, 'status': status}
    if status == 'ok':
        r.update(record=record, stable=stable, slot=slot, observedClockUs=50_000_000)
    return r

def table(records, status='ok'):
    t = {'contract': 'ffxii.reveal.v1', 'status': status}
    if status == 'ok':
        t.update(records=','.join(f'{k}:{v}' for k, v in sorted(records.items())), stable=True, observedClockUs=50_000_000)
    return t

def at(map_id): return {'mapId': map_id, 'mapIndex': 1, 'previousMapId': 581, 'previousMapIndex': 0, 'jumps': 4}

LEFT_HALF_1024 = ('ff00' * 16)          # 1024 px, stride 2: columns 0-7 of every row
TOP_ROWS_512 = 'ff' * 4 + '00' * 28      # 512 px, stride 1: rows 0-3
def caps_reveal(h): h['capabilities']['map.reveal'] = 'on'
def caps_pos(h):
    caps_reveal(h)
    h['capabilities']['map.position'] = 'probe'
def pos(x, z, ox=182.976, oz=225.258):
    return {'contract': 'ffxii.mappos.v1', 'status': 'ok', 'navimapActive': True, 'x': x, 'y': 5.0, 'z': z,
            'originX': ox, 'originZ': oz, 'extentX': 115, 'extentZ': 173, 'observedClockUs': 50_000_000}
def bundle_full(h):
    caps_reveal(h)
    h['mods'].append({'id': 'insurgents.bundle', 'kind': 'lua', 'file_present': {'value': True},
                      'config_observed': {'states': {'fullyRevealedMaps': True}}})
def wrong_build(h):
    caps_reveal(h)
    h['build']['pe']['timeDateStamp'] = 1

FIXTURES.update({
    'reveal_1024_left_half': snap('reveal_1024_left_half', 40, map_override=at(585), reveal=rv(585, LEFT_HALF_1024), hello_patch=caps_reveal),
    'reveal_1024_top_half': snap('reveal_1024_top_half', 53, map_override=at(585), reveal=rv(585, 'ff' * 16 + '00' * 16), hello_patch=caps_reveal),
    'reveal_512_top_rows': snap('reveal_512_top_rows', 41, map_override=at(581), reveal=rv(581, TOP_ROWS_512), hello_patch=caps_reveal),
    'reveal_live_shape': snap('reveal_live_shape', 42, map_override=at(585), reveal=rv(585, LEFT_HALF_1024), synthetic=False, hello_patch=caps_reveal),
    'reveal_live_shape_wrong_build': snap('reveal_live_shape_wrong_build', 43, map_override=at(585), reveal=rv(585, LEFT_HALF_1024), synthetic=False, hello_patch=wrong_build),
    'reveal_live_shape_no_cap': snap('reveal_live_shape_no_cap', 44, map_override=at(585), reveal=rv(585, LEFT_HALF_1024), synthetic=False),
    'reveal_unstable': snap('reveal_unstable', 45, map_override=at(585), reveal=rv(585, None, status='unstable'), hello_patch=caps_reveal),
    'reveal_2048': snap('reveal_2048', 46, map_override=at(384), reveal=rv(384, 'ff' * 32), hello_patch=caps_reveal),
    'reveal_multisheet': snap('reveal_multisheet', 47, map_override=at(318), reveal=rv(318, 'ff' * 32), hello_patch=caps_reveal),
    'reveal_bundle_full': snap('reveal_bundle_full', 48, map_override=at(585), reveal=rv(585, '00' * 32), hello_patch=bundle_full),
    'reveal_other_map': snap('reveal_other_map', 49, map_override=at(585), reveal=rv(581, TOP_ROWS_512), hello_patch=caps_reveal),
    'reveal_bad_record': snap('reveal_bad_record', 50, map_override=at(585), reveal=rv(585, 'zz' * 32), hello_patch=caps_reveal),
    'reveal_unknown_contract': snap('reveal_unknown_contract', 51, map_override=at(585), reveal=rv(585, LEFT_HALF_1024, contract='ffxii.reveal.v9'), hello_patch=caps_reveal),
    # Region view (0.4.1 revealTable). Synthetic region: 581 current, 578 half explored, 585 explored, 588 unvisited.
    'region_table': snap('region_table', 60, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44),
                         table=table({44: 'ff' * 32, 43: 'ff' * 8 + '00' * 24, 46: 'ff' * 32}), hello_patch=caps_reveal),
    'region_no_table': snap('region_no_table', 61, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44), hello_patch=caps_reveal),
    'region_slot_mismatch': snap('region_slot_mismatch', 62, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=7),
                                 table=table({44: 'ff' * 32, 46: 'ff' * 32}), hello_patch=caps_reveal),
    'region_table_unstable': snap('region_table_unstable', 63, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44),
                                  table=table({}, status='unstable'), hello_patch=caps_reveal),
    'region_bundle_full': snap('region_bundle_full', 64, map_override=at(581), reveal=rv(581, '00' * 32, slot=44), hello_patch=bundle_full),
    # Position (PROPOSED for 0.4.2, contract ffxii.mappos.v1). Spot A values from the Thor test, labelled synthetic here.
    'pos_ok': snap('pos_ok', 80, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44), hello_patch=caps_pos,
                   position=pos(270.615, 253.32)),
    'pos_origin_mismatch': snap('pos_origin_mismatch', 81, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44),
                                hello_patch=caps_pos, position=pos(270.615, 253.32, ox=258.5, oz=140.0)),
    'pos_inactive': snap('pos_inactive', 82, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44), hello_patch=caps_pos,
                         position={'contract': 'ffxii.mappos.v1', 'status': 'navimap_inactive', 'navimapActive': False}),
    'pos_outside': snap('pos_outside', 83, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44), hello_patch=caps_pos,
                        position=pos(182.976 + 200, 253.32)),
    'pos_live_shape': snap('pos_live_shape', 84, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44), synthetic=False,
                           hello_patch=caps_pos, position=pos(237.744, 288.219)),
    'pos_live_no_cap': snap('pos_live_no_cap', 85, map_override=at(581), reveal=rv(581, 'ff' * 32, slot=44), synthetic=False,
                            hello_patch=caps_reveal, position=pos(237.744, 288.219)),
    'reveal_no_atlas_entry': snap('reveal_no_atlas_entry', 52, map_override=at(999), reveal=rv(999, 'ff' * 32), hello_patch=caps_reveal),
})

# SYNTHETIC atlas index for the gate tests: shapes only, no game art.
SLOTS = {585: 46, 581: 44, 578: 43, 588: 99, 384: 130, 318: 30}
ORIGINS = {581: (182.9758, 225.2584), 585: (134.0, 0.0722)}
def sheet(map_id, w, h, per=1, idx=0):
    return {'mapId': map_id, 'region': 'synthetic', 'sheet': idx, 'file': f'{map_id}_synthetic_{idx}.png', 'slot': SLOTS[map_id],
            'width': w, 'height': h, 'stride': (w // 64) >> 3, 'sheetsForMap': per,
            'origin': {'x0': ORIGINS.get(map_id, (0.0, 0.0))[0], 'z0': ORIGINS.get(map_id, (0.0, 0.0))[1],
                       'width': w / 4, 'depth': h / 4},
            'tiles': ['#' * (w // 64)] * (h // 64), 'bytes': 0, 'sha256': '0' * 64}
ATLAS = {'format': 'ffxii-companion-atlas-1', 'synthetic': True,
         'sheets': [sheet(585, 1024, 1024), sheet(581, 512, 1024), sheet(384, 2048, 512),
                    sheet(318, 1024, 1024, 2, 0), sheet(318, 1024, 1024, 2, 1),
                    sheet(578, 512, 1024), sheet(588, 1024, 1024)]}
REGIONS = {'format': 'ffxii-companion-regions-1', 'unitsPerSheetPx': 0.25, 'synthetic': True, 'regions': [
    {'region': 'synthetic', 'sheets': [
        {'mapId': 585, 'sheet': 0, 'file': '585_synthetic_0.png', 'x': 274, 'y': 161},
        {'mapId': 581, 'sheet': 0, 'file': '581_synthetic_0.png', 'x': 320, 'y': 417},
        {'mapId': 578, 'sheet': 0, 'file': '578_synthetic_0.png', 'x': 416, 'y': 328},
        {'mapId': 588, 'sheet': 0, 'file': '588_synthetic_0.png', 'x': 167, 'y': 86}],
     'links': [{'a': 585, 'b': 581, 'ax': 300, 'ay': 400, 'bx': 330, 'by': 420},
               {'a': 578, 'b': 581, 'ax': 439, 'ay': 430, 'bx': 399, 'by': 447},
               {'a': 588, 'b': 585, 'ax': 200, 'ay': 150, 'bx': 280, 'by': 170},
               {'a': 585, 'b': 585, 'ax': 348, 'ay': 188, 'bx': 348, 'by': 140}],
     # 0.12.0 exit labels: one beside 578 (visited), one beside 588 (unvisited), two malformed
     'exits': [{'x': 560, 'y': 500, 'lx': 575, 'ly': 500, 'text': 'To Synthetic East', 'gate': 65535},
               {'x': 100, 'y': 60, 'lx': 90, 'ly': 50, 'text': 'To Nowhere', 'gate': 27},
               {'x': 1, 'y': 1, 'lx': 1, 'text': 'No anchor'},
               {'x': 424, 'y': 368, 'lx': 440, 'ly': 360, 'text': 'x' * 65}],
     # 0.13.0 map symbols on 578 (explored rows 0-7): always-gated on explored ground, flag-gated, and in the fog
     'icons': [{'mapId': 578, 'group': 5, 'index': 0, 'type': 1, 'sx': 8, 'sy': 8, 'dx': 10, 'dy': 20, 'gate': 0},
               {'mapId': 578, 'group': 5, 'index': 1, 'type': 2, 'sx': 8, 'sy': 8, 'dx': 30, 'dy': 20, 'gate': 0x1B},
               {'mapId': 578, 'group': 5, 'index': 2, 'type': 1, 'sx': 8, 'sy': 200, 'dx': 10, 'dy': 50, 'gate': 0},
               {'mapId': 578, 'group': 5, 'index': 3, 'type': 29, 'sx': 8, 'sy': 8, 'dx': 10, 'dy': 20, 'gate': 0}]}],
    'symbols': [{'type': 1, 'file': 'icons/type_01.png', 'width': 64, 'height': 56, 'sha256': '0' * 64},
                {'type': 2, 'file': 'icons/type_02.png', 'width': 64, 'height': 56, 'sha256': '0' * 64},
                {'type': 3, 'file': '../evil.png', 'width': 64, 'height': 56, 'sha256': '0' * 64}],
    'thresholds': [None, [0x8005]]}

MALFORMED = {
    'malformed_truncated': '{"v":1,"type":"snapshot","seq":30,"data":{"session":{"clockUs":1',
    'malformed_wrong_version': json.dumps({'v': 2, 'type': 'snapshot', 'seq': 31, 'data': {}}),
    'malformed_no_data': json.dumps({'v': 1, 'type': 'snapshot', 'seq': 32}),
}

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for k, o in FIXTURES.items():
        open(os.path.join(OUT, k + '.json'), 'w', encoding='utf-8').write(json.dumps(o, separators=(',', ':')))
    for k, t in MALFORMED.items():
        open(os.path.join(OUT, k + '.json'), 'w', encoding='utf-8').write(t)
    os.makedirs(os.path.join(OUT, 'atlas'), exist_ok=True)
    open(os.path.join(OUT, 'atlas', 'index.json'), 'w', encoding='utf-8').write(json.dumps(ATLAS, separators=(',', ':')))
    open(os.path.join(OUT, 'atlas', 'regions.json'), 'w', encoding='utf-8').write(json.dumps(REGIONS, separators=(',', ':')))
    print(len(FIXTURES) + len(MALFORMED), 'fixtures + synthetic atlas ->', OUT)
