package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Build / LicenceNodes / ownership-based choices on fixtures generated from the REAL Thor dumps (gen_build_fixture.py):
 * save_27 (before) and save_185 (after Penelo bought licence node 218, Reflectga + Slowga, without owning either magick).
 * Expectations were computed independently in Python.
 */
public final class BuildTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Path p) throws Exception {
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    static List<Integer> ints(Object o) {
        List<Integer> out = new ArrayList<>();
        for (Object x : (List<?>) o) out.add(((Number) x).intValue());
        return out;
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args.length > 0 ? args[0] : "tests/out/build");
        if (!Files.isRegularFile(dir.resolve("build_before.json"))) { System.out.println("SKIP BuildTest (no real-dump fixtures)"); return; }
        LicenceNodes nodes = LicenceNodes.parse(obj(dir.resolve("licence_nodes.json")));
        check(nodes != null && nodes.count == 361 && nodes.stride == 24, "licence table");
        check(nodes.type(218) == 0x0D && nodes.cost(218) == 100, "node 218 = magick licence, 100 LP");
        ok("live licence table parsed (361 x 24); node 218 is the 100-LP magick licence Penelo bought");

        Build before = Build.parse(obj(dir.resolve("build_before.json")));
        Build after = Build.parse(obj(dir.resolve("build_after.json")));
        check(before != null && after != null && before.ownershipKnown(), "parse");
        check(before.members.get(5).lp == 132 && after.members.get(5).lp == 32, "Penelo LP 132 -> 32");
        check(!before.members.get(5).owns(218) && after.members.get(5).owns(218), "node 218 bought");
        check(before.gambits.size() == 255 && before.magicks.size() == 28 && before.technicks.size() == 8, "owned counts");
        ok("data.build: Penelo LP 132 -> 32 and node 218 bought; owned 255 gambits / 28 magicks / 8 technicks");

        for (String name : new String[]{"before", "after"}) {
            Build b = name.equals("before") ? before : after;
            Map<String, Object> exp = obj(dir.resolve("build_" + name + "_expected.json"));
            check(GambitEdit.conditionChoices(null, null, b).equals(new java.util.TreeSet<>(ints(exp.get("gambits"))).stream()
                    .collect(java.util.stream.Collectors.toList())), "owned gambits " + name);
            List<?> usable = (List<?>) exp.get("usable");
            for (int m = 0; m < 6; m++)
                check(GambitEdit.usableAbilities(b, nodes, m).equals(ints(usable.get(m))), name + " member " + m + ": "
                        + GambitEdit.usableAbilities(b, nodes, m) + " vs " + usable.get(m));
        }
        ok("usable magicks/technicks per member == independent Python result, both real dumps");

        int reflectga = -1, slowga = -1;   // the two magicks node 218 grants
        for (int a = 0; a <= 0x50; a++) if (nodes.grants(218, a)) { if (reflectga < 0) reflectga = a; else slowga = a; }
        check(reflectga >= 0 && slowga >= 0, "node 218 grants two magicks");
        check(nodes.licensed(after.members.get(5), reflectga) && !after.magicks.contains(0x3000 + reflectga), "licensed, not owned");
        check(!GambitEdit.usableAbilities(after, nodes, 5).contains(reflectga) && !GambitEdit.usableAbilities(after, nodes, 5).contains(slowga),
                "licensed but not owned stays out");
        check(GambitEdit.usableAbilities(before, nodes, 5).equals(GambitEdit.usableAbilities(after, nodes, 5)), "nothing new usable for Penelo");
        ok("licensed-but-not-owned (Penelo's new Reflectga/Slowga) is NOT offered");

        Map<String, Object> e0 = obj(dir.resolve("build_before_expected.json"));
        check(before.stockKnown(), "stock published");
        List<Integer> inStock = new ArrayList<>();
        for (int a = 0x52; a <= 0x91; a++) if (before.available(a) > 0) inStock.add(a);
        check(inStock.equals(ints(e0.get("items"))), "items in stock " + inStock + " vs " + e0.get("items"));
        check(before.available(0x52) == 14 && before.available(0x53) == 2 && before.available(0x58) == 3, "Potion 14, Hi-Potion 2, Phoenix Down 3");
        check(before.available(0x5F) == 0 && before.available(0x81) == 0, "Chronos Tear and Shock Mote: 0 (both still in gambits)");
        check(Build.itemOf(0x6F) < 0 && Build.itemOf(0x7B) < 0 && before.available(0x70) == -1 && before.available(0x96) == -1, "reserved rows and Attack are not items");
        int[] w = before.stock.clone();
        w[0] = 14 | 3 << 10;   // 3 of the 14 Potions held back (high 6 bits)
        check(Math.max(0, (w[0] & 0x3FF) - (w[0] >> 10)) == 11, "available = low10 - high6");
        ok("stock: items in stock == independent Python result; Potion 14, Phoenix Down 3, Chronos Tear 0; reserved rows not items");

        Map<String, Object> g = new HashMap<>(obj(dir.resolve("build_before.json")));
        Map<String, Object> o = new HashMap<>(Json.obj(g, "owned"));
        o.put("status", "implausible_magicks");
        g.put("owned", o);
        Build noOwn = Build.parse(g);
        check(noOwn != null && !noOwn.ownershipKnown() && GambitEdit.usableAbilities(noOwn, nodes, 0).isEmpty(), "no ownership");
        check(GambitEdit.usableAbilities(before, null, 0).isEmpty(), "no licence table");
        g.put("status", "unstable");
        check(Build.parse(g) == null, "unstable build rejected");
        ok("ownership not ok / no licence table / unstable build -> nothing widened (falls back to session lists)");
        System.out.println(passed + " build checks passed (real Thor dumps)");
    }
}
