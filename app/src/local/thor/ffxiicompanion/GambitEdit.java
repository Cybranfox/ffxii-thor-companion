package local.thor.ffxiicompanion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.TreeSet;

/**
 * Companion side of the gated gambit editor (ThorCompanion.lua 0.5.0, FFXII_Gambit_Editor_050_2026-10-21.md).
 *
 * The companion never touches the game: it drops ONE command file (Download/FFXII_companion/cmd/gambit_cmd.txt) naming a
 * member, set, slot, the slot's value as last read (condition, action, the set's enabled mask) and the change. The game
 * script deletes the file, checks every gate (armed, build, save, Manifesto 36-slot off, field, the expected value still
 * current, the game's runtime list equal to the replica), writes on the game's frame thread, reads back, restores on a
 * mismatch and reports in data.gambitEdit. Offered: switching one slot ON/OFF, or (0.11.0) changing its condition and/or
 * action, only for members whose name was confirmed on the game screen. Choices are limited to what the party provably
 * has (see conditionChoices / actionChoices) until gambit ownership and licences are read from the save.
 */
final class GambitEdit {
    static final String CONTRACT = "ffxii.gambitedit.v1";
    static final String CMD_CONTRACT = "ffxii.gambitcmd.v1";
    static final String CMD_FILE = "gambit_cmd.txt", TMP_FILE = "gambit_cmd.tmp";
    private static final SecureRandom RNG = new SecureRandom();

    /** One proposed change. set 0..2, slot 0..11 (the game's indices; the screen shows slot + 1). */
    static final class Proposal {
        final int member, set, slot, condition, action, setBits;
        final boolean turnOn;
        final String memberName;
        /** change = replace condition/action (op "set", ON/OFF kept) instead of switching ON/OFF. */
        boolean change;
        /** 0.11.2: ON/OFF chosen; the bar then asks for the send tap. A slot tap alone never offers a one-tap send. */
        boolean confirmToggle;
        int newCondition, newAction;
        Proposal(Gambits.Member m, Gambits.Slot s) {
            member = m.id; memberName = m.name;
            set = (s.index - 1) / Gambits.SLOTS; slot = (s.index - 1) % Gambits.SLOTS;
            condition = s.condition; action = s.action; setBits = s.setBits; turnOn = !s.on;
            newCondition = condition; newAction = action;
        }
        String op() { return change ? "set" : turnOn ? "enable" : "disable"; }
        boolean changed() { return newCondition != condition || newAction != action; }
        /** Two taps minimum: a change needs a real difference; ON/OFF needs the separate confirm step. */
        boolean sendable() { return change ? changed() : confirmToggle; }
        String where() { return memberName + " · Set " + (set == 0 ? "I" : set == 1 ? "II" : "III") + " · slot " + (slot + 1); }
    }

    /** Slots the companion may offer to switch: a non-empty slot of a member verified on the game screen. */
    static boolean editable(Gambits.Member m, Gambits.Slot s) {
        return m != null && s != null && m.id >= 0 && m.id < Gambits.MEMBER_VERIFIED.length && Gambits.MEMBER_VERIFIED[m.id]
                && s.condition != 0xFFFF && s.action != 0xFFFF;
    }

    static String newId(long issuedSec) {
        return String.format("c%d-%06x", issuedSec, RNG.nextInt(1 << 24));
    }

    static String commandText(Proposal p, String id, long issuedSec) {
        return CMD_CONTRACT + "\n"
                + "id=" + id + "\n"
                + "issued=" + issuedSec + "\n"
                + "member=" + p.member + "\n"
                + "set=" + p.set + "\n"
                + "slot=" + p.slot + "\n"
                + "op=" + p.op() + "\n"
                + String.format("cond=%04x\naction=%04x\nbits=%04x\n", p.condition, p.action, p.setBits)
                + (p.change ? String.format("newCond=%04x\nnewAction=%04x\n", p.newCondition, p.newAction) : "");
    }

    /**
     * 0.10.0: switch member m's active set to target (game script op setactive). Carries what the companion saw (the
     * current active set and all three stored sets) so the game script can refuse if anything changed meanwhile.
     */
    static String setActiveText(Gambits.Member m, int target, String id, long issuedSec) {
        return CMD_CONTRACT + "\n"
                + "id=" + id + "\n"
                + "issued=" + issuedSec + "\n"
                + "member=" + m.id + "\n"
                + "set=" + target + "\n"
                + "op=setactive\n"
                + "from=" + m.active + "\n"
                + "stored=" + m.storedHex + "\n";
    }

