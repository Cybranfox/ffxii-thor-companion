package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gambit model tests on the real Thor probe dump (args[0]: probe latest json) and reference bundle (args[1]):
 * Vaan's and Balthier's lists must read exactly as the game's Gambits screens did on 21 Oct 2026.
 */
public final class GambitsTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    static String line(Gambits.Slot s, Reference r) {
        return (s.on ? "ON " : "OFF ") + Gambits.condition(s.condition, r) + " -> " + Gambits.action(s.action, r);
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !Files.isRegularFile(Paths.get(args[0])) || !Files.isRegularFile(Paths.get(args[1]))) {
            System.out.println("SKIP GambitsTest (needs probe dump + reference bundle)");
            return;
        }
        Object probe = Json.parse(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        Reference ref = Reference.parse(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        String[] all = Json.str(probe, "sets").split(",");
        Map<String, Object> g = new HashMap<>();
        g.put("contract", "ffxii.gambits.v1");
        g.put("status", "ok");
        g.put("stable", Boolean.TRUE);
        g.put("sets", String.join(",", java.util.Arrays.copyOf(all, 6)));
        List<Gambits.Member> ms = Gambits.parse(g);
        check(ms != null && ms.size() == 6, "six members");

        String[] vaan = {"ON Ally: HP < 50% -> Potion", "ON Ally: status = KO -> Phoenix Down", "ON Foe: HP = 100% -> Steal",
                "ON Foe: nearest -> Attack", "ON Ally: status = Silence -> Echo Herbs", "ON Ally: status = Oil -> Handkerchief",
                "OFF Self: HP < 40% -> Potion"};
        List<Gambits.Slot> v = ms.get(0).slots;
        for (int i = 0; i < vaan.length; i++) check(line(v.get(i), ref).equals(vaan[i]), "Vaan " + (i + 1) + ": " + line(v.get(i), ref));
        check(v.get(0).index == 1 && v.get(vaan.length).index == 13, "set II starts at slot 13");
        ok("Vaan set I reads exactly as the game screen (7 slots, slot 7 OFF)");

        String[] bal = {"ON Ally: HP < 50% -> Potion", "ON Ally: status = KO -> Phoenix Down", "ON Foe: nearest -> Attack",
                "ON Ally: status = Petrify -> Gold Needle", "ON Ally: status = Poison -> Antidote",
                "ON Ally: status = Silence -> Echo Herbs", "ON Ally: status = Sleep -> Prince's Kiss", "OFF Self: MP < 10% -> Hi-Potion"};
        List<Gambits.Slot> b = ms.get(3).slots;
        check(ms.get(3).name.equals("Balthier"), "member 3 is Balthier");
        for (int i = 0; i < bal.length; i++) check(line(b.get(i), ref).equals(bal[i]), "Balthier " + (i + 1) + ": " + line(b.get(i), ref));
        ok("Balthier (member 3) set I reads exactly as the game screen (8 slots, slot 8 OFF)");

        check(Gambits.action(0x04, ref).equals("Cura") && Gambits.action(0x9E, ref).equals("First Aid"), "magick/technick bases");
        check(Gambits.action(0xC0, ref).startsWith("Action ") && Gambits.condition(0x6123, null).equals("Condition 6123"), "unknown IDs raw");
        check(Gambits.action(0x52, null).equals("Action 0052"), "no bundle -> raw");
        ok("unknown IDs and missing bundle shown raw, never guessed");
        if (ref.conditionName(0x6000) != null) {
            check(Gambits.condition(0x6000, ref).equals("Foe: party leader's target") && Gambits.condition(0x608A, ref).equals("Self: MP < 20%"),
                    "bundle condition names");
            for (Map.Entry<Integer, String> e : Gambits.CONDITIONS.entrySet())
                check(ref.conditionName(e.getKey()).equals(e.getValue()), "bundle agrees with screen: " + Integer.toHexString(e.getKey()));
            ok("bundle condition names agree with all 11 read off the game screen");
        }

        g.put("stable", Boolean.FALSE);
        check(Gambits.parse(g) == null, "unstable rejected");
        g.put("stable", Boolean.TRUE);
        g.put("sets", "00,11");
        check(Gambits.parse(g) == null, "malformed rejected");
        ok("unstable / malformed records rejected");
        System.out.println(passed + " gambit checks passed (real Thor dump)");
    }
}
