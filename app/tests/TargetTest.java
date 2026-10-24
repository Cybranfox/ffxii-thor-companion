package local.thor.ffxiicompanion;

import java.util.List;
import java.util.Map;

/** data.target (ffxii.target.v1, the shape test_target_family.py checks on the game side) -> the Targeting card. */
public final class TargetTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String json) { return (Map<String, Object>) Json.parse(json); }

    static final String ENEMY = "{\"kind\":\"enemy\",\"hp\":{\"cur\":900,\"max\":1500},\"level\":12,\"status\":0,"
            + "\"weak\":5,\"absorb\":8,\"half\":0,\"immune\":64,\"potency\":0,\"bestiary\":77}";

    public static void main(String[] args) {
        Map<String, Object> t = obj("{\"contract\":\"ffxii.target.v1\",\"status\":\"ok\",\"members\":["
                + "{\"member\":3,\"handle\":5308418,\"action\":150,\"target\":" + ENEMY + "},"
                + "{\"member\":0,\"leader\":true,\"handle\":5308418,\"action\":150,\"target\":" + ENEMY + "},"
                + "{\"member\":2,\"handle\":1114115,\"action\":12289,\"target\":{\"kind\":\"member\",\"member\":0,"
                + "\"hp\":{\"cur\":2700,\"max\":2747},\"level\":40,\"status\":0}},"
                + "{\"member\":5}]}");
        List<Target.Enemy> es = Target.enemies(t);
        check(es.size() == 1, "two members on one enemy are one enemy; allies and idle members are not enemies");
        Target.Enemy e = es.get(0);
        check(e.hp == 900 && e.maxHp == 1500 && e.level == 12 && e.bestiary == 77 && e.byLeader, "fields");
        check(e.targetedBy.equals(java.util.Arrays.asList(3, 0)), "targeted by Balthier and Vaan " + e.targetedBy);
        check(Target.affinities(e).equals(java.util.Arrays.asList("Weak to Fire, Ice", "Absorbs Earth", "Immune to Holy")),
                "affinity lines " + Target.affinities(e));
        check(Target.elements(0xFF).equals("Fire, Lightning, Ice, Earth, Water, Wind, Holy, Dark") && Target.elements(0).isEmpty(),
                "element order");

        Map<String, Object> two = obj("{\"contract\":\"ffxii.target.v1\",\"status\":\"ok\",\"members\":["
                + "{\"member\":3,\"handle\":1,\"target\":" + ENEMY.replace("\"level\":12", "\"level\":3") + "},"
                + "{\"member\":0,\"leader\":true,\"handle\":2,\"target\":" + ENEMY + "}]}");
        List<Target.Enemy> es2 = Target.enemies(two);
        check(es2.size() == 2 && es2.get(0).byLeader && es2.get(0).level == 12, "the leader's target comes first");

        check(Target.enemies(obj("{\"contract\":\"ffxii.target.v1\",\"status\":\"unstable\"}")).isEmpty(), "not ok -> none");
        check(Target.enemies(obj("{\"contract\":\"other\",\"status\":\"ok\",\"members\":[]}")).isEmpty(), "other contract -> none");
        check(Target.enemies(null).isEmpty(), "older game script -> none");
        check(Target.enemies(obj("{\"contract\":\"ffxii.target.v1\",\"status\":\"ok\",\"members\":[{\"member\":0,\"handle\":1,"
                + "\"target\":" + ENEMY.replace("\"bestiary\":77", "\"bestiary\":4096").replace("\"weak\":5", "\"weak\":999") + "}]}"))
                .get(0).bestiary == null, "bestiary key out of range dropped");

        Reference r = Reference.parse("{\"format\":\"ffxii-companion-reference-1\",\"kind\":\"REFERENCE\",\"bestiary\":["
                + "{\"entry\":77,\"name\":\"Test Wolf\",\"locations\":[\"Somewhere\"],\"pages\":[[\"A line\"]]}]}");
        check(r != null && r.bestiaryByKey(77) != null && r.bestiaryByKey(77).name.equals("Test Wolf") && r.bestiaryByKey(78) == null,
                "bestiary entry by the game's key");
        // 24 Oct: the card asks for the pack itself (nothing else may have loaded it on the Status tab).
        int[] loads = {0};
        Target.Enemy named = Target.enemies(t).get(0);
        Reference.Entry got = Target.entry(named, () -> { loads[0]++; return r; });
        check(got != null && got.name.equals("Test Wolf") && loads[0] == 1,
                "a target with a key loads the pack on demand and shows its name, with no tab visited first");
        Target.Enemy unkeyed = new Target.Enemy();
        check(Target.entry(unkeyed, () -> { loads[0]++; return r; }) == null && loads[0] == 1,
                "no key: unnamed, and the pack isn't read for it");
        check(Target.entry(named, () -> null) == null, "pack not readable (being replaced): unnamed, no crash");
        // 0.16.0: chip groups (weak first) and bar tones.
        List<Target.Group> g = Target.groups(e);
        check(g.size() == 3 && g.get(0).kind == Target.Group.Kind.WEAK && g.get(0).mask == 5
                && g.get(1).kind == Target.Group.Kind.ABSORB && g.get(2).kind == Target.Group.Kind.IMMUNE && g.get(2).mask == 64,
                "affinity groups: weak, absorb, immune, empty kinds left out");
        check(Target.groups(new Target.Enemy()).isEmpty(), "no affinities -> no groups");
        check(Party.tone(new Party.Vital(60, 100)) == 0 && Party.tone(new Party.Vital(49, 100)) == 1
                && Party.tone(new Party.Vital(24, 100)) == 2 && Party.tone(new Party.Vital(0, 100)) == 3
                && Party.tone(null) == 0 && Party.tone(new Party.Vital(5, 0)) == 0, "bar tones: fine, below half, below a quarter, zero");
        // 0.16.0: Suggest puts the live target's weaknesses first (element bits in Target.ELEMENTS order).
        List<Object[]> order = GambitPlan.byLiveWeakness(1 << 5);                 // Wind
        check((Integer) order.get(0)[0] == 0x604E && order.size() == GambitPlan.ELEMENTS.length,
                "Wind-weak target: the Wind gambit comes first, nothing dropped");
        check((Integer) GambitPlan.byLiveWeakness(0).get(0)[0] == 0x6049, "no live target: the usual order");
        check(GambitPlan.liveWeakness(0x6049, 1) && !GambitPlan.liveWeakness(0x604E, 1) && !GambitPlan.liveWeakness(0x6059, 0xFF),
                "live-weakness test per condition; Undead isn't an element");
        // 0.16.0: guests (no name from the game script) are named from the pack's characters by member ID.
        Reference cr = Reference.parse("{\"format\":\"ffxii-companion-reference-1\",\"kind\":\"REFERENCE\",\"bestiary\":[{\"entry\":1,\"name\":\"W\",\"pages\":[[\"x\"]]}],\"characters\":"
                + "{\"0\":\"Pack Zero\",\"13\":\"Guest Thirteen\",\"x\":\"bad\",\"14\":\"\"}}");
        Party pt = Party.parse(obj("{\"members\":[{\"slot\":0,\"member\":0,\"name\":\"Vaan\"},{\"slot\":1,\"member\":13},"
                + "{\"slot\":2,\"member\":14}]}"));
        int[] asked = {0};
        check("Vaan".equals(Party.displayName(pt.members.get(0), () -> { asked[0]++; return cr; })) && asked[0] == 0,
                "a named member keeps the game script's name; the pack isn't read for it");
        check("Guest Thirteen".equals(Party.displayName(pt.members.get(1), () -> cr)), "guest 13 named from the pack");
        check("Member 14".equals(Party.displayName(pt.members.get(2), () -> cr)) && "Member 13".equals(Party.displayName(pt.members.get(1), () -> null)),
                "no name in the pack, or no pack: Member N, never guessed");
        System.out.println("PASS TargetTest 22 checks (grouping, leader first, affinity names, gates, bestiary join, on-demand pack)");
    }
}
