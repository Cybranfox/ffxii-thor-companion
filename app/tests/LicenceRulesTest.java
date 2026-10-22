package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 0.23.0 licence rules + planner against the other session's offline replica (draft/tools/licence_rules/buyable_cells.json)
 * on the real save_27 / save_185 data: for every member and both boards, the buyable cell sets with the story gate true and
 * false must match exactly; Penelo's Time Magick 9 (node 218) must have been buyable before her purchase.
 */
public final class LicenceRulesTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    static Object read(Path p) throws Exception { return Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)); }

    static Set<Integer> cells(Object list) {
        Set<Integer> s = new TreeSet<>();
        for (Object o : (List<?>) list) s.add(Json.lng(o, "cell").intValue());
        return s;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]), boardsJson = Paths.get(args[1]), replica = Paths.get(args[2]);
        if (!Files.isRegularFile(replica) || !Files.isRegularFile(boardsJson)) { System.out.println("SKIP LicenceRulesTest"); return; }
        LicenceBoards lb = LicenceBoards.parse((Map<String, Object>) read(boardsJson));
        LicenceNodes nodes = LicenceNodes.parse((Map<String, Object>) read(dir.resolve("licence_nodes.json")));
        Map<String, Object> rep = Json.obj((Map<String, Object>) read(replica), "dumps");
        int cases = 0, buy = 0;
        for (String[] d : new String[][]{{"before", "save_27.bin"}, {"after", "save_185.bin"}}) {
            Build b = Build.parse((Map<String, Object>) read(dir.resolve("build_" + d[0] + ".json")));
            check(LicenceRules.available(b, nodes), "inputs present " + d[0]);
            for (Object mo : Json.arr(Json.obj(rep, d[1]), "members")) {
                int m = Json.lng(mo, "member").intValue();
                if (m > 5) continue;
                LicenceRules r = new LicenceRules(b, nodes, m);
                for (Object bo : Json.arr(mo, "boards")) {
                    LicenceBoards.Board bd = lb.board(Json.lng(bo, "job").intValue());
                    Set<Integer> t = new TreeSet<>(), f = new TreeSet<>();
                    for (int i = 0; i < bd.cells.length; i++) { if (r.canBuy(bd, i, true)) t.add(i); if (r.canBuy(bd, i, false)) f.add(i); }
                    String who = d[1] + " " + Gambits.MEMBERS[m] + " " + bd.name;
                    check(t.equals(cells(Json.arr(bo, "buyable_if_story_gate_true"))), who + " gate true: " + t);
                    check(f.equals(cells(Json.arr(bo, "buyable_if_story_gate_false"))), who + " gate false: " + f);
                    check(cells(Json.arr(bo, "buyable_certain")).equals(intersect(t, f)), who + " certain");
                    cases++; buy += t.size();
                }
            }
        }
        check(cases == 24, "24 member boards: " + cases);
        ok(cases + " member boards on two real dumps: buyable cells (story gate true and false) == the offline replica exactly (" + buy + " gate-true buyable)");

        Build before = Build.parse((Map<String, Object>) read(dir.resolve("build_before.json")));
        LicenceRules pen = new LicenceRules(before, nodes, 5);
        LicenceBoards.Board tbm = lb.board(6);                              // Time Battlemage
        int cell218 = -1;
        for (int i = 0; i < tbm.cells.length; i++) if (tbm.cells[i] == 218) cell218 = i;
        check(cell218 >= 0 && pen.canBuy(tbm, cell218, true) && pen.canBuy(tbm, cell218, false), "Penelo could buy Time Magick 9 before");
        Build after = Build.parse((Map<String, Object>) read(dir.resolve("build_after.json")));
        check(new LicenceRules(after, nodes, 5).owned(218), "and owns it after");
        ok("Penelo: Time Magick 9 (node 218) buyable before her purchase in save_27, owned in save_185");

        // planner: every certain-buyable cell costs exactly its own LP as a one-step path; a far licence needs a longer path
        LicenceRules vaan = new LicenceRules(before, nodes, 0);
        Build.Member vm = before.members.get(0);
        List<LicenceBoards.Board> vb = new java.util.ArrayList<>();
        for (int j : new int[]{vm.job1, vm.job2}) if (j != 0xFF) vb.add(lb.board(j));
        int one = 0, far = -1;
        for (LicenceBoards.Board bd : vb) {
            int[] k = vaan.kinds(bd);
            for (int i = 0; i < k.length; i++) {
                if (k[i] == LicenceRules.BUY) {
                    LicenceRules.Path p = vaan.plan(vb, bd.cells[i]);
                    check(p != null && p.nodes.size() == 1 && p.lp == nodes.cost(bd.cells[i]), "one step to " + bd.cells[i]);
                    one++;
                }
                if (k[i] == LicenceRules.LOCKED && far < 0) {
                    LicenceRules.Path p = vaan.plan(vb, bd.cells[i]);
                    if (p != null && p.nodes.size() >= 3) {
                        far = bd.cells[i];
                        int sum = 0;
                        for (int x : p.nodes) { check(!vaan.owned(x), "path buys only unowned"); sum += nodes.cost(x); }
                        check(sum == p.lp && p.nodes.get(p.nodes.size() - 1) == far, "path LP = sum, ends at target");
                    }
                }
            }
        }
        check(one > 0 && far >= 0, "planner exercised: " + one + " one-step, far " + far);
        ok("planner: " + one + " buyable cells are one-step paths at their own LP; a locked licence gets a multi-step path whose LP is the sum");
        System.out.println(passed + " licence rule checks passed (real dumps + offline replica)");
    }

    static Set<Integer> intersect(Set<Integer> a, Set<Integer> b) { Set<Integer> s = new TreeSet<>(a); s.retainAll(b); return s; }
}
