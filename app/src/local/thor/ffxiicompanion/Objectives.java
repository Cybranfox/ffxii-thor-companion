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
 * There is no numeric location for either in the game's files, so neither becomes a map pin.
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
    }

    /** A hunt as the journal shows it now. */
    static final class Entry {
        final Hunt hunt;
        final int phase;
        final String paragraph;      // null when the game has none for this phase or it didn't decode
        Entry(Hunt hunt, int phase, String paragraph) { this.hunt = hunt; this.phase = phase; this.paragraph = paragraph; }
    }

    final List<Hint> hints = new ArrayList<>();
    final List<Hunt> hunts = new ArrayList<>();
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
            r.hunts.add(h);
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
            out.add(new Entry(h, p, para));
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
