package local.thor.ffxiicompanion;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.List;

/**
 * Live gambit viewer: a chip per party member, then that member's 36-slot list (sets I-III) with ON/OFF, condition and
 * action. Touch-only (the window stays non-focusable). When the game script reports the editor armed (0.5.0), tapping a
 * slot of a screen-verified member proposes switching it ON/OFF, or (0.11.0) "Change…" to pick a new condition and/or
 * action, the list area then showing only the choices GambitEdit allows. Nothing is sent until the send button is
 * tapped, and then only one command file (GambitEdit), which the game script checks and answers in data.gambitEdit.
 */
final class GambitsView extends LinearLayout {
    private final int text, dim, accent, card, bg, good, warn;
    private final float density;
    private final TextView status;
    private final LinearLayout chips, rows;
    private int selected;
    private List<Gambits.Member> members;
    private Reference reference;
    private String lastKey;
    private final LinearLayout editBar;
    private final TextView editMsg, editSend, editToggle, editChange, editCancel, pickCond, pickAct;
    private GambitEdit.Seen seen;
    private Build build;
    private LicenceNodes nodes;
    private List<GambitPlan.Plan> plans;
    private boolean suggestMode;
    private int plansKey;
    private final LinearLayout pickRow;
    private GambitEdit.State editState;
    private File cmdDir;
    private boolean editAllowed;
    private GambitEdit.Proposal proposal;
    private String sentId, sentWhat, editError;
    /** 0 = slot list, 1 = choosing a condition, 2 = choosing an action (the list area shows the choices). */
    private int pickMode;
    /** 0.10.0: a set switch waiting for its confirm tap (member + target set), or null. */
    private Gambits.Member switchMember;
    private int switchTarget = -1;
    /** The opt-in switch for the editor: the ARMED file in cmd/, read by the game script at game start. */
    private final LinearLayout armRow;
    private final TextView armText, armButton, armConfirm;
    private boolean armAsking;

    GambitsView(Context c, int text, int dim, int accent, int card, int bg, int good, int warn) {
        super(c);
        this.text = text; this.dim = dim; this.accent = accent; this.card = card; this.bg = bg; this.good = good; this.warn = warn;
        density = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        status = tv("", 12, dim, false);
        status.setPadding(dp(4), dp(2), dp(4), dp(4));
        status.setMaxLines(2);
        addView(status);
        armRow = new LinearLayout(c);
        armRow.setOrientation(VERTICAL);
        armRow.setPadding(dp(4), 0, dp(4), dp(6));
        LinearLayout armLine = new LinearLayout(c);
        armLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        armText = tv("", 12, dim, false);
        armLine.addView(armText, new LayoutParams(0, -2, 1));
        armButton = button("", card, text);
        armButton.setOnClickListener(v -> onArmTap());
        armLine.addView(armButton);
        armRow.addView(armLine);
        armConfirm = tv("The editor changes gambits in the running game, one slot at a time, and the game checks each"
                + " change before it lands. Saves are never touched. It turns on at the next game start. Tap again to turn it on.", 12, warn, false);
        armConfirm.setPadding(dp(8), dp(8), dp(8), dp(8));
        armConfirm.setBackground(Theme.section(dp(1)));
        armConfirm.setVisibility(GONE);
        armRow.addView(armConfirm);
        addView(armRow);
        editBar = new LinearLayout(c);
        editBar.setOrientation(VERTICAL);
        editBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        editBar.setBackground(Theme.section(dp(1)));           // 0.17.0: ruled confirmation, not a boxed card
        editMsg = tv("", 13, text, false);
        editBar.addView(editMsg);
        pickRow = new LinearLayout(c);
        pickRow.setPadding(0, dp(6), 0, 0);
        pickCond = button("Condition…", bg, text);
        pickAct = button("Action…", bg, text);
        LayoutParams pl = new LayoutParams(-2, -2);
        pl.rightMargin = dp(8);
        pickRow.addView(pickCond, pl);
        pickRow.addView(pickAct, new LayoutParams(-2, -2));
        editBar.addView(pickRow);
        LinearLayout btns = new LinearLayout(c);
        btns.setPadding(0, dp(6), 0, 0);
        editSend = button("Send to game", Theme.MARK, text);
        editToggle = button("Turn ON…", bg, text);
        editChange = button("Change…", bg, text);
        editCancel = button("Cancel", bg, text);
        LayoutParams bl = new LayoutParams(-2, -2);
        bl.rightMargin = dp(8);
        btns.addView(editSend, bl);
        LayoutParams tl = new LayoutParams(-2, -2);
        tl.rightMargin = dp(8);
        btns.addView(editToggle, tl);
        LayoutParams cl = new LayoutParams(-2, -2);
        cl.rightMargin = dp(8);
        btns.addView(editChange, cl);
        btns.addView(editCancel, new LayoutParams(-2, -2));
        editBar.addView(btns);
        editSend.setOnClickListener(v -> send());
        editToggle.setOnClickListener(v -> { if (proposal != null && sentId == null) { proposal.confirmToggle = true; updateBar(); } });
        editChange.setOnClickListener(v -> { if (proposal != null && sentId == null) { proposal.change = true; setPick(1); } });
        pickCond.setOnClickListener(v -> setPick(pickMode == 1 ? 0 : 1));
        pickAct.setOnClickListener(v -> setPick(pickMode == 2 ? 0 : 2));
        editCancel.setOnClickListener(v -> {
            GambitEdit.Proposal p = proposal;
            if (p != null && sentId == null && (p.confirmToggle || p.change)) {   // Back: to the choice step, nothing sent
                p.confirmToggle = false; p.change = false; p.newCondition = p.condition; p.newAction = p.action;
            } else { proposal = null; sentId = null; switchMember = null; switchTarget = -1; }
            editError = null;
            setPick(0);
        });
        editBar.setVisibility(GONE);
        LayoutParams el = new LayoutParams(-1, -2);
        el.bottomMargin = dp(6);
        addView(editBar, el);
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        chips = new LinearLayout(c);
        hs.addView(chips);
        addView(hs);
        ScrollView sv = new ScrollView(c);
        rows = new LinearLayout(c);
        rows.setOrientation(VERTICAL);
        rows.setPadding(0, dp(6), 0, dp(6));
        sv.addView(rows);
        addView(sv, new LayoutParams(-1, 0, 1));
    }

