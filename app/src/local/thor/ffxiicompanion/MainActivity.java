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
import android.widget.Toast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import local.thor.ffxiicompanion.data.CopyJob;


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
    /** 0.16.0: the shared folder the data pack and the game script's raw copy live in. */
    static final File DATA_DIR = new File(Environment.getExternalStorageDirectory(), "Download/FFXII_companion");
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
    /** 0.16.0: the process's one writer of the pack and the copy job (outlives this activity). */
    DataOwner data;
    int seenInstalls, seenFailures;
    TextView dataLine, dataButton;
    LinearLayout dataCard;                       // 0.16.0: only while there's something to say; else one line in Details
    TextView dataQuiet, dataQuietButton;
    LinearLayout dataQuietRow;
    long referenceFailedStamp = -1;              // reference.json's mtime when it last failed to parse (no re-read)
    LinearLayout setupCard, setupRows, liveBody, tabBar;
    long allSetUntil;                           // elapsedRealtime: show "All set" until then, after setup first completes
    boolean bareShown;                          // no file access yet: only the Setup card is shown
    final java.util.Map<View, Integer> bareSaved = new java.util.HashMap<>();
    TextView setupButton;
    SetupFlow.Action setupShown = SetupFlow.Action.NONE;
    boolean setupOpen;
    Map<String, Object> bundledSig;
    Boolean assetsComplete;
    long probedAt = Long.MIN_VALUE / 2;
    boolean probeOk;
    android.widget.ProgressBar dataBar;
    DataStatus.Action dataShown = DataStatus.Action.NONE;
    static final String PACK_HELD = "Finishing a game data update…";
    static final String NO_DATA = "Game data not built yet. See Status → Game data.";
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
        data = DataOwner.get(DATA_DIR, r -> new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            r.run();
        }, "data-owner"));
        seenInstalls = data.installs();
        seenFailures = data.failures();
        setContentView(buildUi());
    }

    /**
     * Focusable only while this is the top resumed activity (the bottom screen has focus, e.g. just after launch): keys
     * go to this display then anyway, and a non-focusable window there makes any key (the Odin settings app injects a
     * Right Shift when it switches the controller's display) wait 5 s for a window and end in "isn't responding". Once
     * the game is on top, back to non-focusable, so a tap here doesn't take the controller from it.
     */
    @Override public void onTopResumedActivityChanged(boolean top) {
        super.onTopResumedActivityChanged(top);
        if (top) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        else getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
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
        statusDetail.setMaxLines(1);                     // 0.16.0: one line; the Setup card carries guidance
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
        tabBar = tabRow;

        ScrollView scroll = new ScrollView(this);
        liveScroll = scroll;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        liveBody = body;
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

        // 0.16.0 Setup: five ordered checks, one action for the first problem (SetupFlow); shown on first run, when a
        // durable problem comes back, or from Details.
        setupCard = cardShell();
        setupCard.addView(cardHeading("SETUP"));
        setupRows = new LinearLayout(this);
        setupRows.setOrientation(LinearLayout.VERTICAL);
        setupCard.addView(setupRows);
        setupButton = pageButton("");
        setupButton.setVisibility(View.GONE);
        setupButton.setOnClickListener(x -> setupAction());
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(-2, -2);
        sbp.topMargin = dp(6);
        setupCard.addView(setupButton, sbp);
        setupCard.setVisibility(View.GONE);
        body.addView(setupCard, cardParams(-1, 0));

        // 0.16.0: Status in two columns on the near-square bottom screen: Location, Targeting and Party on the left,
        // the map as big as the row on the right (tap for the full map, as before).
        LinearLayout statusRow = new LinearLayout(this);
        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        statusRow.addView(leftCol, new LinearLayout.LayoutParams(0, -2, 45));
        statusRow.addView(rightCol, new LinearLayout.LayoutParams(0, -2, 55));
        body.addView(statusRow, new LinearLayout.LayoutParams(-1, -2));

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
        leftCol.addView(locationCard, cardParams(-1, 0));

        // Targeting (0.13.0): the enemy the party's pending actions are aimed at; only shown while there is one.
        targetCard = cardShell();
        targetCard.addView(cardHeading("TARGETING"));
        targetRows = new LinearLayout(this);
        targetRows.setOrientation(LinearLayout.VERTICAL);
        targetCard.addView(targetRows);
        targetCard.setVisibility(View.GONE);
        leftCol.addView(targetCard, cardParams(-1, 0));

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
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, dp(330));   // fixed: no jump when a fight starts
        mlp.topMargin = dp(4);
        mapCard.addView(mapView, mlp);
        mapNote = text("", 12, DIM, false);
        mapNote.setMaxLines(2);
        mapNote.setEllipsize(TextUtils.TruncateAt.END);
        mapNote.setPadding(0, dp(3), 0, 0);
        mapCard.addView(mapNote);
        LinearLayout.LayoutParams mcp = new LinearLayout.LayoutParams(-1, -2);
        mcp.setMargins(dp(2), dp(3), dp(2), dp(3));
        rightCol.addView(mapCard, mcp);

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
        leftCol.addView(partyCard, cardParams(-1, 0));

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

        // 0.16.0: Game data: build it from the game on this device, see where it is, what to do when it stops.
        dataCard = cardShell();
        dataCard.addView(cardHeading("GAME DATA"));
        dataLine = body(dataCard);
        dataBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        dataBar.setMax(1000);
        dataBar.setVisibility(View.GONE);
        dataCard.addView(dataBar, new LinearLayout.LayoutParams(-1, dp(8)));
        dataButton = pageButton("");
        dataButton.setVisibility(View.GONE);
        dataButton.setOnClickListener(x -> dataAction());
        LinearLayout.LayoutParams dbp = new LinearLayout.LayoutParams(-2, -2);
        dbp.topMargin = dp(6);
        dataCard.addView(dataButton, dbp);
        body.addView(dataCard, body.indexOfChild(statusRow), cardParams(-1, 0));   // above the columns while active

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
        dataQuietRow = new LinearLayout(this);
        dataQuietRow.setGravity(Gravity.CENTER_VERTICAL);
        dataQuietRow.setPadding(dp(8), dp(8), dp(8), dp(2));
        dataQuiet = text("", 14, GOOD, false);
        dataQuietRow.addView(dataQuiet, new LinearLayout.LayoutParams(0, -2, 1));
        dataQuietButton = pageButton("Rebuild");
        dataQuietButton.setOnClickListener(x -> dataAction());
        dataQuietRow.addView(dataQuietButton);
        details.addView(dataQuietRow);
        TextView setupLink = text("Setup checks", 14, ACCENT, true);
        setupLink.setPadding(dp(8), dp(8), dp(8), dp(8));
        setupLink.setOnClickListener(x -> { setupOpen = !setupOpen; if (lastView != null) showSetup(lastView); });
        details.addView(setupLink);
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
        Objectives o = data.packReadable() ? objectives.get() : null;
        Flags f = ok ? Flags.parse(Json.obj(s.data, "flags")) : null;
        String hint = o != null && option(OPT_HINT) ? o.hint(f) : null;
        hintLine.setText(hint != null ? "Next: " + hint.replace('\n', ' ') : "");
        hintLine.setVisibility(hint != null ? View.VISIBLE : View.GONE);
        huntsCard.setVisibility(option(OPT_HUNTS) ? View.VISIBLE : View.GONE);
        if (!option(OPT_HUNTS)) return;
        huntsRows.removeAllViews();
        List<Objectives.Entry> js = o != null && ok ? o.journal(Json.obj(s.data, "quests")) : java.util.Collections.emptyList();
        huntsNote.setText(o == null ? "Needs the game data: see Status → Game data."
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
            referenceMissing.setText(referenceProblem == PACK_HELD ? PACK_HELD : NO_DATA);
            referenceMissing.setVisibility(View.VISIBLE);
            return;
        }
        (i == 2 ? gearView : bestiaryView).setVisibility(View.VISIBLE);
    }

    /** 0.16.0: the Game data card, from the copy job, the newest snapshot and DataOwner. */
    void showData(ReceiverState.View v) {
        local.thor.ffxiicompanion.data.CopyJob job = local.thor.ffxiicompanion.data.CopyJob.load(DATA_DIR);
        Map<String, Object> live = v.current != null ? Json.obj(v.current.data, "rawcopy") : null;
        String builder = data.packReadable() ? PackCheck.builder(DATA_DIR) : null;
        DataStatus d = DataStatus.of(job, live, v.transport == ReceiverState.Transport.LIVE, data.building(),
                data.packReadable(), builder, data.failedGen(), data.lastFailure());
        dataLine.setText(d.line);
        dataBar.setVisibility(d.progress >= 0 ? View.VISIBLE : View.GONE);
        if (d.progress >= 0) dataBar.setProgress((int) Math.round(d.progress * 1000));
        dataShown = d.action;
        // Read the pack's names once while idle, not in the first fight's target card (~400 KB parse, cached after).
        if (reference == null && referenceProblem == null && data.packReadable() && v.transport != ReceiverState.Transport.LIVE)
            loadReference();
        String label = DataStatus.label(d.action);
        dataButton.setVisibility(label != null ? View.VISIBLE : View.GONE);
        if (label != null) dataButton.setText(label);
        // 0.16.0: ready data is not news: one line in Details (tap to rebuild) instead of a card on Status.
        // Quiet only when the pack is usable too (its names load); a damaged pack keeps the card and its Rebuild.
        boolean quiet = (d.state == DataStatus.State.READY || d.state == DataStatus.State.PC_PACK) && loadReference() != null;
        if (!bareShown) dataCard.setVisibility(quiet ? View.GONE : View.VISIBLE);
        if (!quiet && (d.state == DataStatus.State.READY || d.state == DataStatus.State.PC_PACK) && referenceProblem != PACK_HELD) {
            dataLine.setText("Some game data can't be read (" + referenceProblem + "). Rebuild it.");
        }
        dataQuietRow.setVisibility(quiet ? View.VISIBLE : View.GONE);
        if (quiet) {
            dataQuiet.setText("\u2713 " + d.line.replaceAll("\\.$", ""));
            dataQuietButton.setVisibility(label != null ? View.VISIBLE : View.GONE);
        }
    }

    /** 0.16.0: the Setup card, from SetupFlow over what the app observes now (SetupInputs). v is null before file access. */
    void showSetup(ReceiverState.View v) {
        SetupInputs.Obs o = new SetupInputs.Obs();
        o.supported = android.os.Build.VERSION.SDK_INT >= 30;
        o.storageGranted = fixtureMode || Environment.isExternalStorageManager();
        long now = SystemClock.elapsedRealtime();
        if (o.storageGranted && now - probedAt > 30_000) {            // a real write + read in the shared folder
            probedAt = now;
            File probe = new File(DATA_DIR, ".probe");
            try {
                java.nio.file.Files.write(probe.toPath(), new byte[] {1});
                probeOk = java.nio.file.Files.readAllBytes(probe.toPath()).length == 1;
            } catch (Exception e) {
                probeOk = false;
            }
            probe.delete();
        }
        o.readable = o.writable = o.storageGranted && probeOk;
        if (assetsComplete == null) {
            try {
                java.util.List<String> have = java.util.Arrays.asList(getAssets().list("install"));
                try (java.io.InputStream in = getAssets().open("install/ThorCompanion-core.sig")) {
                    byte[] sig = new byte[4096];
                    int n = 0, r;
                    while (n < sig.length && (r = in.read(sig, n, sig.length - n)) > 0) n += r;
                    bundledSig = SetupInputs.parseSig(new String(sig, 0, n, StandardCharsets.UTF_8));
                }
                assetsComplete = SetupInputs.assetsComplete(have, bundledSig);
            } catch (Exception e) {
                assetsComplete = false;
            }
        }
        o.assetsComplete = assetsComplete;
        o.installSynced = installSynced;
        o.bundledSig = bundledSig;
        o.loaderStatus = o.storageGranted ? DataFiles.jsonObject(new File(dir, "loader_status.json"), LoaderStatus.MAX_BYTES) : null;
        if (o.storageGranted && o.loaderStatus == null)                          // only matters before the first game start
            o.bootstrap = SetupInputs.bootstrap(DataFiles.readText(new File(INSTALL_DIR, "result.txt"), 4096));
        Snapshot cur = v != null ? v.current : null;
        o.transport = v != null ? v.transport.name() : "NO_DATA";
        o.accepted = cur != null && !cur.synthetic;
        o.synthetic = cur != null && cur.synthetic;
        Map<String, Object> hello = cur != null ? Json.obj(cur.data, "hello") : null;
        o.buildSupported = v != null && v.transport == ReceiverState.Transport.LIVE && hello != null ? Reveal.buildVerified(hello) : null;
        o.snapshotData = cur != null ? cur.data : null;
        DataOwner.PackVerdict pv = data.packVerdict();
        o.packChecked = pv != null;
        o.packPresent = pv != null && pv.present;
        o.packProblem = pv != null ? pv.problem : null;
        o.atlasFormat = pv != null ? pv.atlasFormat : null;
        o.packReadable = data.packReadable();
        o.building = data.building();
        CopyJob job = o.storageGranted ? CopyJob.load(DATA_DIR) : null;
        boolean copying = job != null && (job.phase() == CopyJob.Phase.HEADER || job.phase() == CopyJob.Phase.DATA
                || job.phase() == CopyJob.Phase.CONFIG);
        o.jobId = copying ? job.jobId() : null;
        o.rawcopy = cur != null ? Json.obj(cur.data, "rawcopy") : null;
        android.content.SharedPreferences prefs = getSharedPreferences("setup", MODE_PRIVATE);
        o.completedRevision = prefs.getInt("completedRevision", 0);

        SetupFlow.View view = SetupFlow.evaluate(SetupInputs.build(o));
        if (view.shouldPersistCompletion) {
            prefs.edit().putInt("completedRevision", SetupInputs.REVISION).apply();
            allSetUntil = now + 120_000;                               // a moment of "done" before the card hides
        }
        boolean allSet = view.complete && now < allSetUntil;
        setupCard.setVisibility(view.needsOnboarding || setupOpen || allSet || v == null ? View.VISIBLE : View.GONE);
        if (setupCard.getVisibility() != View.VISIBLE) return;
        setupRows.removeAllViews();
        for (SetupFlow.Step st : view.steps) {
            boolean busy = st.state == SetupFlow.State.MISSING && SetupText.inProgress(st.messageKey);   // work, not a fault
            String mark = st.state == SetupFlow.State.OK ? "\u2713 " : busy ? "\u2026 " : st.state == SetupFlow.State.MISSING ? "! " : "? ";
            int color = st.state == SetupFlow.State.OK ? GOOD : busy ? ACCENT : st.state == SetupFlow.State.MISSING ? BAD : DIM;
            TextView row = text(mark + SetupText.title(st.id) + ": " + SetupText.line(st.messageKey), 14, color, false);
            row.setPadding(0, dp(2), 0, dp(2));
            setupRows.addView(row);
        }
        if (allSet) {
            TextView done = text(SetupText.ALL_SET, 15, GOOD, true);
            done.setPadding(0, dp(6), 0, dp(2));
            setupRows.addView(done);
        }
        setupShown = view.firstBlocker != null ? view.firstBlocker.action : SetupFlow.Action.NONE;
        String label = SetupText.label(setupShown);
        setupButton.setVisibility(label != null ? View.VISIBLE : View.GONE);
        if (label != null) setupButton.setText(label);
    }

    static final String INSTALL_URL = "https://github.com/Cybranfox/ffxii-thor-companion/blob/main/docs/INSTALL.md";
    static final String RELEASES_URL = "https://github.com/Cybranfox/ffxii-thor-companion/releases";

    /** The Setup card's one button. Opens things on the top screen, where the game and the browser belong. */
    void setupAction() {
        android.os.Bundle top = android.app.ActivityOptions.makeBasic()
                .setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY).toBundle();
        try {
            switch (setupShown) {
                case GRANT_STORAGE: grant.performClick(); break;
                case SYNC_INSTALL: installSynced = false; break;              // the next refresh syncs again
                case OPEN_GAME_NATIVE: {
                    try {                                                    // straight into FFXII, as Cocoon does
                        startActivity(startGame(), top);
                    } catch (RuntimeException e) {                           // older GameNative: its library instead
                        Intent i = getPackageManager().getLaunchIntentForPackage("app.gamenative");
                        startActivity(i != null ? i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : web(INSTALL_URL), top);
                    }
                    break;
                }
                case SHOW_SETUP_STEPS:
                case REINSTALL_BOOTSTRAP: startActivity(web(INSTALL_URL), top); break;
                case UPDATE_APP: startActivity(web(RELEASES_URL), top); break;
                case START_BUILD:
                case RETRY_BUILD: data.startCopy("g" + Long.toString(System.currentTimeMillis() / 1000, 36)); break;
                default: break;
            }
        } catch (RuntimeException e) {                                    // no browser, a vanished package, ...
            Toast.makeText(this, "Couldn't open that here: see " + INSTALL_URL, Toast.LENGTH_LONG).show();
        }
    }

    /** FFXII's Steam app id, for GameNative's launch action. */
    static final int FFXII_STEAM_APP_ID = 595520;

    /**
     * GameNative's own game-launch action (IntentLaunchManager: app_id + game_source; the same one Cocoon sends). No
     * container_config: the game starts with its normal settings.
     */
    static Intent startGame() {
        return new Intent("app.gamenative.LAUNCH_GAME")
                .setClassName("app.gamenative", "app.gamenative.MainActivity")
                .putExtra("app_id", FFXII_STEAM_APP_ID)
                .putExtra("game_source", "STEAM")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    static Intent web(String url) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** The card's button: a new copy (Build game data / Try again / Rebuild); DataOwner does the rest. */
    void dataAction() {
        if (dataShown == DataStatus.Action.NONE) return;
        data.startCopy("g" + Long.toString(System.currentTimeMillis() / 1000, 36));
        dataButton.setVisibility(View.GONE);
        dataLine.setText("Starting…");
    }

    /**
     * 0.16.0: what DataOwner did since the last refresh. A new pack went live: reload everything read from the old one,
     * in place. A build failed: say why; the previous pack is still the one in use.
     */
    void dataEvents() {
        if (data.installs() != seenInstalls) {
            seenInstalls = data.installs();
            dataChanged();
            Toast.makeText(this, "Game data updated", Toast.LENGTH_SHORT).show();
        }
        if (data.failures() != seenFailures) {
            seenFailures = data.failures();
            android.util.Log.w("FFXIICompanion", "data build: " + data.lastFailure());
            Toast.makeText(this, data.lastFailure() + ". Using your previous data.", Toast.LENGTH_LONG).show();
        }
    }

    /** A new pack is in place: drop everything read from the old one; screens reload it as they did at start. */
    void dataChanged() {
        atlas = null;
        regions = null;
        symbolArt = null;
        masks.clear();
        atlasProblem = "map atlas not loaded yet";
        atlasCheckedMono = -ATLAS_RETRY_MS;
        boolean hadReference = reference != null || referenceProblem != null;
        reference = null;
        referenceProblem = null;
        boardsCache = null;
        boardsProblem = null;
        areasStamp = -1;
        objectives.reset();
        if (hadReference) loadReference();
        selectTab(tab);                   // the open tab shows the new data, also when it had none before
        if (lastView != null) render(lastView);
    }

    /** Loads and binds reference.json once; null (with referenceProblem) when missing or unreadable. */
    Reference loadReference() {
        if (reference != null) return reference;
        if (!data.packReadable()) { referenceProblem = PACK_HELD; return null; }
        File refFile = new File(referenceDir, "reference.json");
        if (referenceFailedStamp > 0 && refFile.lastModified() == referenceFailedStamp) return null;   // same bad file
        byte[] data = DataFiles.readBytes(refFile, Reference.MAX_BYTES);
        if (data == null) { referenceProblem = "reference.json missing"; return null; }
        reference = Reference.parse(new String(data, StandardCharsets.UTF_8));
        if (reference == null) {
            referenceProblem = "reference.json unreadable";
            referenceFailedStamp = refFile.lastModified();
            return null;
        }
        referenceFailedStamp = -1;
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
            // First run: only the Setup card (its button opens the permission page); the other cards have nothing yet.
            setStatus("Setup", DIM, "Welcome. Allow file access to start: the companion reads the files FFXII writes in Download.");
            showBare(true);
            showSetup(null);
            return;
        }
        showBare(false);
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
        dataEvents();
        render(v);
        showData(v);
        showSetup(v);
        data.tick(v.current != null ? Json.obj(v.current.data, "rawcopy") : null);
        closeAfterExit(v.transport == ReceiverState.Transport.LIVE,
                v.transport == ReceiverState.Transport.GAME_CLOSED, v.synthetic);
    }

    /** Before file access: hide the tabs and every card but Setup; put them back once access is granted. */
    void showBare(boolean bare) {
        if (bare == bareShown) return;
        bareShown = bare;
        tabBar.setVisibility(bare ? View.GONE : View.VISIBLE);
        if (bare) selectTab(0);
        for (int i = 0; i < liveBody.getChildCount(); i++) {
            View c = liveBody.getChildAt(i);
            if (c == setupCard) continue;
            if (bare) {
                bareSaved.put(c, c.getVisibility());
                c.setVisibility(View.GONE);
            } else if (bareSaved.containsKey(c)) {
                c.setVisibility(bareSaved.get(c));
            }
        }
        if (!bare) bareSaved.clear();
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
                setStatus("Waiting", DIM, ("Waiting for FFXII." + bad).trim());
                break;
            case GAME_CLOSED:
                setStatus("Game closed", DIM, "Last played " + Details.secs(v.transportAgeMs) + " ago." + bad);
                break;
            case NO_SIGNAL:
                setStatus("No signal", WARN, "Showing the last known state." + bad);
                break;
            case STALE:
                setStatus("Paused", WARN, "No update for " + Details.secs(v.transportAgeMs) + "." + bad);
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

    /**
     * 0.16.0, for the narrow column: name and HP numbers on one line, the HP bar (amber below half, red below a
     * quarter, grey at KO), then MP. Status chips follow once the game script reports statuses.
     */
    View memberRow(Party.Member m, boolean stale) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(8), dp(3), dp(8), dp(4));
        r.setBackground(round(TRACK, 6));
        r.setAlpha(stale ? 0.55f : 1f);
        boolean ko = m.ko != null ? m.ko : Party.tone(m.hp) == 3;   // the game script's flag wins; HP 0 only without it

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String tags = ("active".equals(m.role) ? "" : " · " + m.role.toUpperCase()) + (ko ? " · KO" : "");
        String name = Party.displayName(m, this::loadReference);
        TextView n = text((m.leader ? "★ " : "") + (name != null ? name : "Unknown") + tags, 16, ko ? DIM : TEXT, true);
        oneLine(n);
        top.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        TextView hp = text(m.hp != null ? num(m.hp.cur) + " / " + num(m.hp.max) : m.hpInvalid ? "HP invalid" : "HP —", 16,
                m.hp != null ? TEXT : DIM, false);
        oneLine(hp);
        top.addView(hp);
        r.addView(top);
        LinearLayout low = new LinearLayout(this);                // HP bar, then MP on the same line
        low.setGravity(Gravity.CENTER_VERTICAL);
        View hb = bar(m.hp, ko ? DIM : toneColor(Party.tone(m.hp), HP));
        hb.setLayoutParams(new LinearLayout.LayoutParams(0, dp(6), 1));
        low.addView(hb);
        TextView mp = text(m.mp != null ? "MP " + num(m.mp.cur) + "/" + num(m.mp.max) : m.mpInvalid ? "MP invalid" : "MP —", 13,
                m.mp != null ? MP : DIM, false);
        oneLine(mp);
        mp.setPadding(dp(8), 0, 0, 0);
        mp.setGravity(Gravity.END);
        mp.setMinWidth(dp(92));                                  // same bar length on every row
        low.addView(mp);
        r.addView(low);
        return r;
    }

    /** A thin bar for a vital; an empty bar stays visible on the darker track. */
    View bar(Party.Vital val, int color) {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackground(round(BG, 3));
        if (val != null && val.cur > 0) {
            View fill = new View(this);
            fill.setBackground(round(color, 3));
            bar.addView(fill, new LinearLayout.LayoutParams(0, -1, val.cur));
        }
        if (val != null && val.max > val.cur) bar.addView(new View(this), new LinearLayout.LayoutParams(0, -1, val.max - val.cur));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(6));
        lp.topMargin = dp(3);
        bar.setLayoutParams(lp);
        return bar;
    }

    static int toneColor(int tone, int fine) {
        switch (tone) {
            case 1: return WARN;
            case 2: case 3: return BAD;
            default: return fine;
        }
    }

    static String num(long v) { return String.format(java.util.Locale.US, "%,d", v); }

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

    /** Element chip colours, in the game's element order (Target.ELEMENTS). */
    static final int[] ELEMENT_COLORS = { 0xFFE0654A, 0xFFE8C547, 0xFF7FD3E8, 0xFFB08A5A, 0xFF5B95E6, 0xFF62C27E,
            0xFFF2ECDA, 0xFFA98BE0 };

    View enemyRow(Target.Enemy e) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(8), dp(4), dp(8), dp(5));
        r.setBackground(round(TRACK, 6));
        Reference.Entry entry = Target.entry(e, this::loadReference);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(entry != null ? entry.name : "Enemy", 16, TEXT, true);
        oneLine(n);
        head.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        TextView lv = text("Lv " + e.level, 13, DIM, false);
        head.addView(lv);
        r.addView(head);
        Party.Vital hpv = new Party.Vital(e.hp, e.maxHp);
        LinearLayout hpLine = new LinearLayout(this);
        hpLine.setGravity(Gravity.CENTER_VERTICAL);
        hpLine.addView(text("HP", 13, DIM, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView hpn = text(num(e.hp) + " / " + num(e.maxHp), 15, e.hp > 0 ? TEXT : DIM, false);
        oneLine(hpn);
        hpLine.addView(hpn);
        r.addView(hpLine);
        r.addView(bar(hpv, e.hp > 0 ? toneColor(Party.tone(hpv), Theme.GOLD) : DIM));
        // Affinities as chips that wrap like words; the words stay (colour alone never carries the meaning).
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        float density = getResources().getDisplayMetrics().density;
        for (Target.Group g : Target.groups(e)) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(g.label).append("  ");
            for (int i = 0; i < Target.ELEMENTS.length; i++) {
                if ((g.mask & (1 << i)) == 0) continue;
                int s0 = sb.length();
                sb.append(Target.ELEMENTS[i]);
                ChipSpan.Style st = g.kind == Target.Group.Kind.ABSORB ? ChipSpan.Style.OUTLINE
                        : g.kind == Target.Group.Kind.IMMUNE ? ChipSpan.Style.STRUCK : ChipSpan.Style.FILL;
                int col = g.kind == Target.Group.Kind.HALF ? (ELEMENT_COLORS[i] & 0x00FFFFFF) | 0x80000000 : ELEMENT_COLORS[i];
                sb.setSpan(new ChipSpan(col, g.kind == Target.Group.Kind.HALF ? TEXT : BG, st, density), s0, sb.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.append(" ");
            }
        }
        TextView a = text("", 14, TEXT, false);
        a.setText(sb.length() > 0 ? sb : "No elemental weaknesses");
        if (sb.length() == 0) a.setTextColor(DIM);
        a.setLineSpacing(dp(4), 1f);
        a.setPadding(0, dp(5), 0, 0);
        r.addView(a);
        StringBuilder by = new StringBuilder();
        for (int m : e.targetedBy) {
            String who = m >= 0 && m < Gambits.MEMBERS.length ? Gambits.MEMBERS[m] : null;
            if (who == null) {                                   // 0.16.0: a guest, named from the game data
                Reference pack = loadReference();
                who = pack != null ? pack.characterName(m) : null;
            }
            if (who != null) by.append(by.length() > 0 ? " · " : "").append(who);
        }
        if (by.length() > 0) {
            TextView who = text(by.toString(), 13, DIM, false);
            who.setPadding(0, dp(3), 0, 0);
            r.addView(who);
        }
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
        if (!data.packReadable()) return;
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
        Long mapId = Json.lng(m, "mapId");
        // 0.16.0: never a raw map number: names come with the game data; a stale location says it's the last known one.
        boolean noNames = !Place.hasNames() && mapId != null && mapId >= 0 && !Place.title(mapId);
        String areaName = Place.name(mapId);
        if (!noNames && mapId != null && mapId >= 0 && areaName.equals("Map " + mapId)) areaName = "Unknown area";
        locationName.setText(noNames ? "Area names need the game data" : areaName);
        String from = noNames ? "" : Place.from(mapId, Json.lng(m, "previousMapId"));
        if (v.locationFamily.state == ReceiverState.FamilyState.STALE && !Place.loading(mapId))
            from = from.isEmpty() ? "Last known" : "Last known \u00b7 " + from;
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
            Long hid = v.location != null ? Json.lng(v.location.map(), "mapId") : null;
            mapNote.setText(Place.loading(hid) ? "Loading\u2026" : Place.title(hid) ? "Title screen"
                    : atlas == null ? "The map comes with the game data." : "No map for this area.");
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
        if (!data.packReadable()) { boardsProblem = PACK_HELD; return null; }
        File f = new File(referenceDir, "boards.json");
        if (!f.isFile()) { boardsProblem = NO_DATA; return null; }
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
        int liveWeak = 0;                                   // 0.16.0: what the party is fighting right now
        if (build != null) for (Target.Enemy e : Target.enemies(Json.obj(s.data, "target"))) liveWeak |= e.weak;
        gambitsView.setPlans(ms != null ? GambitPlan.suggest(build, nodes, reference, ms, build != null ? boards() : null, liveWeak) : null);
        GambitEdit.State es = s != null ? GambitEdit.parse(Json.obj(s.data, "gambitEdit")) : null;
        gambitsView.setEditor(es, new File(dir.getParentFile(), "cmd"), why == null && ms != null && live);
        String st = why != null ? why                          // 0.22.0: say something only when it matters
                : join(live ? null : "Last known",
                       gambitsView.editorOn() ? "Tap a gambit to change it" : null,
                       reference == null ? "Names need the game data: see Status → Game data" : null);
        gambitsView.show(ms, reference, st, live);
    }

    /**
     * Reads atlas/index.json at most every ATLAS_RETRY_MS until it parses. Atlas 2 (0.15.0) also needs
     * atlas/files.json, and index.json and regions.json must be the same version (one builder run).
     */
    void loadAtlas() {
        if (atlas != null) return;
        if (!data.packReadable()) { atlasProblem = PACK_HELD; return; }
        long now = SystemClock.elapsedRealtime();
        if (now - atlasCheckedMono < ATLAS_RETRY_MS) return;
        atlasCheckedMono = now;
        File f = new File(atlasDir, "index.json");
        if (!f.isFile()) { atlasProblem = "map atlas not installed on this device"; return; }
        byte[] data = DataFiles.readBytes(f, Atlas.MAX_INDEX_BYTES);
        byte[] rec = DataFiles.readBytes(new File(atlasDir, "files.json"), AtlasFiles.MAX_BYTES);
        AtlasFiles files = rec == null ? null : AtlasFiles.parse(new String(rec, StandardCharsets.UTF_8));
        atlas = data == null ? null : Atlas.parse(new String(data, StandardCharsets.UTF_8), files);
        if (atlas == null) {
            atlasProblem = files == null && data != null && new String(data, StandardCharsets.UTF_8).contains(Atlas.FORMAT_2)
                    ? "map data is incomplete: rebuild it (Status → Game data)"
                    : "map atlas index unreadable";
            return;
        }
        byte[] reg = DataFiles.readBytes(new File(atlasDir, "regions.json"), Regions.MAX_BYTES);
        regions = reg == null ? null : Regions.parse(new String(reg, StandardCharsets.UTF_8), files);
        if (regions != null && regions.version != atlas.version) {
            atlas = null;
            regions = null;
            atlasProblem = "map data is from two different builds: rebuild it (Status → Game data)";
            return;
        }
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
