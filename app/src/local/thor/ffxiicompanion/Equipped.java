package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Live equipped gear (0.18.0, logger 0.5.11 data.build.gear; Party_Vitals_Trace_2026-10-21.md) shown on the Gear tab as
 * one filter chip per member. Each slot links to that item's REFERENCE entry (Dynamic Equipment Description text, keyed
 * by the same equipment ID 0x1000+index). Pure: no Android types.
 */
final class Equipped {
    static final String[] SLOTS = {"Weapon", "Shield / ammo", "Head", "Body", "Accessory"};
    static final String SUFFIX = " (equipped)";

    static String filter(int member) { return Gambits.MEMBERS[member] + SUFFIX; }

    /** Member index for a filter chip made by filter(), or -1. */
    static int member(String filter) {
        if (filter == null || !filter.endsWith(SUFFIX)) return -1;
        String name = filter.substring(0, filter.length() - SUFFIX.length());
        for (int m = 0; m < Gambits.MEMBERS.length; m++) if (Gambits.MEMBERS[m].equals(name)) return m;
        return -1;
    }

    /** The five slots, then (0.19.0) what the game's Optimize would change: one entry per changed slot, or one "no change". */
    static List<Reference.Entry> entries(int[][] gear, int member, Reference ref, int[] optimized) {
        List<Reference.Entry> out = new ArrayList<>(entries(gear, member, ref));
        if (optimized == null || gear == null || member < 0 || member >= gear.length) return out;
        boolean any = false;
        for (int i = 0; i < 4; i++) {
            if (optimized[i] == gear[member][i]) continue;
            any = true;
            int id = optimized[i];
            Reference.Entry e = id == 0xFFFF || ref == null ? null : ref.gearById(id);
            String name = id == 0xFFFF ? "(empty)" : e != null ? e.name : String.format("Equipment %04X", id);
            out.add(new Reference.Entry("Optimize → " + name, SLOTS[i] + " · now " + label(gear[member][i], ref),
                    e != null ? e.tags : Collections.emptyList(), e != null ? e.lines : Collections.emptyList(), Collections.emptyList()));
        }
        if (!any) out.add(new Reference.Entry("Optimize: no change", "Already optimized",
                Collections.emptyList(), Collections.singletonList(OPT_NOTE), Collections.emptyList()));
        else out.add(new Reference.Entry("To apply: in the game, Equip → " + Gambits.MEMBERS[member] + " → Optimize",
                "Optimize in the Equip menu makes exactly these changes",
                Collections.emptyList(), java.util.Arrays.asList(OPT_NOTE, "", APPLY_NOTE), Collections.emptyList()));
        return out;
    }

    /** 0.20.1 (Equip_Writer_Trace_2026-10-21.md): why gear is never written from the companion. */
    static final String APPLY_NOTE = "Why not a button here: equipping in FFXII also rebuilds stats and augments, adjusts HP/MP, "
            + "resets the character's current action and refreshes their model, all inside the Equip menu. The companion can't "
            + "do that safely from outside, so it points you to the menu's Optimize, which follows the same rule.";

    static final String OPT_NOTE = "Follows the Equip menu's Optimize rule: best attack for weapons (bows and guns count their best "
            + "ammo), evade then magick evade for shields, magick resist then defence for head, defence then magick resist for body. "
            + "Only licensed items with a spare copy are considered; accessories are left alone. Suggestions only: nothing is changed in the game.";

    private static String label(int id, Reference ref) {
        if (id == 0xFFFF) return "empty";
        Reference.Entry e = ref != null ? ref.gearById(id) : null;
        return e != null ? e.name : String.format("Equipment %04X", id);
    }

    /** The five slots of one member, in game order; empty slots and IDs missing from the reference are still listed. */
    static List<Reference.Entry> entries(int[][] gear, int member, Reference ref) {
        if (gear == null || member < 0 || member >= gear.length) return Collections.emptyList();
        List<Reference.Entry> out = new ArrayList<>();
        for (int i = 0; i < SLOTS.length; i++) {
            int id = gear[member][i];
            Reference.Entry e = id == 0xFFFF || ref == null ? null : ref.gearById(id);
            if (id == 0xFFFF) out.add(new Reference.Entry("(empty)", SLOTS[i], Collections.emptyList(), Collections.emptyList(), Collections.emptyList()));
            else if (e == null) out.add(new Reference.Entry(String.format("Equipment %04X", id), SLOTS[i], Collections.emptyList(),
                    Collections.singletonList("Not in the reference bundle."), Collections.emptyList()));
            else out.add(new Reference.Entry(e.name, SLOTS[i] + " · " + e.category, e.tags, e.lines, e.locations));
        }
        return out;
    }
}
