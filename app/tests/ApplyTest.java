package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * ApplyPlan on REAL data (sets, build and licences from the Thor dump save_27 via gen_build_fixture.py; reference bundle
 * args[1]). Writes the first setslot command of Vaan's plan to args[2] for the Lua cross-check against the same dump.
 */
public final class ApplyTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Path p) throws Exception {
        return (Map<String, Object>) Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get(args[0]);
        if (args.length < 3 || !Files.isRegularFile(dir.resolve("gambits_before.json"))) { System.out.println("SKIP ApplyTest"); return; }
        List<Gambits.Member> ms = Gambits.parse(obj(dir.resolve("gambits_before.json")));
        Build build = Build.parse(obj(dir.resolve("build_before.json")));
        LicenceNodes nodes = LicenceNodes.parse(obj(dir.resolve("licence_nodes.json")));
        Reference ref = Reference.parse(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8));
        List<GambitPlan.Plan> plans = GambitPlan.suggest(build, nodes, ref, ms, null);
        Gambits.Member vaan = ms.get(0);
        GambitPlan.Plan vp = plans.get(0);
        List<ApplyPlan.Step> steps = ApplyPlan.steps(vaan, vp);
        ApplyPlan.SetI s = ApplyPlan.setI(vaan);
        int expect = 0;
        for (int i = 0; i < vp.slots.size(); i++)
            if (!(s.cond[i] == vp.slots.get(i).condition && s.act[i] == vp.slots.get(i).action && s.on(i))) expect++;
        for (int i = vp.slots.size(); i < 12; i++) if (s.cond[i] != 0xFFFF && s.on(i)) expect++;
        check(steps.size() == expect && expect > 0, "step count " + steps.size() + " vs " + expect);
        for (ApplyPlan.Step st : steps)
            check(st.disable ? st.slot >= vp.slots.size() && s.on(st.slot)
                    : st.condition == vp.slots.get(st.slot).condition && st.action == vp.slots.get(st.slot).action, "step " + st.slot);
        System.out.println("  Vaan: " + steps.size() + " step(s) for a " + vp.slots.size() + "-slot suggestion");
        ok("steps = exactly the differing suggested slots + ON leftovers switched OFF; identical slots skipped");

        ApplyPlan.Step first = steps.get(0);
        check(!first.disable, "first is a setslot");
        String cmd = ApplyPlan.command(vaan, first, "c1792600000-abcdef", 1792600000L);
        check(cmd.startsWith("ffxii.gambitcmd.v1\nid=c1792600000-abcdef\nissued=1792600000\nmember=0\nset=0\nslot=" + first.slot + "\nop=setslot\n")
                && cmd.contains(String.format("cond=%04x\naction=%04x\nbits=%04x\n", s.cond[first.slot], s.act[first.slot], s.bits))
                && cmd.endsWith(String.format("newCond=%04x\nnewAction=%04x\n", first.condition, first.action)), cmd);
        ok("setslot command carries Set I's current values as expected + the new condition/action");

        check(!ApplyPlan.done(vaan, first), "not done before");
        StringBuilder sets = new StringBuilder();
        Map<String, Object> g = obj(dir.resolve("gambits_before.json"));
        String[] hx = Json.str(g, "sets").split(",");
        byte[] v = Gambits.hexBytes(hx[0]);
        int o = 4 * first.slot;
        v[o] = (byte) first.condition; v[o + 1] = (byte) (first.condition >> 8);
        v[o + 2] = (byte) first.action; v[o + 3] = (byte) (first.action >> 8);
        int bits = (v[0x30] & 0xFF) | (v[0x31] & 0xFF) << 8 | 1 << first.slot;
        v[0x30] = (byte) bits; v[0x31] = (byte) (bits >> 8);
        StringBuilder h = new StringBuilder();
        for (byte b : v) h.append(String.format("%02x", b));
        hx[0] = h.toString();
        g.put("sets", String.join(",", hx));
        Gambits.Member after = Gambits.parse(g).get(0);
        check(ApplyPlan.done(after, first), "done after");
        check(ApplyPlan.steps(after, vp).size() == steps.size() - 1, "one step fewer");
        ok("done() recognises the change in fresh data; re-planning drops that step");

        List<ApplyPlan.Step> one = ApplyPlan.one(vaan, vp, first.slot);
        check(one.size() == 1 && one.get(0).slot == first.slot, "single slot");
        check(ApplyPlan.one(after, vp, first.slot).isEmpty(), "already applied -> nothing");
        ok("single-slot apply: one step, or nothing when the slot already matches");

        long now = System.currentTimeMillis() / 1000;
        Files.write(Paths.get(args[2]), ApplyPlan.command(vaan, first, GambitEdit.newId(now), now).getBytes(StandardCharsets.US_ASCII));
        System.out.println(passed + " apply checks passed (real Thor dump); setslot command for the Lua cross-check: " + args[2]);
    }
}