    /** A set switch can be offered: verified member, active set known, stored bytes known, a different set. */
    static boolean canSwitch(Gambits.Member m, int target) {
        return m != null && m.id >= 0 && m.id < Gambits.MEMBER_VERIFIED.length && Gambits.MEMBER_VERIFIED[m.id]
                && m.active >= 0 && m.storedHex != null && m.storedHex.length() == Gambits.MEMBER_BYTES * 2
                && target >= 0 && target <= 2 && target != m.active;
    }

    static String setName(int k) { return "Set " + (k == 0 ? "I" : k == 1 ? "II" : "III"); }

    /** IDs the game script accepts (ThorCompanion.lua validAction; condition table of 256). */
    static boolean validCondition(int c) { return c >= 0x6000 && c < 0x6100; }

    static boolean validAction(int a) {
        return a <= 0x50 || (a >= 0x52 && a <= 0x91 && !reservedItem(a)) || a == 0x96 || (a >= 0x9E && a <= 0x9E + 0x17);
    }

    /** 0x6F..0x7B sit inside the item range but are reserved records, not items (Action_IDs_and_Set_Switch_2026-10-22). */
    static boolean reservedItem(int a) { return a >= 0x6F && a <= 0x7B; }

    /**
     * Everything seen in this game session's live gambit data (0.11.2): conditions party-wide, actions per member. Gambits
     * stay owned and abilities stay learned, so what was in the party's lists earlier this session is still allowed after
     * it has been swapped out; this is what lets a swap always be undone. Cleared when the receiver sees a new session
     * (another save could own different gambits).
     */
    static final class Seen {
        private int session = Integer.MIN_VALUE;
        private final TreeSet<Integer> conditions = new TreeSet<>();
        private final Map<Integer, TreeSet<Integer>> actions = new HashMap<>();

        /** Call with every live, verified gambit snapshot. */
        void update(int session, List<Gambits.Member> ms) {
            if (session != this.session) { this.session = session; conditions.clear(); actions.clear(); }
            if (ms == null) return;
            for (Gambits.Member m : ms) {
                TreeSet<Integer> a = actions.computeIfAbsent(m.id, k -> new TreeSet<>());
                for (Gambits.Slot s : m.slots) {
                    if (validCondition(s.condition)) conditions.add(s.condition);
                    if (validAction(s.action)) a.add(s.action);
                }
            }
        }
    }

    /** Conditions the party provably owns: every condition in any member's three sets now (ON or OFF) or seen this session. */
    static List<Integer> conditionChoices(List<Gambits.Member> ms) { return conditionChoices(ms, null); }

    static List<Integer> conditionChoices(List<Gambits.Member> ms, Seen seen) { return conditionChoices(ms, seen, null); }

    /** 0.12.0: plus every gambit the party owns (data.build.owned, probe) when the game script reports it. */
    static List<Integer> conditionChoices(List<Gambits.Member> ms, Seen seen, Build build) {
        TreeSet<Integer> out = new TreeSet<>();
        if (ms != null) for (Gambits.Member m : ms) for (Gambits.Slot s : m.slots) if (validCondition(s.condition)) out.add(s.condition);
        if (seen != null) out.addAll(seen.conditions);
        if (build != null && build.gambits != null) for (int c : build.gambits) if (validCondition(c)) out.add(c);
        return new ArrayList<>(out);
    }

    /**
     * Magicks and technicks member m can use: the party owns it (data.build.owned, probe) AND one of m's licences grants
     * it (licence bits + live node table, runtime-confirmed). Empty when either source is missing.
     */
    static List<Integer> usableAbilities(Build build, LicenceNodes nodes, int member) {
        List<Integer> out = new ArrayList<>();
        if (build == null || nodes == null || !build.ownershipKnown() || member < 0 || member >= build.members.size()) return out;
        Build.Member bm = build.members.get(member);
        for (int id : build.magicks) { int a = id - 0x3000; if (nodes.licensed(bm, a)) out.add(a); }
        for (int id : build.technicks) { int a = 0x9E + (id - 0x4000); if (nodes.licensed(bm, a)) out.add(a); }
        Collections.sort(out);
        return out;
    }

    /** Actions this member provably can use: Attack plus every action in their own three sets now or seen this session. */
    static List<Integer> actionChoices(Gambits.Member m) { return actionChoices(m, null); }

    static List<Integer> actionChoices(Gambits.Member m, Seen seen) { return actionChoices(m, seen, null, null); }

