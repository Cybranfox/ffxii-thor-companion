package local.thor.ffxiicompanion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 0.21.0 Licences tab: a member's two job boards, live. Gold = owned, glowing blue outline = open (touches an owned
 * licence), dark = locked. Tap a licence for its name, type, LP cost, state and what it grants. Read-only.
 * Board layouts and names come from boards.json (the user's own game files), everything else is live.
 */
final class LicencesView extends LinearLayout {
    private final MainActivity ui;
    private final LinearLayout memberChips, boardChips;
    private final TextView summary, detail;
    private final Board board;
    private int member, which;                         // which: 0 = first job, 1 = second job
    private Build build;
    private LicenceNodes nodes;
    private LicenceBoards boards;
    private Reference ref;
    private String key;

    LicencesView(MainActivity ui) {
        super(ui);
        this.ui = ui;
        setOrientation(VERTICAL);
        memberChips = new LinearLayout(ui);
        addView(memberChips);
        boardChips = new LinearLayout(ui);
        boardChips.setPadding(0, ui.dp(4), 0, 0);
        addView(boardChips);
        summary = ui.text("", 12, MainActivity.DIM, false);
        summary.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(2));
        MainActivity.oneLine(summary);
        addView(summary);
        board = new Board(ui);
        addView(board, new LayoutParams(-1, 0, 1));
        detail = ui.text("Tap a license.", 13, MainActivity.TEXT, false);
        detail.setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(8));
        detail.setBackground(Theme.section(ui.dp(1)));          // 0.17.0: a ruled footer line, no box
        detail.setMaxLines(4);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.topMargin = ui.dp(4);
        addView(detail, lp);
    }

    /** New data; returns quietly when nothing that matters changed. why != null shows that reason instead. */
    void show(Build b, LicenceNodes n, LicenceBoards lb, Reference r, String why) {
        build = b; nodes = n; boards = lb; ref = r;
        if (why != null) {
            summary.setText(why);
            board.set(null, null);
            return;
        }
        StringBuilder k = new StringBuilder().append(member).append(which);
        for (Build.Member m : b.members) k.append('|').append(m.job1).append(m.job2).append(m.lp).append(java.util.Arrays.hashCode(m.licences));
        if (k.toString().equals(key)) return;
        key = k.toString();
        render();
    }

    private void render() {
        memberChips.removeAllViews();
        for (int m = 0; m < build.members.size(); m++) {
            final int mm = m;
            memberChips.addView(chip(Gambits.MEMBERS[m], m == member, v -> { member = mm; which = 0; key = null; clearSelection(); render(); }), chipLp());
        }
        Build.Member bm = build.members.get(member);
        int[] jobs = {bm.job1, bm.job2};
        boardChips.removeAllViews();
        for (int i = 0; i < 2; i++) {
            if (jobs[i] == 0xFF || boards.board(jobs[i]) == null) continue;
            final int ii = i;
            boardChips.addView(chip(boards.board(jobs[i]).name, i == which, v -> { which = ii; key = null; clearSelection(); render(); }), chipLp());
        }
        int job = jobs[which] != 0xFF ? jobs[which] : jobs[1 - which];
        LicenceBoards.Board bd = job == 0xFF ? null : boards.board(job);
        if (bd == null) { summary.setText("No license board yet."); board.set(null, null); return; }
        // 0.23.0: the game's own rules when logger 0.5.14 data is there (LicenceRules), else the simple "open" view
        rules = LicenceRules.available(build, nodes) ? new LicenceRules(build, nodes, member) : null;
        myBoards.clear();
        for (int j : jobs) if (j != 0xFF && boards.board(j) != null) myBoards.add(boards.board(j));
        int[] kinds;
        if (rules != null) kinds = rules.kinds(bd);
        else {
            int[] st = LicenceBoards.states(bd, bm);
            kinds = new int[st.length];
            for (int i = 0; i < st.length; i++)
                kinds[i] = st[i] == LicenceBoards.EMPTY ? LicenceRules.EMPTY : st[i] == LicenceBoards.OWNED ? LicenceRules.OWNED
                        : st[i] == LicenceBoards.OPEN ? LicenceRules.OPEN : LicenceRules.LOCKED;
        }
        int owned = 0, buy = 0;
        for (int k : kinds) { if (k == LicenceRules.OWNED) owned++; else if (k == LicenceRules.BUY || k == LicenceRules.MAYBE || k == LicenceRules.OPEN) buy++; }
        summary.setText(owned + " owned · " + buy + (rules != null ? " can buy · " : " open · ") + bm.lp + " LP");
        board.set(bd, kinds);
    }

    private LicenceRules rules;
    private final List<LicenceBoards.Board> myBoards = new ArrayList<>();

    private void describe(LicenceBoards.Board bd, int cell, int kind) {
        int node = bd.cells[cell];
        int lp = build.members.get(member).lp;
        StringBuilder s = new StringBuilder(boards.name(node));
        String word = kind == LicenceRules.OWNED ? "✦ owned" : kind == LicenceRules.BUY ? "◆ can buy" : kind == LicenceRules.MAYBE ? "◇ may need story progress"
                : kind == LicenceRules.OPEN ? "◇ open" : kind == LicenceRules.BLOCKED ? "blocked" : "locked";
        s.append("   ").append(word);
        board.path.clear();
        if (nodes != null && node < nodes.count) {
            s.append(" · ").append(LicenceBoards.typeName(nodes.type(node))).append(" · ").append(nodes.cost(node)).append(" LP");
            if (kind == LicenceRules.BLOCKED && rules != null && rules.reason(node) != null) s.append(" · ").append(rules.reason(node));
            if (rules == null && kind == LicenceRules.OPEN && nodes.cost(node) > lp) s.append(" · not enough LP");
            if (rules != null && kind != LicenceRules.OWNED && kind != LicenceRules.BUY && kind != LicenceRules.BLOCKED) {
                LicenceRules.Path p = rules.plan(myBoards, node);
                if (p == null) s.append("\nNo route on these boards yet.");
                else {
                    board.path.addAll(p.nodes);
                    s.append("\nRoute: ").append(p.nodes.size()).append(p.nodes.size() == 1 ? " license · " : " licenses · ").append(p.lp).append(" LP");
                    if (p.lp > lp) s.append(" (").append(p.lp - lp).append(" more LP needed)");
                    if (p.needsGambitGate) s.append(" · via a Gambit Slot");
                }
            }
            List<String> g = grants(node);
            if (!g.isEmpty()) s.append('\n').append("Grants: ").append(TextUtils.join(", ", g));
            if (nodes.type(node) == 0x13 || nodes.type(node) == 0x14)
                s.append("\nEspers and Quickenings have extra limits in the game.");
        }
        detail.setText(s);
    }

    private void clearSelection() {
        board.sel = -1;
        board.path.clear();
        detail.setText("Tap a license.");
    }

    private List<String> grants(int node) {
        List<String> out = new ArrayList<>();
        int t = nodes.type(node);
        for (int g : nodes.grantList(node)) {
            String n = null;
            if (ref != null) {
                if (t >= 9 && t <= 0x0D) n = ref.nameOf(0x3000 + g);
                else if (t == 0x0E) n = ref.nameOf(0x4000 + g - 0x9E);
                else if (g >= 0x1000 && g < 0x2000) n = ref.nameOf(g);
            }
            if (n != null) out.add(n);
        }
        return out;
    }

    private TextView chip(String s, boolean on, View.OnClickListener l) {
        TextView t = ui.text(s, 12, MainActivity.TEXT, true);
        t.setSingleLine(true);
        // 0.17.0: native-feeling member/job words on the band, not boxed buttons.
        t.setMinHeight(ui.dp(48));
        t.setGravity(Gravity.CENTER);
        t.setPadding(ui.dp(12), ui.dp(4), ui.dp(10), ui.dp(4));
        t.setBackground(on ? ui.round(Theme.MARK, 0) : null);
        t.setSelected(on);
        t.setOnClickListener(l);
        return t;
    }

    private LayoutParams chipLp() {
        LayoutParams lp = new LayoutParams(-2, -2);
        lp.rightMargin = ui.dp(6);
        return lp;
    }

    /** The board, cropped to its used area. Animated lightly (open cells pulse, the selected cell has a bobbing pointer). */
    private final class Board extends View {
        private LicenceBoards.Board bd;
        private int[] st;                                    // LicenceRules kinds
        final java.util.Set<Integer> path = new java.util.HashSet<>();   // route nodes to highlight
        private int sel = -1, x0, y0, x1, y1;
        private float cs, ox, oy;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG), ptr = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Path tri = new Path();

        Board(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            ptr.setColor(Theme.GOLD);
        }

        void set(LicenceBoards.Board b, int[] s) {
            bd = b; st = s;
            if (b != null) {
                x0 = b.w; y0 = b.h; x1 = -1; y1 = -1;
                for (int i = 0; i < b.cells.length; i++) if (b.cells[i] >= 0) {
                    int x = i % b.w, y = i / b.w;
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); x1 = Math.max(x1, x); y1 = Math.max(y1, y);
                }
                if (sel >= 0 && (sel >= b.cells.length || b.cells[sel] < 0)) sel = -1;
            } else sel = -1;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            if (bd == null || x1 < 0) return;
            int cols = x1 - x0 + 1, rows = y1 - y0 + 1;
            cs = Math.min(getWidth() / (float) cols, getHeight() / (float) rows);
            ox = (getWidth() - cs * cols) / 2f; oy = (getHeight() - cs * rows) / 2f;
            float pad = cs * 0.08f, rad = cs * 0.18f, pulse = 0.5f + 0.5f * (float) Math.sin(Theme.phase * Math.PI * 4);
            for (int i = 0; i < bd.cells.length; i++) {
                if (st[i] == LicenceRules.EMPTY) continue;
                int x = i % bd.w - x0, y = i / bd.w - y0;
                r.set(ox + x * cs + pad, oy + y * cs + pad, ox + (x + 1) * cs - pad, oy + (y + 1) * cs - pad);
                if (st[i] == LicenceRules.OWNED) {
                    fill.setColor(0xFFFFFFFF);                       // full alpha: the colour's alpha also scales a shader
                    fill.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0xFFF7DE9A, 0xFFB8862E, Shader.TileMode.CLAMP));
                    c.drawRoundRect(r, rad, rad, fill);
                    fill.setShader(null);
                } else if (st[i] == LicenceRules.BUY || st[i] == LicenceRules.OPEN || st[i] == LicenceRules.MAYBE) {
                    fill.setShader(null);
                    fill.setColor(0x6620407A);
                    c.drawRoundRect(r, rad, rad, fill);
                    line.setStrokeWidth(Math.max(1f, cs * 0.07f));
                    int rgb = st[i] == LicenceRules.MAYBE ? 0x8FA6C8 : 0x9CC8FF;      // story-dependent: quieter
                    line.setColor(((int) ((st[i] == LicenceRules.MAYBE ? 70 : 120) + (st[i] == LicenceRules.MAYBE ? 90 : 135) * pulse) << 24) | rgb);
                    c.drawRoundRect(r, rad, rad, line);
                } else if (st[i] == LicenceRules.BLOCKED) {
                    fill.setShader(null);
                    fill.setColor(0x90203258);
                    c.drawRoundRect(r, rad, rad, fill);
                    line.setStrokeWidth(Math.max(1f, cs * 0.05f));
                    line.setColor(0x705C7AA8);
                    c.drawRoundRect(r, rad, rad, line);
                } else {
                    fill.setShader(null);
                    fill.setColor(0xB0223566);
                    c.drawRoundRect(r, rad, rad, fill);
                    line.setStrokeWidth(Math.max(1f, cs * 0.04f));
                    line.setColor(0x553C5C96);
                    c.drawRoundRect(r, rad, rad, line);
                }
            }
            if (!path.isEmpty()) {                            // planned route: gold outline on every step on this board
                line.setStrokeWidth(Math.max(2f, cs * 0.09f));
                line.setColor(((int) (150 + 105 * pulse) << 24) | 0xE6C36E);
                for (int i = 0; i < bd.cells.length; i++) {
                    if (bd.cells[i] < 0 || !path.contains(bd.cells[i])) continue;
                    int x = i % bd.w - x0, y = i / bd.w - y0;
                    r.set(ox + x * cs + pad, oy + y * cs + pad, ox + (x + 1) * cs - pad, oy + (y + 1) * cs - pad);
                    c.drawRoundRect(r, rad, rad, line);
                }
            }
            if (sel >= 0) {
                int x = sel % bd.w - x0, y = sel / bd.w - y0;
                r.set(ox + x * cs, oy + y * cs, ox + (x + 1) * cs, oy + (y + 1) * cs);
                line.setStrokeWidth(Math.max(2f, cs * 0.12f));
                line.setColor(0xFFFFFFFF);
                c.drawRoundRect(r, rad, rad, line);
                float bob = (float) Math.sin(Theme.phase * Math.PI * 8) * cs * 0.08f, h = cs * 0.5f, px = r.left - cs * 0.45f + bob, cy = r.centerY();
                tri.reset(); tri.moveTo(px, cy - h / 2); tri.lineTo(px + h * 0.7f, cy); tri.lineTo(px, cy + h / 2); tri.close();
                c.drawPath(tri, ptr);
            }
            postInvalidateDelayed(50);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (bd == null || cs <= 0 || e.getAction() != MotionEvent.ACTION_UP) return true;
            int x = (int) ((e.getX() - ox) / cs) + x0, y = (int) ((e.getY() - oy) / cs) + y0;
            if (x < x0 || x > x1 || y < y0 || y > y1) return true;
            int i = y * bd.w + x;
            if (bd.cells[i] < 0) return true;
            sel = i;
            describe(bd, i, st[i]);
            invalidate();
            return true;
        }
    }
}
