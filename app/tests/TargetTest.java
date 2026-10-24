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
        System.out.println("PASS TargetTest 13 checks (grouping, leader first, affinity names, gates, bestiary join, on-demand pack)");
    }
}
