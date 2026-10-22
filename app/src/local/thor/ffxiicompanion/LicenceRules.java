package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 0.23.0: the game's licence-purchase rules (Licence_Rules_Trace_2026-10-21.md: core check 0x323D10, menu 0x55C163 /
 * 0x55E090 / 0x55D617) and a path planner. Pure; suggestions only.
 *
 * owned = the member's licence bit. core (in order): owned -> 1; rule byte +6: 0 ordinary (type 0x1E Second Board needs key
 * item 0xC01F, else 8); 1 party-exclusive (3 if records 0..6 with flag 0x08 own it); 2 Quickening (4 if the member owns 3 of
 * nodes 0..17); 3 Esper (party-exclusive, then key item 0xC000+node for nodes 18..30, else 5); other rules: unknown.
 * hidden = core in {3,4,5,8}, or a Gambit Slot (266..275) while the story gate is false. Adjacency: left/right/up/down on the
 * SAME board, to a non-hidden owned cell. canBuy = not owned, not hidden, core 0, adjacent, cost <= LP.
 * The Gambit Slot story gate isn't readable, so cells are evaluated with it true and false: "certain" if both agree.
 */
final class LicenceRules {
    static final int GS_FIRST = 266, GS_LAST = 275, Q_LAST = 17;
    // cell kinds for the view
    static final int EMPTY = -1, LOCKED = 0, OPEN = 1, OWNED = 2, BUY = 3, MAYBE = 4, BLOCKED = 5, HIDDEN = 6;

    private final Build b;
    private final LicenceNodes n;
    private final int m;
    private final Build.Member me;

    LicenceRules(Build b, LicenceNodes n, int m) { this.b = b; this.n = n; this.m = m; this.me = b.members.get(m); }

    /** True when the inputs the rules need are present (key items, roster, rule bytes). */
    static boolean available(Build b, LicenceNodes n) { return b != null && n != null && b.keyItems != null && b.roster != null; }

    boolean owned(int node) { return me.owns(node); }

    boolean keyItem(int id) {
        int i = id - 0xC000;
        return i >= 0 && (i >> 4) < b.keyItems.length && (b.keyItems[i >> 4] >> (i & 15) & 1) != 0;
    }

    private boolean partyOwned(int node) {
        for (int r = 0; r < b.roster.length; r++) if ((b.rosterFlags[r] & 0x08) != 0 && owns(b.roster[r], node)) return true;
        return false;
    }

    private static boolean owns(byte[] bits, int node) { return node >= 0 && (node >> 3) < bits.length && (bits[node >> 3] >> (node & 7) & 1) != 0; }

    int quickenings() {
        int c = 0;
        for (int q = 0; q <= Q_LAST; q++) if (owned(q)) c++;
        return c;
    }

    /** 0x323D10 for this member (0 ok, 1 owned, 3 party-owned, 4 Quickening cap, 5 Esper not obtained, 8 Second Board, 9 unknown). */
    int core(int node) { return core(node, quickenings()); }

    private int core(int node, int qOwned) {
        if (owned(node)) return 1;
        int rule = n.rule(node);
        if (rule == 0) return n.type(node) == 0x1E && !keyItem(0xC01F) ? 8 : 0;
        if (rule == 1) return partyOwned(node) ? 3 : 0;
        if (rule == 2) return qOwned >= 3 ? 4 : 0;
        if (rule == 3) {
            if (partyOwned(node)) return 3;
            return node >= 18 && node <= 30 && keyItem(0xC000 + node) ? 0 : 5;
        }
        return 9;
    }

    private boolean hidden(int node, boolean gate) {
        int c = core(node);
        return c == 3 || c == 4 || c == 5 || c == 8 || (!gate && node >= GS_FIRST && node <= GS_LAST);
    }

    private boolean nativeOwned(int node, boolean gate) { return node >= 0 && !hidden(node, gate) && owned(node); }

    /** canBuy for one cell under a given story-gate value. */
    boolean canBuy(LicenceBoards.Board bd, int cell, boolean gate) {
        int node = bd.cells[cell];
        if (node < 0 || owned(node) || hidden(node, gate) || core(node) != 0 || n.cost(node) > me.lp) return false;
        int x = cell % bd.w, y = cell / bd.w;
        return nativeOwned(bd.at(x - 1, y), gate) || nativeOwned(bd.at(x + 1, y), gate) || nativeOwned(bd.at(x, y - 1), gate) || nativeOwned(bd.at(x, y + 1), gate);
    }

