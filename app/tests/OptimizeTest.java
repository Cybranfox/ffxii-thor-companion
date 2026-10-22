package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * Optimize (0.19.0) against the other session's Python replica of the game's rule, on the REAL save_gear_1918 dump and
 * modified copies (gen_optimize_fixture.py): every member, every case, all five slots must match.
 */
public final class OptimizeTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    @SuppressWarnings("unchecked")
    static Object read(Path p) throws Exception { return Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)); }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        if (!Files.isRegularFile(dir.resolve("optimize_cases.json"))) { System.out.println("SKIP OptimizeTest (no fixtures)"); return; }
        EquipTable eq = EquipTable.parse(Json.obj(java.util.Collections.singletonMap("x", read(dir.resolve("equipment.json"))), "x"));
        LicenceNodes nodes = LicenceNodes.parse(Json.obj(java.util.Collections.singletonMap("x", read(Paths.get(args[1]))), "x"));
        check(eq != null && eq.count == 557 && eq.stride == 0x34, "equipment table");
        check(nodes != null, "licence nodes");
        check(eq.attack(0x1076) == 83 && eq.b19(0x10E9) == 34 && eq.b18(0x1148) == 21, "trace's stats: Platinum Dagger 83, Officer's Hat 34, Golden Armor 21");
        ok("live-shape equipment table parsed; field values match the trace");

        List<Object> cases = (List<Object>) read(dir.resolve("optimize_cases.json"));
        int slots = 0, changed = 0;
        for (Object c : cases) {
            String name = Json.str(c, "name");
            Build b = Build.parse(Json.obj(c, "build"));
            check(b != null && b.gear != null && b.equipStock != null, name + ": build");
            List<Object> exp = Json.arr(c, "expected");
            for (int m = 0; m < 6; m++) {
                int[] got = Optimize.pick(eq, b, nodes, m);
                List<Object> e = (List<Object>) exp.get(m);
                for (int k = 0; k < 5; k++) {
                    int want = ((Number) e.get(k)).intValue();
                    check(got[k] == want, String.format("%s %s slot %d: java %04X, python %04X", name, Gambits.MEMBERS[m], k, got[k], want));
                    slots++;
                    if (want != b.gear[m][k]) changed++;
                }
                check(got[4] == b.gear[m][4], "accessory kept");
            }
            if (name.equals("real") || name.equals("nospare"))
                for (int m = 0; m < 6; m++) check(java.util.Arrays.equals(Optimize.pick(eq, b, nodes, m), b.gear[m]), name + ": no change");
        }
        check(changed > 50, "cases exercise real changes: " + changed);
        ok(cases.size() + " cases, " + slots + " slots: Java Optimize == Python replica (" + changed + " changed slots); real dump and no-spare: no change; accessories kept");

        // 0.20.1: the chip adds the "To apply" pointer only when something would change
        Build all = null;
        for (Object c : cases) if ("allspare".equals(Json.str(c, "name"))) all = Build.parse(Json.obj(c, "build"));
        List<Reference.Entry> ch = Equipped.entries(all.gear, 0, null, Optimize.pick(eq, all, nodes, 0));
        check(ch.get(ch.size() - 1).name.equals("To apply: in the game, Equip → Vaan → Optimize") && ch.get(5).name.startsWith("Optimize → "), "guided apply entry");
        Build real = Build.parse(Json.obj(cases.get(0), "build"));
        List<Reference.Entry> same = Equipped.entries(real.gear, 0, null, Optimize.pick(eq, real, nodes, 0));
        check(same.size() == 6 && same.get(5).name.equals("Optimize: no change"), "no change: no apply pointer");
        ok("guided apply: 'To apply: Equip → Vaan → Optimize' only when Optimize would change something");

        Build b = Build.parse(Json.obj(cases.get(0), "build"));
        check(Optimize.pick(null, b, nodes, 0) == null && Optimize.pick(eq, b, null, 0) == null, "missing inputs");
        ok("missing table / licences -> no suggestion (never guessed)");
        System.out.println(passed + " optimize checks passed (real dump + Python replica)");
    }
}
