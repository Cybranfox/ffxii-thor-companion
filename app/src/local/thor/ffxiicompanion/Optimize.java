package local.thor.ffxiicompanion;

/**
 * The game's Equip-menu Optimize rule (routine 0x2F9F20, comparator 0x2F7A10, weapon score 0x2F7C80;
 * Gear_Optimize_Trace_2026-10-21.md), reproduced for suggestions only. Pure: no I/O, nothing is written to the game.
 *
 * Slots 0-3 only (the accessory is kept). Candidates are scanned in ascending ID ranges and must be the right slot,
 * licensed (or noLicense) and have a SPARE copy (owned - equipped >= 1), so another member's worn copy is never taken;
 * current gear is the starting pick and may stay without a spare. Weapons/ammo by attack (bow + best matching ammo),
 * shields by evade then magick evade, head by magick resist then defence, body by defence then magick resist; ties as
 * in the game. Section-16 category restrictions are all zero in this pack and are not modelled.
 */
final class Optimize {
    static final int EMPTY = 0xFFFF, UNARMED = 0x1000;
    private static final int[][] RANGES = {{0x1000, 0x10C8}, {0x10C8, 0x11A4}, {0x10DC, 0x1118}, {0x1118, 0x1154}};
    private static final int[] DEFAULT = {UNARMED, EMPTY, EMPTY, EMPTY, EMPTY};

    private final EquipTable eq;
    private final int[] stock;          // equipment stock words by ID - 0x1000
    private final LicenceNodes nodes;
    private final Build.Member member;
    private final int[] orig;

    private Optimize(EquipTable eq, int[] stock, LicenceNodes nodes, Build.Member member, int[] orig) {
        this.eq = eq; this.stock = stock; this.nodes = nodes; this.member = member; this.orig = orig;
    }

    /** What Optimize would pick for member m (five IDs), or null when an input is missing. */
    static int[] pick(EquipTable eq, Build build, LicenceNodes nodes, int m) {
        if (eq == null || build == null || build.gear == null || build.equipStock == null || nodes == null || m < 0 || m >= build.gear.length) return null;
        for (int g : build.gear[m]) if (g != EMPTY && !eq.has(g)) return null;   // table doesn't match the game's IDs
        return new Optimize(eq, build.equipStock, nodes, build.members.get(m), build.gear[m].clone()).run();
    }

    private int spare(int g) {
        int i = g - 0x1000;
        if (i < 0 || i >= stock.length) return 0;
        return (stock[i] & 0x3FF) - (stock[i] >> 10);
    }

    private boolean licensed(int g) { return eq.noLicense(g) || nodes.equipLicensed(member, g); }

    private boolean valid(int g, int slot, int[] gear) {
        if (g == DEFAULT[slot]) return true;
        if (g == EMPTY || !eq.has(g) || spare(g) < 1) return false;
        if (eq.slot(g) != slot) return false;
        if (slot == 1 && eq.category(g) != 0x12 && gear[0] != EMPTY && eq.category(g) != eq.offhandReq(gear[0])) return false;
        return licensed(g);
    }

    private int score(int g) {
        if (g == UNARMED || g == EMPTY) return 0;
        int req = eq.offhandReq(g);
        if (req < 0x17 || req > 0x1A) return eq.attack(g);
        int best = orig[1] != EMPTY && eq.has(orig[1]) && eq.category(orig[1]) == req ? orig[1] : EMPTY;
        int[] none = {EMPTY, EMPTY, EMPTY, EMPTY, EMPTY};
        for (int ammo = 0x1184; ammo < 0x11A4; ammo++)
            if (valid(ammo, 1, none) && eq.category(ammo) == req) best = better(best, ammo, 0);
        return best == EMPTY ? -1 : eq.attack(g) + eq.attack(best);
    }

    /** The comparator: mode 0 weapon/ammo, 1 shield, 2 head, 3 body. Returns the winner of cur vs cand. */
    private int better(int cur, int cand, int mode) {
        if (eq.slot(cand) == 4 || eq.slot(cand) == 5) return cur;
        if (cur == EMPTY) return cand;
        if (mode >= 1 && mode <= 3) {
            int a1 = mode == 2 ? eq.b19(cur) : eq.b18(cur), a2 = mode == 2 ? eq.b18(cur) : eq.b19(cur);
            int b1 = mode == 2 ? eq.b19(cand) : eq.b18(cand), b2 = mode == 2 ? eq.b18(cand) : eq.b19(cand);
            if (a1 != b1) return a1 > b1 ? cur : cand;
            if (a2 != b2) return a2 > b2 ? cur : cand;
        } else if (mode == 0) {
            int sa = score(cur), sb = score(cand);
            if (sa != sb) return sa > sb ? cur : cand;
            if (orig[0] != EMPTY) {
                if (orig[0] == cur) return cur;
                if (orig[0] == cand) return cand;
                if (eq.category(cur) != eq.category(cand)) {
                    int c = eq.category(orig[0]);
                    if (c == eq.category(cand)) return cand;
                    if (c == eq.category(cur)) return cur;
                }
            }
            if (eq.category(cur) != eq.category(cand)) return eq.category(cur) < eq.category(cand) ? cur : cand;
        } else {
            return cur;
        }
        if (eq.tie(cur) != eq.tie(cand)) return eq.tie(cur) > eq.tie(cand) ? cur : cand;
        return Math.max(cur, cand);
    }

    private int[] run() {
        int[] chosen = orig.clone();
        for (int slot = 0; slot < 4; slot++) {
            for (int g = RANGES[slot][0]; g < RANGES[slot][1]; g++) {
                if (!eq.has(g) || !valid(g, slot, chosen)) continue;
                if (slot == 1 && chosen[0] != EMPTY && eq.category(g) != eq.offhandReq(chosen[0])) continue;
                int mode = g >= 0x1184 && g < 0x11A4 ? 0 : slot;
                chosen[slot] = better(chosen[slot], g, mode);
            }
            if (slot == 0) {
                if (chosen[0] == EMPTY) chosen[0] = UNARMED;
                if (chosen[1] != EMPTY && eq.has(chosen[1]) && eq.category(chosen[1]) != eq.offhandReq(chosen[0])) chosen[1] = EMPTY;
            }
        }
        return chosen;
    }
}
