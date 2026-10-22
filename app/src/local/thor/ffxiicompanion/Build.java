package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Party build from data.build (contract ffxii.build.v1, ThorCompanion.lua 0.5.4+; Party_Build_054_2026-10-21.md):
 * per member the two jobs, licence points and licence bits (runtime-confirmed by an in-game licence purchase), and the
 * party's owned gambits / magicks / technicks (0.5.5, PROBE: found in a live dump, not yet tied to game code), and (0.5.8)
 * consumable stock: 64 packed u16 by item ID, the copy the game's gambit item check reads (Item_Actions_Trace_2026-10-21.md).
 */
final class Build {
    static final String CONTRACT = "ffxii.build.v1";

    static final class Member {
        final int id, job1, job2, lp;      // job 0xFF = none
        final byte[] licences;              // node n = licences[n >> 3] bit (n & 7)
        Member(int id, int job1, int job2, int lp, byte[] licences) {
            this.id = id; this.job1 = job1; this.job2 = job2; this.lp = lp; this.licences = licences;
        }
        boolean owns(int node) { return node >= 0 && (node >> 3) < licences.length && (licences[node >> 3] >> (node & 7) & 1) != 0; }
    }

    final List<Member> members;
    /** Owned item IDs in list order; null when data.build.owned is missing or not ok. */
    final List<Integer> gambits, magicks, technicks;
    /** Raw stock words by item ID (low 10 bits = count, high 6 subtracted for "available"); null when not published. */
    final int[] stock;
    /** (0.18.0) Equipped IDs per member, five slots (Equipped.SLOTS), 0xFFFF empty; null when not published. */
    final int[][] gear;
    /** (0.19.0) Equipment stock by ID - 0x1000, 420 words: low 10 bits owned, high 6 equipped; null when not published. */
    final int[] equipStock;
    /** (0.23.0) Key-item words (0xC000+i = bit i&15 of word i>>4) and records 0..6 (flag byte, licence bits); null if absent. */
    int[] keyItems;
    byte[][] roster;
    int[] rosterFlags;

    private Build(List<Member> members, List<Integer> gambits, List<Integer> magicks, List<Integer> technicks, int[] stock, int[][] gear,
                  int[] equipStock) {
        this.members = members; this.gambits = gambits; this.magicks = magicks; this.technicks = technicks; this.stock = stock;
        this.gear = gear; this.equipStock = equipStock;
    }

    boolean ownershipKnown() { return gambits != null && magicks != null && technicks != null; }
    boolean stockKnown() { return stock != null; }

    /** Item ID for a gambit item action (0x52..0x91 minus the 13 reserved rows 0x6F..0x7B), else -1. */
    static int itemOf(int action) {
        if (action < 0x52 || action > 0x91 || (action >= 0x6F && action <= 0x7B)) return -1;
        return action - 0x52;
    }

    /** What the game's gambit item check counts (getter mode 0: low10 - high6), or -1 when unknown / not an item. */
    int available(int action) {
        int i = itemOf(action);
        if (stock == null || i < 0) return -1;
        return Math.max(0, (stock[i] & 0x3FF) - (stock[i] >> 10));
    }

    /** Null unless data.build is an ok, stable ffxii.build.v1 record with six well-formed members. */
    static Build parse(Map<String, Object> b) {
        if (b == null || !CONTRACT.equals(Json.str(b, "contract")) || !"ok".equals(Json.str(b, "status"))
                || !Boolean.TRUE.equals(Json.bool(b, "stable"))) return null;
        String s = Json.str(b, "members");
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 6) return null;
        List<Member> ms = new ArrayList<>();
        for (int m = 0; m < parts.length; m++) {
            String[] f = parts[m].split(":");
            if (f.length != 3 || f[0].length() != 4) return null;
            byte[] bits = Gambits.hexBytes(f[2]);
            if (bits == null) return null;
            try {
                ms.add(new Member(m, Integer.parseInt(f[0].substring(0, 2), 16), Integer.parseInt(f[0].substring(2), 16),
                        Integer.parseInt(f[1]), bits));
            } catch (NumberFormatException e) { return null; }
        }
        Map<String, Object> o = Json.obj(b, "owned");
        List<Integer> g = null, mg = null, t = null;
        if (o != null && "ok".equals(Json.str(o, "status"))) {
            g = ids(Json.str(o, "gambits"), 0x6000, 256);
            mg = ids(Json.str(o, "magicks"), 0x3000, 81);
            t = ids(Json.str(o, "technicks"), 0x4000, 24);
            if (g == null || mg == null || t == null) g = mg = t = null;
        }
        Map<String, Object> st = Json.obj(b, "stock");
        int[] stock = st != null && "ok".equals(Json.str(st, "status")) ? words(Json.str(st, "words"), 64) : null;
        Map<String, Object> gr = Json.obj(b, "gear");
        int[][] gear = gr != null && "ok".equals(Json.str(gr, "status")) ? gear(Json.str(gr, "members")) : null;
        Map<String, Object> es = Json.obj(b, "equipStock");
        int[] equipStock = es != null && "ok".equals(Json.str(es, "status")) ? words(Json.str(es, "words"), 420) : null;
        Build out = new Build(Collections.unmodifiableList(ms), g, mg, t, stock, gear, equipStock);
        Map<String, Object> ki = Json.obj(b, "keyItems");
        if (ki != null && "ok".equals(Json.str(ki, "status"))) {
            String w = Json.str(ki, "words");
            out.keyItems = w != null && w.length() % 4 == 0 && w.length() > 0 ? words(w, w.length() / 4) : null;
        }
        Map<String, Object> ro = Json.obj(b, "roster");
        String rs = ro != null && "ok".equals(Json.str(ro, "status")) ? Json.str(ro, "members") : null;
        if (rs != null) {
            String[] rp = rs.split(",");
            byte[][] lic = new byte[rp.length][];
            int[] fl = new int[rp.length];
            boolean okR = rp.length > 0;
            for (int i = 0; i < rp.length && okR; i++) {
                String[] f = rp[i].split(":");
                byte[] bits = f.length == 2 ? Gambits.hexBytes(f[1]) : null;
                if (bits == null || f[0].length() != 2) { okR = false; break; }
                try { fl[i] = Integer.parseInt(f[0], 16); } catch (NumberFormatException e) { okR = false; break; }
                lic[i] = bits;
            }
            if (okR) { out.roster = lic; out.rosterFlags = fl; }
        }
        return out;
    }

    private static int[][] gear(String s) {
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length != 6) return null;
        int[][] out = new int[6][5];
        for (int m = 0; m < 6; m++) {
            if (parts[m].length() != 20) return null;
            try { for (int i = 0; i < 5; i++) out[m][i] = Integer.parseInt(parts[m].substring(4 * i, 4 * i + 4), 16); }
            catch (NumberFormatException e) { return null; }
        }
        return out;
    }

    private static int[] words(String hex, int n) {
        if (hex == null || hex.length() != n * 4) return null;
        int[] w = new int[n];
        try { for (int i = 0; i < n; i++) w[i] = Integer.parseInt(hex.substring(4 * i, 4 * i + 4), 16); }
        catch (NumberFormatException e) { return null; }
        return w;
    }

    private static List<Integer> ids(String hex, int base, int cap) {
        if (hex == null || hex.length() % 4 != 0) return null;
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < hex.length(); i += 4) {
            int v;
            try { v = Integer.parseInt(hex.substring(i, i + 4), 16); } catch (NumberFormatException e) { return null; }
            if (v < base || v >= base + cap || out.contains(v)) return null;
            out.add(v);
        }
        return Collections.unmodifiableList(out);
    }
}
