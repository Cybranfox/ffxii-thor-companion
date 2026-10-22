package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gambit suggestions on REAL data: build/ownership from the Thor dump (tests/out/build), the live licence table, the
 * modded reference bundle (args[1]) and the party's gambits from the Thor probe dump (args[2]). Prints the plans.
 */
public final class GambitPlanTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Path p) throws Exception {
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        if (args.length < 3 || !Files.isRegularFile(dir.resolve("build_before.json")) || !Files.isRegularFile(Paths.get(args[1]))
                || !Files.isRegularFile(Paths.get(args[2]))) { System.out.println("SKIP GambitPlanTest"); return; }
        Build build = Build.parse(obj(dir.resolve("build_before.json")));
        LicenceNodes nodes = LicenceNodes.parse(obj(dir.resolve("licence_nodes.json")));
        Reference ref = Reference.parse(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        Map<String, Object> probe = obj(Paths.get(args[2]));
        Map<String, Object> g = new HashMap<>();
        g.put("contract", "ffxii.gambits.v1"); g.put("status", "ok"); g.put("stable", Boolean.TRUE);
        g.put("sets", String.join(",", java.util.Arrays.copyOf(Json.str(probe, "sets").split(","), 6)));
        List<Gambits.Member> cur = Gambits.parse(g);

        String[][] names = {{"60a3", "Ally: any"}, {"60c4", "Ally: status = KO"}, {"60b7", "Ally: HP < 30%"}, {"60b6", "Ally: HP < 40%"},
                {"60b4", "Ally: HP < 60%"}, {"60c6", "Ally: status = Petrify"}, {"60cd", "Ally: status = Silence"},
                {"60cc", "Ally: status = Poison"}, {"60cb", "Ally: status = Blind"}, {"60c8", "Ally: status = Sleep"},
                {"602a", "Foe: HP = 100%"}, {"605a", "Foe: flying"}, {"6000", "Foe: party leader's target"},
                {"6001", "Foe: nearest visible"}, {"607f", "Self: HP < 30%"}, {"608b", "Self: MP < 10%"}};
        for (String[] n : names) check(n[1].equals(ref.conditionName(Integer.parseInt(n[0], 16))), n[0] + " = " + ref.conditionName(Integer.parseInt(n[0], 16)));
        ok("all " + names.length + " rule conditions match the modded game text by name");

        List<GambitPlan.Plan> plans = GambitPlan.suggest(build, nodes, ref, cur);
        check(plans != null && plans.size() == 6, "six plans");
        Set<Integer> items = new HashSet<>();
        for (int a = 0x52; a <= 0x91; a++) if (build.available(a) > 0) items.add(a);   // live stock (0.16.0)
        check(build.stockKnown() && !items.contains(0x5F), "Chronos Tear (0 left) is never suggested");
        int[] rank = new int[0x10000];
        for (int c : new int[]{0x60C4}) rank[c] = 1;
        for (int c : new int[]{0x60B7, 0x60B6, 0x60B4, 0x607F}) rank[c] = 2;
        for (int c : new int[]{0x60C6, 0x60CD, 0x60CC, 0x60CB, 0x60C8, 0x608B}) rank[c] = 3;
        rank[0x602A] = 4; rank[0x60A3] = 5; for (int c = 0x6049; c <= 0x6050; c++) rank[c] = 6; rank[0x6059] = 6; rank[0x605A] = 7; rank[0x6000] = 8; rank[0x6001] = 9;
        for (GambitPlan.Plan p : plans) {
            List<Integer> usable = GambitEdit.usableAbilities(build, nodes, p.member);
            System.out.println("  " + Gambits.MEMBERS[p.member] + " (" + p.jobs + ") - " + p.role);
            int last = 0;
            check(p.slots.size() >= 2 && p.slots.size() <= 12, "slot count");
            for (GambitPlan.Slot s : p.slots) {
                System.out.println("     " + Gambits.condition(s.condition, ref) + "  ->  " + Gambits.action(s.action, ref) + "   (" + s.reason + ")");
                check(build.gambits.contains(s.condition), "condition owned");
                check(s.action == 0x96 || usable.contains(s.action) || items.contains(s.action), Gambits.MEMBERS[p.member] + " cannot use " + Gambits.action(s.action, ref));
                check(!s.reason.contains("check stock"), "stock known: no 'check stock' label");
                check(rank[s.condition] >= last, "order: " + Gambits.condition(s.condition, ref));
                last = rank[s.condition];
            }
            check(p.slots.get(p.slots.size() - 1).action == 0x96, "ends with an attack");
        }
        ok("every suggested action is usable by that member (owned+licensed), Attack, or an item in stock right now; conditions owned");
        ok("order everywhere: revive > heal > status > Steal > buffs > attacks, attack last, <= 12 slots");

        int healers = 0, thieves = 0;
        for (GambitPlan.Plan p : plans) { if (p.role.equals("Healer")) healers++; if (p.role.startsWith("Thief")) thieves++; }
        check(healers == 1 && thieves == 1, "one healer, one thief");
        check(plans.get(5).role.equals("Healer"), "Penelo (White Mage) is the healer: " + plans.get(5).role);
        check(plans.get(0).role.startsWith("Thief"), "Vaan (Shikari) steals");
        ok("roles from usable abilities: Penelo (White Mage) heals, Vaan (Shikari) steals");
        boolean ashFlying = false;
        for (GambitPlan.Slot s : plans.get(1).slots) if (s.condition == 0x605A) ashFlying = true;
        check(ashFlying, "Ashe (Archer) keeps Foe: flying after trimming to 12");
        boolean penShell = false;
        for (GambitPlan.Slot s : plans.get(5).slots) if (s.condition == 0x60A3) penShell = true;
        check(penShell, "healer keeps a buff");
        ok("trimming to 12 drops extra weakness spells first; the Archer keeps Foe: flying");

        check(build.gear != null, "gear published");
        List<Reference.Entry> vaan = Equipped.entries(build.gear, 0, ref), ashe = Equipped.entries(build.gear, 1, ref), fran = Equipped.entries(build.gear, 2, ref);
        String[] vn = {"Platinum Dagger", "Golden Shield", "Officer's Hat", "Golden Armor", "Manufacted Nethicite"};
        for (int i = 0; i < 5; i++) check(vn[i].equals(vaan.get(i).name) && vaan.get(i).category.startsWith(Equipped.SLOTS[i]), "Vaan slot " + i + ": " + vaan.get(i).name);
        check("Onion Arrows".equals(ashe.get(1).name) && ashe.get(1).category.equals("Shield / ammo · Arrow"), "Ashe ammo: " + ashe.get(1).category);
        check("(empty)".equals(fran.get(1).name) && !vaan.get(0).lines.isEmpty(), "Fran no shield; reference text attached");
        check(Equipped.member(Equipped.filter(5)) == 5 && Equipped.member("Sword") == -1 && Equipped.entries(null, 0, ref).isEmpty(), "chips");
        ok("gear tab: each member's five live slots name the right items from the real dump, linked to the mod's reference text");

        // 0.20.0 ItemCheck on the real dump's own stored gambits (gambits_before.json, same dump as build_before.json)
        List<Gambits.Member> mine = Gambits.parse(obj(dir.resolve("gambits_before.json")));
        List<ItemCheck.Issue> pen = ItemCheck.check(mine.get(5), build, nodes, ref);
        check(pen.size() == 1 && pen.get(0).slot.action == 0x5F && pen.get(0).fix == -1, "Penelo: ON Chronos Tear, 0 left, no swap -> switch OFF");
        check(ItemCheck.check(mine.get(4), build, nodes, ref).isEmpty(), "Basch's Shock Mote is OFF: no warning");
        int warned = 0;
        for (Gambits.Member gm : mine) warned += ItemCheck.check(gm, build, nodes, ref).size();
        check(warned == 1, "only Penelo's slot in the whole party: " + warned);
        Map<String, Object> bj = new HashMap<>(obj(dir.resolve("build_before.json")));
        Map<String, Object> st = new HashMap<>(Json.obj(bj, "stock"));
        String w = Json.str(st, "words");
        st.put("words", "0000" + w.substring(4));                     // Potion: 0 left (Hi-Potion still 2)
        bj.put("stock", st);
        Build noPot = Build.parse(bj);
        List<ItemCheck.Issue> vi = ItemCheck.check(mine.get(0), noPot, nodes, ref);
        check(!vi.isEmpty() && vi.get(0).slot.action == 0x52 && vi.get(0).fix == 0x53, "Vaan: Potion out -> swap to Hi-Potion");
        st.put("words", "00000000" + w.substring(8));                 // Potion and Hi-Potion out, X-Potion 0
        Build noHeal = Build.parse(bj);
        int f = ItemCheck.check(mine.get(0), noHeal, nodes, ref).get(0).fix;
        check(f == -1 || GambitEdit.usableAbilities(noHeal, nodes, 0).contains(f), "then a Cure spell Vaan can cast, or switch OFF");
        bj.remove("stock");
        check(ItemCheck.check(mine.get(5), Build.parse(bj), nodes, ref).isEmpty(), "no stock data -> no warnings (never guessed)");
        ok("item check: Penelo's ON Chronos Tear (0 left) warned with 'switch OFF'; Potion out -> Hi-Potion; nothing guessed without stock");

        check(GambitPlan.suggest(null, nodes, ref, cur) == null && GambitPlan.suggest(build, null, ref, cur) == null
                && GambitPlan.suggest(build, nodes, null, cur) == null, "missing inputs -> no plan");
        ok("missing build / licence table / reference -> no suggestions (never guessed)");
        System.out.println(passed + " gambit plan checks passed (real Thor data)");
    }
}
