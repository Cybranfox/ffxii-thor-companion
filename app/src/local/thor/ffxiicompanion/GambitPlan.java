package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gambit suggestions (0.13.0, Gambit_Autobuilder_2026-10-21.md). SUGGESTIONS ONLY: nothing here writes to the game.
 *
 * Inputs (all live): owned gambits, the magicks/technicks each member can use (owned AND licensed, GambitEdit.usableAbilities),
 * jobs (job id j = licence board_(j+1); confirmed by every member's licences fitting exactly their two boards), and items:
 * (0.16.0) those the game would let a gambit use right now (live stock, available > 0); with no stock data, the items the
 * party already has in its gambits, labelled "check stock".
 *
 * Ordering follows what common guides agree on: revive, heal, cure status, then Steal (above attacks, below survival),
 * buffs, then attacks with a general Attack last (GamerGuides battle-system page; Samurai Gamers "Recommended Gambits";
 * Jegged gambit notes; see the doc for links). Roles come from usable abilities, jobs only break ties.
 */
final class GambitPlan {
    static final String[] JOBS = {"White Mage", "Uhlan", "Machinist", "Red Battlemage", "Knight", "Monk", "Time Battlemage",
            "Foebreaker", "Archer", "Black Mage", "Bushi", "Shikari"};

    // conditions (names checked against the modded game text in tests)
    static final int ALLY_ANY = 0x60A3, ALLY_KO = 0x60C4, ALLY_HP30 = 0x60B7, ALLY_HP40 = 0x60B6, ALLY_HP60 = 0x60B4,
            ALLY_PETRIFY = 0x60C6, ALLY_SILENCE = 0x60CD, ALLY_POISON = 0x60CC, ALLY_BLIND = 0x60CB, ALLY_SLEEP = 0x60C8,
            FOE_FULL_HP = 0x602A, FOE_FLYING = 0x605A, FOE_LEADER_TARGET = 0x6000, FOE_NEAREST_VISIBLE = 0x6001,
            SELF_HP30 = 0x607F, SELF_MP10 = 0x608B, FOE_UNDEAD = 0x6059;
    /** Foe: <element>-weak conditions and that element's spells, strongest first (game text names). */
    static final Object[][] ELEMENTS = {
            {0x6049, new String[]{"Firaga", "Fira", "Fire"}}, {0x604A, new String[]{"Thundaga", "Thundara", "Thunder"}},
            {0x604B, new String[]{"Blizzaga", "Blizzara", "Blizzard"}}, {0x604D, new String[]{"Aqua"}},
            {0x604E, new String[]{"Aeroga", "Aero"}}, {0x604F, new String[]{"Holy"}}, {0x6050, new String[]{"Darkga", "Darkra", "Dark"}}};
    static final int ATTACK = 0x96;

    static final class Slot {
        final int condition, action;
        final String reason;
        Slot(int condition, int action, String reason) { this.condition = condition; this.action = action; this.reason = reason; }
    }

    static final class Plan {
        final int member;
        final String role, jobs;
        final List<Slot> slots;
        Plan(int member, String role, String jobs, List<Slot> slots) { this.member = member; this.role = role; this.jobs = jobs; this.slots = slots; }
    }

    /** Ability name -> gambit action, from the reference bundle (magick 0x3000+a, technick 0x4000+(a-0x9E), item a-0x52). */
    static int action(Reference ref, String name) {
        if (ref == null) return -1;
        for (int a = 0; a <= 0x50; a++) if (name.equals(ref.nameOf(0x3000 + a))) return a;
        for (int a = 0x9E; a <= 0x9E + 0x17; a++) if (name.equals(ref.nameOf(0x4000 + a - 0x9E))) return a;
        for (int a = 0x52; a <= 0x91; a++) if (name.equals(ref.nameOf(a - 0x52))) return a;
        return -1;
    }

    static String jobs(Build.Member bm) {
        String a = bm.job1 < JOBS.length ? JOBS[bm.job1] : null, b = bm.job2 < JOBS.length ? JOBS[bm.job2] : null;
        return a == null ? (b == null ? "no job" : b) : b == null ? a : a + " / " + b;
    }

    /** Suggestions for all six members, or null when the inputs are missing (no ownership, licences or reference). */
    static List<Plan> suggest(Build build, LicenceNodes nodes, Reference ref, List<Gambits.Member> current) {
        if (build == null || nodes == null || ref == null || !build.ownershipKnown()) return null;
        Set<Integer> conds = new HashSet<>(build.gambits);
        List<Set<Integer>> can = new ArrayList<>();
        for (int m = 0; m < 6; m++) can.add(new HashSet<>(GambitEdit.usableAbilities(build, nodes, m)));
        Set<Integer> items = new HashSet<>();
        if (build.stockKnown()) { for (int a = 0x52; a <= 0x91; a++) if (build.available(a) > 0) items.add(a); }
        else if (current != null) for (Gambits.Member gm : current) for (Gambits.Slot s : gm.slots) if (Build.itemOf(s.action) >= 0) items.add(s.action);
        String stockNote = build.stockKnown() ? "" : " (item: check stock)";

        int cure = action(ref, "Cure"), cura = action(ref, "Cura"), curaga = action(ref, "Curaga"), curaja = action(ref, "Curaja");
        int raise = action(ref, "Raise"), arise = action(ref, "Arise"), esuna = action(ref, "Esuna");
        int[] healing = {curaja, curaga, cura, cure};
        // roles: primary healer = best healing score (jobs break ties: White Mage, then Red Battlemage)
        int healer = -1, healer2 = -1, best = 0, best2 = 0;
        for (int m = 0; m < 6; m++) {
            Set<Integer> c = can.get(m);
            int score = (c.contains(curaja) ? 8 : 0) + (c.contains(curaga) ? 6 : 0) + (c.contains(cura) ? 3 : 0) + (c.contains(cure) ? 1 : 0)
                    + (c.contains(raise) || c.contains(arise) ? 2 : 0) + (c.contains(esuna) ? 1 : 0);
            Build.Member bm = build.members.get(m);
            if (score > 0 && (bm.job1 == 0 || bm.job2 == 0)) score += 1;
            if (score > best) { best2 = best; healer2 = healer; best = score; healer = m; }
            else if (score > best2) { best2 = score; healer2 = m; }
        }
        int steal = action(ref, "Steal"), thief = -1;
        for (int m = 0; m < 6 && thief < 0; m++) {
            Build.Member bm = build.members.get(m);
            if (can.get(m).contains(steal) && (bm.job1 == 11 || bm.job2 == 11)) thief = m;   // Shikari first
        }
        for (int m = 0; m < 6 && thief < 0; m++) if (can.get(m).contains(steal) && m != healer) thief = m;

        int phoenix = action(ref, "Phoenix Down"), potion = action(ref, "Potion"), hiPotion = action(ref, "Hi-Potion");
        int protect = action(ref, "Protect"), shell = action(ref, "Shell"), haste = action(ref, "Haste"), charge = action(ref, "Charge");
        int[][] cures = {{ALLY_PETRIFY, action(ref, "Stona"), action(ref, "Gold Needle")}, {ALLY_SILENCE, action(ref, "Vox"), action(ref, "Echo Herbs")},
                {ALLY_POISON, action(ref, "Poisona"), action(ref, "Antidote")}, {ALLY_BLIND, action(ref, "Blindna"), action(ref, "Eye Drops")},
                {ALLY_SLEEP, esuna, -1}};

        List<Plan> out = new ArrayList<>();
        for (int m = 0; m < 6; m++) {
            Set<Integer> c = can.get(m);
            Build.Member bm = build.members.get(m);
            List<Slot> s = new ArrayList<>();
            String role;
            if (m == healer) {
                role = "Healer";
                int rev = c.contains(arise) ? arise : c.contains(raise) ? raise : items.contains(phoenix) ? phoenix : -1;
                add(s, conds, ALLY_KO, rev, "Revive first: a KO'd ally deals and takes nothing" + (rev == phoenix ? stockNote : ""));
                int big = first(c, healing);
                add(s, conds, ALLY_HP30, big, "Emergency heal with the strongest cure " + Gambits.action(big, ref) + " can cast");
                int small = big == cura && c.contains(cure) ? cure : c.contains(cura) ? cura : c.contains(cure) ? cure : -1;
                add(s, conds, ALLY_HP60, small, "Top up before it gets critical");
                for (int[] k : cures) add(s, conds, k[0], c.contains(k[1]) ? k[1] : -1, "Cure the status with magick");
                add(s, conds, SELF_MP10, c.contains(charge) ? charge : -1, "Keep MP for healing");
            } else if (m == healer2) {
                role = "Backup healer";
                add(s, conds, ALLY_KO, items.contains(phoenix) ? phoenix : c.contains(raise) ? raise : -1,
                        "Second reviver in case the healer is down" + (items.contains(phoenix) ? stockNote : ""));
                add(s, conds, ALLY_HP40, first(c, healing), "Second healer covers spikes");
                for (int[] k : cures) if (k[2] >= 0 && items.contains(k[2])) add(s, conds, k[0], k[2], "Status cure by item (faster than magick)" + stockNote);
            } else {
                role = m == thief ? "Thief / fighter" : spells(c, ref) >= 2 ? "Caster" : "Fighter";
                add(s, conds, SELF_HP30, items.contains(hiPotion) ? hiPotion : items.contains(potion) ? potion : -1, "Look after itself; leaves the healer free" + stockNote);
            }
            if (m == thief) add(s, conds, FOE_FULL_HP, steal, "Steal before anyone damages the foe; above attacks, below survival");
            add(s, conds, ALLY_ANY, c.contains(haste) ? haste : -1, "Keep Haste up (the game skips allies that already have it)");
            add(s, conds, ALLY_ANY, c.contains(protect) ? protect : -1, "Keep Protect up");
            add(s, conds, ALLY_ANY, c.contains(shell) ? shell : -1, "Keep Shell up");
            if (spells(c, ref) >= 2) {   // casters: exploit weaknesses (guides: specific conditions above general ones)
                for (Object[] e : ELEMENTS) {
                    int sp = -1;
                    for (String n : (String[]) e[1]) { int a = action(ref, n); if (a >= 0 && c.contains(a)) { sp = a; break; } }
                    add(s, conds, (Integer) e[0], sp, "Hit the weakness with " + Gambits.action(sp, ref));
                }
                int heal = first(c, healing);
                add(s, conds, FOE_UNDEAD, heal, "Curative magick damages the undead");
            }
            if (bm.job1 == 8 || bm.job2 == 8 || bm.job1 == 2 || bm.job2 == 2)
                add(s, conds, FOE_FLYING, ATTACK, "Ranged weapon: take flying foes others can't reach");
            add(s, conds, FOE_LEADER_TARGET, ATTACK, "Focus the party leader's target");
            add(s, conds, FOE_NEAREST_VISIBLE, ATTACK, "Fallback attack");
            while (s.size() > 12) {   // over the 12 slots: drop the least important first (last weakness spell, then buffs)
                int drop = -1;
                for (int i = s.size() - 1; i >= 0 && drop < 0; i--) if (isWeakness(s.get(i).condition)) drop = i;
                for (int i = s.size() - 1; i >= 0 && drop < 0; i--) if (s.get(i).condition == ALLY_ANY) drop = i;
                s.remove(drop >= 0 ? drop : s.size() - 3);
            }
            out.add(new Plan(m, role, jobs(bm), Collections.unmodifiableList(s)));
        }
        return out;
    }

    static boolean isWeakness(int c) { return (c >= 0x6049 && c <= 0x6050) || c == FOE_UNDEAD; }

    /** How many distinct element spells this member can cast (2+ makes them a caster). */
    static int spells(Set<Integer> can, Reference ref) {
        int n = 0;
        for (Object[] e : ELEMENTS) for (String name : (String[]) e[1]) { int a = action(ref, name); if (a >= 0 && can.contains(a)) { n++; break; } }
        return n;
    }

    private static int first(Set<Integer> can, int[] order) {
        for (int a : order) if (a >= 0 && can.contains(a)) return a;
        return -1;
    }

    private static void add(List<Slot> s, Set<Integer> owned, int cond, int action, String reason) {
        if (action < 0 || !owned.contains(cond)) return;
        for (Slot x : s) if (x.condition == cond && x.action == action) return;
        s.add(new Slot(cond, action, reason));
    }
}
