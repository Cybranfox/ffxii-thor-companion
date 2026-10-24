package local.thor.ffxiicompanion.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java port of the data builder's map atlas, format 2 (builder/mapatlas_pack.py at c2ce7eb and the modules it calls:
 * menumap_sloc, navimap_index, menumap_tables.parse_location, menumap_names, menumap_icons, menumap_tile_index,
 * reveal_state.stride_for). Same checks, same refusals, same output order. index.json and regions.json come out
 * byte-identical to Python's; the PNG files are this writer's own (files.json lists them).
 * One region file is decoded at a time; nothing is kept but the output documents.
 */
final class AtlasStep {
    /** Where the game's files come from: Inputs (the app's raw-copy files, or the PC test's capture). */
    interface Source {
        byte[] read(String path) throws IOException;            // inflated entry; NoSuchEntry when absent
        Collection<String> names();                             // every entry name in the archive index
        long archiveBytes();
    }

    static final class NoSuchEntry extends IOException { NoSuchEntry(String p) { super("no entry " + p); } }

    static final class Bad extends IOException { Bad(String m) { super(m); } }

    /** One output file: its archive-relative name and bytes. */
    static final class Out {
        final String name;
        final byte[] data;
        Out(String n, byte[] d) { name = n; data = d; }
    }

    static final String FORMAT = "ffxii-companion-atlas-2", REGIONS_FORMAT = "ffxii-companion-regions-2",
            FILES_FORMAT = "ffxii-companion-atlas-files-1";
    static final String PREFIX = "ps2data/image/ff12/myoshiok/us/worldmap/menumap_sub";
    static final String LOCATION = "ps2data/image/ff12/myoshiok/us/worldmap/menumap_location.bin";
    static final String NAV = "ps2data/image/ff12/in/bin/navimapdata.bin";
    static final String MAPNAME = "ps2data/image/ff12/test_battle/us/binaryfile/menu_mapname.bin";
    static final String ICON_ATLAS = "ps2data/image/ff12/myoshiok/us/tm2_menu/loca_8_c.tm2";
    static final String FLAGROM = "ps2data/image/ff12/us/bin/mapjumpgroupflagrom.bin";
    static final int ICON_TYPES = 29, TILE = 64;
    static final String NOTE = "Coverage masks from the user's own game files, for personal use on their device. No tint.";

    /** Builds the atlas; returns the output files (index.json, regions.json, files.json, PNGs) in write order. */
    static List<Out> build(Source src) throws IOException {
        List<Out> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String n : src.names()) if (n.startsWith(PREFIX) && n.endsWith(".bin")) names.add(n);
        Collections.sort(names);
        byte[][] nav = parseNav(src.read(NAV));
        List<Lmap> lmap = parseLocation(src.read(LOCATION));
        Map<Integer, String> labels = new TreeMap<>();
        if (src.names().contains(MAPNAME)) {
            try { labels = parseMapnames(src.read(MAPNAME)); } catch (Bad e) { labels = new TreeMap<>(); }
        }
        List<Object> thresholds = null;
        List<Object> symbols = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        try {
            thresholds = flagrom(src.read(FLAGROM));
            for (Object[] s : symbolImages(src.read(ICON_ATLAS))) {
                int typ = (Integer) s[0], w = (Integer) s[1], h = (Integer) s[2];
                byte[] rgba = (byte[]) s[3], png = Png.encode(4, w, h, rgba);
                String fn = String.format("icons/type_%02d.png", typ);
                out.add(new Out(fn, png));
                symbols.add(map("type", typ, "file", fn, "width", w, "height", h,
                        "pixelEncoding", "RGBA8", "pixelSha256", pixelSha256(4, w, h, rgba)));
                files.add(receipt(fn, png));
            }
        } catch (Bad | NoSuchEntry e) {
            thresholds = null; symbols = new ArrayList<>(); files = new ArrayList<>();
            out.removeIf(o -> o.name.startsWith("icons/"));
        }

