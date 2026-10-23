package local.thor.ffxiicompanion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The companion's one screen, on the Thor's bottom display. Polls the two A/B snapshot files the game script writes to
 * Download/FFXII_companion/live and shows the newest valid one (selection, session and freshness rules: ReceiverState).
 *
 * Tabs:
 * - Status: location, the explored map of the region (atlas built on the PC from the user's own game files, gated by
 *   Reveal), the party, and collapsed Details.
 * - Gambits: live sets (Gambits), suggestions (GambitPlan) and the opt-in editor (GambitEdit, GambitsView).
 * - Equip and Bestiary: the data pack's reference.json (Reference), with live equipped gear and Optimize.
 * - Licenses: both boards (LicenceBoards) with the live licences (Build, LicenceRules).
 *
 * Live data is only shown for a verified game build; synthetic test data only in the JVM tests (fixtureMode).
 * File reads are bounded (DataFiles). The app also keeps Download/FFXII_companion/install in step with its own
 * bundled game script (Installer) and reports the loader's receipt (LoaderStatus).
 */
public class MainActivity extends Activity {
    static final File LIVE_DIR = new File(Environment.getExternalStorageDirectory(), "Download/FFXII_companion/live");
    static final File ATLAS_DIR = new File(Environment.getExternalStorageDirectory(), "Download/FFXII_companion/atlas");
    static final File REFERENCE_DIR = new File(Environment.getExternalStorageDirectory(), "Download/FFXII_companion/reference");
    static final File INSTALL_DIR = new File(Environment.getExternalStorageDirectory(), "Download/FFXII_companion/install");
    static final long ATLAS_RETRY_MS = 10_000;
    static final int MAX_MASK_BYTES = 4 * 1024 * 1024;
    static final int PARCHMENT = Color.rgb(222, 196, 146);
    static final int PARCHMENT_DIM = Color.rgb(104, 94, 76);
    static final int MAP_LINK = Color.rgb(150, 136, 110);
    /** Masks are decoded at half size: the region view draws a sheet at well under half its pixel size. */
    static final int MASK_SAMPLE = 2;
    static final int MASK_CACHE = 16;
    static final String[] SLOTS = { "state_a.json", "state_b.json" };
    static final long POLL_MS = 1000;

    // 0.21.0: FFXII-menu-inspired palette (Theme.java). BG doubles as the dark text colour on highlights.
    static final int BG = Theme.ON_HILITE;
    static final int CARD = Theme.PANEL;
    static final int TEXT = Theme.TEXT;
    static final int DIM = Theme.DIM;
    static final int GOOD = Color.rgb(76, 201, 128);
    static final int WARN = Color.rgb(240, 180, 60);
    static final int BAD = Color.rgb(235, 87, 87);
    static final int ACCENT = Theme.ACCENT;
    static final int HP = Color.rgb(96, 200, 120);
    static final int MP = Color.rgb(110, 150, 245);
    static final int TRACK = 0x80203458;
    static final int SYNTH = Color.rgb(214, 92, 214);

