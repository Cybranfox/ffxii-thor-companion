package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * 0.13.0: the game's story flags, from data.flags (contract ffxii.flags.v1, game script 0.13.0+), and the game's own
 * gate test over them (Map_Icons_Flags_2026-10-22.md, checked in the exe):
 * - 0x32A8A0: id = gate & 0x3FFF; bit14 set -> threshold row id; else 0 true, 1 false, >= 0x200 false, otherwise bit
 *   (id & 7) of flag byte id / 8.
 * - 0x377970 (bit14): start false; for each word of the row, while signed16(scenario) >= (word & 0x7FFF) flip the
 *   result, and stop after a word with bit 15. A missing or empty row is false.
 * The thresholds come from the data pack (the game's FLAGROM1, built on the PC); the flags are live.
 */
final class Flags {
    static final String CONTRACT = "ffxii.flags.v1";
    static final int BYTES = 64;

    final byte[] generic;
    final int scenario;        // raw u16, compared signed
    final int currentExit;     // -1 when the game has none

    private Flags(byte[] generic, int scenario, int currentExit) {
        this.generic = generic; this.scenario = scenario; this.currentExit = currentExit;
    }

    /** The live flags, or null unless an ok ffxii.flags.v1 block with exactly 64 bytes. */
    static Flags parse(Map<String, Object> f) {
        if (f == null || !CONTRACT.equals(Json.str(f, "contract")) || !"ok".equals(Json.str(f, "status"))) return null;
        String hex = Json.str(f, "generic");
        Long sc = Json.lng(f, "scenario"), ex = Json.lng(f, "currentExit");
        if (hex == null || hex.length() != 2 * BYTES || sc == null || sc < 0 || sc > 0xFFFF || ex == null) return null;
        byte[] b = new byte[BYTES];
        for (int i = 0; i < BYTES; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16), lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) (hi << 4 | lo);
        }
        return new Flags(b, sc.intValue(), ex.intValue());
    }

    /** The game's generic gate (0x32A8A0); the 0xFFFF "always" sentinel is the caller's to handle. */
    boolean gate(int gate, int[][] thresholds) {
        int id = gate & 0x3FFF;
        if ((gate & 0x4000) != 0) return threshold(thresholds, id, scenario);
        if (id == 0) return true;
        if (id == 1 || id >= 0x200) return false;
        return (generic[id >> 3] >> (id & 7) & 1) != 0;
    }

    static boolean threshold(int[][] rows, int index, int scenarioRaw) {
        if (rows == null || index < 0 || index >= rows.length || rows[index] == null) return false;
        int s = (short) scenarioRaw;
        boolean result = false;
        for (int word : rows[index]) {
            if (s < (word & 0x7FFF)) return result;
            result = !result;
            if ((word & 0x8000) != 0) return result;
        }
        return result;
    }

    /** An exit's label is dimmed while its gate is closed (0x3C24F7 / 0x40463F); unknown flags never dim it. */
    static boolean exitDimmed(Flags f, int gate, int[][] thresholds) {
        return f != null && gate != 0xFFFF && !f.gate(gate, thresholds);
    }

    /**
     * Whether the game draws a map symbol (0x3DFB50, 0x40499A, 0x3FFF40, 0x404A42, 0x404A5F; the field map's default
     * mode). revealed: the area's reveal cells [row][col] (one per 64-px tile = 16 map units), all true when the game
     * shows whole maps. Unknown flags hide every symbol except those gated "always" (0).
     * Group 9 has its own loop (0x403C69..0x403D87, Objectives_Hunts_2026-10-22.md, checked in the exe): gate, type and
     * not type 14, then drawn; no zero-source test, no fog test and no line back to a source point.
     */
    static boolean symbolVisible(Flags f, int[][] thresholds, int group, int index, int type, int sx, int sy, int gate,
                                 boolean[][] revealed) {
        boolean open = gate == 0 || (f != null && f.gate(gate, thresholds));
        if (!open) return false;
        if (group == 9) return type != 0 && type != 14 && gate != 1;
        if (group != 6 && (sx == 0 || sy == 0)) return false;
        if (type == 0 || gate == 1) return false;
        if (type != 14 && type != 20 && type != 23 && type != 24) {
            int cx = sx / 16, cy = sy / 16;      // Java division truncates toward zero, like the game
            if (revealed == null || cy < 0 || cx < 0 || cy >= revealed.length || cx >= revealed[cy].length || !revealed[cy][cx]) return false;
        }
        return type != 14 || (f != null && index == f.currentExit);
    }
}
