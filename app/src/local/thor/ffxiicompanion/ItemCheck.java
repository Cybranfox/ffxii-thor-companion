package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * (0.20.0) ON gambits whose item the game would refuse right now (live stock: available = 0, Build.available), each with a
 * safe fix: a same-job item in stock, or a spell this member can cast, else "switch OFF". Pure: no I/O.
 *
 * Substitutes are deliberately narrow, only swaps that do the same job for certain: Potion / Hi-Potion / X-Potion,
 * Ether / Hi-Ether (Elixirs are never offered), Phoenix Down -> Raise / Arise, and the four classic status cures
 * (Gold Needle -> Stona, Echo Herbs -> Vox, Antidote -> Poisona, Eye Drops -> Blindna). Anything else (e.g. Chronos Tear)
 * only gets "switch OFF".
 */
final class ItemCheck {
    static final class Issue {
        final Gambits.Slot slot;
        final int fix;            // replacement action, or -1 = switch OFF
        Issue(Gambits.Slot slot, int fix) { this.slot = slot; this.fix = fix; }
    }

    private static final String[][] TIERS = {{"Potion", "Hi-Potion", "X-Potion"}, {"Ether", "Hi-Ether"}};
    private static final String[][] SPELLS = {
            {"Potion", "Cure", "Cura"}, {"Hi-Potion", "Cura", "Cure"}, {"X-Potion", "Curaga", "Cura"},
            {"Phoenix Down", "Raise", "Arise"}, {"Gold Needle", "Stona"}, {"Echo Herbs", "Vox"},
            {"Antidote", "Poisona"}, {"Eye Drops", "Blindna"}};

    /** Issues for one member's ON slots (all three sets), in slot order; empty when stock is unknown. */
    static List<Issue> check(Gambits.Member m, Build b, LicenceNodes nodes, Reference ref) {
        if (m == null || b == null || !b.stockKnown()) return Collections.emptyList();
        Set<Integer> can = new java.util.HashSet<>(GambitEdit.usableAbilities(b, nodes, m.id));
        List<Issue> out = new ArrayList<>();
        for (Gambits.Slot s : m.slots) {
            if (!s.on || s.condition == 0xFFFF || b.available(s.action) != 0) continue;   // -1 = not an item; > 0 = fine
            out.add(new Issue(s, fix(s.action, b, can, ref)));
        }
        return out;
    }

    static int fix(int action, Build b, Set<Integer> can, Reference ref) {
        String name = ref != null ? Gambits.action(action, ref) : null;
        if (name == null) return -1;
        for (String[] tier : TIERS) {
            int at = indexOf(tier, name);
            if (at < 0) continue;
            for (int d = 1; d < tier.length; d++)           // nearest stronger first, then weaker
                for (int i : new int[]{at + d, at - d}) {
                    if (i < 0 || i >= tier.length) continue;
                    int a = GambitPlan.action(ref, tier[i]);
                    if (a >= 0 && b.available(a) > 0) return a;
                }
        }
        for (String[] sp : SPELLS) {
            if (!sp[0].equals(name)) continue;
            for (int i = 1; i < sp.length; i++) {
                int a = GambitPlan.action(ref, sp[i]);
                if (a >= 0 && can.contains(a)) return a;
            }
        }
        return -1;
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return -1;
    }
}