    /** View kinds per cell: OWNED, BUY (certain), MAYBE (depends on the story gate), BLOCKED (next to owned but the rules or LP say no), LOCKED, EMPTY. */
    int[] kinds(LicenceBoards.Board bd) {
        int[] simple = LicenceBoards.states(bd, me);
        int[] k = new int[bd.cells.length];
        for (int i = 0; i < k.length; i++) {
            if (bd.cells[i] < 0) { k[i] = EMPTY; continue; }
            boolean t = canBuy(bd, i, true), f = canBuy(bd, i, false);
            if (simple[i] == LicenceBoards.OWNED) k[i] = OWNED;
            else if (t && f) k[i] = BUY;
            else if (t || f) k[i] = MAYBE;
            else if (simple[i] == LicenceBoards.OPEN) k[i] = BLOCKED;
            else k[i] = LOCKED;
        }
        return k;
    }

    /** Why an unowned, adjacent cell can't be bought now (or null). */
    String reason(int node) {
        switch (core(node)) {
            case 3: return "Another party member already has it";
            case 4: return "Already three Quickenings";
            case 5: return "Esper not obtained yet";
            case 8: return "Second Board not unlocked yet";
            case 9: return "Unknown rule";
            default: return n.cost(node) > me.lp ? "Not enough LP" : null;
        }
    }

    static final class Path {
        final List<Integer> nodes;   // licences to buy, in order (owned ones excluded); last = target
        final int lp;
        final boolean needsGambitGate; // passes through a Gambit Slot (story gate unknown)
        Path(List<Integer> nodes, int lp, boolean g) { this.nodes = nodes; this.lp = lp; this.needsGambitGate = g; }
    }

    /**
     * Cheapest set of licences to buy to reach target on the member's boards (node graph across both boards: an owned node opens
     * every occurrence). Quickening capacity is part of the state; hidden nodes are impassable except Gambit Slots (assumed
     * reachable, flagged). Null when unreachable.
     */
    Path plan(List<LicenceBoards.Board> boards, int target) {
        if (owned(target)) return new Path(Collections.emptyList(), 0, false);
        int count = n.count;
        List<Integer>[] adj = neighbours(boards, count);
        int qStart = quickenings();
        int S = count * 4;                            // state = node * 4 + quickenings used so far (0..3)
        long[] dist = new long[S];
        int[] prev = new int[S];
        Arrays.fill(dist, Long.MAX_VALUE);
        Arrays.fill(prev, -1);
        PriorityQueue<long[]> pq = new PriorityQueue<>((a, c) -> Long.compare(a[0], c[0]));
        for (int node = 0; node < count; node++) if (nativeOwned(node, true)) {
            int s = node * 4 + Math.min(qStart, 3);
            dist[s] = 0; pq.add(new long[]{0, s});
        }
        while (!pq.isEmpty()) {
            long[] cur = pq.poll();
            int s = (int) cur[1];
            if (cur[0] != dist[s]) continue;
            int node = s / 4, q = s % 4;
            if (node == target) return build(prev, s, qStart);
            for (int nb : adj[node]) {
                if (owned(nb)) continue;                 // owned-but-hidden: not a usable stepping stone
                int c = core(nb, q);
                if (c != 0) continue;
                boolean isQ = nb <= Q_LAST && n.rule(nb) == 2;
                int nq = isQ ? q + 1 : q;
                if (nq > 3) continue;
                int t = nb * 4 + nq;
                long d = cur[0] + n.cost(nb);
                if (d < dist[t]) { dist[t] = d; prev[t] = s; pq.add(new long[]{d, t}); }
            }
        }
        return null;
    }

    private Path build(int[] prev, int s, int qStart) {
        List<Integer> out = new ArrayList<>();
        int lp = 0;
        boolean gate = false;
        for (int x = s; x >= 0 && prev[x] >= 0; x = prev[x]) {
            int node = x / 4;
            out.add(node);
            lp += n.cost(node);
            gate |= node >= GS_FIRST && node <= GS_LAST;
        }
        Collections.reverse(out);
        return new Path(Collections.unmodifiableList(out), lp, gate);
    }

    @SuppressWarnings("unchecked")
    private static List<Integer>[] neighbours(List<LicenceBoards.Board> boards, int count) {
        List<Integer>[] adj = new List[count];
        for (int i = 0; i < count; i++) adj[i] = new ArrayList<>();
        for (LicenceBoards.Board bd : boards)
            for (int i = 0; i < bd.cells.length; i++) {
                int a = bd.cells[i];
                if (a < 0 || a >= count) continue;
                int x = i % bd.w, y = i / bd.w;
                for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int c = bd.at(x + d[0], y + d[1]);
                    if (c >= 0 && c < count && !adj[a].contains(c)) adj[a].add(c);
                }
            }
        return adj;
    }
}
