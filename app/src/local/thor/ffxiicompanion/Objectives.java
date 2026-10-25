package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 0.14.0: the game's own story hint and hunt journal (Objectives_Hunts_2026-10-22.md; reference/objectives.json, built
 * from the user's own game files; never from guides or wikis).
 * - Hint: the line at the bottom of the game's map screen. The game takes the LAST row whose threshold <= the signed
 *   scenario variable and whose gate is 0xFFFF or open (0x3BD950). The live inputs are data.flags (Flags).
 * - Hunts: listed when either of the quest menu's banks has the quest's bit (0x37EAA0); phase = nibble (stage / 5) of
 *   the hunt's 28-byte table (0x37EF70); the journal shows paragraph phase - 1. The companion lists only hunts at
 *   phase 2 or later, which the game's own text says is "accepted" for all 45 hunts.
 * - Hunt maps (0.17.0, HuntMap_Native_2026-10-25.md): each hunt's QHD0 helper lists maps; the game shows the first
 *   entry whose map mask has bit min(stage / 5, 50) (0x37EB90) and marks the NAVIICN2 group-9 rows of that map whose
 *   marker mask has the bit too (0x3BDCD0). That is where the game's own hunt map points, not where the mark spawns.
 */
final class Objectives {
    static final String FORMAT = "ffxii-companion-objectives-1";
    static final String QUESTS = "ffxii.quests.v1";
    static final int MAX_BYTES = 512 * 1024;

    static final class Hint { int threshold, gate; String text; }

    static final class Hunt {
        int questId;
        String title;
        byte[] phases;               // 28 bytes
        List<String> paragraphs;     // null entries: text the builder couldn't decode
        int mapKind = -1;            // 1: maps with markers, 0: whole regions; -1: none in this data pack
        final List<MapEntry> map = new ArrayList<>();
    }

    /** One QHD0 entry: a map (kind 1) or a region (kind 0), and the stage bits that show it and its marker. */
    static final class MapEntry {
        int mapId, markerIndex, regionId;
        byte[] show, marker;         // 7 bytes each
    }

    /** 0.17.0: a world-map area a kind-0 hunt names, with its atlas regions {key "subKK", name or null}. */
    static final class WorldArea {
        int worldId;
        String name;
        final List<String[]> regions = new ArrayList<>();
    }

    /** What the game's hunt map shows for a hunt now: a map and its marker rows (group 9). */
    static final class HuntMap {
        final int mapId;
        final List<Integer> markers;
        HuntMap(int mapId, List<Integer> markers) { this.mapId = mapId; this.markers = markers; }
    }

    /** A hunt as the journal shows it now. */
    static final class Entry {
        final Hunt hunt;
        final int phase;
        final String paragraph;      // null when the game has none for this phase or it didn't decode
        final int stage;             // the raw stage byte
        Entry(Hunt hunt, int phase, String paragraph, int stage) {
            this.hunt = hunt; this.phase = phase; this.paragraph = paragraph; this.stage = stage;
        }
    }

    final List<Hint> hints = new ArrayList<>();
    final List<Hunt> hunts = new ArrayList<>();
    final List<WorldArea> worldAreas = new ArrayList<>();
    int[][] thresholds;

    private Objectives() {}

    static Objectives parse(Map<String, Object> o) {
        if (o == null || !FORMAT.equals(Json.str(o, "format"))) return null;
        Objectives r = new Objectives();
        List<Object> hs = Json.arr(o, "hints");
        if (hs != null) for (Object h : hs) {
            Long th = Json.lng(h, "threshold"), g = Json.lng(h, "gate");
            if (th == null || g == null || th < 0 || th > 0xFFFF || g < 0 || g > 0xFFFF) return null;   // order matters: all or none
            Hint x = new Hint();
            x.threshold = th.intValue();
            x.gate = g.intValue();
            String t = Json.str(h, "text");
            x.text = t != null && t.length() <= 300 ? t : null;
            r.hints.add(x);
        }
        List<Object> qs = Json.arr(o, "hunts");
        if (qs != null) for (Object q : qs) {
            Long id = Json.lng(q, "questId");
            String phases = Json.str(q, "phases");
            if (id == null || id < 0 || id > 255 || phases == null || phases.length() != 56) continue;
            Hunt h = new Hunt();
            h.questId = id.intValue();
            h.phases = hex(phases);
            if (h.phases == null) continue;
            String t = Json.str(q, "title");
            h.title = t != null && t.length() <= 100 ? t : null;
            h.paragraphs = new ArrayList<>();
            List<Object> ps = Json.arr(q, "paragraphs");
            if (ps != null) for (Object p : ps) h.paragraphs.add(p instanceof String && ((String) p).length() <= 2000 ? (String) p : null);
            parseMap(h, Json.obj(q, "mapHints"));
            r.hunts.add(h);
        }
        List<Object> wa = Json.arr(o, "worldAreas");
        if (wa != null) for (Object w : wa) {
            Long id = Json.lng(w, "worldId");
            List<Object> rs = Json.arr(w, "regions");
            if (id == null || id < 0 || id > 255 || rs == null || rs.size() > 64) continue;
            WorldArea a = new WorldArea();
            a.worldId = id.intValue();
            String n = Json.str(w, "name");
            a.name = n != null && n.length() <= 64 ? n : null;
            for (Object x : rs) {
                String key = Json.str(x, "region"), rn = Json.str(x, "name");
                if (key == null || !key.matches("sub[0-9]{2}")) continue;
                a.regions.add(new String[] { key, rn != null && rn.length() <= 64 ? rn : null });
            }
            r.worldAreas.add(a);
        }
        List<Object> th = Json.arr(o, "thresholds");
        if (th != null) {
            r.thresholds = new int[Math.min(th.size(), 4096)][];
            for (int i = 0; i < r.thresholds.length; i++) {
                if (!(th.get(i) instanceof List)) continue;
                List<?> row = (List<?>) th.get(i);
                if (row.size() > 64) continue;
                r.thresholds[i] = new int[row.size()];
                for (int k = 0; k < row.size(); k++) {
                    Object v = row.get(k);
                    r.thresholds[i][k] = v instanceof Long && (Long) v >= 0 && (Long) v <= 0xFFFF ? ((Long) v).intValue() : 0xFFFF;
                }
            }
        }
        return r;
    }