    /** Editor state from data.gambitEdit; allowed = the snapshot is live and the build verified. Call before show(). */
    void setEditor(GambitEdit.State st, File cmdDir, boolean allowed) {
        this.editState = st;
        this.cmdDir = cmdDir;
        boolean was = editAllowed;
        this.editAllowed = allowed && st != null && st.ready();
        if (!editAllowed && sentId == null) { proposal = null; pickMode = 0; }
        if (was != editAllowed) lastKey = null;
        updateBar();
        updateArm();
    }

    private File armFile() { return cmdDir == null ? null : new File(cmdDir, "ARMED"); }

    /** Off: "Turn on…" (asks once, then creates ARMED). On or waiting for a game start: "Turn off" (removes it). */
    private void updateArm() {
        File f = armFile();
        armRow.setVisibility(f == null ? GONE : VISIBLE);
        if (f == null) return;
        boolean armed = f.isFile();
        if (armed) armAsking = false;
        armText.setText(!armed ? "Gambit editor: off" : editAllowed ? "Gambit editor: on"
                : editState != null && editState.hooked ? "Gambit editor: on (waiting for live data)"
                : "Gambit editor: on from the next game start");
        armButton.setText(armed ? "Turn off" : armAsking ? "Yes, turn on" : "Turn on…");
        armConfirm.setVisibility(armAsking ? VISIBLE : GONE);
    }

    private void onArmTap() {
        File f = armFile();
        if (f == null) return;
        if (f.isFile()) {
            if (!f.delete()) editError = "Could not turn the editor off (file in use).";
            new File(cmdDir, GambitEdit.CMD_FILE).delete();   // 0.11.0: switching off also drops any waiting request
        } else if (!armAsking) {
            armAsking = true;
        } else {
            armAsking = false;
            try {
                if (!cmdDir.isDirectory() && !cmdDir.mkdirs()) throw new java.io.IOException("mkdirs");
                new java.io.FileOutputStream(f).close();
            } catch (java.io.IOException e) {
                editError = "Could not turn the editor on: no access to " + cmdDir + ".";
            }
        }
        updateBar();
        updateArm();
    }

    boolean editorOn() { return editAllowed; }

    /** Session memory of conditions/actions seen live (GambitEdit.Seen), so a swap can always be undone. */
    void setSeen(GambitEdit.Seen seen) { this.seen = seen; }

    /** Party build + live licence table (0.12.0): widen the choices to everything owned and licensed. Either may be null. */
    void setBuild(Build build, LicenceNodes nodes) {
        boolean was = this.build != null && this.build.ownershipKnown() && this.nodes != null;
        this.build = build;
        this.nodes = nodes;
        if (was != (build != null && build.ownershipKnown() && nodes != null) && pickMode != 0) lastKey = null;
    }

    private boolean ownershipOn() { return build != null && build.ownershipKnown() && nodes != null; }

    private void propose(Gambits.Member m, Gambits.Slot s) {
        if (!editAllowed || sentId != null || !GambitEdit.editable(m, s)) return;
        proposal = new GambitEdit.Proposal(m, s);
        switchMember = null;
        switchTarget = -1;
        editError = null;
        setPick(0);
    }

    /** Switches the list area between the slots and the condition/action choices, then refreshes both. */
    private void setPick(int mode) {
        pickMode = proposal != null && proposal.change && sentId == null ? mode : 0;
        lastKey = null;
        updateBar();
        if (members != null) show(members, reference, status.getText().toString(), true);
    }

    private void choose(int id) {
        if (proposal == null || sentId != null) return;
        if (pickMode == 1) proposal.newCondition = id; else if (pickMode == 2) proposal.newAction = id;
        setPick(0);
    }

