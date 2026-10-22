package local.thor.ffxiicompanion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Bottom-screen receiver for the FFXII Thor companion (read-only).
 * Polls the two A/B snapshot files that ThorCompanion.lua writes to Wine D:\FFXII_companion\live
 * (= /sdcard/Download/FFXII_companion/live) and shows the newest valid one. Selection, session and
 * freshness rules live in ReceiverState.
 *
 * Map fog (0.5.0): draws the current area's menu map from the local atlas (Download/FFXII_companion/atlas,
 * built on the PC from the user's own game files) only where Reveal's verified-data gate allows it.
 *
 * Reference (0.8.0): Gear and Bestiary tabs browse Download/FFXII_companion/reference/reference.json, built on the
 * PC from files the user's mods write (Reference). Always labelled REFERENCE, never live. Fixture mode may point it
 * elsewhere with "referenceDir"; test renders may open a tab with the string extra "tab" (gambits | gear | bestiary).
 *
 * Gambits (0.9.0): read-only live gambit lists from data.gambits (Gambits); action names via the reference bundle.
 * Gambit editor (0.10.0): when data.gambitEdit reports the game script armed, a slot can be switched ON/OFF (GambitEdit).
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
    TextView mapChip, mapNote;
    RevealMapView mapView;
    File atlasDir = ATLAS_DIR;
    Atlas atlas;
    String atlasProblem = "map atlas not loaded yet";
    long atlasCheckedMono = -ATLAS_RETRY_MS;
    Regions regions;
    File referenceDir = REFERENCE_DIR;
    Reference reference;
    String referenceProblem;
    int[][] liveGear;       // 0.18.0: equipped IDs from the live build (verified build, live session only)
    String liveGearKey;
    Build liveGearBuild;    // 0.19.0: the build behind liveGear (licences, equipment stock) for Optimize suggestions
    EquipTable equipTable;  // 0.19.0: live/equipment.json from logger 0.5.12, loaded once per change of the file
    TextView[] tabs;
    ScrollView liveScroll;
    ReferenceView gearView, bestiaryView;
    LicencesView licencesView;           // 0.21.0
    LicenceBoards boardsCache;           // boards.json, loaded once
    String boardsProblem;
    GambitsView gambitsView;
    final GambitEdit.Seen gambitsSeen = new GambitEdit.Seen();
    long licenceNodesMtime = Long.MIN_VALUE;
    LicenceNodes licenceNodesCache;
    TextView referenceMissing;
    int tab;
    final Map<String, Bitmap> masks = new java.util.LinkedHashMap<String, Bitmap>(MASK_CACHE, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Bitmap> e) { return size() > MASK_CACHE; }
    };
    String maskProblem;
    boolean detailsOpen;
    String detailsSummary = "";
    Button grant;
    File dir = LIVE_DIR;
    /** Synthetic test data is only accepted by the JVM tests; the app itself always treats it as untrusted. */
    final boolean fixtureMode = false;
    ReceiverState state;
    final AutoClosePolicy autoClose = new AutoClosePolicy();
    boolean installSynced;

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
        body.addView(locationCard, cardParams(-1, 0));

        // Map: explored parts of the current area only (verified-data gate in Reveal). No position marker.
        mapCard = cardShell();
        LinearLayout mapHead = new LinearLayout(this);
        mapHead.setGravity(Gravity.CENTER_VERTICAL);
        mapHead.addView(cardHeading("MAP"), new LinearLayout.LayoutParams(0, -2, 1));
        mapChip = chip();
        mapHead.addView(mapChip);
        mapCard.addView(mapHead);
        mapView = new RevealMapView(this, PARCHMENT, PARCHMENT_DIM, MAP_LINK);
        mapView.setVisibility(View.GONE);
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
        byte[] data = readBytes(new File(referenceDir, "reference.json"), Reference.MAX_BYTES);
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
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Rounded background; panels (CARD) and highlights (ACCENT) get the 0.21.0 FFXII-style treatment. */
    android.graphics.drawable.Drawable round(int color, int radiusDp) {
        if (color == CARD) return Theme.panel(dp(radiusDp), dp(1));
        if (color == ACCENT) return Theme.selected(dp(radiusDp), dp(1));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radiusDp));
        return bg;
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
            slots[i] = Snapshot.parse(readText(f), f.lastModified());
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

    static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), Snapshot.MAX_BYTES + 1)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
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
        switch (v.transport) {
            case NO_DATA:
                setStatus("Waiting", DIM, "Start FFXII to begin. First time? Run install.cmd from Download/FFXII_companion/install"
                        + " in the game's GameNative container (see README.txt there)." + bad);
                break;
            case GAME_CLOSED: {
                Map<String, Object> sess = Json.obj(v.current.data, "session");
                String type = sess != null && Json.str(sess, "exitType") != null ? Json.str(sess, "exitType") : "exit";
                setStatus("Game closed", DIM, "Last played " + ago(v.transportAgeMs) + "." + bad);
                break;
            }
            case NO_SIGNAL:
                setStatus("No signal", WARN, "Showing the last known state." + bad);
                break;
            case STALE:
                setStatus("Paused", WARN, "No update for " + secs(v.transportAgeMs) + " (paused, loading or closed)." + bad);
                break;
            default: {
                Map<String, Object> hello = v.current != null ? v.current.hello() : null;
                String hint = Place.updateHint(hello != null ? Json.str(Json.obj(hello, "producer"), "version") : null, appVersion());
                setStatus("Live", GOOD, ((hint != null ? hint + " " : "") + bad).trim());
            }
        }

        showParty(v);
        showLocation(v);
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
        showSession(v.current, hello);
        showBuild(hello);
        showMods(hello);
        showCaps(hello);
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

    private long areasStamp = -1;

    /** Area names from the user's data pack (reference/areas.json), re-read only when the file changes. */
    void loadAreas() {
        File f = new File(referenceDir, "areas.json");
        long s = f.isFile() ? f.lastModified() : 0;
        if (s == areasStamp) return;
        areasStamp = s;
        byte[] d = s == 0 ? null : readBytes(f, 256 * 1024);
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
        mapView.set(drawable, rv.links, masks, MASK_SAMPLE);
        Position.Marker mk = Position.locate(v.location, d.sheet, fixtureMode);
        boolean live = v.locationFamily.state == ReceiverState.FamilyState.LIVE;
        mapView.setMarker(mk.reason == null ? mk.px : null, mk.reason == null ? mk.py : null, live);
        mapNote.setText("");                                   // 0.22.0: the map speaks for itself
        mapNote.setVisibility(View.GONE);
    }

    ReceiverState.View lastView;

    static String join(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (p != null && !p.isEmpty()) b.append(b.length() > 0 ? " · " : "").append(p);
        return b.toString();
    }

    /** 0.21.0: boards.json (beside reference.json), loaded once; null with boardsProblem when missing. */
    LicenceBoards boards() {
        if (boardsCache != null) return boardsCache;
        byte[] data = readBytes(new File(referenceDir, "boards.json"), 512 * 1024);
        if (data == null) { boardsProblem = "Game data not installed yet (run the data builder, see INSTALL)"; return null; }
        Object o;
        try { o = Json.parse(new String(data, StandardCharsets.UTF_8)); } catch (RuntimeException e) { o = null; }
        boardsCache = o instanceof Map ? LicenceBoards.parse(Json.obj(java.util.Collections.singletonMap("x", o), "x")) : null;
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
        licencesView.show(b, licenceNodes(), lb, reference, why);
    }

    /** Gear tab: a chip per member with what they have equipped right now (when live), then the reference categories. */
    void bindGear() {
        final Reference r = reference;
        if (r == null) return;
        final int[][] gear = liveGear;
        final Build gb = liveGearBuild;
        final EquipTable et = equipTable();
        final LicenceNodes ln = gb != null ? licenceNodes() : null;
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
        LicenceNodes nodes = build != null ? licenceNodes() : null;
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

    /** The game script's live/licence_nodes.json (next to the snapshots), re-read only when the file changes. */
    LicenceNodes licenceNodes() {
        File f = new File(dir, "licence_nodes.json");
        long mt = f.isFile() ? f.lastModified() : -1;
        if (mt != licenceNodesMtime) {
            licenceNodesMtime = mt;
            byte[] data = mt < 0 ? null : readBytes(f, LicenceNodes.MAX_BYTES);
            Object o = null;
            try { o = data == null ? null : Json.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8)); }
            catch (RuntimeException e) { o = null; }
            licenceNodesCache = o instanceof Map ? LicenceNodes.parse(Json.obj(java.util.Collections.singletonMap("x", o), "x")) : null;
        }
        return licenceNodesCache;
    }

    private long equipTableMtime = -2;

    /** 0.19.0: live/equipment.json (logger 0.5.12), reloaded when the file changes; null when missing or invalid. */
    EquipTable equipTable() {
        File f = new File(dir, "equipment.json");
        long mt = f.isFile() ? f.lastModified() : -1;
        if (mt != equipTableMtime) {
            equipTableMtime = mt;
            byte[] data = mt < 0 ? null : readBytes(f, 256 * 1024);
            Object o = null;
            try { o = data == null ? null : Json.parse(new String(data, java.nio.charset.StandardCharsets.UTF_8)); }
            catch (RuntimeException e) { o = null; }
            equipTable = o instanceof Map ? EquipTable.parse(Json.obj(java.util.Collections.singletonMap("x", o), "x")) : null;
        }
        return equipTable;
    }

    /** Reads atlas/index.json at most every ATLAS_RETRY_MS until it parses. */
    void loadAtlas() {
        if (atlas != null) return;
        long now = SystemClock.elapsedRealtime();
        if (now - atlasCheckedMono < ATLAS_RETRY_MS) return;
        atlasCheckedMono = now;
        File f = new File(atlasDir, "index.json");
        if (!f.isFile()) { atlasProblem = "map atlas not installed on this device"; return; }
        byte[] data = readBytes(f, Atlas.MAX_INDEX_BYTES);
        atlas = data == null ? null : Atlas.parse(new String(data, StandardCharsets.UTF_8));
        if (atlas == null) { atlasProblem = "map atlas index unreadable"; return; }
        byte[] reg = readBytes(new File(atlasDir, "regions.json"), Regions.MAX_BYTES);
        regions = reg == null ? null : Regions.parse(new String(reg, StandardCharsets.UTF_8));
    }

    /**
     * The sheet's mask at 1/MASK_SAMPLE size, only when its sha256 and size match the atlas index. Successful
     * decodes are cached (LRU, MASK_CACHE sheets); failures are retried on later polls.
     */
    Bitmap mask(Atlas.Sheet sh) {
        Bitmap cached = masks.get(sh.file);
        if (cached != null) return cached;
        maskProblem = null;
        byte[] data = readBytes(new File(atlasDir, sh.file), MAX_MASK_BYTES);
        if (data == null) { maskProblem = "map image missing from the atlas"; return null; }
        if (!sh.sha256.equals(sha256(data))) { maskProblem = "map image does not match the atlas index"; return null; }
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

    static byte[] readBytes(File f, int max) {
        if (!f.isFile() || f.length() > max) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return n == buf.length ? buf : null;
        } catch (IOException e) {
            return null;
        }
    }

    static String sha256(byte[] data) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    static String areaName(Map<String, Object> m, String key) {
        return Place.name(Json.lng(m, key));
    }

    void showSession(Snapshot s, Map<String, Object> hello) {
        StringBuilder b = new StringBuilder();
        if (hello != null) {
            String lua = Json.str(Json.obj(hello, "loader"), "lua");
            if (lua != null && lua.startsWith("sol.")) lua = "1.7.x";  // v0.3.0 printed the version object itself
            b.append("Lua Loader: ").append(lua == null || lua.isEmpty() ? "?" : lua).append('\n');
            String p = Json.str(Json.obj(hello, "producer"), "version");
            if (p != null) b.append("Script: ").append(p).append('\n');
        }
        if (s.clockUs != null) {
            long us = s.clockUs;
            b.append("Game clock: ").append(us / 3_600_000_000L).append("h ").append((us / 60_000_000L) % 60).append("m\n");
        }
        b.append("Snapshot #").append(s.seq);
        session.setText(b);
    }

    void showBuild(Map<String, Object> hello) {
        Map<String, Object> bi = Json.obj(hello, "build");
        if (bi == null) { build.setText("—"); return; }
        StringBuilder b = new StringBuilder();
        b.append(Boolean.TRUE.equals(Json.bool(bi, "exeLengthSupported")) ? "Supported Steam exe" : "Unknown exe size").append('\n');
        b.append(Json.obj(bi, "pe") != null ? "Image at 0x120000 (verified)" : "PE header not readable").append('\n');
        Map<String, Object> patches = Json.obj(bi, "patches");
        if (patches != null) {
            String gn = Json.str(patches, "gn.595520.displaymodes");
            b.append("GameNative display fix: ").append(gn != null ? gn : "?");
        }
        build.setText(b);
    }

    void showMods(Map<String, Object> hello) {
        List<Object> arr = Json.arr(hello, "mods");
        if (arr == null) { mods.setText("—"); return; }
        StringBuilder b = new StringBuilder();
        for (Object m : arr) {
            Map<String, Object> fp = Json.obj(m, "file_present");
            if (fp == null || !Boolean.TRUE.equals(Json.bool(fp, "value"))) continue;  // only list what is installed
            b.append("• ").append(prettyMod(Json.str(m, "id")));
            if (Json.obj(m, "config_observed") != null) b.append("  (config)");
            b.append('\n');
        }
        mods.setText(b.length() == 0 ? "None found" : b.toString().trim());
    }

    void showCaps(Map<String, Object> hello) {
        Map<String, Object> c = Json.obj(hello, "capabilities");
        if (c == null) { caps.setText("—"); return; }
        StringBuilder on = new StringBuilder(), off = new StringBuilder();
        for (Map.Entry<String, Object> e : c.entrySet()) {
            String k = e.getKey();
            // The producer may claim party data before this app accepts it (ReceiverState.LIVE_PARTY_ENABLED).
            if ("party".equals(k) && !"off".equals(e.getValue()) && !ReceiverState.LIVE_PARTY_ENABLED) k = "party (not enabled in this app)";
            ("off".equals(e.getValue()) || k.startsWith("party (") ? off : on).append(k).append("  ");
        }
        String text = "Available: " + (on.length() == 0 ? "none" : on.toString().trim());
        if (off.length() > 0) text += "\nNot yet: " + off.toString().trim();
        caps.setText(text);
    }

    static String prettyMod(String id) {
        if (id == null) return "?";
        switch (id) {
            case "insurgents.manifesto": return "The Insurgent's Manifesto";
            case "insurgents.companions": return "The Insurgent's Companions";
            case "insurgents.vision": return "The Insurgent's Vision";
            case "insurgents.bundle": return "The Insurgent's Bountiful Bundle";
            case "insurgents.descinv": return "The Insurgent's Descriptive Inventory";
            case "fehdead.dynequipdesc": return "Dynamic Equipment Description";
            case "lpc.foedexprimer": return "Foedex Clan Primer Text Replacer";
            case "ffgriever.efl": return "External File Loader";
            case "ffgriever.lualoader": return "Lua Script Loader";
            case "ffgriever.tkmalloc": return "tkmalloc";
            case "dualwield.vaan": return "Dual Wield";
            default: return id;
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    static String shortId(String session) {
        return session == null ? "—" : session.substring(0, 8);
    }

    static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        return (s / 3600) + "h " + ((s / 60) % 60) + "m";
    }

    static String ago(long ms) {
        return secs(ms) + " ago";
    }
}
