package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * The game's gate test (0x32A8A0 / 0x377970) and the symbol rule, on synthetic inputs. Checked once against the
 * static audit's Python replica on all 631 real symbol rows and 111 exits (4452 comparisons, 0 mismatches, 22 Oct).
 */
public final class FlagsTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    @SuppressWarnings("unchecked")
    static Flags flags(String hex, int scenario, int exit) {
        return Flags.parse((Map<String, Object>) Json.parse("{\"contract\":\"ffxii.flags.v1\",\"status\":\"ok\",\"generic\":\""
                + hex + "\",\"scenario\":" + scenario + ",\"currentExit\":" + exit + "}"));
    }

    static String hex(int... setIds) {
        byte[] b = new byte[64];
        for (int id : setIds) b[id >> 3] |= (byte) (1 << (id & 7));
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    public static void main(String[] args) {
        int[][] rows = { null, { 10, 20, 0x801E }, { 0x8005 } };
        Flags f = flags(hex(0x1B, 511), 0, 2);
        check(f != null && f.gate(0x1B, rows) && !f.gate(0x1C, rows) && f.gate(511, rows), "plain flags");
        check(f.gate(0, rows) && !f.gate(1, rows) && !f.gate(0x200, rows) && !f.gate(0x3FFF, rows), "0 true, 1 false, >= 0x200 false");
        boolean[] want = { false, true, false, true };
        int[] at = { 9, 15, 25, 30 };
        for (int i = 0; i < at.length; i++) check(Flags.threshold(rows, 1, at[i]) == want[i], "parity at " + at[i]);
        check(Flags.threshold(rows, 1, 31) && !Flags.threshold(rows, 1, 0xFFFF), "past the end stays; signed -1 is below everything");
        check(!Flags.threshold(rows, 0, 50) && !Flags.threshold(rows, 9, 50) && Flags.threshold(rows, 2, 5), "missing row false");
        check(flags(hex(), 15, -1).gate(0x4001, rows) && !flags(hex(), 25, -1).gate(0x4001, rows), "bit14 gates use the scenario");

        check(!Flags.exitDimmed(f, 0x1B, rows) && Flags.exitDimmed(f, 0x1C, rows) && !Flags.exitDimmed(f, 0xFFFF, rows), "exit dimming");
        check(!Flags.exitDimmed(null, 0x1C, rows), "unknown flags never dim");

        boolean[][] rev = new boolean[4][4];
        rev[1][2] = true;                                     // cell (2, 1) = source 32..47, 16..31
        check(Flags.symbolVisible(f, rows, 5, 0, 1, 40, 20, 0x1B, rev), "open gate on an explored cell");
        check(!Flags.symbolVisible(f, rows, 5, 0, 1, 40, 40, 0x1B, rev), "fog hides it");
        check(Flags.symbolVisible(f, rows, 5, 0, 20, 40, 40, 0x1B, rev), "types 14/20/23/24 ignore fog");
        check(!Flags.symbolVisible(f, rows, 5, 0, 1, 40, 20, 0x1C, rev), "closed gate");
        check(!Flags.symbolVisible(f, rows, 5, 0, 1, 0, 20, 0x1B, rev) && Flags.symbolVisible(f, rows, 6, 0, 20, 0, 20, 0x1B, rev),
                "zero source hides it, except in group 6");
        check(Flags.symbolVisible(f, rows, 9, 0, 17, 0, 0, 0, rev) && Flags.symbolVisible(f, rows, 9, 0, 17, 40, 40, 0, rev)
                && !Flags.symbolVisible(f, rows, 9, 0, 14, 40, 20, 0x1B, rev) && !Flags.symbolVisible(f, rows, 9, 0, 17, 40, 20, 0x1C, rev),
                "group 9: own loop, no fog or zero-source test, never type 14, gate still applies");
        check(!Flags.symbolVisible(f, rows, 5, 0, 0, 40, 20, 0, rev) && !Flags.symbolVisible(f, rows, 5, 0, 1, 40, 20, 1, rev),
                "type 0 and gate 1 never show");
        check(Flags.symbolVisible(f, rows, 5, 2, 14, 40, 20, 0x1B, rev) && !Flags.symbolVisible(f, rows, 5, 1, 14, 40, 20, 0x1B, rev),
                "type 14 only for the current exit");
        check(Flags.symbolVisible(null, rows, 5, 0, 1, 40, 20, 0, rev) && !Flags.symbolVisible(null, rows, 5, 0, 1, 40, 20, 0x1B, rev),
                "unknown flags: only always-gated symbols");
        check(Flags.symbolVisible(f, rows, 5, 0, 1, -5, 20, 0x1B, rev) == rev[1][0], "negative source truncates toward zero");

        check(flags(hex().substring(2), 0, -1) == null && flags(hex().replace('0', 'g'), 0, -1) == null
                && flags(hex(), 70000, -1) == null, "malformed flags refused");
        System.out.println("PASS FlagsTest 20 checks (plain/threshold gates, parity, exit dimming, symbol rule, unknown flags)");
    }
}