    private void proposeSwitch(Gambits.Member m, int target) {
        if (!editAllowed || sentId != null || !GambitEdit.canSwitch(m, target)) return;
        proposal = null;
        switchMember = m;
        switchTarget = target;
        editError = null;
        setPick(0);
    }

    private void sendSwitch() {
        Gambits.Member m = switchMember;
        int target = switchTarget;
        if (m == null || !editAllowed || sentId != null || cmdDir == null || !GambitEdit.canSwitch(m, target)) return;
        long now = System.currentTimeMillis() / 1000;
        String id = GambitEdit.newId(now);
        try {
            if (!GambitEdit.write(cmdDir, GambitEdit.setActiveText(m, target, id, now))) { editError = "An earlier request is still waiting for the game."; updateBar(); return; }
        } catch (java.io.IOException e) {
            editError = "Could not write the request: " + e.getMessage();
            updateBar();
            return;
        }
        sentId = id;
        sentWhat = m.name + " · use " + GambitEdit.setName(target);
        switchMember = null;
        switchTarget = -1;
        setPick(0);
    }

    private void send() {
        if (switchMember != null && proposal == null) { sendSwitch(); return; }
        GambitEdit.Proposal p = proposal;
        if (p == null || !editAllowed || sentId != null || cmdDir == null) return;
        if (!p.sendable()) return;   // two taps minimum: choose, then send
        long now = System.currentTimeMillis() / 1000;
        String id = GambitEdit.newId(now);
        try {
            if (!GambitEdit.write(cmdDir, GambitEdit.commandText(p, id, now))) { editError = "An earlier request is still waiting for the game."; updateBar(); return; }
        } catch (java.io.IOException e) {
            editError = "Could not write the request: " + e.getMessage();
            updateBar();
            return;
        }
        sentId = id;
        sentWhat = p.change ? "Change " + p.where() + " → " + Gambits.condition(p.newCondition, reference) + "  ▸  "
                + Gambits.action(p.newAction, reference) : (p.turnOn ? "Turn ON " : "Turn OFF ") + p.where();
        setPick(0);
    }

    private void updateBar() {
        String out = sentId != null ? GambitEdit.outcome(editState, sentId) : null;
        if (sentId != null) {
            editMsg.setText(sentWhat + "\n" + (out != null ? out : "Sent · waiting for the game…"));
            editSend.setVisibility(GONE);
            editToggle.setVisibility(GONE);
            editChange.setVisibility(GONE);
            pickRow.setVisibility(GONE);
            editCancel.setText(out != null ? "OK" : "Hide");
            editBar.setVisibility(VISIBLE);
            if (out != null) lastKey = null;
            return;
        }
        if (proposal == null && switchMember != null) {
            editMsg.setText(switchMember.name + ": use " + GambitEdit.setName(switchTarget) + " instead of "
                    + GambitEdit.setName(switchMember.active) + "\nTakes effect straight away. Only in the field with the game's"
                    + " menu closed; the game checks it first." + (editError != null ? "\n" + editError : ""));
            editSend.setText("Switch set in game");
            editSend.setAlpha(1f);
            editSend.setVisibility(VISIBLE);
            editToggle.setVisibility(GONE);
            editChange.setVisibility(GONE);
            pickRow.setVisibility(GONE);
            editCancel.setText("Cancel");
            editBar.setVisibility(VISIBLE);
            return;
        }
        if (proposal == null) {
            editBar.setVisibility(editError != null ? VISIBLE : GONE);
            editMsg.setText(editError);
            editSend.setVisibility(GONE);
            editToggle.setVisibility(GONE);
            editChange.setVisibility(GONE);
            pickRow.setVisibility(GONE);
            editCancel.setText("OK");
            return;
        }
        GambitEdit.Proposal p = proposal;
        String now = Gambits.condition(p.condition, reference) + "  ▸  " + Gambits.action(p.action, reference);
        String tail = "\nOnly with the game's menu closed. The game checks it first." + (editError != null ? "\n" + editError : "");
        if (p.change) {
            String nw = Gambits.condition(p.newCondition, reference) + "  ▸  " + Gambits.action(p.newAction, reference);
            String hint = pickMode == 1 ? (ownershipOn() ? "\nPick a condition below (gambits you own)." : "\nPick a condition below (ones your party has had this session).")
                    : pickMode == 2 ? (ownershipOn() ? "\nPick an action below (what " + p.memberName + " can use: owned and licensed, items used this session, Attack)."
                    : "\nPick an action below (ones " + p.memberName + " has used this session, or Attack).") : "";
            editMsg.setText("Change  " + p.where() + " (stays " + (p.turnOn ? "OFF" : "ON") + ")\nNow:  " + now + "\nNew:  "
                    + (p.changed() ? nw : "(pick a different condition or action)") + hint + (pickMode == 0 ? tail : ""));
            editSend.setText("Send change to game");
            editSend.setAlpha(p.changed() ? 1f : 0.4f);
            editSend.setVisibility(VISIBLE);
            editToggle.setVisibility(GONE);
            editChange.setVisibility(GONE);
            pickRow.setVisibility(VISIBLE);
            pickCond.setBackground(pickMode == 1 ? round(Theme.MARK, 0) : null);
            pickCond.setTextColor(text);
            pickAct.setBackground(pickMode == 2 ? round(Theme.MARK, 0) : null);
            pickAct.setTextColor(text);
        } else if (p.confirmToggle) {
            editMsg.setText((p.turnOn ? "Turn ON  " : "Turn OFF  ") + p.where() + "\n" + now + tail);
            editSend.setText(p.turnOn ? "Turn ON in game" : "Turn OFF in game");
            editSend.setAlpha(1f);
            editSend.setVisibility(VISIBLE);
            editToggle.setVisibility(GONE);
            editChange.setVisibility(GONE);
            pickRow.setVisibility(GONE);
        } else {   // choice step: nothing here sends
            editMsg.setText(p.where() + " (" + (p.turnOn ? "OFF" : "ON") + ")\n" + now
                    + "\nSwitch it " + (p.turnOn ? "ON" : "OFF") + ", or change its condition or action?");
            editSend.setVisibility(GONE);
            editToggle.setText(p.turnOn ? "Turn ON…" : "Turn OFF…");
            editToggle.setVisibility(VISIBLE);
            editChange.setVisibility(VISIBLE);
            pickRow.setVisibility(GONE);
        }
        editCancel.setText(p.confirmToggle || p.change ? "Back" : "Cancel");
        editBar.setVisibility(VISIBLE);
    }

