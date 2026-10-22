package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turning a suggested set (GambitPlan) into editor steps for Set I (0.14.0, Gambit_Apply_2026-10-21.md). Pure: no I/O.
 *
 * Slot i of the suggestion goes to Set I slot i with op "setslot" (condition + action + ON in one checked write; the game
 * script also re-checks ownership and licences). Slots after the suggestion that are ON are switched OFF (kept, not
 * deleted). Slots already equal are skipped. Each step's command is built only when it is sent, from the freshest data,
 * so its expected values are current; the runner sends one step, waits for the game's answer and the change to show up,
 * and stops at the first failure.
 */
final class ApplyPlan {
    static final class Step {
        final int slot;            // 0..11 in Set I
        final boolean disable;     // true = switch OFF; false = setslot
        final int condition, action;
        Step(int slot, boolean disable, int condition, int action) {
            this.slot = slot; this.disable = disable; this.condition = condition; this.action = action;
        }
    }

    /** Set I as read: condition/action per slot (0xFFFF empty) and the set's enabled mask. */
    static final class SetI {
        final int[] cond = new int[12], act = new int[12];
        int bits;
        boolean on(int i) { return (bits >> i & 1) != 0; }
    }

    static SetI setI(Gambits.Member m) {
        SetI s = new SetI();
        java.util.Arrays.fill(s.cond, 0xFFFF);
        java.util.Arrays.fill(s.act, 0xFFFF);
        for (Gambits.Slot x : m.slots) {
            if (x.index > Gambits.SLOTS) continue;
            int i = x.index - 1;
            s.cond[i] = x.condition; s.act[i] = x.action; s.bits = x.setBits;
        }
        if (m.slots.isEmpty() || m.slots.get(0).index > Gambits.SLOTS) s.bits = 0;
        return s;
    }

    /** Steps to make Set I equal the suggestion (at most 12 slots); empty list = already identical. */
    static List<Step> steps(Gambits.Member current, GambitPlan.Plan plan) {
        SetI s = setI(current);
        List<Step> out = new ArrayList<>();
        int n = Math.min(plan.slots.size(), Gambits.SLOTS);
        for (int i = 0; i < n; i++) {
            GambitPlan.Slot p = plan.slots.get(i);
            if (s.cond[i] == p.condition && s.act[i] == p.action && s.on(i)) continue;
            out.add(new Step(i, false, p.condition, p.action));
        }
        for (int i = n; i < Gambits.SLOTS; i++) if (s.cond[i] != 0xFFFF && s.act[i] != 0xFFFF && s.on(i)) out.add(new Step(i, true, -1, -1));
        return Collections.unmodifiableList(out);
    }

    /** A single suggested slot only. */
    static List<Step> one(Gambits.Member current, GambitPlan.Plan plan, int i) {
        SetI s = setI(current);
        GambitPlan.Slot p = plan.slots.get(i);
        if (s.cond[i] == p.condition && s.act[i] == p.action && s.on(i)) return Collections.emptyList();
        return Collections.singletonList(new Step(i, false, p.condition, p.action));
    }

    /** True once the fresh Set I shows this step done. */
    static boolean done(Gambits.Member fresh, Step st) {
        SetI s = setI(fresh);
        return st.disable ? !s.on(st.slot) : s.cond[st.slot] == st.condition && s.act[st.slot] == st.action && s.on(st.slot);
    }

    /** The command for this step, built from fresh data (expected values = Set I as it is right now). */
    static String command(Gambits.Member fresh, Step st, String id, long issuedSec) {
        SetI s = setI(fresh);
        return GambitEdit.CMD_CONTRACT + "\n"
                + "id=" + id + "\n"
                + "issued=" + issuedSec + "\n"
                + "member=" + fresh.id + "\n"
                + "set=0\n"
                + "slot=" + st.slot + "\n"
                + "op=" + (st.disable ? "disable" : "setslot") + "\n"
                + String.format("cond=%04x\naction=%04x\nbits=%04x\n", s.cond[st.slot], s.act[st.slot], s.bits)
                + (st.disable ? "" : String.format("newCond=%04x\nnewAction=%04x\n", st.condition, st.action));
    }
}
