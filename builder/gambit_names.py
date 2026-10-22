"""Gambit condition names from the deployed (modded) battle_pack.bin (read-only).

Established 21 Oct 2026 (FFXII_Gambits_Static_2026-10-21.md): condition ID 0x6000+n -> battle-pack section 7 record n
('ts2e', 0x20-byte records from +0x20); its u16 at +0x14 is 0x3000 + text index into section 61 sub-table 6 (u32 count,
u32 offsets, entries "\\0\\0name\\0\\0description..."). All 11 conditions read off the Thor's Gambits screens map exactly.
Text encoding (FFXII 8-bit): 0x20-0x39 'A'-'Z', 0x3A-0x53 'a'-'z', 0x85-0x8E '0'-'9', plus the punctuation below.

Usage: gambit_names.py <battle_pack.bin> [out.json]
"""
import json
import struct
import sys
from pathlib import Path

# Only bytes that occur in the condition names; each checked against screen text or unambiguous context.
PUNCT = {0x04: ' ', 0xAA: ':', 0xAC: "'", 0xB2: '<', 0xA6: '=', 0x9E: '%', 0xC4: '≥', 0xA5: '-', 0xA4: '+', 0xA7: ',', 0xA3: '-'}


def decode(b):
    out, unknown = [], []
    for c in b:
        if 0x20 <= c <= 0x39:
            out.append(chr(c + 0x21))
        elif 0x3A <= c <= 0x53:
            out.append(chr(c + 0x27))
        elif 0x85 <= c <= 0x8E:
            out.append(chr(c - 0x85 + 48))
        elif c in PUNCT:
            out.append(PUNCT[c])
        else:
            out.append('{%02x}' % c)
            unknown.append(c)
    return ''.join(out), unknown


def sections(d):
    n = struct.unpack_from('<I', d, 0)[0]
    offs = list(struct.unpack_from(f'<{n}I', d, 4)) + [len(d)]
    return [d[offs[i]:offs[i + 1]] for i in range(n)]


def condition_names(pack):
    secs = sections(pack)
    s61 = secs[61]
    m = struct.unpack_from('<I', s61, 0)[0]
    so = list(struct.unpack_from(f'<{m}I', s61, 4)) + [len(s61)]
    t = s61[so[6]:so[7]]
    cnt = struct.unpack_from('<I', t, 0)[0]
    to = list(struct.unpack_from(f'<{cnt}I', t, 4)) + [len(t)]
    texts = []
    for i in range(cnt):
        parts = [p for p in t[to[i]:to[i + 1]].split(b'\0') if p]
        texts.append(parts[0] if parts else b'')
    s7 = secs[7]
    magic, count, stride, data = struct.unpack_from('<4I', s7, 0)
    if magic != 0x65327473 or stride != 0x20:
        raise ValueError('unexpected condition section header')
    names, unknown = {}, set()
    for n in range(count):
        rec = s7[data + n * stride:data + (n + 1) * stride]
        if len(rec) < 0x16:
            break
        tid = struct.unpack_from('<H', rec, 0x14)[0]
        if tid < 0x3000 or tid - 0x3000 >= cnt:
            continue
        name, unk = decode(texts[tid - 0x3000])
        unknown.update(unk)
        if name:
            names[0x6000 + n] = name
    return names, sorted(unknown)


if __name__ == '__main__':
    names, unknown = condition_names(Path(sys.argv[1]).read_bytes())
    sys.stdout.reconfigure(encoding='utf-8')
    print(len(names), 'conditions; undecoded bytes', [hex(x) for x in unknown])
    for k in sorted(names)[:8] + sorted(names)[-4:]:
        print(hex(k), names[k])
    bad = [(hex(k), v) for k, v in names.items() if '{' in v]
    print('with undecoded chars:', bad[:12])
    if len(sys.argv) > 2:
        Path(sys.argv[2]).write_text(json.dumps({hex(k): v for k, v in sorted(names.items())}, ensure_ascii=False, indent=0),
                                     encoding='utf-8')
