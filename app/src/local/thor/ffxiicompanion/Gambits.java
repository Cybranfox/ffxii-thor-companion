package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live gambit lists (data.gambits, contract ffxii.gambits.v1, ThorCompanion.lua 0.4.3+): each party member's three
 * stored 12-slot sets, which the game's Gambits menu commits when the player leaves the Gambits screen
 * (FFXII_Gambits_Static_2026-10-21.md; runtime-verified on the Thor for Vaan and Balthier, including a slot toggle).
 *
 * Condition names come from the reference bundle's gambitConditions (modded battle pack text; all screen-read names
 * match); the built-in screen-read names below are the fallback for an older bundle.
 * Names: actions resolve through the modded reference bundle (magicks 0x00-0x50 = magick 0x3000+a, items 0x52-0x91 =
 * item a-0x52, technicks 0x9E+ = technick 0x4000+(a-0x9E), Attack 0x96), each anchor seen on the game screen. Conditions
 * only have names that were read off the game screen; every other ID is shown raw, never guessed.
 */
final class Gambits {
    static final String CONTRACT = "ffxii.gambits.v1";
    static final int SET_BYTES = 0x34, MEMBER_BYTES = 0x9C, SLOTS = 12;
    /** Memory order is the game's internal one, not the menu's portrait order. All six established statically via member +4 ->
     *  battle-pack section 16 name ID (0x536310; Members_Battle_Trace_2026-10-21.md); Vaan, Ashe, Balthier also on screen. */
    static final String[] MEMBERS = {"Vaan", "Ashe", "Fran", "Balthier", "Basch", "Penelo"};
    static final boolean[] MEMBER_VERIFIED = {true, true, true, true, true, true};  // 0.14.1: Fran/Basch/Penelo by static name trace

    /** Condition names read from the game's Gambits screen (Vaan + Balthier, 21 Oct 2026). */
    static final Map<Integer, String> CONDITIONS = new HashMap<>();
    static {
        CONDITIONS.put(0x60B5, "Ally: HP < 50%");
        CONDITIONS.put(0x60C4, "Ally: status = KO");
        CONDITIONS.put(0x602A, "Foe: HP = 100%");
        CONDITIONS.put(0x6007, "Foe: nearest");
        CONDITIONS.put(0x60CD, "Ally: status = Silence");
        CONDITIONS.put(0x60CF, "Ally: status = Oil");
        CONDITIONS.put(0x607E, "Self: HP < 40%");
        CONDITIONS.put(0x60C6, "Ally: status = Petrify");
        CONDITIONS.put(0x60CC, "Ally: status = Poison");
        CONDITIONS.put(0x60C8, "Ally: status = Sleep");
        CONDITIONS.put(0x608B, "Self: MP < 10%");
    }

    static final class Slot {
        final int index;            // 1..36 across the three sets
        final int condition, action;
        final boolean on;
        final int setBits;          // the whole set's enabled mask, as read (the editor's expected value)
        Slot(int index, int condition, int action, boolean on, int setBits) {
            this.index = index; this.condition = condition; this.action = action; this.on = on; this.setBits = setBits;
        }
    }

    static final class Member {
        final int id;
        final String name;
        final List<Slot> slots;     // non-empty slots only, in menu order (set I, II, III)
        /** 0.10.0: the set the game uses now (0..2), or -1 when the game script doesn't publish it. */
        int active = -1;
        /** 0.10.0: the member's three stored sets as read (0x9C bytes, hex): the expected value for a set switch. */
        String storedHex;
        Member(int id, String name, List<Slot> slots) { this.id = id; this.name = name; this.slots = slots; }
    }

    /** Members 0..5, or null unless data.gambits is an ok, stable ffxii.gambits.v1 record. */
    static List<Member> parse(Map<String, Object> g) {
        if (g == null || !CONTRACT.equals(Json.str(g, "contract")) || !"ok".equals(Json.str(g, "status"))
                || !Boolean.TRUE.equals(Json.bool(g, "stable"))) return null;
        String sets = Json.str(g, "sets");
        if (sets == null) return null;
        String[] parts = sets.split(",");
        if (parts.length != MEMBERS.length) return null;
        String act = Json.str(g, "active");
        String[] actives = act != null ? act.split(",") : null;
        if (actives != null && actives.length != MEMBERS.length) actives = null;
        List<Member> out = new ArrayList<>();
        for (int m = 0; m < parts.length; m++) {
            byte[] raw = hex(parts[m]);
            if (raw == null || raw.length != MEMBER_BYTES) return null;
            List<Slot> slots = new ArrayList<>();
            for (int k = 0; k < 3; k++) {
                int base = k * SET_BYTES;
                int bits = u16(raw, base + 0x30);
                for (int i = 0; i < SLOTS; i++) {
                    int c = u16(raw, base + 4 * i), a = u16(raw, base + 4 * i + 2);
                    if (c == 0xFFFF && a == 0xFFFF) continue;
                    slots.add(new Slot(k * SLOTS + i + 1, c, a, (bits >> i & 1) != 0, bits));
                }
            }
            Member mem = new Member(m, MEMBERS[m], Collections.unmodifiableList(slots));
            mem.storedHex = parts[m].toLowerCase(java.util.Locale.ROOT);
            if (actives != null) {
                try {
                    int a = Integer.parseInt(actives[m].trim());
                    if (a >= 0 && a <= 2) mem.active = a;
                } catch (NumberFormatException ignored) {
                    // unknown: no set switching for this member
                }
            }
            out.add(mem);
        }
        return out;
    }

    static String condition(int id, Reference ref) {
        String n = ref != null ? ref.conditionName(id) : null;
        if (n == null) n = CONDITIONS.get(id);
        return n != null ? n : String.format("Condition %04X", id);
    }

    /** Action name via the modded reference bundle; raw ID when unknown or when no bundle is installed. */
    static String action(int a, Reference ref) {
        if (a == 0xFFFF) return "(none)";
        String exact = ref != null ? ref.actionName(a) : null;   // the menu's own wording, from the data pack
        if (exact != null) return exact;
        if (a == 0x96) return "Attack";
        Integer key = null;
        if (a <= 0x50) key = 0x3000 + a;
        else if (a >= 0x52 && a <= 0x91) key = a - 0x52;
        else if (a >= 0x9E && a <= 0x9E + 0x17) key = 0x4000 + (a - 0x9E);
        String n = key != null && ref != null ? ref.nameOf(key) : null;
        return n != null ? n : String.format("Action %04X", a);
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xFF) | (b[o + 1] & 0xFF) << 8; }

    /** Hex string to bytes; null if odd length, null input or a non-hex digit. */
    static byte[] hexBytes(String s) { return s == null ? null : hex(s); }

    private static byte[] hex(String s) {
        if (s.length() % 2 != 0) return null;
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(2 * i), 16), lo = Character.digit(s.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }
}