        List<Map<String, Object>> entries = new ArrayList<>();
        List<Object> regions = new ArrayList<>();
        for (String name : names) {
            String base = name.substring(name.lastIndexOf('/') + 1);
            String region = base.substring("menumap_".length(), base.length() - ".bin".length());
            byte[] raw = src.read(name);
            List<Sheet> sheets = parseSloc(raw);
            List<Map<String, Object>> mine = new ArrayList<>();
            for (int i = 0; i < sheets.size(); i++) {
                Sheet s = sheets.get(i);
                byte[] samples = new byte[s.mask.length];
                for (int p = 0; p < samples.length; p++) samples[p] = (byte) Math.min(255, (s.mask[p] & 255) * 2);
                byte[] png = Png.encode(1, s.width, s.height, samples);
                String fn = s.mapId + "_" + region + "_" + i + ".png";
                out.add(new Out(fn, png));
                Map<String, Object> origin = map("x0", PyJson.round4(s.nps2[0]), "z0", PyJson.round4(s.nps2[1]),
                        "width", PyJson.round4(s.nps2[2]), "depth", PyJson.round4(s.nps2[3]));
                Map<String, Object> e = map("mapId", s.mapId, "region", region, "sheet", i, "file", fn,
                        "slot", slotOf(nav, s.mapId), "width", s.width, "height", s.height,
                        "stride", (s.width / TILE) >> 3, "tiles", occupancy(s), "origin", origin,
                        "pixelEncoding", "L8", "pixelSha256", pixelSha256(1, s.width, s.height, samples));
                entries.add(e);
                mine.add(e);
                files.add(receipt(fn, png));
            }
            Map<String, Object> layout = regionLayout(region, mine, lmap, labels);
            if (layout != null && !symbols.isEmpty()) {
                List<Object> icons = new ArrayList<>();
                for (int[] r : navi(raw))
                    if (r[3] > 0 && r[3] < ICON_TYPES)
                        icons.add(map("mapId", r[0], "group", r[1], "index", r[2], "type", r[3], "sx", r[4], "sy", r[5],
                                "dx", r[6], "dy", r[7], "gate", r[8]));
                layout.put("icons", icons);
            }
            if (layout != null) regions.add(layout);
        }
        Map<Integer, Integer> perMap = new LinkedHashMap<>();
        for (Map<String, Object> e : entries) perMap.merge((Integer) e.get("mapId"), 1, Integer::sum);
        for (Map<String, Object> e : entries) e.put("sheetsForMap", perMap.get((Integer) e.get("mapId")));

        Map<String, Object> index = map("format", FORMAT,
                "source", map("vbf", "FFXII_TZA.vbf", "vbfBytes", src.archiveBytes(), "regionFiles", names.size()),
                "note", NOTE, "sheets", entries);
        Map<String, Object> reg = map("format", REGIONS_FORMAT, "unitsPerSheetPx", 0.25, "regions", regions);
        if (!symbols.isEmpty()) { reg.put("symbols", symbols); reg.put("thresholds", thresholds); }
        files.sort((a, b) -> ((String) a.get("file")).compareTo((String) b.get("file")));
        List<Out> docs = new ArrayList<>();
        docs.add(new Out("index.json", PyJson.dumps(index).getBytes(StandardCharsets.UTF_8)));
        docs.add(new Out("regions.json", PyJson.dumps(reg).getBytes(StandardCharsets.UTF_8)));
        docs.add(new Out("files.json", PyJson.dumps(map("format", FILES_FORMAT, "files", files)).getBytes(StandardCharsets.UTF_8)));
        docs.addAll(out);
        return docs;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static Map<String, Object> receipt(String fn, byte[] data) {
        return map("file", fn, "bytes", data.length, "sha256", hex(sha256(data)));
    }

    /** "ffxii.pixels.v1\0", channels, u32 BE width, height, raw samples (= Atlas.pixelSha256 in the app). */
    static String pixelSha256(int channels, int w, int h, byte[] samples) {
        MessageDigest md = digest();
        md.update("ffxii.pixels.v1\0".getBytes(StandardCharsets.US_ASCII));
        md.update(new byte[] {(byte) channels, (byte) (w >>> 24), (byte) (w >>> 16), (byte) (w >>> 8), (byte) w,
                (byte) (h >>> 24), (byte) (h >>> 16), (byte) (h >>> 8), (byte) h});
        md.update(samples);
        return hex(md.digest());
    }