    final Handler handler = new Handler(Looper.getMainLooper());
    TextView title, status, statusDetail, banner, location, locationName, locationChip, session, build, mods, caps, partyChip, partyNote, detailsToggle;
    LinearLayout partyRow, partyCard, locationCard, details, mapCard;
    LinearLayout targetCard, targetRows;   // 0.13.0: what the party is acting on
    TextView mapChip, mapNote;
    RevealMapView mapView, bigMap;      // 0.12.0: bigMap = the full-screen region map
    LinearLayout mapPage;
    TextView mapRecentre, mapPageNote;
    File atlasDir = ATLAS_DIR;
    Atlas atlas;
    String atlasProblem = "map atlas not loaded yet";
    long atlasCheckedMono = -ATLAS_RETRY_MS;
    Regions regions;
    File referenceDir = REFERENCE_DIR;
    Reference reference;
    String referenceProblem;
    int[][] liveGear;       // equipped IDs from the live build (verified build, live session only)
    String liveGearKey;
    Build liveGearBuild;    // the build behind liveGear (licences, equipment stock) for Optimize suggestions
    TextView[] tabs;
    ScrollView liveScroll;
    ReferenceView gearView, bestiaryView;
    LicencesView licencesView;
    LicenceBoards boardsCache;           // boards.json, loaded once
    String boardsProblem;
    GambitsView gambitsView;
    final GambitEdit.Seen gambitsSeen = new GambitEdit.Seen();
    TextView referenceMissing;
    int tab;
    final Map<String, Bitmap> masks = new java.util.LinkedHashMap<String, Bitmap>(MASK_CACHE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > MASK_CACHE; }
    };
    String maskProblem;
    boolean detailsOpen;
    String detailsSummary = "";
    Button grant;
    final File dir = LIVE_DIR;
    /** The game script's licence table and equipment table, next to the snapshots; re-read when they change. */
    final DataFiles.Cached<LicenceNodes> licenceNodes =
            new DataFiles.Cached<>(new File(dir, "licence_nodes.json"), LicenceNodes.MAX_BYTES, LicenceNodes::parse);
    final DataFiles.Cached<EquipTable> equipTable =
            new DataFiles.Cached<>(new File(dir, "equipment.json"), 256 * 1024, EquipTable::parse);
    /** 0.14.0: the story hint and hunt journal from the data pack (reference/objectives.json). */
    final DataFiles.Cached<Objectives> objectives =
            new DataFiles.Cached<>(new File(REFERENCE_DIR, "objectives.json"), Objectives.MAX_BYTES, Objectives::parse);
    /** 0.14.0 map options, remembered on the device: symbols and story hint on, hunts off by default (spoilers). */
    static final String OPT_SYMBOLS = "symbols", OPT_HINT = "hint", OPT_HUNTS = "hunts";
    TextView hintLine, huntsNote;
    LinearLayout huntsCard, huntsRows, optionsRow;
    Map<Integer, Bitmap> symbolArt;
    /** Synthetic test data is only accepted by the JVM tests; the app itself always treats it as untrusted. */
    final boolean fixtureMode = false;
    ReceiverState state;
    ReceiverState.View lastView;
    final AutoClosePolicy autoClose = new AutoClosePolicy();
    boolean installSynced;
    long restoredFor = -1;

    final Runnable poll = new Runnable() {
        @Override public void run() {
            refresh();
            if (!isFinishing()) handler.postDelayed(this, POLL_MS);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        // Keep touch scrolling, but leave keyboard/gamepad focus with the game on the other display.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        state = new ReceiverState(fixtureMode);
        setContentView(buildUi());
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override protected void onResume() {
        super.onResume();
        Theme.start();
        handler.post(poll);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(poll);
        Theme.pause();
        super.onPause();
    }

    // ---- UI ----

    /*
     * Layout budget: the Thor bottom screen is 1240x1080 px at about 2.25 density (measured from device
     * captures, 2026-10-21), i.e. only ~551x480 dp. Order is by priority: connection status, location,
     * party; session/mods/build/feed diagnostics collapse behind "Details". Text uses sp so the user's
     * font scale is respected; long strings are ellipsized instead of pushing content off screen.
     */
    View buildUi() {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);   // 0.21.0: animated sky behind everything
        frame.addView(new ZodiacBackground(this), new android.widget.FrameLayout.LayoutParams(-1, -1));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.setPadding(dp(12), dp(8), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        status = text("…", 15, TEXT, true);
        status.setPadding(dp(10), dp(3), dp(10), dp(3));
        status.setSingleLine(true);
        header.addView(status);
        title = text(fixtureMode ? "FFXII Companion · fixture mode" : "FFXII Companion", 15, Theme.GOLD, true);
        heading(title);
        oneLine(title);
        title.setPadding(dp(10), 0, 0, 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);

        statusDetail = text("", 12, DIM, false);
        statusDetail.setMaxLines(2);
        statusDetail.setEllipsize(TextUtils.TruncateAt.END);
        statusDetail.setPadding(0, dp(3), 0, dp(4));
        root.addView(statusDetail);

        banner = text("", 12, BG, true);
        oneLine(banner);
        banner.setPadding(dp(10), dp(3), dp(10), dp(3));
        banner.setBackground(round(SYNTH, 6));
        banner.setVisibility(View.GONE);
        root.addView(banner);

        grant = new Button(this);
        grant.setText("Allow file access");
        grant.setVisibility(View.GONE);
        grant.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });
        root.addView(grant);

        // Tabs: live companion first; reference browsers load their bundle on first use.
        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setPadding(0, dp(2), 0, dp(6));
        String[] names = {"Status", "Gambits", "Equip", "Bestiary", "Licenses"};   // 0.22.0: the game's menu words
        tabs = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = text(names[i], 13, TEXT, true);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(8), dp(5), dp(8), dp(5));
            t.setOnClickListener(v -> selectTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            lp.rightMargin = i < names.length - 1 ? dp(6) : 0;
            tabRow.addView(t, lp);
            tabs[i] = t;
        }
        root.addView(tabRow);

        ScrollView scroll = new ScrollView(this);
        liveScroll = scroll;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        gambitsView = new GambitsView(this, TEXT, DIM, ACCENT, CARD, BG, GOOD, WARN);
        gambitsView.setVisibility(View.GONE);
        root.addView(gambitsView, new LinearLayout.LayoutParams(-1, 0, 1));
        gearView = new ReferenceView(this, TEXT, DIM, ACCENT, CARD, BG);
        gearView.setVisibility(View.GONE);
        root.addView(gearView, new LinearLayout.LayoutParams(-1, 0, 1));
        bestiaryView = new ReferenceView(this, TEXT, DIM, ACCENT, CARD, BG);
        bestiaryView.setVisibility(View.GONE);
        root.addView(bestiaryView, new LinearLayout.LayoutParams(-1, 0, 1));
        licencesView = new LicencesView(this);
        licencesView.setVisibility(View.GONE);
        root.addView(licencesView, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(buildMapPage(), new LinearLayout.LayoutParams(-1, 0, 1));
        referenceMissing = text("", 13, DIM, false);
        referenceMissing.setPadding(dp(6), dp(10), dp(6), dp(10));
        referenceMissing.setVisibility(View.GONE);
        root.addView(referenceMissing, new LinearLayout.LayoutParams(-1, 0, 1));

        // Location: area name large on one line, then where from / ids.
        locationCard = cardShell();
        LinearLayout locHead = new LinearLayout(this);
        locHead.setGravity(Gravity.CENTER_VERTICAL);
        locHead.addView(cardHeading("LOCATION"), new LinearLayout.LayoutParams(0, -2, 1));
        locationChip = chip();
        locHead.addView(locationChip);
        locationCard.addView(locHead);
        locationName = text("", 18, TEXT, true);
        oneLine(locationName);
        locationName.setPadding(0, dp(2), 0, 0);
        locationCard.addView(locationName);
        location = text("", 13, DIM, false);
        location.setMaxLines(3);
        location.setEllipsize(TextUtils.TruncateAt.END);
        locationCard.addView(location);
        hintLine = text("", 13, Theme.GOLD, false);     // 0.14.0: the game's own "where to go next" line
        hintLine.setMaxLines(3);
        hintLine.setEllipsize(TextUtils.TruncateAt.END);
        hintLine.setPadding(0, dp(3), 0, 0);
        hintLine.setVisibility(View.GONE);
        locationCard.addView(hintLine);
        body.addView(locationCard, cardParams(-1, 0));

        // Targeting (0.13.0): the enemy the party's pending actions are aimed at; only shown while there is one.
        targetCard = cardShell();
        targetCard.addView(cardHeading("TARGETING"));
        targetRows = new LinearLayout(this);
        targetRows.setOrientation(LinearLayout.VERTICAL);
        targetCard.addView(targetRows);
        targetCard.setVisibility(View.GONE);
        body.addView(targetCard, cardParams(-1, 0));

        // Map: the explored parts of the region around the current area (gated by Reveal), with the player marker.
        mapCard = cardShell();
        LinearLayout mapHead = new LinearLayout(this);
        mapHead.setGravity(Gravity.CENTER_VERTICAL);
        mapHead.addView(cardHeading("MAP"), new LinearLayout.LayoutParams(0, -2, 1));
        mapChip = chip();
        mapHead.addView(mapChip);
        mapCard.addView(mapHead);
        mapView = new RevealMapView(this, PARCHMENT, PARCHMENT_DIM, MAP_LINK, TEXT, BG, 10, false);
        mapView.setVisibility(View.GONE);
        mapView.setOnClickListener(x -> openMap(true));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(190));
        mlp.topMargin = dp(4);
        mapCard.addView(mapView, mlp);
        mapNote = text("", 12, DIM, false);
        mapNote.setMaxLines(2);
        mapNote.setEllipsize(TextUtils.TruncateAt.END);
        mapNote.setPadding(0, dp(3), 0, 0);
        mapCard.addView(mapNote);
        body.addView(mapCard, cardParams(-1, 0));

        // Party: heading + chip, then one compact row per member (or one explanatory line).
        partyCard = cardShell();
        LinearLayout partyHead = new LinearLayout(this);
        partyHead.setGravity(Gravity.CENTER_VERTICAL);
        partyHead.addView(cardHeading("PARTY"), new LinearLayout.LayoutParams(0, -2, 1));
        partyChip = chip();
        partyHead.addView(partyChip);
        partyCard.addView(partyHead);
        partyNote = text("", 13, DIM, false);
        partyNote.setMaxLines(3);
        partyNote.setEllipsize(TextUtils.TruncateAt.END);
        partyNote.setPadding(0, dp(3), 0, 0);
        partyCard.addView(partyNote);
        partyRow = new LinearLayout(this);
        partyRow.setOrientation(LinearLayout.VERTICAL);
        partyCard.addView(partyRow);
        body.addView(partyCard, cardParams(-1, 0));

        // Hunts (0.14.0, off by default): the game's own journal text for hunts you have accepted.
        huntsCard = cardShell();
        huntsCard.addView(cardHeading("HUNTS"));
        huntsNote = text("", 13, DIM, false);
        huntsCard.addView(huntsNote);
        huntsRows = new LinearLayout(this);
        huntsRows.setOrientation(LinearLayout.VERTICAL);
        huntsCard.addView(huntsRows);
        huntsCard.setVisibility(View.GONE);
        body.addView(huntsCard, cardParams(-1, 0));

        // Secondary diagnostics: collapsed by default, one tap to open (touch only; the window stays non-focusable).
        detailsToggle = text("", 13, ACCENT, true);
        detailsToggle.setPadding(dp(8), dp(8), dp(8), dp(8));
        detailsToggle.setOnClickListener(x -> {
            detailsOpen = !detailsOpen;
            details.setVisibility(detailsOpen ? View.VISIBLE : View.GONE);
            updateDetailsToggle();
        });
        body.addView(detailsToggle);
        details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setVisibility(View.GONE);
        LinearLayout row = new LinearLayout(this);
        session = card(row, "Session");
        caps = card(row, "Data feeds");
        details.addView(row);
        LinearLayout row2 = new LinearLayout(this);
        mods = card(row2, "Mods");
        build = card(row2, "Game build");
        details.addView(row2);
        body.addView(details);
        updateDetailsToggle();
        styleTabs();
        return frame;
    }

    /** 0.12.0: the whole explored region, full height, with pinch zoom and pan. Opened by tapping the map card. */
    View buildMapPage() {
        mapPage = new LinearLayout(this);
        mapPage.setOrientation(LinearLayout.VERTICAL);
        mapPage.setVisibility(View.GONE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(cardHeading("MAP"), new LinearLayout.LayoutParams(0, -2, 1));
        mapRecentre = pageButton("Re-centre");
        mapRecentre.setVisibility(View.GONE);
        mapRecentre.setOnClickListener(x -> bigMap.recentre());
        head.addView(mapRecentre);
        TextView close = pageButton("Close");
        close.setOnClickListener(x -> openMap(false));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = dp(8);
        head.addView(close, clp);
        mapPage.addView(head);
        optionsRow = new LinearLayout(this);              // 0.14.0: map options, remembered
        optionsRow.setPadding(0, dp(4), 0, 0);
        for (String[] o : new String[][] {{OPT_SYMBOLS, "Symbols"}, {OPT_HINT, "Story hint"}, {OPT_HUNTS, "Hunts"}}) {
            TextView chip = pageButton("");
            chip.setTextSize(12);
            chip.setTag(o[0]);
            chip.setOnClickListener(x -> {
                setOption(o[0], !option(o[0]));
                styleOptions();
                applySymbolOption();
                if (lastView != null) render(lastView);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            optionsRow.addView(chip, lp);
        }
        mapPage.addView(optionsRow);
        styleOptions();
        bigMap = new RevealMapView(this, PARCHMENT, PARCHMENT_DIM, MAP_LINK, TEXT, BG, 12, true);
        bigMap.setOnMoved(moved -> mapRecentre.setVisibility(moved ? View.VISIBLE : View.GONE));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, 0, 1);
        blp.topMargin = dp(4);
        mapPage.addView(bigMap, blp);
        mapPageNote = text("Pinch to zoom, drag to move. Double-tap: this area / the whole region.", 11, DIM, false);
        oneLine(mapPageNote);
        mapPageNote.setPadding(0, dp(3), 0, 0);
        mapPage.addView(mapPageNote);
        return mapPage;
    }

    boolean option(String key) {
        return getSharedPreferences("options", MODE_PRIVATE).getBoolean(key, !OPT_HUNTS.equals(key));
    }

    void setOption(String key, boolean on) {
        getSharedPreferences("options", MODE_PRIVATE).edit().putBoolean(key, on).apply();
    }

    void styleOptions() {
        String[] names = {"Symbols", "Story hint", "Hunts"};
        for (int i = 0; i < optionsRow.getChildCount(); i++) {
            TextView c = (TextView) optionsRow.getChildAt(i);
            boolean on = option((String) c.getTag());
            c.setText((on ? "\u2713 " : "") + names[i]);
            c.setBackground(round(on ? ACCENT : CARD, 14));
            c.setTextColor(on ? BG : DIM);
        }
    }

    void applySymbolOption() {
        Map<Integer, Bitmap> art = option(OPT_SYMBOLS) ? symbolArt : null;
        mapView.setSymbolArt(art);
        bigMap.setSymbolArt(art);
    }

    /** 0.14.0: the story hint line and the hunts card; live and verified data only, like the party. */
    void showObjectives(ReceiverState.View v) {
        Snapshot s = v.current != null && v.current.map() != null ? v.current : v.location;
        boolean ok = s != null && v.locationFamily.state == ReceiverState.FamilyState.LIVE
                && (s.synthetic ? fixtureMode : Reveal.buildVerified(s.hello()));
        Objectives o = objectives.get();
        Flags f = ok ? Flags.parse(Json.obj(s.data, "flags")) : null;
        String hint = o != null && option(OPT_HINT) ? o.hint(f) : null;
        hintLine.setText(hint != null ? "Next: " + hint.replace('\n', ' ') : "");
        hintLine.setVisibility(hint != null ? View.VISIBLE : View.GONE);
        huntsCard.setVisibility(option(OPT_HUNTS) ? View.VISIBLE : View.GONE);
        if (!option(OPT_HUNTS)) return;
        huntsRows.removeAllViews();
        List<Objectives.Entry> js = o != null && ok ? o.journal(Json.obj(s.data, "quests")) : java.util.Collections.emptyList();
        huntsNote.setText(o == null ? "Needs the game data (run the data builder, see INSTALL)."
                : !ok ? "Shown while the game is running." : js.isEmpty() ? "No accepted hunts yet." : "");
        huntsNote.setVisibility(huntsNote.getText().length() == 0 ? View.GONE : View.VISIBLE);
        for (Objectives.Entry e : js) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.VERTICAL);
            r.setPadding(dp(8), dp(4), dp(8), dp(5));
            r.setBackground(round(TRACK, 6));
            TextView n = text(e.hunt.title != null ? e.hunt.title : "Hunt " + e.hunt.questId, 13, TEXT, true);
            oneLine(n);
            r.addView(n);
            if (e.paragraph != null) {
                TextView p = text(e.paragraph.replace('\n', ' '), 12, DIM, false);
                p.setMaxLines(3);
                p.setEllipsize(TextUtils.TruncateAt.END);
                r.addView(p);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            huntsRows.addView(r, lp);
        }
    }

    TextView pageButton(String s) {
        TextView t = text(s, 13, TEXT, true);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setBackground(round(CARD, 14));
        return t;
    }

    /** Shows the full map in place of the Status page, or goes back to it. */
    void openMap(boolean open) {
        if (open && tab != 0) return;
        mapPage.setVisibility(open ? View.VISIBLE : View.GONE);
        liveScroll.setVisibility(open || tab != 0 ? View.GONE : View.VISIBLE);
        if (open) bigMap.recentre();
    }

    TextView cardHeading(String s) {
        TextView h = text(s, 11, ACCENT, true);
        heading(h);
        return h;
    }

    /** 0.21.0: headings in the device serif, letter-spaced, like FFXII's menu titles (no game font is used). */
    static void heading(TextView t) {
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        t.setLetterSpacing(0.08f);
    }

    void styleTabs() {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setBackground(round(i == tab ? ACCENT : CARD, 14));
            tabs[i].setTextColor(i == tab ? BG : TEXT);
        }
    }

    void selectTab(int i) {
        tab = i;
        styleTabs();
        mapPage.setVisibility(View.GONE);
        liveScroll.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
        gearView.setVisibility(View.GONE);
        bestiaryView.setVisibility(View.GONE);
        gambitsView.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        licencesView.setVisibility(i == 4 ? View.VISIBLE : View.GONE);
        referenceMissing.setVisibility(View.GONE);
        if (i == 0) return;
        if (i == 1 || i == 4) { loadReference(); if (i == 4 && lastView != null) showLicences(lastView); return; }
        if (loadReference() == null) {
            referenceMissing.setText("Game data not installed yet. Build it with the FFXII Companion data builder"
                    + " on your PC and copy it to Download/FFXII_companion (see INSTALL).");
            referenceMissing.setVisibility(View.VISIBLE);
            return;
        }
        (i == 2 ? gearView : bestiaryView).setVisibility(View.VISIBLE);
    }

    /** Loads and binds reference.json once; null (with referenceProblem) when missing or unreadable. */
    Reference loadReference() {
        if (reference != null) return reference;
        byte[] data = DataFiles.readBytes(new File(referenceDir, "reference.json"), Reference.MAX_BYTES);
        if (data == null) { referenceProblem = "reference.json missing"; return null; }
        reference = Reference.parse(new String(data, StandardCharsets.UTF_8));
        if (reference == null) { referenceProblem = "reference.json unreadable"; return null; }
        final Reference r = reference;
        bindGear();
        bestiaryView.bind(new ReferenceView.Source() {
            @Override public List<String> filters() { return r.locations(); }
            @Override public List<Reference.Entry> entries(String f) { return r.bestiaryAt(f); }
            @Override public String subtitle(Reference.Entry e) {
                return e.locations.isEmpty() ? "" : String.join(", ", e.locations);
            }
        }, "");
        return reference;
    }

    void updateDetailsToggle() {
        detailsToggle.setText((detailsOpen ? "▾ Details" : "▸ Details") + detailsSummary);
    }

    static void oneLine(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
    }

    LinearLayout cardShell() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(10), dp(6), dp(10), dp(8));
        c.setBackground(round(CARD, 8));
        return c;
    }

    LinearLayout.LayoutParams cardParams(int width, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, -2, weight);
        lp.setMargins(dp(2), dp(3), dp(2), dp(3));
        return lp;
    }

    TextView body(LinearLayout c) {
        TextView v = text("", 13, TEXT, false);
        v.setLineSpacing(dp(1), 1f);
        v.setPadding(0, dp(3), 0, 0);
        c.addView(v);
        return v;
    }

    TextView card(LinearLayout row, String heading) {
        LinearLayout c = cardShell();
        TextView h = text(heading.toUpperCase(), 11, ACCENT, true);
        heading(h);
        c.addView(h);
        TextView v = body(c);
        row.addView(c, cardParams(0, 1));
        return v;
    }

    TextView chip() {
        TextView t = text("", 11, BG, true);
        t.setSingleLine(true);
        t.setPadding(dp(7), dp(1), dp(7), dp(1));
        return t;
    }

    TextView text(String s, int sp, int color, boolean bold) {
        return Theme.label(this, s, sp, color, bold);
    }

    android.graphics.drawable.Drawable round(int color, int radiusDp) {
        return Theme.fill(color, dp(radiusDp), dp(1));
    }

    int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    void setStatus(String label, int color, String detail) {
        status.setText(label);
        status.setBackground(round(color, 14));
        status.setTextColor(BG);
        statusDetail.setText(detail);
        statusDetail.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void setChip(TextView chip, ReceiverState.Family f) {
        String label;
        int color;
        switch (f.state) {
            case LIVE: label = null; color = GOOD; break;          // 0.22.0: nothing to say while live
            case STALE: case CLOSED: label = null; color = DIM; break;   // the header already says Paused / Game closed
            case MISSING: label = "No data"; color = DIM; break;
            default: label = null; color = DIM; break;
        }
        chip.setVisibility(label == null ? View.GONE : View.VISIBLE);
        if (label == null) return;
        chip.setText(label);
        chip.setBackground(round(color, 10));
    }

    // ---- Data ----

    void refresh() {
        if (!fixtureMode && !Environment.isExternalStorageManager()) {
            grant.setVisibility(View.VISIBLE);
            setStatus("NO ACCESS", BAD, "This app needs all-files access to read " + dir + ".");
            return;
        }
        grant.setVisibility(View.GONE);
        if (!installSynced) {
            installSynced = Installer.sync(getAssets(), INSTALL_DIR) >= 0;
            if (!installSynced) android.util.Log.w("FFXIICompanion", "install folder: " + Installer.lastError);
        }

        Snapshot[] slots = new Snapshot[SLOTS.length];
        int malformed = 0;
        boolean present = false;
        for (int i = 0; i < SLOTS.length; i++) {
            File f = new File(dir, SLOTS[i]);
            if (!f.isFile()) continue;
            present = true;
            slots[i] = Snapshot.parse(DataFiles.readText(f, Snapshot.MAX_BYTES), f.lastModified());
            if (slots[i] == null) malformed++;
        }
        ReceiverState.View v = state.update(slots[0], slots[1], malformed, present,
                SystemClock.elapsedRealtime(), System.currentTimeMillis());
        render(v);
        closeAfterExit(v.transport == ReceiverState.Transport.LIVE,
                v.transport == ReceiverState.Transport.GAME_CLOSED, v.synthetic);
    }

    void closeAfterExit(boolean running, boolean exited, boolean synthetic) {
        if (fixtureMode) return;
        if (autoClose.update(running, exited, synthetic, SystemClock.elapsedRealtime())) finishAndRemoveTask();
    }

    void render(ReceiverState.View v) {
        if (v.synthetic || fixtureMode) {
            String fx = v.current != null && v.current.fixture != null ? " · " + v.current.fixture : "";
            banner.setText("SYNTHETIC TEST DATA: not from the game" + fx);
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }

        String bad = v.malformedReads > 0 ? " " + v.malformedReads + " unreadable snapshot file(s) skipped." : "";
        // 0.11.0: the loader's receipt. A refusal written after the newest snapshot means this game start ran no script.
        File receipt = new File(dir, "loader_status.json");
        long newestSnapshot = 0;
        for (String s : SLOTS) newestSnapshot = Math.max(newestSnapshot, new File(dir, s).lastModified());
        String loaderResult = receipt.isFile() && receipt.length() <= LoaderStatus.MAX_BYTES ? LoaderStatus.result(DataFiles.readText(receipt, LoaderStatus.MAX_BYTES)) : null;
        String loaderProblem = receipt.lastModified() > newestSnapshot ? LoaderStatus.message(loaderResult) : null;
        if (loaderProblem != null && LoaderStatus.restorable(loaderResult) && receipt.lastModified() != restoredFor) {
            restoredFor = receipt.lastModified();   // put the app's own copies back right away (once per refusal)
            installSynced = Installer.sync(getAssets(), INSTALL_DIR) >= 0;
        }
        if (loaderProblem != null && v.transport != ReceiverState.Transport.LIVE) {
            setStatus("Not running", BAD, loaderProblem);
        } else switch (v.transport) {
            case NO_DATA:
                setStatus("Waiting", DIM, "Start FFXII to begin. First time? Run install.cmd from Download/FFXII_companion/install"
                        + " in the game's GameNative container (see README.txt there)." + bad);
                break;
            case GAME_CLOSED:
                setStatus("Game closed", DIM, "Last played " + Details.secs(v.transportAgeMs) + " ago." + bad);
                break;
            case NO_SIGNAL:
                setStatus("No signal", WARN, "Showing the last known state." + bad);
                break;
            case STALE:
                setStatus("Paused", WARN, "No update for " + Details.secs(v.transportAgeMs) + " (paused, loading or closed)." + bad);
                break;
            default: {
                Map<String, Object> hello = v.current != null ? v.current.hello() : null;
                String hint = LoaderStatus.updateHint(hello != null ? Json.str(Json.obj(hello, "producer"), "version") : null,
                        appVersion(), loaderResult != null);
                setStatus("Live", GOOD, ((hint != null ? hint + " " : "") + bad).trim());
            }
        }

        showParty(v);
        showLocation(v);
        showTarget(v);
        showObjectives(v);
        showMap(v);
        showGambits(v);
        showGear(v);
        lastView = v;
        showLicences(v);
        detailsSummary = v.malformedReads > 0 ? " · " + v.malformedReads + " unreadable file(s)" : "";
        updateDetailsToggle();
        if (v.current == null) {
            session.setText("—"); build.setText("—"); mods.setText("—"); caps.setText("—");
            return;
        }
        Map<String, Object> hello = v.current.hello();
        session.setText(Details.session(v.current, hello));
        build.setText(Details.build(hello));
        mods.setText(Details.mods(hello));
        caps.setText(Details.feeds(hello));
    }

    String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    void showParty(ReceiverState.View v) {
        setChip(partyChip, v.partyFamily);
        partyRow.removeAllViews();
        if (v.location != null && Place.title(Json.lng(v.location.map(), "mapId"))) {   // 0.10.0: no party before a save loads
            partyNote.setText("Load a save to see your party.");
            partyNote.setVisibility(View.VISIBLE);
            return;
        }
        if (v.party == null) {
            partyNote.setText(partyReason(v.partyFamily));
            partyNote.setVisibility(View.VISIBLE);
            return;
        }
        String note = v.partyFamily.state == ReceiverState.FamilyState.STALE
                ? "Last known" : "";
        if (v.party.members.isEmpty()) note += (note.isEmpty() ? "" : " ") + "No one in the field party.";
        if (v.partyProbe) note += (note.isEmpty() ? "" : " ") + "New: please check these match the game's party menu once.";
        if (v.party.dropped > 0) note += (note.isEmpty() ? "" : " ") + "+" + v.party.dropped + " more member(s) not shown.";
        partyNote.setText(note);
        partyNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        boolean stale = v.partyFamily.state != ReceiverState.FamilyState.LIVE;
        for (Party.Member m : v.party.members) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            partyRow.addView(memberRow(m, stale), lp);
        }
    }

    static String partyReason(ReceiverState.Family f) {
        if (f.state == ReceiverState.FamilyState.CLOSED) return "Game closed.";
        if ("no save loaded".equals(f.reason)) return "Load a save to see your party.";
        if ("party feed not available yet".equals(f.reason)) return "Not available yet.";
        return "Not available (" + f.reason + ").";
    }

    /** One compact row per member: name (+ leader/guest/KO) | HP value + bar | MP value + bar. */
    View memberRow(Party.Member m, boolean stale) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(8), dp(4), dp(8), dp(5));
        r.setBackground(round(TRACK, 6));
        r.setAlpha(stale ? 0.55f : 1f);

        LinearLayout who = new LinearLayout(this);
        who.setOrientation(LinearLayout.VERTICAL);
        TextView n = text((m.leader ? "★ " : "") + (m.name != null ? m.name : "Unknown"), 14, TEXT, true);
        oneLine(n);
        who.addView(n);
        String tags = ("active".equals(m.role) ? "" : m.role.toUpperCase()) + (Boolean.TRUE.equals(m.ko) ? ("active".equals(m.role) ? "" : " · ") + "KO" : "");
        if (!tags.isEmpty()) {
            TextView t = text(tags, 10, Boolean.TRUE.equals(m.ko) ? BAD : WARN, true);
            oneLine(t);
            who.addView(t);
        }
        r.addView(who, new LinearLayout.LayoutParams(0, -2, 0.9f));
        r.addView(vital("HP", m.hp, m.hpInvalid, HP), new LinearLayout.LayoutParams(0, -2, 1.1f));
        r.addView(vital("MP", m.mp, m.mpInvalid, MP), new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    View vital(String label, Party.Vital val, boolean invalid, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), 0, 0, 0);
        String value = val != null ? val.cur + "/" + val.max : invalid ? "invalid" : "—";
        TextView t = text(label + " " + value, 12, val != null ? TEXT : DIM, false);
        oneLine(t);
        box.addView(t);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(round(BG, 3));  // darker than the member card, so an empty bar stays visible
        if (val != null && val.cur > 0) {
            View fill = new View(this);
            fill.setBackground(round(color, 3));
            bar.addView(fill, new LinearLayout.LayoutParams(0, -1, val.cur));
        }
        if (val != null && val.max > val.cur) bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, val.max - val.cur));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(5));
        lp.topMargin = dp(2);
        box.addView(bar, lp);
        return box;
    }

    /** 0.13.0: the leader's target first, at most two enemies; live and verified data only, like the party. */
    void showTarget(ReceiverState.View v) {
        Snapshot s = v.current != null && v.current.map() != null ? v.current : v.location;
        boolean ok = s != null && v.locationFamily.state == ReceiverState.FamilyState.LIVE
                && (s.synthetic ? fixtureMode : Reveal.buildVerified(s.hello()));
        List<Target.Enemy> enemies = ok ? Target.enemies(Json.obj(s.data, "target")) : java.util.Collections.emptyList();
        targetRows.removeAllViews();
        targetCard.setVisibility(enemies.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < Math.min(2, enemies.size()); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(4);
            targetRows.addView(enemyRow(enemies.get(i)), lp);
        }
        if (enemies.size() > 2) {
            TextView more = text("+" + (enemies.size() - 2) + " more", 11, DIM, false);
            targetRows.addView(more);
        }
    }

    View enemyRow(Target.Enemy e) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(8), dp(4), dp(8), dp(5));
        r.setBackground(round(TRACK, 6));
        Reference.Entry entry = reference != null && e.bestiary != null ? reference.bestiaryByKey(e.bestiary) : null;
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(entry != null ? entry.name : "Enemy", 14, TEXT, true);
        oneLine(n);
        head.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        StringBuilder by = new StringBuilder();
        for (int m : e.targetedBy) {
            if (m < 0 || m >= Gambits.MEMBERS.length) continue;
            by.append(by.length() > 0 ? ", " : "").append(Gambits.MEMBERS[m]);
        }
        TextView meta = text("Lv " + e.level + (by.length() > 0 ? " · " + by : ""), 11, DIM, false);
        oneLine(meta);
        head.addView(meta);
        r.addView(head);
        r.addView(vital("HP", new Party.Vital(e.hp, e.maxHp), false, BAD));
        List<String> aff = Target.affinities(e);
        TextView a = text(aff.isEmpty() ? "No elemental weaknesses" : String.join(" · ", aff), 12,
                aff.isEmpty() ? DIM : TEXT, false);
        a.setMaxLines(2);
        a.setEllipsize(TextUtils.TruncateAt.END);
        a.setPadding(0, dp(3), 0, 0);
        r.addView(a);
        if (entry != null) r.setOnClickListener(x -> openBestiary(entry));
        return r;
    }

    /** Opens the Bestiary tab on this entry. */
    void openBestiary(Reference.Entry entry) {
        selectTab(3);
        bestiaryView.open(entry);
    }

    private long areasStamp = -1;

    /** Area names from the user's data pack (reference/areas.json), re-read only when the file changes. */
    void loadAreas() {
        File f = new File(referenceDir, "areas.json");
        long s = f.isFile() ? f.lastModified() : 0;
        if (s == areasStamp) return;
        areasStamp = s;
        byte[] d = s == 0 ? null : DataFiles.readBytes(f, 256 * 1024);
        Place.setNames(d == null ? null : Place.parse(new String(d, StandardCharsets.UTF_8)));
    }

    void showLocation(ReceiverState.View v) {
        loadAreas();
        setChip(locationChip, v.locationFamily);
        if (v.location == null) {
            locationName.setText("No map data yet");
            location.setText("");
            return;
        }
        Map<String, Object> m = v.location.map();
        locationName.setText(Place.name(Json.lng(m, "mapId")));                     // 0.23.1: "Loading…", not "Map -1"
        String from = Place.from(Json.lng(m, "mapId"), Json.lng(m, "previousMapId"));
        String battle = v.location.synthetic || Place.loading(Json.lng(m, "mapId")) ? null : battleLine(Json.obj(v.location.data, "battle"));
        String line = from + (battle != null ? (from.isEmpty() ? "" : "   ") + battle : "");
        location.setText(line);
        location.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
    }

    /** Logger 0.5.7 battle-state candidate (0x22C1F48), shown raw for the live check; not used as a gate yet. */
    static String battleLine(Map<String, Object> b) {
        if (b == null) return null;
        if (!"ok".equals(Json.str(b, "status"))) return null;
        Long f = Json.lng(b, "flags");
        return f != null && (f & 0x05) != 0 ? "⚔ Weapons drawn" : null;   // 0.22.0: only when it's true (raw flags: Details)
    }

    void showMap(ReceiverState.View v) {
        loadAtlas();
        Reveal.Decision d = Reveal.decide(v.location, atlas, fixtureMode);
        if (d.mode == Reveal.Mode.HIDDEN && atlas == null && d.reason.contains("atlas not installed")) d.reason = atlasProblem;
        Bitmap bm = d.mode == Reveal.Mode.HIDDEN ? null : mask(d.sheet);
        if (d.mode != Reveal.Mode.HIDDEN && bm == null) d = Reveal.Decision.hidden(d.mapId, maskProblem);
        if (d.mode == Reveal.Mode.HIDDEN) {
            setChip(mapChip, new ReceiverState.Family(ReceiverState.FamilyState.DISABLED, -1, null));
            mapView.setVisibility(View.GONE);
            mapView.set(null, null, null, MASK_SAMPLE);
            mapView.setMarker(null, null, false);
            bigMap.set(null, null, null, MASK_SAMPLE);
            bigMap.setMarker(null, null, false);
            if (mapPage.getVisibility() == View.VISIBLE) openMap(false);
            mapNote.setText("No map for this area.");
            mapNote.setVisibility(View.VISIBLE);
            return;
        }
        // Region context: neighbours whose image fails its checks are simply left out.
        Regions.View rv;
        if (regions != null) {
            rv = regions.build(d, v.location, atlas);
        } else {
            rv = new Regions.View();
            Regions.Part p = new Regions.Part();
            p.sheet = d.sheet; p.visible = d.visible; p.focus = true;
            rv.parts.add(p);
            rv.note = "region layout not installed";
        }
        List<Regions.Part> drawable = new java.util.ArrayList<>();
        int others = 0;
        for (Regions.Part p : rv.parts) {
            if (p.focus || mask(p.sheet) != null) {
                drawable.add(p);
                if (!p.focus) others++;
            }
        }
        setChip(mapChip, v.locationFamily);
        mapView.setVisibility(View.VISIBLE);
        mapView.set(rv, drawable, masks, MASK_SAMPLE);
        bigMap.set(rv, drawable, masks, MASK_SAMPLE);
        Position.Marker mk = Position.locate(v.location, d.sheet, fixtureMode);
        boolean live = v.locationFamily.state == ReceiverState.FamilyState.LIVE;
        mapView.setMarker(mk.reason == null ? mk.px : null, mk.reason == null ? mk.py : null, live);
        bigMap.setMarker(mk.reason == null ? mk.px : null, mk.reason == null ? mk.py : null, live);
        mapNote.setText("");                                   // 0.22.0: the map speaks for itself
        mapNote.setVisibility(View.GONE);
    }

    static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (p != null && !p.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(p);
        return b.toString();
    }

    /** 0.21.0: boards.json (beside reference.json), loaded once; null with boardsProblem when missing. */
    LicenceBoards boards() {
        if (boardsCache != null) return boardsCache;
        File f = new File(referenceDir, "boards.json");
        if (!f.isFile()) { boardsProblem = "Game data not installed yet (run the data builder, see INSTALL)"; return null; }
        Map<String, Object> o = DataFiles.jsonObject(f, 512 * 1024);
        boardsCache = o != null ? LicenceBoards.parse(o) : null;
        if (boardsCache == null) boardsProblem = "boards.json unreadable";
        return boardsCache;
    }

    /** Licences tab: same gates as the other live families (verified build, live session, fixtures only in fixture mode). */
    void showLicences(ReceiverState.View v) {
        if (tab != 4) return;
        Snapshot s = v.current != null && v.current.map() != null ? v.current : v.location;
        String why = null;
        Build b = null;
        if (s == null) why = "No game data yet.";
        else if (s.synthetic ? !fixtureMode : !Reveal.buildVerified(s.hello())) why = "Game build not verified.";
        else if ((b = Build.parse(Json.obj(s.data, "build"))) == null) why = "No licence data yet (load a save).";
        LicenceBoards lb = why == null ? boards() : null;
        if (why == null && lb == null) why = boardsProblem;
        licencesView.show(b, licenceNodes.get(), lb, reference, why);
    }

    /** Gear tab: a chip per member with what they have equipped right now (when live), then the reference categories. */
    void bindGear() {
        final Reference r = reference;
        if (r == null) return;
        final int[][] gear = liveGear;
        final Build gb = liveGearBuild;
        final EquipTable et = equipTable.get();
        final LicenceNodes ln = gb != null ? licenceNodes.get() : null;
        gearView.bind(new ReferenceView.Source() {
            @Override public List<String> filters() {
                List<String> f = new java.util.ArrayList<>();
                if (gear != null) for (int m = 0; m < 6; m++) f.add(Equipped.filter(m));
                f.addAll(r.gearCategories());
                return f;
            }
            @Override public List<Reference.Entry> entries(String f) {
                int m = Equipped.member(f);
                return m >= 0 ? Equipped.entries(gear, m, r, Optimize.pick(et, gb, ln, m)) : r.gearIn(f);
            }
            @Override public String subtitle(Reference.Entry e) {
                String t = String.join(" · ", e.tags);
                return (e.category != null ? e.category : "") + (t.isEmpty() ? "" : " · " + t);
            }
        }, gear != null ? "Tap a name for their equipment" : "");
    }

    /** Keeps liveGear in step with the live build: same gates as gambits (verified build, live, fixtures only in fixture mode). */
    void showGear(ReceiverState.View v) {
        Snapshot s = v.current != null && v.current.map() != null ? v.current : v.location;
        boolean ok = s != null && v.locationFamily.state == ReceiverState.FamilyState.LIVE
                && (s.synthetic ? fixtureMode : Reveal.buildVerified(s.hello()));
        Build b = ok ? Build.parse(Json.obj(s.data, "build")) : null;
        int[][] gear = b != null ? b.gear : null;
        // Content only (0.19.1): gear, equipment stock and licence bits. Never object identities, which change every parse
        // and made the tab rebind (and jump back to "All") on every update.
        String key = null;
        if (gear != null) {
            StringBuilder k = new StringBuilder(java.util.Arrays.deepToString(gear)).append('|')
                    .append(b.equipStock != null ? java.util.Arrays.hashCode(b.equipStock) : 0);
            for (Build.Member bm : b.members) k.append('|').append(java.util.Arrays.hashCode(bm.licences));
            key = k.toString();
        }
        if (java.util.Objects.equals(key, liveGearKey)) return;
        liveGearKey = key;
        liveGear = gear;
        liveGearBuild = gear != null ? b : null;
        bindGear();
    }

    /** Live gambits: same gates as the other live families (fixture data only in fixture mode, verified build). */
    void showGambits(ReceiverState.View v) {
        if (tab != 1) return;
        Snapshot s = v.current != null && v.current.map() != null ? v.current : v.location;
        Map<String, Object> g = s != null ? Json.obj(s.data, "gambits") : null;
        String why = null;
        if (s == null) why = "No game data yet.";
        else if (g == null) why = "No gambit data (game script older than 0.4.3).";
        else if (s.synthetic && !fixtureMode) why = "Synthetic gambit data outside fixture mode ignored.";
        else if (!s.synthetic && !Reveal.buildVerified(s.hello())) why = "Game build not verified for gambits.";
        else if (!"ok".equals(Json.str(g, "status"))) why = "Gambits not available: " + Json.str(g, "status") + ".";
        List<Gambits.Member> ms = why == null ? Gambits.parse(g) : null;
        if (why == null && ms == null) why = "Gambit data malformed.";
        boolean live = v.locationFamily.state == ReceiverState.FamilyState.LIVE;
        if (why == null && ms != null && live) gambitsSeen.update(v.session, ms);   // only live, verified data
        gambitsView.setSeen(gambitsSeen);
        Build build = why == null && live ? Build.parse(Json.obj(s.data, "build")) : null;
        LicenceNodes nodes = build != null ? licenceNodes.get() : null;
        gambitsView.setBuild(build, nodes);
        gambitsView.setPlans(ms != null ? GambitPlan.suggest(build, nodes, reference, ms) : null);
        GambitEdit.State es = s != null ? GambitEdit.parse(Json.obj(s.data, "gambitEdit")) : null;
        gambitsView.setEditor(es, new File(dir.getParentFile(), "cmd"), why == null && ms != null && live);
        String st = why != null ? why                          // 0.22.0: say something only when it matters
                : join(live ? null : "Last known",
                       gambitsView.editorOn() ? "Tap a gambit to change it" : null,
                       reference == null ? "Names need the game data (see INSTALL)" : null);
        gambitsView.show(ms, reference, st, live);
    }

    /** Reads atlas/index.json at most every ATLAS_RETRY_MS until it parses. */
    void loadAtlas() {
        if (atlas != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - atlasCheckedMono < ATLAS_RETRY_MS) return;
        atlasCheckedMono = now;
        File f = new File(atlasDir, "index.json");
        if (!f.isFile()) { atlasProblem = "map atlas not installed on this device"; return; }
        byte[] data = DataFiles.readBytes(f, Atlas.MAX_INDEX_BYTES);
        atlas = data == null ? null : Atlas.parse(new String(data, StandardCharsets.UTF_8));
        if (atlas == null) { atlasProblem = "map atlas index unreadable"; return; }
        byte[] reg = DataFiles.readBytes(new File(atlasDir, "regions.json"), Regions.MAX_BYTES);
        regions = reg == null ? null : Regions.parse(new String(reg, StandardCharsets.UTF_8));
        symbolArt = regions != null ? symbolArt(regions) : null;
        applySymbolOption();
    }

    /** 0.13.0: the map symbol pictures, each only when its sha256 matches regions.json; null when there are none. */
    Map<Integer, Bitmap> symbolArt(Regions r) {
        if (r.symbols.isEmpty()) return null;
        Map<Integer, Bitmap> out = new java.util.HashMap<>();
        for (Regions.Symbol s : r.symbols.values()) {
            byte[] data = DataFiles.readBytes(new File(atlasDir, s.file), 256 * 1024);
            if (data == null || !s.sha256.equals(DataFiles.sha256(data))) continue;
            Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (b != null && b.getWidth() == s.width && b.getHeight() == s.height) out.put(s.type, b);
        }
        return out;
    }

    /**
     * The sheet's mask at 1/MASK_SAMPLE size, only when its sha256 and size match the atlas index. Successful
     * decodes are cached (LRU, MASK_CACHE sheets); failures are retried on later polls.
     */
    Bitmap mask(Atlas.Sheet sh) {
        Bitmap cached = masks.get(sh.file);
        if (cached != null) return cached;
        maskProblem = null;
        byte[] data = DataFiles.readBytes(new File(atlasDir, sh.file), MAX_MASK_BYTES);
        if (data == null) { maskProblem = "map image missing from the atlas"; return null; }
        if (!sh.sha256.equals(DataFiles.sha256(data))) { maskProblem = "map image does not match the atlas index"; return null; }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = MASK_SAMPLE;
        Bitmap b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (b == null || b.getWidth() != sh.width / MASK_SAMPLE || b.getHeight() != sh.height / MASK_SAMPLE) {
            maskProblem = "map image has the wrong size";
            return null;
        }
        masks.put(sh.file, b);
        return b;
    }
}
