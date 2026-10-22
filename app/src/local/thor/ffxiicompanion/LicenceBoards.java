package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Licence boards (0.21.0): the 12 job boards and licence names from boards.json (format ffxii-companion-boards-1, built on
 * the PC from the user's own game files by draft/tools/boards_pack.py; never shipped in the APK). Ownership, LP, costs and
 * types are live (Build, LicenceNodes). Pure: no Android types.
 *
 * Cell state: OWNED (the member's licence bit for that node), OPEN (not owned, orthogonally next to an owned cell on the
 * same board: how a board unlocks), LOCKED (anything else). OPEN ignores the game's extra rules for Espers and
 * Quickenings (one character per Esper, Quickening limits), so the detail view says so for those nodes.
 */
final class LicenceBoards {
    static final String FORMAT = "ffxii-companion-boards-1";
    static final int EMPTY = -1, LOCKED = 0, OPEN = 1, OWNED = 2;

    static final class Board {
        final int job, w, h;
        final String name;
        final int[] cells;                  // node index or -1
        Board(int job, String name, int w, int h, int[] cells) { this.job = job; this.name = name; this.w = w; this.h = h; this.cells = cells; }
        int at(int x, int y) { return x < 0 || y < 0 || x >= w || y >= h ? -1 : cells[y * w + x]; }
    }

    final List<Board> boards;
    private final Map<Integer, String> names;

    private LicenceBoards(List<Board> boards, Map<Integer, String> names) { this.boards = boards; this.names = names; }

    String name(int node) {
        String n = names.get(node);
        return n != null ? n : "Licence " + node;
    }

    /** Board for job j (0..11), or null. */
    Board board(int job) {
        for (Board b : boards) if (b.job == job) return b;
        return null;
    }

    static LicenceBoards parse(Map<String, Object> o) {
        if (o == null || !FORMAT.equals(Json.str(o, "format"))) return null;
        List<Object> bs = Json.arr(o, "boards");
        if (bs == null) return null;
        List<Board> out = new ArrayList<>();
        for (Object b : bs) {
            Long job = Json.lng(b, "job"), w = Json.lng(b, "w"), h = Json.lng(b, "h");
            byte[] raw = Gambits.hexBytes(Json.str(b, "cells"));
            if (job == null || w == null || h == null || raw == null || w < 1 || h < 1 || w > 64 || h > 64 || raw.length != 2 * w * h) return null;
            int[] cells = new int[(int) (w * h)];
            for (int i = 0; i < cells.length; i++) {
                int v = (raw[2 * i] & 0xFF) | (raw[2 * i + 1] & 0xFF) << 8;
                cells[i] = v == 0xFFFF ? EMPTY : v;
            }
            out.add(new Board(job.intValue(), Json.str(b, "name"), w.intValue(), h.intValue(), cells));
        }
        Map<Integer, String> names = new HashMap<>();
        Map<String, Object> nm = Json.obj(o, "names");
        if (nm != null) for (Map.Entry<String, Object> e : nm.entrySet())
            if (e.getValue() instanceof String) try { names.put(Integer.parseInt(e.getKey()), (String) e.getValue()); } catch (NumberFormatException ignored) { }
        return out.isEmpty() ? null : new LicenceBoards(Collections.unmodifiableList(out), names);
    }

    /** State per cell of board b for member m (EMPTY/LOCKED/OPEN/OWNED), row-major. */
    static int[] states(Board b, Build.Member m) {
        int[] s = new int[b.cells.length];
        for (int i = 0; i < s.length; i++) s[i] = b.cells[i] < 0 ? EMPTY : m.owns(b.cells[i]) ? OWNED : LOCKED;
        for (int y = 0; y < b.h; y++)
            for (int x = 0; x < b.w; x++) {
                int i = y * b.w + x;
                if (s[i] != LOCKED) continue;
                if (owned(b, m, x - 1, y) || owned(b, m, x + 1, y) || owned(b, m, x, y - 1) || owned(b, m, x, y + 1)) s[i] = OPEN;
            }
        return s;
    }

    private static boolean owned(Board b, Build.Member m, int x, int y) {
        int n = b.at(x, y);
        return n >= 0 && m.owns(n);
    }

    /** {owned, open, total} cells on the board for this member. */
    static int[] counts(int[] states) {
        int[] c = new int[3];
        for (int s : states) { if (s == OWNED) c[0]++; else if (s == OPEN) c[1]++; if (s != EMPTY) c[2]++; }
        return c;
    }

    /** Licence-node type groups (types 1-14 from Gear_Optimize_Trace / Party_Build; 0x0F-0x1E from the live table's names). */
    static String typeName(int t) {
        if (t >= 1 && t <= 3) return "Weapon";
        if (t == 4) return "Heavy Armor";
        if (t == 5) return "Mystic Armor";
        if (t == 6) return "Light Armor";
        if (t == 7) return "Shield";
        if (t == 8) return "Accessory";
        if (t >= 9 && t <= 0x0D) return "Magick";
        if (t == 0x0E) return "Technick";
        if (t == 0x0F || t == 0x10) return "Augment";
        if (t == 0x11) return "HP boost";
        if (t == 0x12) return "Essentials / Gambit Slot";
        if (t == 0x13) return "Quickening";
        if (t == 0x14) return "Esper";
        if (t == 0x1E) return "Second Board";
        return "Other";
    }
}
