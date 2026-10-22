package local.thor.ffxiicompanion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Companion side of the gambit editor, on the real Thor dump (args[0]: probe latest json). Builds the proposed first test
 * (Vaan set I slot 7 ON) and writes its command text to args[1] so test_gambit_editor.py can feed it to the Lua parser.
 */
public final class GambitEditTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !Files.isRegularFile(Paths.get(args[0]))) {
            System.out.println("SKIP GambitEditTest (needs probe dump)");
            return;
        }
        Object probe = Json.parse(new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8));
        String[] all = Json.str(probe, "sets").split(",");
        Map<String, Object> g = new HashMap<>();
        g.put("contract", "ffxii.gambits.v1");
        g.put("status", "ok");
        g.put("stable", Boolean.TRUE);
        g.put("sets", String.join(",", java.util.Arrays.copyOf(all, 6)));
        List<Gambits.Member> ms = Gambits.parse(g);
        Gambits.Member vaan = ms.get(0);
        Gambits.Slot s7 = vaan.slots.get(6);
        check(s7.index == 7 && !s7.on && s7.condition == 0x607E && s7.action == 0x52 && s7.setBits == 0x3F, "Vaan slot 7 as on screen");
        GambitEdit.Proposal p = new GambitEdit.Proposal(vaan, s7);
        check(p.turnOn && p.set == 0 && p.slot == 6 && p.where().equals("Vaan · Set I · slot 7"), p.where());
        String text = GambitEdit.commandText(p, "c1792600000-abcdef", 1792600000L);
        check(text.equals("ffxii.gambitcmd.v1\nid=c1792600000-abcdef\nissued=1792600000\nmember=0\nset=0\nslot=6\nop=enable\n"
                + "cond=607e\naction=0052\nbits=003f\n"), text);
        GambitEdit.Proposal off = new GambitEdit.Proposal(vaan, vaan.slots.get(0));
        check(!off.turnOn && off.op().equals("disable") && off.setBits == 0x3F, "slot 1 is ON -> disable");
        check(GambitEdit.newId(1792600000L).matches("c1792600000-[0-9a-f]{6}"), "id format (8..40 chars, [A-Za-z0-9-])");
        ok("Vaan slot 7 proposal -> exact command text (op enable, expected 607e/0052/003f)");

        check(GambitEdit.editable(vaan, s7) && GambitEdit.editable(ms.get(1), ms.get(1).slots.get(0))
                && GambitEdit.editable(ms.get(3), ms.get(3).slots.get(0)), "Vaan, Ashe, Balthier editable");
        check(GambitEdit.editable(ms.get(2), ms.get(2).slots.get(0)) && GambitEdit.editable(ms.get(4), ms.get(4).slots.get(0))
                && GambitEdit.editable(ms.get(5), ms.get(5).slots.get(0)), "Fran, Basch, Penelo editable (static name trace)");
        check(!GambitEdit.editable(vaan, new Gambits.Slot(9, 0xFFFF, 0xFFFF, false, 0)), "empty slot not editable");
        ok("all six established members and only non-empty slots are editable");

        File dir = Files.createTempDirectory("gambitcmd").toFile();
        File sub = new File(dir, "cmd");
        check(GambitEdit.write(sub, text), "first write");
        check(new String(Files.readAllBytes(new File(sub, "gambit_cmd.txt").toPath()), StandardCharsets.US_ASCII).equals(text), "content");
        check(!GambitEdit.write(sub, text), "second write refused while one waits");
        check(!new File(sub, "gambit_cmd.tmp").exists(), "no temp left");
        ok("command written atomically; a second one refused while the first waits");

        Map<String, Object> e = new HashMap<>(), last = new HashMap<>();
        e.put("contract", "ffxii.gambitedit.v1");
        e.put("armed", Boolean.TRUE);
        check(!GambitEdit.parse(e).ready(), "armed without hooked (logger <= 0.5.2, or armed after start) is not ready");
        e.put("hooked", Boolean.FALSE);
        check(!GambitEdit.parse(e).ready() && GambitEdit.reason("editor_not_started").contains("restart"), "hooked false");
        e.put("hooked", Boolean.TRUE);
        check(GambitEdit.parse(e).ready(), "armed + hooked = ready");
        last.put("id", "c1792600000-abcdef");
        last.put("status", "applied");
        e.put("last", last);
        GambitEdit.State st = GambitEdit.parse(e);
        check(st.armed && GambitEdit.outcome(st, "c1792600000-abcdef").startsWith("Done ✓") && GambitEdit.outcome(st, "other") == null, "applied");
        last.put("persisted", Boolean.TRUE);
        check(GambitEdit.outcome(GambitEdit.parse(e), "c1792600000-abcdef").contains("still in place"), "persisted");
        last.put("status", "rejected");
        last.put("reason", "not_in_field");
        check(GambitEdit.outcome(GambitEdit.parse(e), "c1792600000-abcdef").contains("close the menu"), "rejected reason");
        last.put("status", "restore_failed");
        check(GambitEdit.outcome(GambitEdit.parse(e), "c1792600000-abcdef").startsWith("PROBLEM"), "restore_failed loud");
        e.put("contract", "other");
        check(GambitEdit.parse(e) == null && GambitEdit.parse(null) == null, "wrong contract");
        e.put("contract", "ffxii.gambitedit.v1");
        e.remove("armed");
        check(!GambitEdit.parse(e).armed, "armed only when true");
        ok("data.gambitEdit parsed; outcome only for the sent id; failures worded plainly");

        GambitEdit.Proposal ch = new GambitEdit.Proposal(vaan, s7);
        ch.change = true;
        check(!ch.changed() && ch.op().equals("set"), "change starts as a no-op");
        ch.newCondition = 0x60B5;
        check(ch.changed(), "changed");
        String setText = GambitEdit.commandText(ch, "c1792600000-abcdef", 1792600000L);
        check(setText.equals("ffxii.gambitcmd.v1\nid=c1792600000-abcdef\nissued=1792600000\nmember=0\nset=0\nslot=6\nop=set\n"
                + "cond=607e\naction=0052\nbits=003f\nnewCond=60b5\nnewAction=0052\n"), setText);
        ok("change proposal -> op set with the expected current value and newCond/newAction");

        List<Integer> conds = GambitEdit.conditionChoices(ms);
        java.util.Set<Integer> partyConds = new java.util.HashSet<>();
        for (Gambits.Member m : ms) for (Gambits.Slot s : m.slots) partyConds.add(s.condition);
        check(conds.containsAll(Gambits.CONDITIONS.keySet()), "all screen-read conditions offered");
        for (int c : conds) check(partyConds.contains(c) && GambitEdit.validCondition(c), "condition not in party: " + Integer.toHexString(c));
        List<Integer> acts = GambitEdit.actionChoices(vaan);
        java.util.Set<Integer> vaanActs = new java.util.HashSet<>();
        for (Gambits.Slot s : vaan.slots) vaanActs.add(s.action);
        check(acts.contains(0x96) && acts.contains(0xA9) && acts.contains(0x52), "Attack, Steal, Potion offered to Vaan");
        for (int a : acts) check((a == 0x96 || vaanActs.contains(a)) && GambitEdit.validAction(a), "action not Vaan's: " + Integer.toHexString(a));
        boolean other = false;
        for (Gambits.Slot s : ms.get(1).slots) if (!vaanActs.contains(s.action) && s.action != 0x96) other = true;
        check(other, "Ashe has actions Vaan does not (so the per-member limit is exercised)");
        for (Gambits.Slot s : ms.get(1).slots) if (!vaanActs.contains(s.action) && s.action != 0x96) check(!acts.contains(s.action), "Ashe-only action offered to Vaan");
        System.out.println("  conditions offered: " + conds.size() + ", actions offered to Vaan: " + acts.size());
        ok("choices: conditions from the party's own gambits; actions only Vaan's own + Attack (never Ashe-only ones)");

        // 0.11.2: replay of the 21 Oct live test. Vaan slot 7 was the party's only "Self: HP < 40%" (607e); once swapped
        // to Ally: HP < 50% it vanished from the choices, so the swap could not be undone.
        String[] after = java.util.Arrays.copyOf(all, 6);
        after[0] = after[0].substring(0, 6 * 8) + "b5605200" + after[0].substring(7 * 8);   // slot 7 condition 607e -> 60b5
        Map<String, Object> g2 = new HashMap<>(g);
        g2.put("sets", String.join(",", after));
        List<Gambits.Member> ms2 = Gambits.parse(g2);
        check(ms2.get(0).slots.get(6).condition == 0x60B5, "replayed swap");
        check(!GambitEdit.conditionChoices(ms2).contains(0x607E), "the bug: 607e no longer offered without session memory");
        GambitEdit.Seen seen = new GambitEdit.Seen();
        seen.update(2, ms);
        seen.update(2, ms2);
        check(GambitEdit.conditionChoices(ms2, seen).contains(0x607E), "seen this session -> offered, swap can be undone");
        check(GambitEdit.conditionChoices(ms2, seen).containsAll(GambitEdit.conditionChoices(ms2)), "superset of current");
        seen.update(3, ms2);
        check(!GambitEdit.conditionChoices(ms2, seen).contains(0x607E), "new game session forgets (another save may differ)");
        ok("undo after swap: the swapped-out condition stays offered for the rest of the session, forgotten on a new session");

        String[] lost = java.util.Arrays.copyOf(all, 6);
        lost[0] = lost[0].substring(0, 2 * 8 + 4) + "9600" + lost[0].substring(3 * 8);       // Vaan slot 3 Steal (00a9) -> Attack
        Map<String, Object> g3 = new HashMap<>(g);
        g3.put("sets", String.join(",", lost));
        List<Gambits.Member> ms3 = Gambits.parse(g3);
        GambitEdit.Seen seenA = new GambitEdit.Seen();
        seenA.update(5, ms);
        seenA.update(5, ms3);
        check(!GambitEdit.actionChoices(ms3.get(0)).contains(0xA9), "Steal gone from Vaan's current list");
        check(GambitEdit.actionChoices(ms3.get(0), seenA).contains(0xA9), "Steal offered again from session memory");
        check(!GambitEdit.actionChoices(ms3.get(1), seenA).contains(0xA9) || ms.get(1).slots.stream().anyMatch(x -> x.action == 0xA9),
                "Vaan's Steal is not offered to Ashe");
        ok("actions: a member's swapped-out action stays offered to that member only");

        GambitEdit.Proposal tp = new GambitEdit.Proposal(vaan, s7);
        check(!tp.sendable(), "slot tap alone cannot send");
        tp.confirmToggle = true;
        check(tp.sendable() && tp.op().equals("enable"), "ON/OFF needs the confirm step");
        GambitEdit.Proposal cp = new GambitEdit.Proposal(vaan, s7);
        cp.change = true;
        check(!cp.sendable(), "change without a new value cannot send");
        cp.newAction = 0x96;
        check(cp.sendable() && cp.op().equals("set"), "change with a new value");
        ok("two-tap rule: nothing is sendable straight after tapping a slot");

        long now = System.currentTimeMillis() / 1000;
        Files.write(Paths.get(args[1]), GambitEdit.commandText(p, GambitEdit.newId(now), now).getBytes(StandardCharsets.US_ASCII));
        if (args.length > 2)
            Files.write(Paths.get(args[2]), GambitEdit.commandText(ch, GambitEdit.newId(now), now).getBytes(StandardCharsets.US_ASCII));
        System.out.println(passed + " gambit editor checks passed (real Thor dump); command for the Lua cross-check: " + args[1]);
    }
}