    /** 0.12.0: plus the magicks/technicks this member can use (usableAbilities) when ownership and licences are known. */
    static List<Integer> actionChoices(Gambits.Member m, Seen seen, Build build, LicenceNodes nodes) {
        TreeSet<Integer> out = new TreeSet<>();
        out.add(0x96);
        if (m != null) for (Gambits.Slot s : m.slots) if (validAction(s.action)) out.add(s.action);
        if (m != null && seen != null && seen.actions.containsKey(m.id)) out.addAll(seen.actions.get(m.id));
        if (m != null) for (int a : usableAbilities(build, nodes, m.id)) if (validAction(a)) out.add(a);
        return new ArrayList<>(out);
    }

    /** Writes the command atomically (temp file + rename). False if a command is already waiting for the game. */
    static boolean write(File dir, String text) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File cmd = new File(dir, CMD_FILE), tmp = new File(dir, TMP_FILE);
        if (cmd.exists()) return false;
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(text.getBytes(StandardCharsets.US_ASCII));
            o.getFD().sync();
        }
        if (!tmp.renameTo(cmd)) { tmp.delete(); throw new IOException("rename failed"); }
        return true;
    }

    /** data.gambitEdit as reported by the game script; null when absent or another contract. */
    static final class State {
        /** armed = the ARMED file exists now; hooked = the game script installed its frame hook at load (0.5.3+),
         *  which only happens when ARMED existed at game start. Edits need both. */
        final boolean armed, hooked;
        final String pending, lastId, lastStatus, lastReason;
        final Boolean persisted;
        State(boolean armed, boolean hooked, String pending, String lastId, String lastStatus, String lastReason, Boolean persisted) {
            this.armed = armed; this.hooked = hooked; this.pending = pending; this.lastId = lastId; this.lastStatus = lastStatus;
            this.lastReason = lastReason; this.persisted = persisted;
        }
        boolean ready() { return armed && hooked; }
    }

    static State parse(Map<String, Object> e) {
        if (e == null || !CONTRACT.equals(Json.str(e, "contract"))) return null;
        Map<String, Object> last = Json.obj(e, "last");
        return new State(Boolean.TRUE.equals(Json.bool(e, "armed")), Boolean.TRUE.equals(Json.bool(e, "hooked")), Json.str(e, "pending"),
                last != null ? Json.str(last, "id") : null, last != null ? Json.str(last, "status") : null,
                last != null ? Json.str(last, "reason") : null, last != null ? Json.bool(last, "persisted") : null);
    }

    /** What the player sees once the game script has answered command id. Null while it has not. */
    static String outcome(State st, String id) {
        if (st == null || id == null || !id.equals(st.lastId) || st.lastStatus == null) return null;
        switch (st.lastStatus) {
            case "applied":
                return "Done ✓ — the game has the change" + (Boolean.TRUE.equals(st.persisted) ? " (still in place 2 s later)."
                        : Boolean.FALSE.equals(st.persisted) ? ", but the game changed it again within 2 s." : ".");
            case "restored": return "Not applied: the game did not read back as expected, so the old gambit was put back.";
            case "restore_failed": return "PROBLEM: the write did not read back and could not be fully undone. Check this gambit in the game's menu.";
            case "expired": return "Not applied: the game did not get to it in time (paused?). Nothing changed.";
            default: return "Not applied (" + reason(st.lastReason) + "). Nothing changed.";
        }
    }

    static String reason(String r) {
        if (r == null) return "unknown";
        switch (r) {
            case "not_armed": return "editor not armed";
            case "editor_not_started": return "editor was armed after the game started — restart the game";
            case "not_in_field": return "not in the field view — close the menu";
            case "stale_expected": return "the gambit changed in the game since it was shown";
            case "runtime_mismatch": return "the game's live list differs from the model";
            case "already_on": return "already ON";
            case "already_off": return "already OFF";
            case "command_too_old": return "request too old";
            case "duplicate_id": return "duplicate request";
            case "manifesto_36_slot_on_or_unknown": return "Manifesto '36 Gambits & 1 Set' is on or unreadable";
            case "save_not_ready": return "no save loaded";
            case "busy": return "another change is in progress";
            case "gambits_menu_open": return "the game's Gambits menu is open — close it first";
            case "condition_table_unavailable": return "the game's gambit table isn't readable right now";
            case "slot_locked": return "that set uses a slot the character hasn't unlocked";
            default: return r.replace('_', ' ');
        }
    }
}