    /** The hunt's map helper; anything malformed leaves the hunt without one (its journal entry still shows). */
    private static void parseMap(Hunt h, Map<String, Object> m) {
        Long kind = m != null ? Json.lng(m, "kind") : null;
        List<Object> es = m != null ? Json.arr(m, "entries") : null;
        if (kind == null || (kind != 0 && kind != 1) || es == null || es.size() > 64) return;
        List<MapEntry> out = new ArrayList<>();
        for (Object o : es) {
            MapEntry e = new MapEntry();
            e.show = hex(Json.str(o, "showMask"));
            e.marker = hex(Json.str(o, "markerMask"));
            if (e.show == null || e.marker == null || e.show.length != 7 || e.marker.length != 7) return;
            Long a = Json.lng(o, kind == 1 ? "mapId" : "regionId"), b = kind == 1 ? Json.lng(o, "markerIndex") : Long.valueOf(0);
            if (a == null || b == null || a < 0 || a > 0xFFFF || b < 0 || b > 255) return;
            if (kind == 1) { e.mapId = a.intValue(); e.markerIndex = b.intValue(); } else e.regionId = a.intValue();
            out.add(e);
        }
        h.mapKind = kind.intValue();
        h.map.addAll(out);
    }

    private static boolean bit(byte[] mask, int k) { return (mask[k >> 3] >> (k & 7) & 1) != 0; }

    /** The map the game's hunt map shows at this raw stage, with its marker rows; null when none or region-only. */
    static HuntMap mapFor(Hunt h, int stage) {
        if (h == null || h.mapKind != 1 || stage < 0 || stage > 255) return null;
        int k = Math.min(stage / 5, 50);
        MapEntry chosen = null;
        for (MapEntry e : h.map) if (bit(e.show, k)) { chosen = e; break; }
        if (chosen == null) return null;
        List<Integer> markers = new ArrayList<>();
        for (MapEntry e : h.map)
            if (e.mapId == chosen.mapId && bit(e.show, k) && bit(e.marker, k) && !markers.contains(e.markerIndex)) markers.add(e.markerIndex);
        return new HuntMap(chosen.mapId, markers);
    }

    /** 0.17.0: the world-map areas a kind-0 hunt's map shows at this raw stage (every showing entry, in file order). */
    List<WorldArea> areasFor(Hunt h, int stage) {
        List<WorldArea> out = new ArrayList<>();
        if (h == null || h.mapKind != 0 || stage < 0 || stage > 255) return out;
        int k = Math.min(stage / 5, 50);
        for (MapEntry e : h.map) {
            if (!bit(e.show, k)) continue;
            for (WorldArea a : worldAreas) if (a.worldId == e.regionId && !out.contains(a) && !a.regions.isEmpty()) out.add(a);
        }
        return out;
    }

    /** The game's current map hint (0x3BD950), or null without live flags or when no row applies. */
    String hint(Flags f) {
        if (f == null) return null;
        int s = (short) f.scenario;
        for (int i = hints.size() - 1; i >= 0; i--) {
            Hint h = hints.get(i);
            if (s >= h.threshold && (h.gate == 0xFFFF || f.gate(h.gate, thresholds))) return h.text;
        }
        return null;
    }

    /** Phase of a hunt for a raw stage byte (0x37EF70). */
    static int phase(byte[] table, int raw) {
        int stage = raw / 5;
        return (table[stage >> 1] >> (4 * (stage & 1))) & 0xF;
    }

    /** Hunts the game's journal lists, at phase 2 (accepted) or later, in the menu's order; empty without live data. */
    List<Entry> journal(Map<String, Object> quests) {
        if (quests == null || !QUESTS.equals(Json.str(quests, "contract")) || !"ok".equals(Json.str(quests, "status")))
            return Collections.emptyList();
        Long first = Json.lng(quests, "first"), count = Json.lng(quests, "count");
        byte[] a = hex(Json.str(quests, "bankA")), b = hex(Json.str(quests, "bankB")), stages = hex(Json.str(quests, "stages"));
        if (first == null || count == null || a == null || b == null || stages == null || a.length != 32 || b.length != 32
                || stages.length != count || first < 0 || first + count > 256) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        for (Hunt h : hunts) {
            int q = h.questId;
            boolean listed = ((a[q >> 3] | b[q >> 3]) >> (q & 7) & 1) != 0;
            if (!listed || q < first || q >= first + count) continue;
            int p = phase(h.phases, stages[q - first.intValue()] & 0xFF);
            if (p < 2) continue;
            String para = p - 1 < h.paragraphs.size() ? h.paragraphs.get(p - 1) : null;
            out.add(new Entry(h, p, para, stages[q - first.intValue()] & 0xFF));
        }
        return out;
    }

    static byte[] hex(String s) {
        if (s == null || s.length() % 2 != 0) return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) (hi << 4 | lo);
        }
        return b;
    }
}
