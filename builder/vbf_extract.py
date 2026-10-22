# Read-only extractor for single files from FFXII_TZA.vbf (SRYK archive).
# Layout (observed): 'SRYK', u32 header_len, u64 n; n*16 name hashes; n*32 entries
# {u32 first_block, u32 ?, u64 size, u64 data_offset, u64 name_offset}; u32 names_len + names;
# u16 per-block compressed size (0 = raw 64 KiB) up to header_len. Blocks are zlib, 64 KiB each.
import struct, sys, zlib

def open_vbf(path):
    f = open(path, 'rb')
    magic, hlen, n = struct.unpack('<4sIQ', f.read(16))
    assert magic == b'SRYK'
    f.seek(16 + 16 * n)
    ents = [struct.unpack('<IIQQQ', f.read(32)) for _ in range(n)]
    nlen = struct.unpack('<I', f.read(4))[0]
    names = f.read(nlen - 4) if nlen > 4 else b''
    pos = f.tell()
    blocks = struct.unpack('<%dH' % ((hlen - pos) // 2), f.read(((hlen - pos) // 2) * 2))
    def name(off):
        e = names.find(b'\0', off)
        return names[off:e].decode('utf-8', 'replace')
    index = {name(e[4]): e for e in ents}
    return f, index, blocks

def extract(f, ent, blocks, limit=None):
    """Decompresses one entry. limit (bytes) stops early after the covering blocks: a bounded prefix read."""
    first, _, size, off, _ = ent
    stop = size if limit is None else min(size, limit)
    out = bytearray()
    f.seek(off)
    b = first
    while len(out) < stop:
        want = min(65536, size - len(out))   # raw-vs-zlib test uses the entry's true block size
        c = blocks[b]
        raw = f.read(c if c else 65536)
        out += raw if (c == 0 or c == want) else zlib.decompress(raw)
        b += 1
    return bytes(out[:stop])

if __name__ == '__main__':
    vbf, outdir = sys.argv[1], sys.argv[2]
    f, index, blocks = open_vbf(vbf)
    for p in sys.argv[3:]:
        data = extract(f, index[p], blocks)
        dst = outdir + '/' + p.replace('/', '__')
        open(dst, 'wb').write(data)
        print(p, len(data), '->', dst)