    /** 0.17.0: the set on screen (one at a time, as the game); -1 follows the set the game uses. Viewing never sends. */
    private int viewedSet = -1;

    /** members null = unavailable (statusText explains). Rebuilds only when content or selection changed. */
    void show(List<Gambits.Member> members, Reference reference, String statusText, boolean live) {
        status.setText(statusText);
        status.setTextColor(members == null ? warn : live ? dim : warn);
        status.setVisibility(statusText == null || statusText.isEmpty() ? GONE : VISIBLE);
        tickApply(members);   // an apply in progress advances on every refresh, whatever is on screen
        String key = (members == null ? "none" : String.valueOf(signature(members))) + "|" + selected + "|" + (reference != null) + "|" + editAllowed + "|" + pickMode
                + "|" + suggestMode + "|" + viewedSet + "|" + plansKey + "|" + applyStage + ":" + applyAt + ":" + applyId + ":" + applyMsg;
        if (key.equals(lastKey)) return;
        lastKey = key;
        this.members = members;
        this.reference = reference;
        chips.removeAllViews();
        rows.removeAllViews();
        if (members == null) return;
        if (pickMode != 0 && proposal != null) {
            showChoices();
            return;
        }
        for (Gambits.Member m : members) {
            TextView t = tv(m.name + (Gambits.MEMBER_VERIFIED[m.id] ? "" : "*"), 12, text, true);
            t.setSingleLine(true);
            boolean on = m.id == selected;
            // 0.17.0: a flat member strip; selection has a wash, never a boxed plate or another glove.
            t.setMinHeight(dp(48));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(10), dp(4), dp(10), dp(4));
            t.setBackground(on ? round(Theme.MARK, 0) : null);
            t.setSelected(on);
            t.setTextColor(text);
            final int id = m.id;
            t.setOnClickListener(v -> { selected = id; viewedSet = -1; lastKey = null; show(this.members, this.reference, status.getText().toString(), true); });
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            chips.addView(t, lp);
        }
        if (plans != null) {   // 0.13.0: suggestions (read-only)
            TextView sg = tv(suggestMode ? "✦ Suggested" : "✦ Suggest", 12, suggestMode ? text : accent, true);
            sg.setSingleLine(true);
            sg.setMinHeight(dp(48));
            sg.setGravity(Gravity.CENTER);
            sg.setPadding(dp(10), dp(4), dp(10), dp(4));
            sg.setBackground(suggestMode ? round(Theme.MARK, 0) : null);
            sg.setSelected(suggestMode);
            sg.setOnClickListener(v -> { suggestMode = !suggestMode; lastKey = null; show(this.members, this.reference, status.getText().toString(), true); });
            chips.addView(sg, new LayoutParams(-2, -2));
        }
        Gambits.Member m = members.get(Math.min(selected, members.size() - 1));
        if (suggestMode && plans != null) {
            showSuggestions(m);
            return;
        }
        if (m.slots.isEmpty()) {
            rows.addView(tv("No gambits set.", 13, dim, false));
            return;
        }
        showItemIssues(m);
        // 0.17.0: one set at a time, as the game's list; small I / II / III plates pick which set to look at
        int sets = 0;
        for (Gambits.Slot s : m.slots) sets = Math.max(sets, (s.index - 1) / Gambits.SLOTS + 1);
        int view = viewedSet >= 0 && viewedSet < sets ? viewedSet : Math.max(0, Math.min(m.active, sets - 1));
        if (sets > 1) rows.addView(setPlates(m, sets, view));
        rows.addView(setHeader(m, view));
        for (Gambits.Slot s : m.slots) {
            if ((s.index - 1) / Gambits.SLOTS != view) continue;
            LinearLayout r = row(s);
            if (editAllowed && GambitEdit.editable(m, s)) {
                final Gambits.Member fm = m;
                r.setOnClickListener(v -> propose(fm, s));
            }
            rows.addView(r);
        }
        if (!Gambits.MEMBER_VERIFIED[m.id]) {
            TextView n = tv("* Name from the game's member order; checked on screen for Vaan and Balthier only.", 11, dim, false);
            n.setPadding(dp(4), dp(8), dp(4), 0);
            rows.addView(n);
        }
    }

    /**
     * 0.20.0: one card per ON gambit whose item has run out (ItemCheck), with its fix. The fix button only prepares the
     * change in the usual bar (still needs the Send tap), and only when the editor is armed and started.
     */
    private void showItemIssues(Gambits.Member m) {
        for (ItemCheck.Issue is : ItemCheck.check(m, build, nodes, reference)) {
            Gambits.Slot s = is.slot;
            int set = (s.index - 1) / Gambits.SLOTS, slot = (s.index - 1) % Gambits.SLOTS;
            LinearLayout box = new LinearLayout(getContext());
            box.setOrientation(VERTICAL);
            box.setPadding(dp(8), dp(6), dp(8), dp(6));
            box.setBackground(round(card, 6));
            String where = "Set " + (set == 0 ? "I" : set == 1 ? "II" : "III") + " slot " + (slot + 1);
            box.addView(tv("⚠ " + Gambits.action(s.action, reference) + ": none left   (" + where + ")", 13, warn, true));
            String fixText = is.fix >= 0 ? "Swap to " + actionLabel(is.fix) : "Turn it off";
            box.addView(tv(fixText, 12, dim, false));
            if (editAllowed && sentId == null && GambitEdit.editable(m, s)) {
                TextView go = button(fixText + "…", Theme.MARK, text);
                final Gambits.Member fm = m;
                final int fix = is.fix;
                go.setOnClickListener(v -> {
                    if (!editAllowed || sentId != null) return;
                    proposal = new GambitEdit.Proposal(fm, s);
                    editError = null;
                    if (fix >= 0) { proposal.change = true; proposal.newAction = fix; }
                    else proposal.confirmToggle = true;          // turnOn is false for an ON slot: this is "switch OFF"
                    setPick(0);
                });
                LayoutParams bl = new LayoutParams(-2, -2);
                bl.topMargin = dp(4);
                box.addView(go, bl);
            } else {
                if (editAllowed) box.addView(tv("Finish the current change first.", 11, dim, false));
            }
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.bottomMargin = dp(4);
            rows.addView(box, lp);
        }
    }

    /** The list area while choosing: the allowed conditions or actions, by name, the current pick highlighted. */
    private void showChoices() {
        GambitEdit.Proposal p = proposal;
        Gambits.Member who = null;
        for (Gambits.Member m : members) if (m.id == p.member) who = m;
        boolean cond = pickMode == 1;
        List<Integer> ids = cond ? GambitEdit.conditionChoices(members, seen, build) : GambitEdit.actionChoices(who, seen, build, nodes);
        List<String[]> named = new java.util.ArrayList<>();
        for (int id : ids) named.add(new String[]{cond ? Gambits.condition(id, reference) : Gambits.action(id, reference), String.valueOf(id)});
        named.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        TextView h = tv(cond ? (ownershipOn() ? "Gambits you own (" + named.size() + ") · ownership read from the save (probe)"
                        : "Conditions your party has had this session (" + named.size() + ")")
                : (ownershipOn() ? "What " + p.memberName + " can use (" + named.size() + ") · owned + licensed (probe)"
                        : "Actions " + p.memberName + " has used this session (" + named.size() + ")"), 11, accent, true);
        h.setPadding(dp(4), dp(4), 0, dp(4));
        rows.addView(h);
        int current = cond ? p.newCondition : p.newAction;
        for (String[] n : named) {
            int id = Integer.parseInt(n[1]);
            LinearLayout r = new LinearLayout(getContext());
            r.setPadding(id == current ? dp(14) : dp(10), dp(7), dp(10), dp(7));
            r.setBackground(round(id == current ? Theme.MARK : card, 6));
            TextView t = tv(n[0], 14, text, id == current);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(t, new LayoutParams(0, -2, 1));
            r.setOnClickListener(v -> choose(id));
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.bottomMargin = dp(3);
            rows.addView(r, lp);
        }
    }

    /** Action name, plus the live count for items (0.16.0): what the game's gambit item check sees. */
    private String actionLabel(int action) {
        String n = Gambits.action(action, reference);
        int have = build != null ? build.available(action) : -1;
        return have < 0 ? n : have == 0 ? n + " (0 left)" : n + " ×" + have;
    }

    private LinearLayout row(Gambits.Slot s) {
        LinearLayout r = new LinearLayout(getContext());
        r.setGravity(Gravity.CENTER_VERTICAL);
        // 0.17.0: the game's gambit capsule: blue for ally/self rules, brown for foe rules, grey when OFF
        String cond = Gambits.condition(s.condition, reference);
        int kind = !s.on ? 2 : cond.startsWith("Foe") ? 1 : 0;
        r.setPadding(dp(6), dp(1), dp(14), dp(1));
        r.setBackground(Theme.capsule(kind, density));
        TextView n = tv(String.valueOf((s.index - 1) % Gambits.SLOTS + 1), 20, 0x99C9CDD2, true);
        n.setTypeface(android.graphics.Typeface.create(Theme.bodyMedium, android.graphics.Typeface.ITALIC));
        n.setGravity(Gravity.CENTER);
        n.setMinWidth(dp(26));
        r.addView(n);
        TextView on = tv(s.on ? "ON" : "OFF", 12, s.on ? Theme.ON_GOLD : dim, true);
        on.setMinWidth(dp(34));
        r.addView(on);
        TextView c = tv(cond, 14, s.on ? text : dim, false);
        c.setSingleLine(true);
        c.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(c, new LayoutParams(0, -2, 1.2f));
        TextView a = tv("\u25b8 " + actionLabel(s.action), 14, s.on ? text : dim, false);
        a.setSingleLine(true);
        a.setEllipsize(TextUtils.TruncateAt.END);
        r.addView(a, new LayoutParams(0, -2, 1f));
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.bottomMargin = dp(3);
        r.setLayoutParams(lp);
        return r;
    }

    /** Gambit suggestions from GambitPlan (null = inputs missing: the Suggest chip is hidden). Read-only. */
    void setPlans(List<GambitPlan.Plan> plans) {
        int k = 1;
        if (plans != null) for (GambitPlan.Plan p : plans) for (GambitPlan.Slot s : p.slots) k = 31 * k + s.condition * 7 + s.action;
        this.plans = plans;
        if (plans == null) suggestMode = false;
        plansKey = plans == null ? 0 : k;
    }

    // ---- 0.14.0: apply a suggestion through the editor (ApplyPlan), one checked step at a time ----
    private List<ApplyPlan.Step> applySteps;
    private int applyStage, applyAt, applyMember = -1;   // stage 0 none, 1 confirm, 2 running, 3 finished
    private String applyId, applyMsg;
    private long applySentMs;
    private static final long APPLY_TIMEOUT_MS = 20000;

    private boolean canApply(Gambits.Member m) {
        return editAllowed && m != null && m.id < Gambits.MEMBER_VERIFIED.length && Gambits.MEMBER_VERIFIED[m.id] && applyStage != 2;
    }

    private void startConfirm(Gambits.Member m, List<ApplyPlan.Step> steps) {
        if (steps.isEmpty()) { applyStage = 3; applyMsg = "Set I already matches the suggestion. Nothing to send."; }
        else { applySteps = steps; applyMember = m.id; applyAt = 0; applyId = null; applyStage = 1; applyMsg = null; }
        lastKey = null;
        show(members, reference, status.getText().toString(), true);
    }

    /** Called on every refresh: sends the next step, or checks the game's answer to the one in flight. */
    private void tickApply(List<Gambits.Member> ms) {
        if (applyStage != 2 || ms == null) return;
        Gambits.Member fresh = null;
        for (Gambits.Member x : ms) if (x.id == applyMember) fresh = x;
        if (fresh == null) return;
        if (!editAllowed) { finishApply("Stopped: the editor is off (armed + started needed). " + applyAt + " change(s) were made."); return; }
        if (applyId == null) {
            while (applyAt < applySteps.size() && ApplyPlan.done(fresh, applySteps.get(applyAt))) applyAt++;
            if (applyAt >= applySteps.size()) { finishApply("Done ✓ Set I now matches the suggestion (" + applySteps.size() + " change(s))."); return; }
            long now = System.currentTimeMillis() / 1000;
            String id = GambitEdit.newId(now);
            try {
                if (!GambitEdit.write(cmdDir, ApplyPlan.command(fresh, applySteps.get(applyAt), id, now))) return;   // earlier file still waiting
            } catch (java.io.IOException e) { finishApply("Stopped: could not write the request (" + e.getMessage() + ")."); return; }
            applyId = id;
            applySentMs = System.currentTimeMillis();
            lastKey = null;
            return;
        }
        String status = editState != null && applyId.equals(editState.lastId) ? editState.lastStatus : null;
        if (status == null) {
            if (System.currentTimeMillis() - applySentMs > APPLY_TIMEOUT_MS) finishApply("Stopped: no answer from the game for slot " + (applySteps.get(applyAt).slot + 1) + ". Nothing else sent.");
            return;
        }
        if (!"applied".equals(status)) {
            finishApply("Stopped at slot " + (applySteps.get(applyAt).slot + 1) + ": " + GambitEdit.outcome(editState, applyId)
                    + " " + applyAt + " earlier change(s) stay; nothing else sent.");
            return;
        }
        if (ApplyPlan.done(fresh, applySteps.get(applyAt))) { applyAt++; applyId = null; lastKey = null; }   // next tick sends the next step
    }

    private void finishApply(String msg) { applyStage = 3; applyMsg = msg; applyId = null; lastKey = null; }

    private void applyPanel(Gambits.Member m, GambitPlan.Plan p) {
        if (applyStage == 0) {
            if (!canApply(m)) {
                String why = !editAllowed ? "To apply, arm the editor before launching the game." : m.name + "'s name isn't screen-verified yet, so it can't be edited.";
                rows.addView(note(why));
                return;
            }
            TextView b = button("Apply set to Set I…", card, accent);
            b.setOnClickListener(v -> startConfirm(m, ApplyPlan.steps(m, p)));
            rows.addView(b, wrapLp());
            rows.addView(note("Or tap one suggested slot to apply only that slot."));
            return;
        }
        if (applyMember != m.id && applyStage != 3) { rows.addView(note("Applying to " + Gambits.MEMBERS[applyMember] + "…")); return; }
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(VERTICAL);
        box.setPadding(dp(10), dp(6), dp(10), dp(6));
        box.setBackground(round(card, 8));
        if (applyStage == 1) {
            StringBuilder sb = new StringBuilder("Apply to " + m.name + " · Set I (" + applySteps.size() + " change(s)):");
            ApplyPlan.SetI s = ApplyPlan.setI(m);
            for (ApplyPlan.Step st : applySteps) {
                String now = s.cond[st.slot] == 0xFFFF ? "(empty)" : Gambits.condition(s.cond[st.slot], reference) + " ▸ " + Gambits.action(s.act[st.slot], reference)
                        + (s.on(st.slot) ? "" : " (OFF)");
                sb.append("\n").append(st.slot + 1).append(": ").append(now).append("  →  ")
                        .append(st.disable ? "switch OFF (kept)" : Gambits.condition(st.condition, reference) + " ▸ " + Gambits.action(st.action, reference) + " (ON)");
            }
            sb.append("\nOne change at a time; the game checks each first. Keep the game's menu closed.");
            box.addView(tv(sb.toString(), 13, text, false));
            LinearLayout b = new LinearLayout(getContext());
            b.setPadding(0, dp(6), 0, 0);
            TextView go = button("Apply " + applySteps.size() + " change(s)", Theme.MARK, text);
            go.setOnClickListener(v -> { applyStage = 2; applyAt = 0; applyId = null; lastKey = null; tickApply(members); show(members, reference, status.getText().toString(), true); });
            TextView back = button("Back", bg, text);
            back.setOnClickListener(v -> { applyStage = 0; applySteps = null; lastKey = null; show(members, reference, status.getText().toString(), true); });
            LayoutParams gl = wrapLp();
            gl.rightMargin = dp(8);
            b.addView(go, gl);
            b.addView(back, wrapLp());
            box.addView(b);
        } else if (applyStage == 2) {
            int at = Math.min(applyAt, applySteps.size() - 1);   // applyAt may already be past the last step until the next tick says Done
            ApplyPlan.Step st = applySteps.get(at);
            box.addView(tv("Applying " + (at + 1) + "/" + applySteps.size() + " · slot " + (st.slot + 1) + " · waiting for the game…", 13, text, false));
        } else {
            box.addView(tv(applyMsg, 13, text, false));
            TextView okb = button("OK", bg, text);
            okb.setOnClickListener(v -> { applyStage = 0; applySteps = null; applyMsg = null; lastKey = null; show(members, reference, status.getText().toString(), true); });
            box.addView(okb, wrapLp());
        }
        LayoutParams lp = new LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        rows.addView(box, lp);
    }

    private TextView note(String s) {
        TextView n = tv(s, 11, dim, false);
        n.setPadding(dp(4), dp(2), dp(4), dp(6));
        return n;
    }

    private LayoutParams wrapLp() { return new LayoutParams(-2, -2); }

    private void showSuggestions(Gambits.Member m) {
        GambitPlan.Plan p = null;
        for (GambitPlan.Plan x : plans) if (x.member == m.id) p = x;
        if (p == null) return;
        final GambitPlan.Plan plan = p;
        TextView h = tv("Suggested for " + m.name + " · " + p.jobs + " · " + p.role, 13, accent, true);
        h.setPadding(dp(4), dp(4), dp(4), 0);
        rows.addView(h);
        TextView n = tv("Ordered by how gambits run, top to bottom, first match wins (revive, heal, status, Steal, buffs, weaknesses, "
                + "attacks), using only gambits you own "
                + "and abilities " + m.name + " owns and is licensed for (probe). Nothing is sent unless you apply and confirm.", 11, dim, false);
        n.setPadding(dp(4), dp(2), dp(4), dp(6));
        rows.addView(n);
        applyPanel(m, plan);
        int i = 0;
        for (GambitPlan.Slot s : p.slots) {
            final int slotIdx = i;
            i++;
            boolean have = false;
            for (Gambits.Slot c : m.slots) if (c.index <= Gambits.SLOTS && c.condition == s.condition && c.action == s.action) have = true;
            LinearLayout r = new LinearLayout(getContext());
            r.setOrientation(VERTICAL);
            r.setPadding(0, 0, 0, dp(2));
            LinearLayout top = new LinearLayout(getContext());
            top.setGravity(Gravity.CENTER_VERTICAL);
            String sc = Gambits.condition(s.condition, reference);
            top.setPadding(dp(6), dp(1), dp(14), dp(1));
            top.setBackground(Theme.capsule(sc.startsWith("Foe") ? 1 : 0, density));   // 0.17.0: the game's capsule
            TextView num = tv(String.valueOf(i), 20, 0x99C9CDD2, true);
            num.setTypeface(android.graphics.Typeface.create(Theme.bodyMedium, android.graphics.Typeface.ITALIC));
            num.setGravity(Gravity.CENTER);
            num.setMinWidth(dp(26));
            top.addView(num);
            TextView c = tv(sc, 14, text, false);
            c.setSingleLine(true);
            c.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(c, new LayoutParams(0, -2, 1.2f));
            TextView a = tv("▸ " + actionLabel(s.action), 14, text, false);
            a.setSingleLine(true);
            a.setEllipsize(TextUtils.TruncateAt.END);
            top.addView(a, new LayoutParams(0, -2, 1f));
            TextView mk = tv(have ? "in Set I" : "new", 11, have ? text : Theme.ON_GOLD, true);
            top.addView(mk);
            r.addView(top);
            TextView why = tv(s.reason, 11, dim, false);
            why.setPadding(dp(36), dp(1), 0, dp(3));
            r.addView(why);
            if (canApply(m) && applyStage == 0 && slotIdx < Gambits.SLOTS) r.setOnClickListener(v -> startConfirm(m, ApplyPlan.one(m, plan, slotIdx)));
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.bottomMargin = dp(3);
            rows.addView(r, lp);
        }
    }

    private static int signature(List<Gambits.Member> ms) {
        int h = 1;
        for (Gambits.Member m : ms) {
            h = 31 * h + m.active;   // 0.10.0: a set switch redraws the headers
            for (Gambits.Slot s : m.slots) h = 31 * h + (s.index * 7 + s.condition * 13 + s.action * 17 + (s.on ? 1 : 0));
        }
        return h;
    }

    /** The I / II / III plates: tap to look at a set (only looks; "Use this set…" is still the separate, confirmed step). */
    private android.view.View setPlates(Gambits.Member m, int sets, int view) {
        LinearLayout h = new LinearLayout(getContext());
        h.setPadding(0, dp(6), 0, 0);
        for (int k = 0; k < sets; k++) {
            boolean on = k == view;
            TextView t = tv((k == 0 ? "I" : k == 1 ? "II" : "III") + (m.active == k ? " \u00b7" : ""), 13, on ? text : dim, true);
            t.setGravity(android.view.Gravity.CENTER);
            t.setMinWidth(dp(56));
            t.setPadding(dp(12), dp(5), dp(12), dp(5));
            t.setBackground(round(on ? Theme.MARK : card, 6));
            final int target = k;
            t.setOnClickListener(v -> { viewedSet = target; lastKey = null; show(this.members, this.reference, status.getText().toString(), true); });
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            h.addView(t, lp);
        }
        return h;
    }

    /** "Set II", marked "· in use" when the game uses it; with the editor on, other sets offer "Use this set…". */
    private android.view.View setHeader(Gambits.Member m, int set) {
        LinearLayout h = new LinearLayout(getContext());
        h.setGravity(android.view.Gravity.CENTER_VERTICAL);
        h.setPadding(dp(4), dp(8), 0, dp(2));
        h.addView(tv(GambitEdit.setName(set), 11, accent, true));
        if (m.active == set) {
            TextView u = tv("  · in use", 11, good, true);
            h.addView(u);
        } else if (editAllowed && GambitEdit.canSwitch(m, set)) {
            TextView b = button("Use this set…", card, text);
            b.setTextSize(11);
            b.setPadding(dp(10), dp(2), dp(10), dp(2));
            final int target = set;
            b.setOnClickListener(v -> proposeSwitch(m, target));
            LayoutParams bl = new LayoutParams(-2, -2);
            bl.leftMargin = dp(10);
            h.addView(b, bl);
        }
        return h;
    }

    private TextView button(String s, int fill, int color) {
        TextView t = tv(s, 13, color, true);
        // 0.17.0: flat action words with a wash for the offered action; keep all existing confirm steps.
        t.setMinHeight(dp(48));
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(fill == Theme.MARK ? round(Theme.MARK, 0) : Theme.underline(density));   // an action, not a label
        return t;
    }

    private TextView tv(String s, int sp, int color, boolean bold) {
        return Theme.label(getContext(), s, sp, color, bold);
    }

    private android.graphics.drawable.Drawable round(int color, int radiusDp) {
        return Theme.fill(color, dp(radiusDp), dp(1));
    }

    private int dp(int v) { return Math.round(v * density); }
}