    static byte[] sha256(byte[] d) { return digest().digest(d); }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x & 255));
        return s.toString();
    }

    private static void need(boolean ok, String why) throws Bad { if (!ok) throw new Bad(why); }

    private static int u8(byte[] d, int o, int end) throws Bad { need(o >= 0 && o + 1 <= Math.min(end, d.length), "u8 out of range"); return d[o] & 255; }

    private static int u16(byte[] d, int o, int end) throws Bad {
        need(o >= 0 && o + 2 <= Math.min(end, d.length), "u16 out of range");
        return (d[o] & 255) | (d[o + 1] & 255) << 8;
    }

    private static int s16(byte[] d, int o, int end) throws Bad { return (short) u16(d, o, end); }

    private static long u32(byte[] d, int o, int end) throws Bad {
        need(o >= 0 && o + 4 <= Math.min(end, d.length), "u32 out of range");
        return (d[o] & 255L) | (d[o + 1] & 255L) << 8 | (d[o + 2] & 255L) << 16 | (d[o + 3] & 255L) << 24;
    }

    private static float f32(byte[] d, int o) throws Bad { return Float.intBitsToFloat((int) u32(d, o, d.length)); }

    /** Python bytes.find(sub, start, end): the whole match within [start, end). */
    static int find(byte[] d, byte[] sub, int start, int end) {
        end = Math.min(end, d.length);
        if (start < 0) start = 0;
        for (int i = start; i + sub.length <= end; i++) {
            int k = 0;
            while (k < sub.length && d[i + k] == sub[k]) k++;
            if (k == sub.length) return i;
        }
        return -1;
    }

    private static final byte[] SLOC = ascii("sloc"), TIM2 = ascii("TIM2"), NPS2 = ascii("NPS2"),
            LCMPOS1 = ascii("LCMPOS1"), NAVIICN2 = ascii("NAVIICN2");

    private static byte[] ascii(String s) { return s.getBytes(StandardCharsets.US_ASCII); }

    private static boolean starts(byte[] d, byte[] magic) {
        return d.length >= magic.length && Arrays.equals(Arrays.copyOf(d, magic.length), magic);
    }

    // ------------------------------------------------------------------------------------------------- sloc

    static final class Sheet {
        int mapId, width, height;
        byte[] mask;
        double[] nps2;
    }

    /** menumap_sloc.parse: sheets (mask, map ID, size) and their NPS2 records; same refusals. */
    static List<Sheet> parseSloc(byte[] data) throws Bad {
        need(data.length <= 64 << 20, "file too large");
        need(starts(data, SLOC), "not a sloc file");
        int table = u16(data, 10, data.length);
        int count = u16(data, table, data.length), rel = u16(data, table + 2, data.length);
        need(count > 0 && count <= 64, "implausible sheet count");
        long[] rawIds = new long[count];
        for (int k = 0; k < count; k++) rawIds[k] = u32(data, table + rel + 4 * k, data.length);
        long infoN = u32(data, 0x10, data.length);
        need(infoN <= 64, "implausible info count");
        int[] offs = new int[(int) infoN];
        for (int k = 0; k < infoN; k++) offs[k] = u16(data, 0x14 + 2 * k, data.length);
        for (int k = 0; k < infoN; k++) {
            int start = 0x10 + offs[k], end = k + 1 < infoN ? 0x10 + offs[k + 1] : table;
            need(0 < start && start < end && end <= data.length, "info block out of range");
            u16(data, start, end);                                  // map id (read, as Python does)
            int lp = find(data, LCMPOS1, start, end), ni = find(data, NAVIICN2, start, end);
            if (lp >= 0) {                                          // LCMPOS1 floats: read like Python, unused here
                int stop = ni > lp ? ni : end;
                for (int q = lp + 0x70; q < stop - 15; q += 16) f32(data, q);
            }
        }
        List<Sheet> sheets = new ArrayList<>();
        int o = table;
        for (int k = 0; k < count; k++) {
            o = find(data, TIM2, o, data.length);
            need(o >= 0, "TIM2 not found");
            long img = u32(data, o + 16 + 8, data.length);
            int hdr = u16(data, o + 16 + 12, data.length);
            int itype = u8(data, o + 16 + 19, data.length);
            int w = u16(data, o + 16 + 20, data.length), h = u16(data, o + 16 + 22, data.length);
            u32(data, o + 16, data.length); u32(data, o + 16 + 4, data.length);   // total, clut (read)
            u32(data, o + 16 + 24, data.length); u32(data, o + 16 + 28, data.length); // tex0 (read)
            long start = (long) o + 16 + hdr;
            need(itype == 5 && w > 0 && w <= 4096 && h > 0 && h <= 4096 && img == (long) w * h
                    && start + img <= data.length, "unsupported or truncated TIM2");
            Sheet s = new Sheet();
            s.mapId = (int) (rawIds[k] & 0xFFFF);
            s.width = w;
            s.height = h;
            s.mask = Arrays.copyOfRange(data, (int) start, (int) (start + img));
            sheets.add(s);
            o = (int) (start + img);
        }
        List<double[]> nps2 = new ArrayList<>();
        int p = find(data, NPS2, o, data.length);
        while (p >= 0 && nps2.size() < count) {
            nps2.add(new double[] {f32(data, p + 16), f32(data, p + 20), f32(data, p + 24), f32(data, p + 28)});
            p = find(data, NPS2, p + 32, data.length);
        }
        need(nps2.size() == count, "NPS2 records do not match the sheets");
        for (int k = 0; k < count; k++) sheets.get(k).nps2 = nps2.get(k);
        return sheets;
    }

    static List<String> occupancy(Sheet s) {
        List<String> rows = new ArrayList<>();
        for (int ty = 0; ty < s.height / TILE; ty++) {
            StringBuilder r = new StringBuilder();
            for (int tx = 0; tx < s.width / TILE; tx++) {
                boolean any = false;
                for (int y = ty * TILE; y < (ty + 1) * TILE && !any; y++)
                    for (int x = tx * TILE; x < (tx + 1) * TILE; x++)
                        if (s.mask[y * s.width + x] != 0) { any = true; break; }
                r.append(any ? '#' : '.');
            }
            rows.add(r.toString());
        }
        return rows;
    }

    // ------------------------------------------------------------------------------------------------- NAV

    static byte[][] parseNav(byte[] d) throws Bad {
        need(d.length >= 8 && d[0] == 'N' && d[1] == 'A' && d[2] == 'V', "not a NAV file");
        long count = u32(d, 4, d.length);
        need(8 + 3 * count == d.length, "NAV size does not match its record count");
        byte[][] recs = new byte[(int) count][];
        for (int i = 0; i < count; i++) recs[i] = Arrays.copyOfRange(d, 8 + 3 * i, 11 + 3 * i);
        return recs;
    }

    static Integer slotOf(byte[][] nav, int mapId) {
        if (mapId >= nav.length) return null;
        int v = ((nav[mapId][0] & 255) | (nav[mapId][1] & 255) << 8) & 0xFFF;
        return (v == 0xFFF || v > 0x180) ? null : v;
    }

    // ------------------------------------------------------------------------------------------------- lmap

    static final class Lmap {
        final List<int[]> maps = new ArrayList<>();     // x, y, map_id
        final List<int[]> exits = new ArrayList<>();    // x, y, x2, y2, text_id, gate
        final List<int[]> links = new ArrayList<>();    // map_a, map_b, xa, ya, xb, yb
    }

    /** menumap_tables.parse_location. */
    static List<Lmap> parseLocation(byte[] d) throws Bad {
        need(d.length <= 1 << 20 && d.length >= 4 && d[0] == 'l' && d[1] == 'm' && d[2] == 'a' && d[3] == 'p', "not an lmap file");
        int count = u8(d, 9, d.length);
        int[] offs = new int[count + 1];
        for (int k = 0; k < count; k++) offs[k] = u16(d, 0x0A + 2 * k, d.length);
        offs[count] = d.length;
        List<Lmap> out = new ArrayList<>();
        for (int k = 0; k < count; k++) {
            int a = offs[k], b = offs[k + 1];
            need(0 < a && a < b && b <= d.length, "lmap record out of range");
            int nm = u8(d, a, d.length), ne = u8(d, a + 1, d.length), nl = u8(d, a + 2, d.length);
            need(4 + 24 * nm + 24 * ne + 20 * nl + 8 <= b - a, "lmap counts exceed the record");
            Lmap r = new Lmap();
            int o = a + 4;
            for (int i = 0; i < nm; i++, o += 24)
                r.maps.add(new int[] {u16(d, o, d.length), u16(d, o + 2, d.length), u16(d, o + 12, d.length)});
            for (int i = 0; i < ne; i++, o += 24)
                r.exits.add(new int[] {u16(d, o, d.length), u16(d, o + 2, d.length), u16(d, o + 8, d.length),
                        u16(d, o + 10, d.length), u16(d, o + 12, d.length), u16(d, o + 18, d.length)});
            for (int i = 0; i < nl; i++, o += 20)
                r.links.add(new int[] {u16(d, o, d.length), u16(d, o + 2, d.length), u16(d, o + 4, d.length),
                        u16(d, o + 6, d.length), u16(d, o + 12, d.length), u16(d, o + 14, d.length)});
            out.add(r);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------- st2e labels

    private static final Map<Integer, Character> PUNCT = new TreeMap<>();
    static {
        PUNCT.put(0x04, ' '); PUNCT.put(0xAA, ':'); PUNCT.put(0xAC, '\''); PUNCT.put(0xB2, '<'); PUNCT.put(0xA6, '=');
        PUNCT.put(0x9E, '%'); PUNCT.put(0xC4, '≥'); PUNCT.put(0xA5, '-'); PUNCT.put(0xA4, '+'); PUNCT.put(0xA7, ',');
        PUNCT.put(0xA3, '-');
    }

    /** gambit_names.decode; null when any byte is unknown (the builder leaves such labels out). */
    static String decode(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) {
            int c = x & 255;
            if (c >= 0x20 && c <= 0x39) s.append((char) (c + 0x21));
            else if (c >= 0x3A && c <= 0x53) s.append((char) (c + 0x27));
            else if (c >= 0x85 && c <= 0x8E) s.append((char) (c - 0x85 + 48));
            else if (PUNCT.containsKey(c)) s.append(PUNCT.get(c));
            else return null;
        }
        return s.toString();
    }

    /** menumap_names.parse: {text_id: label} for rows that decode completely. */
    static Map<Integer, String> parseMapnames(byte[] d) throws Bad {
        need(d.length <= 1 << 20 && d.length >= 4 && d[0] == 's' && d[1] == 't' && d[2] == '2' && d[3] == 'e', "not a menu_mapname file");
        long count = u32(d, 4, d.length), stride = u32(d, 8, d.length), rows = u32(d, 12, d.length);
        need(count <= 4096 && stride == 4 && rows + count * stride <= d.length, "bad row table");
        long root = u32(d, 0x14, d.length);
        need(root == rows + count * stride, "unexpected text section offset");
        long groups = u32(d, (int) root, d.length), rel = u32(d, (int) root + 4, d.length);
        need(groups == 1, "unexpected text group count");
        long base = root + rel;
        need(base <= Integer.MAX_VALUE && u32(d, (int) base, d.length) == count, "text count does not match the rows");
        Map<Integer, String> out = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int tid = u16(d, (int) rows + i * 4, d.length), zero = u16(d, (int) rows + i * 4 + 2, d.length);
            need(zero == 0, "non-zero row tail");
            long at = base + u32(d, (int) base + 4 + 4 * i, d.length);
            need(at >= base + 4 + 4 * count && at < d.length, "text offset out of range");
            int stop = -1;
            for (int q = (int) at; q < Math.min(d.length, at + 512); q++) if (d[q] == 0) { stop = q; break; }
            need(stop >= 0, "unterminated text");
            String text = decode(Arrays.copyOfRange(d, (int) at, stop));
            if (text != null && !text.isEmpty()) out.put(tid, text);
        }
        return out;
    }

    private static int s16v(int v) { return v >= 0x8000 ? v - 0x10000 : v; }

    /** mapatlas_pack.region_layout + menumap_names.exit_labels. */
    static Map<String, Object> regionLayout(String region, List<Map<String, Object>> sheets, List<Lmap> lmap,
                                            Map<Integer, String> labels) {
        if (!region.startsWith("sub") || region.length() == 3) return null;
        for (int i = 3; i < region.length(); i++) if (!Character.isDigit(region.charAt(i))) return null;
        int k0 = Integer.parseInt(region.substring(3));
        if (k0 >= lmap.size()) return null;
        Lmap rec = lmap.get(k0);
        List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
        for (int[] m : rec.maps) a.add(m[2]);
        for (Map<String, Object> s : sheets) b.add((Integer) s.get("mapId"));
        Collections.sort(a);
        Collections.sort(b);
        if (!a.equals(b)) return null;
        Map<Integer, Integer> taken = new TreeMap<>();
        List<Object> placed = new ArrayList<>();
        for (Map<String, Object> s : sheets) {
            int id = (Integer) s.get("mapId"), k = taken.getOrDefault(id, 0);
            taken.put(id, k + 1);
            int seen = 0;
            int[] m = null;
            for (int[] e : rec.maps) if (e[2] == id && seen++ == k) { m = e; break; }
            placed.add(map("mapId", id, "sheet", s.get("sheet"), "file", s.get("file"), "x", m[0], "y", m[1]));
        }
        List<Object> links = new ArrayList<>();
        for (int[] l : rec.links) links.add(map("a", l[0], "b", l[1], "ax", l[2], "ay", l[3], "bx", l[4], "by", l[5]));
        List<Object> exits = new ArrayList<>();
        for (int[] e : rec.exits) {
            String text = labels.get(e[4]);
            if (text == null || text.isEmpty()) continue;
            exits.add(map("x", s16v(e[0]), "y", s16v(e[1]), "lx", s16v(e[2]), "ly", s16v(e[3]), "text", text, "gate", e[5]));
        }
        return map("region", region, "sheets", placed, "links", links, "exits", exits);
    }

    // ------------------------------------------------------------------------------------------------- symbols

    /** menumap_icons.navi: (map_id, group, index, type, sx, sy, dx, dy, gate) rows in file order. */
    static List<int[]> navi(byte[] data) throws Bad {
        need(data.length >= 20 && data.length <= 64 << 20 && starts(data, SLOC), "not a sloc file");
        int table = u16(data, 10, data.length);
        long count = u32(data, 16, data.length);
        need(count <= 64 && 20 + 2 * count <= table && table <= data.length, "sloc directory");
        int[] offsets = new int[(int) count];
        for (int i = 0; i < count; i++) offsets[i] = u16(data, 20 + 2 * i, data.length);
        List<int[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int start = 16 + offsets[i], end = i + 1 < count ? 16 + offsets[i + 1] : table;
            need(20 + 2 * count <= start && start < end && end <= table, "sloc info extent");
            int mapId = u16(data, start, end);
            int tag = find(data, NAVIICN2, start, end);
            if (tag < 0) continue;
            need(tag + 0x32 <= end && u16(data, tag + 8, end) == 10, "NAVIICN2 header");
            for (int g = 1; g < 10; g++) {
                int off = u16(data, tag + 0xA + 4 * g, end), n = u16(data, tag + 0xC + 4 * g, end);
                need(n <= 4096 && (n == 0 || (off >= 0x32 && (long) tag + off + 16L * n <= end)), "NAVIICN2 rows");
                for (int idx = 0; idx < n; idx++) {
                    int q = tag + off + 16 * idx;
                    u32(data, q + 12, end);
                    rows.add(new int[] {mapId, g, idx, s16(data, q + 8, end), s16(data, q, end), s16(data, q + 2, end),
                            s16(data, q + 4, end), s16(data, q + 6, end), u16(data, q + 10, end)});
                }
            }
        }
        return rows;
    }

    /** menumap_icons.flagrom: threshold rows (u16 lists, the last word with bit 15) or null. */
    static List<Object> flagrom(byte[] d) throws Bad {
        need(d.length >= 10 && d.length <= 65536 && new String(d, 0, 8, StandardCharsets.ISO_8859_1).equals("FLAGROM1"), "not a FLAGROM1 file");
        int count = u16(d, 8, d.length);
        need(count <= 4096 && 10 + 2 * count <= d.length, "FLAGROM1 directory");
        List<Object> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int o = u16(d, 10 + 2 * i, d.length);
            if (o == 0) { rows.add(null); continue; }
            need(o >= 10 + 2 * count && (o & 1) == 0, "FLAGROM1 row offset");
            List<Object> row = new ArrayList<>();
            boolean ended = false;
            for (int n = 0; n < 64; n++) {
                int w = u16(d, o, d.length);
                o += 2;
                row.add(w);
                if ((w & 0x8000) != 0) { ended = true; break; }
            }
            need(ended, "FLAGROM1 row too long");
            rows.add(row);
        }
        return rows;
    }

    /** menumap_icons.symbols: (type, width, height, rgba) for types 0..28 from loca_8_c.tm2. */
    static List<Object[]> symbolImages(byte[] data) throws Bad {
        need(data.length <= 4 << 20 && data.length >= 4 && starts(data, TIM2) && u16(data, 6, data.length) == 1, "unsupported TIM2");
        long total = u32(data, 16, data.length), clutSize = u32(data, 20, data.length), imageSize = u32(data, 24, data.length);
        int hs = u16(data, 28, data.length), colors = u16(data, 30, data.length);
        int fmt = u8(data, 32, data.length), mip = u8(data, 33, data.length), ct = u8(data, 34, data.length), it = u8(data, 35, data.length);
        int w = u16(data, 36, data.length), h = u16(data, 38, data.length);
        need(total == data.length - 16 && hs >= 48 && 16 + hs <= data.length && fmt == 0 && mip == 1 && ct == 3 && it == 5, "unsupported picture");
        need(w > 0 && w <= 2048 && h > 0 && h <= 2048 && imageSize == (long) w * h && clutSize == colors * 4L
                && colors % 256 == 0 && colors <= 4096 && hs + imageSize + clutSize == total, "inconsistent picture sizes");
        int ext = 0x40, headEnd = 16 + hs;
        need(ext + 4 <= headEnd && data[ext] == 'e' && data[ext + 1] == 'X' && data[ext + 2] == 't' && data[ext + 3] == 0, "missing eXt");
        int uv = ext + u16(data, ext + 0x10, headEnd), pal = ext + u16(data, ext + 0x12, headEnd), group = u16(data, ext + 0x14, headEnd);
        need(group <= 16 && u16(data, ext + 0x16 + 2 * group, headEnd) == 9, "unexpected symbol group");
        int pix = 16 + hs, clut = (int) (16 + hs + imageSize);
        List<Object[]> out = new ArrayList<>();
        for (int typ = 0; typ < ICON_TYPES; typ++) {
            int x = u16(data, uv + 8 * (9 + typ), headEnd), y = u16(data, uv + 8 * (9 + typ) + 2, headEnd);
            long packed = u32(data, uv + 8 * (9 + typ) + 4, headEnd);
            int cw = (int) (packed & 4095), ch = (int) ((packed >> 12) & 4095), sel = (int) (packed >>> 24);
            int bank = u8(data, pal + (sel >> 4), headEnd) + (sel & 15);
            need(cw > 0 && cw <= w && ch > 0 && ch <= h && x + cw <= w && y + ch <= h && bank < colors / 256,
                    "symbol rectangle or palette out of range");
            byte[] rgba = new byte[cw * ch * 4];
            int q = 0;
            for (int yy = y; yy < y + ch; yy++)
                for (int xx = x; xx < x + cw; xx++) {
                    int index = data[pix + yy * w + xx] & 255;
                    int k = (index & ~24) | ((index & 8) << 1) | ((index & 16) >> 1);    // PS2 CSM1 CLUT order
                    int c = clut + bank * 1024 + k * 4;
                    rgba[q++] = data[c];
                    rgba[q++] = data[c + 1];
                    rgba[q++] = data[c + 2];
                    rgba[q++] = (byte) Math.min(255, (data[c + 3] & 255) * 2);
                }
            out.add(new Object[] {typ, cw, ch, rgba});
        }
        return out;
    }
}
