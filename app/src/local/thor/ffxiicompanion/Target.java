package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 0.13.0: what the party is about to act on, from data.target (contract ffxii.target.v1, game script 0.13.0+). Each
 * field member's pending action and its target, read through the game's own generational handles. It is the action's
 * target, not proven to be the on-screen cursor, so the app calls it "Targeting", never "Selected".
 *
 * An enemy target carries live HP, level and its affinity masks (gear and effects included); the bestiary key joins
 * the data pack's bestiary entry ("entry"), which gives the name. Without the pack the card still works, unnamed.
 */
final class Target {
    static final String CONTRACT = "ffxii.target.v1";
    /** Affinity bit order, as the game's own gambit conditions number them (Party_Target_Export, part B). */
    static final String[] ELEMENTS = { "Fire", "Lightning", "Ice", "Earth", "Water", "Wind", "Holy", "Dark" };

    static final class Enemy {
        int hp, maxHp, level, weak, absorb, half, immune, potency;
        Integer bestiary;
        /** The handle the game gave this target: a new one means a new target, even at the same HP. */
        long handle;
        final List<Integer> targetedBy = new ArrayList<>();
        boolean byLeader;
    }

    private Target() {}

    /** The enemies the field party is acting on, the leader's first, then in party order; empty when none or invalid. */
    static List<Enemy> enemies(Map<String, Object> t) {
        if (t == null || !CONTRACT.equals(Json.str(t, "contract")) || !"ok".equals(Json.str(t, "status"))) return Collections.emptyList();
        List<Object> members = Json.arr(t, "members");
        if (members == null) return Collections.emptyList();
        List<Enemy> out = new ArrayList<>();
        for (Object m : members) {
            Long id = Json.lng(m, "member"), h = Json.lng(m, "handle");
            Map<String, Object> tg = Json.obj(m, "target");
            if (id == null || h == null || tg == null || !"enemy".equals(Json.str(tg, "kind"))) continue;
            Enemy e = null;
            for (Enemy x : out) if (x.handle == h) e = x;
            if (e == null) {
                Map<String, Object> hp = Json.obj(tg, "hp");
                Long cur = Json.lng(hp, "cur"), max = Json.lng(hp, "max"), lv = Json.lng(tg, "level");
                if (cur == null || max == null || lv == null) continue;
                e = new Enemy();
                e.handle = h;
                e.hp = cur.intValue();
                e.maxHp = max.intValue();
                e.level = lv.intValue();
                e.weak = mask(tg, "weak");
                e.absorb = mask(tg, "absorb");
                e.half = mask(tg, "half");
                e.immune = mask(tg, "immune");
                e.potency = mask(tg, "potency");
                Long b = Json.lng(tg, "bestiary");
                e.bestiary = b != null && b >= 0 && b < 0x200 ? b.intValue() : null;
                out.add(e);
            }
            e.targetedBy.add(id.intValue());
            if (Boolean.TRUE.equals(Json.bool(m, "leader"))) e.byLeader = true;
        }
        out.sort((a, b) -> Boolean.compare(b.byLeader, a.byLeader));
        return out;
    }

    private static int mask(Map<String, Object> o, String key) {
        Long v = Json.lng(o, key);
        return v != null && v >= 0 && v <= 0xFF ? v.intValue() : 0;
    }

    /** "Fire, Ice" for a mask, or "" when no bit is set. */
    static String elements(int mask) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ELEMENTS.length; i++) {
            if ((mask & (1 << i)) == 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(ELEMENTS[i]);
        }
        return b.toString();
    }

    /** One line per affinity the enemy has, e.g. "Weak to Fire, Ice"; empty list when it has none. */
    static List<String> affinities(Enemy e) {
        List<String> out = new ArrayList<>();
        if (e.weak != 0) out.add("Weak to " + elements(e.weak));
        if (e.absorb != 0) out.add("Absorbs " + elements(e.absorb));
        if (e.immune != 0) out.add("Immune to " + elements(e.immune));
        if (e.half != 0) out.add("Halves " + elements(e.half));
        return out;
    }
}
