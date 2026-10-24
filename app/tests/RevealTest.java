package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import local.thor.ffxiicompanion.Reveal.Decision;
import local.thor.ffxiicompanion.Reveal.Mode;

/**
 * Map-fog gate tests on the synthetic fixtures and synthetic atlas from gen_fixtures.py, plus the bit test
 * against records captured on the Thor (2026-10-21) with tile counts computed independently by
 * draft/tools/reveal_table_report.py.
 */
public final class RevealTest {
    static Path dir;
    static Atlas atlas;
    static Regions regions;
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    static Snapshot fx(String name) throws Exception {
        Snapshot s = Snapshot.parse(new String(Files.readAllBytes(dir.resolve(name + ".json")), StandardCharsets.UTF_8), 1000);
        check(s != null, "fixture parses: " + name);
        return s;
    }

    static Decision d(String name, boolean fixtureMode) throws Exception { return Reveal.decide(fx(name), atlas, fixtureMode); }

    static void hidden(String name, boolean fixtureMode, String reasonPart) throws Exception {
        Decision x = d(name, fixtureMode);
        check(x.mode == Mode.HIDDEN && x.show == null, name + " hidden, got " + x.mode);
        check(x.reason != null && x.reason.contains(reasonPart), name + " reason '" + x.reason + "' lacks '" + reasonPart + "'");
        ok(name + (fixtureMode ? "" : " (live mode)") + ": hidden, " + x.reason);
    }

    public static void main(String[] args) throws Exception {
        dir = Paths.get(args.length > 0 ? args[0] : "tests/fixtures");
        atlas = Atlas.parse(new String(Files.readAllBytes(dir.resolve("atlas/index.json")), StandardCharsets.UTF_8));
        check(atlas != null && atlas.sheetCount == 7, "synthetic atlas parses");
        regions = Regions.parse(new String(Files.readAllBytes(dir.resolve("atlas/regions.json")), StandardCharsets.UTF_8));
        check(regions != null, "synthetic regions parse");

        { // Bit test on live Thor records; expected counts are the records' popcounts (all 16 rows fit the record at the game stride).
            byte[] r585 = Reveal.parseRecord("7c00fc00fc01ff03ff03ff03ff01ff00ff00ff00ff007f003c00000000000000");
            byte[] r581 = Reveal.parseRecord("fffffffffffffffffffff0600000000000000000000000000000000000000000");
            check(count(r585, 1024, 1024) == 100 && count(r581, 512, 1024) == 86, "revealed tile counts at game stride");
            check(Reveal.strideFor(512) == 1 && Reveal.strideFor(1024) == 2 && Reveal.strideFor(2048) == 4, "stride rule");
            check(Reveal.cell(r585, 2, 0, 16, 16, 2) && !Reveal.cell(r585, 1, 0, 16, 16, 2), "LSB = leftmost tile");
            check(!Reveal.cell(r585, 16, 0, 16, 16, 2) && !Reveal.cell(r585, -1, 0, 16, 16, 2), "bounds checked");
            check(Reveal.parseRecord("00") == null && Reveal.parseRecord("zz".repeat(32)) == null, "bad hex rejected");
            ok("bit test and stride match the live Thor records");
        }
        { // Fixture mode, 1024 px, left 8 columns revealed in every row.
            Decision x = d("reveal_1024_left_half", true);
            check(x.mode == Mode.GATED && x.sheet.mapId == 585, "gated 585");
            check(x.show[0][7] && !x.show[0][8] && x.show[15][0] && !x.show[15][15], "left half only");
            check(x.drawnTiles == 256 && x.shownTiles == 128, "counts " + x.drawnTiles + "/" + x.shownTiles);
            check(x.visible[0][7] && !x.visible[0][8], "visible = shown and drawn");
            ok("1024-px sheet gated at stride 2");
        }
        {
            Decision x = d("reveal_512_top_rows", true);
            check(x.mode == Mode.GATED && x.shownTiles == 32 && x.show[3][7] && !x.show[4][0], "512 top rows");
            ok("512-px sheet gated at stride 1");
        }
        hidden("reveal_1024_left_half", false, "synthetic");
        {
            Decision x = d("reveal_live_shape", false);
            check(x.mode == Mode.GATED && x.shownTiles == 128, "non-synthetic with verified build + capability passes the gate");
            ok("live-shaped snapshot (verified build, capability on): shown");
        }
        hidden("reveal_live_shape_wrong_build", false, "build not verified");
        hidden("reveal_live_shape_no_cap", false, "does not offer");
        hidden("reveal_unstable", true, "changing");
        hidden("reveal_2048", true, "2048-px");
        hidden("reveal_multisheet", true, "multi-level");
        hidden("reveal_other_map", true, "different area");
        hidden("reveal_bad_record", true, "malformed");
        hidden("reveal_unknown_contract", true, "unknown map fog format");
        hidden("reveal_no_atlas_entry", true, "no menu map");
        hidden("location_only", true, "older than 0.4.0");
        {
            Decision x = Reveal.decide(fx("reveal_1024_left_half"), null, true);
            check(x.mode == Mode.HIDDEN && x.reason.contains("atlas not installed"), "no atlas");
            ok("no atlas on device: hidden");
        }
        {
            Decision x = d("reveal_bundle_full", true);
            check(x.mode == Mode.FULL && x.shownTiles == 256 && x.reason.contains("Bountiful Bundle"), "bundle full reveal");
            ok("Bundle fullyRevealedMaps=true in observed config: whole sheet, labelled");
        }
        check(Atlas.parse("{\"format\":\"other\",\"sheets\":[]}") == null && Atlas.parse("{") == null, "bad atlas index");
        check(Atlas.parse("{\"format\":\"ffxii-companion-atlas-1\",\"sheets\":[{\"mapId\":1,\"width\":512,\"height\":512,"
                + "\"stride\":1,\"sheetsForMap\":1,\"file\":\"../x.png\",\"sha256\":\"" + "0".repeat(64) + "\",\"tiles\":"
                + "[\"########\",\"########\",\"########\",\"########\",\"########\",\"########\",\"########\",\"########\"]}]}") == null,
                "path traversal file name rejected");
        ok("malformed atlas index and unsafe file names rejected");
        { // Region view: unvisited neighbour (588, no table record) hidden; half-explored 578 gated; links only between shown.
            Snapshot s = fx("region_table");
            Decision cur = Reveal.decide(s, atlas, true);
            Regions.View rv = regions.build(cur, s, atlas);
            check(rv.note == null && rv.neighbours == 2 && rv.parts.size() == 3, "two neighbours, note " + rv.note);
            Regions.Part p578 = null;
            for (Regions.Part p : rv.parts) {
                check(p.sheet.mapId != 588, "unvisited 588 not drawn");
                if (p.sheet.mapId == 578) p578 = p;
                if (p.sheet.mapId == 581) check(p.focus && p.x == 320 && p.y == 417, "focus placed at its lmap point");
            }
            check(p578 != null && p578.visible[7][0] && !p578.visible[8][0] && p578.x == 416, "578 gated by its own record");
            check(rv.links.size() == 3, "links: 585-581, 578-581 and 585's exit line, got " + rv.links.size());
            ok("region view: visited neighbours gated by their records, unvisited hidden, links between shown areas only");
        }
        { // 0.12.0 exit labels: only where explored; malformed entries dropped; old packs simply have none
            Snapshot s = fx("region_table");
            Regions.View rv = regions.build(Reveal.decide(s, atlas, true), s, atlas);
            check(rv.exits.size() == 1 && rv.exits.get(0).text.equals("To Synthetic East") && rv.exits.get(0).lx == 575,
                    "one explored exit label, got " + rv.exits.size());
            check("synthetic".equals(rv.region), "view names its region");
            Regions old = Regions.parse("{\"format\":\"ffxii-companion-regions-1\",\"unitsPerSheetPx\":0.25,\"regions\":[{\"region\":"
                    + "\"synthetic\",\"sheets\":[{\"mapId\":581,\"sheet\":0,\"file\":\"581_synthetic_0.png\",\"x\":320,\"y\":417}],\"links\":[]}]}");
            check(old != null && old.build(Reveal.decide(s, atlas, true), s, atlas).exits.isEmpty(), "pre-0.12 regions file: no labels");
            check(Regions.nearestMap(regions.regionOf("578_synthetic_0.png").sheets, atlas, 560, 500) == 578
                    && Regions.nearestMap(regions.regionOf("578_synthetic_0.png").sheets, atlas, 100, 60) == 588,
                    "an exit belongs to the nearest sheet");
            java.util.List<Regions.Link> lines = java.util.Arrays.asList(new Regions.Link(585, 585, 348, 188, 348, 140),
                    new Regions.Link(578, 578, 500, 300, 520, 300), new Regions.Link(581, 581, 10, 10, 11, 11),
                    new Regions.Link(585, 581, 348, 120, 348, 120));
            check(Regions.lineOwner(lines, 350, 125) == 585, "exit at the end of 585's line belongs to 585");
            check(Regions.lineOwner(lines, 348, 300) == null, "no line within 30 units: fall back");
            check(Regions.lineOwner(java.util.Arrays.asList(new Regions.Link(1, 1, 0, 0, 0, 10), new Regions.Link(2, 2, 20, 0, 20, 10)),
                    10, 5) == null, "two lines equally close: fall back");
            Regions.Part p = new Regions.Part();
            p.x = 100; p.y = 200;
            p.visible = new boolean[4][4];
            p.visible[1][2] = true;                    // tile (2, 1) = layout x 132..148, y 216..232
            java.util.List<Regions.Part> one = java.util.Collections.singletonList(p);
            float[] b = Regions.bounds(one, false);
            check(b != null && b[0] == 132 && b[1] == 216 && b[2] == 148 && b[3] == 232, "bounds of drawn tiles");
            check(Regions.bounds(one, true) == null, "focus-only bounds ignore other parts");
            float[] all = Regions.bounds(rv.parts, false), cur = Regions.bounds(rv.parts, true);
            check(all[0] <= cur[0] && all[1] <= cur[1] && all[2] >= cur[2] && all[3] >= cur[3]
                    && (all[2] - all[0]) * (all[3] - all[1]) > (cur[2] - cur[0]) * (cur[3] - cur[1]), "region fit covers more than the area");
            // 0.16.0: a fresh area (nothing explored) fits the region around it, marker included, instead of drawing nothing
            Regions.Part fresh = new Regions.Part();
            fresh.focus = true; fresh.x = 300; fresh.y = 40; fresh.visible = new boolean[4][4];
            java.util.List<Regions.Part> two = java.util.Arrays.asList(p, fresh);
            float[] f = Regions.fit(two, true, 40f, 80f);                  // marker at layout (310, 60)
            check(f != null && f[0] == 132 && f[1] == 60 && f[2] == 310 && f[3] == 232, "fresh area: region plus marker");
            check(Regions.fit(two, true, null, null)[2] == 148, "fresh area, no marker: the region");
            fresh.visible[0][0] = true;
            check(Regions.fit(two, true, 40f, 80f)[2] == 316, "explored area: its own tiles only, as before");
            fresh.visible[0][0] = false;
            float[] lone = Regions.fit(java.util.Collections.singletonList(fresh), true, 40f, 80f);
            check(lone[0] == 246 && lone[1] == -4 && lone[2] == 374 && lone[3] == 124 &&Regions.fit(java.util.Collections.singletonList(new Regions.Part()), true, null, null) == null,
                    "nothing to fit: null, so nothing is drawn");
            ok("exit labels only beside areas the player has visited; old packs have none; full-map fit covers every shown area");
        }
        { // 0.13.0 map symbols: without live flags only always-gated symbols on explored ground; bad entries dropped
            Snapshot s = fx("region_table");
            Regions.View rv = regions.build(Reveal.decide(s, atlas, true), s, atlas);
            check(rv.symbols.size() == 1 && rv.symbols.get(0).type == 1 && rv.symbols.get(0).x == 426 && rv.symbols.get(0).y == 348 && rv.symbols.get(0).sx == 424 && rv.symbols.get(0).sy == 336,
                    "one symbol at 578 + display point, got " + rv.symbols.size());
            check(regions.symbols.size() == 2 && !regions.symbols.containsKey(3), "symbol pictures only from icons/type_NN.png");
            check(regions.thresholds != null && regions.thresholds.length == 2 && regions.thresholds[0] == null
                    && regions.thresholds[1][0] == 0x8005, "threshold rows");
            check(rv.dimmed.isEmpty(), "no live flags: no exit is dimmed");
            ok("map symbols: only where explored and only when the game's gate is open; unknown flags show always-gated ones only");
        }
        {
            Snapshot s = fx("region_no_table");
            Regions.View rv = regions.build(Reveal.decide(s, atlas, true), s, atlas);
            check(rv.parts.size() == 1 && rv.note.contains("0.4.1"), "no table: current area only");
            s = fx("region_table_unstable");
            rv = regions.build(Reveal.decide(s, atlas, true), s, atlas);
            check(rv.parts.size() == 1 && rv.note.contains("0.4.1"), "unstable table: current area only");
            s = fx("region_slot_mismatch");
            rv = regions.build(Reveal.decide(s, atlas, true), s, atlas);
            check(rv.parts.size() == 1 && rv.note.contains("does not match"), "live slot != atlas slot: current only");
            ok("region view falls back to the current area: no/unstable table, atlas slot mismatch");
        }
        {
            Snapshot s = fx("region_bundle_full");
            Regions.View rv = regions.build(Reveal.decide(s, atlas, true), s, atlas);
            check(rv.parts.size() == 4 && rv.links.size() == 4, "Bundle full reveal: all four areas, all four links");
            ok("Bundle fullyRevealedMaps: whole region, as the game shows it");
        }
        { // Position marker: sheet px = 4 * (x - x0), 4 * (z - z0); spot A of the Thor test -> (350.6, 112.2)
            Snapshot s = fx("pos_ok");
            Position.Marker m = Position.locate(s, Reveal.decide(s, atlas, true).sheet, true);
            check(m.reason == null && Math.abs(m.px - 350.56f) < 0.05f && Math.abs(m.py - 112.25f) < 0.05f,
                    "spot A marker " + m.px + "," + m.py + " " + m.reason);
            ok("position marker: 4 px per unit from the NPS2 origin (Thor spot A values)");
        }
        for (String[] c : new String[][] {{"pos_origin_mismatch", "does not match this map sheet"}, {"pos_inactive", "not active"},
                {"pos_outside", "outside this map sheet"}, {"location_only", "older than 0.4.2"}}) {
            Snapshot s = fx(c[0]);
            Atlas.Sheet sh = atlas.sheets(581).get(0);
            Position.Marker m = Position.locate(s, sh, true);
            check(m.reason != null && m.reason.contains(c[1]), c[0] + ": " + m.reason);
        }
        ok("no marker: origin mismatch, game map inactive, outside the sheet, old script");
        {
            Snapshot s = fx("pos_live_shape");
            Position.Marker m = Position.locate(s, atlas.sheets(581).get(0), false);
            check(m.reason == null && Math.abs(m.px - 219.07f) < 0.05f, "live-shaped spot B marker " + m.reason);
            m = Position.locate(fx("pos_live_no_cap"), atlas.sheets(581).get(0), false);
            check(m.reason != null && m.reason.contains("does not offer"), "no capability -> no marker");
            m = Position.locate(fx("pos_ok"), atlas.sheets(581).get(0), false);
            check(m.reason != null && m.reason.contains("synthetic"), "synthetic outside fixture mode -> no marker");
            ok("live-shaped position passes; missing capability and synthetic-outside-fixture rejected");
        }
        check(Regions.parse("{\"format\":\"ffxii-companion-regions-1\",\"unitsPerSheetPx\":0.5,\"regions\":[]}") == null,
                "unverified layout scale rejected");
        ok("regions file with an unverified scale rejected");
        if (args.length > 1 && Files.isRegularFile(Paths.get(args[1]))) {  // optional: the real PC-built atlas index
            java.nio.file.Path rec = Paths.get(args[1]).resolveSibling("files.json");   // atlas 2 receipt, if any
            AtlasFiles files = Files.isRegularFile(rec)
                    ? AtlasFiles.parse(new String(Files.readAllBytes(rec), StandardCharsets.UTF_8)) : null;
            Atlas real = Atlas.parse(new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8), files);
            check(real != null && real.sheetCount == 390, "real atlas: 390 sheets");
            check(real.sheets(585).size() == 1 && real.sheets(585).get(0).width == 1024, "585 single 1024-px sheet");
            check(real.sheets(581).size() == 1 && real.sheets(581).get(0).width == 512, "581 single 512-px sheet");
            check(real.sheets(318).size() == 2, "318 is multi-level");
            ok("real atlas index parses (390 sheets; 585/581/318 as expected)");
        } else {
            System.out.println("SKIP real atlas index check (pass its path as the 2nd argument)");
        }
        System.out.println(passed + " reveal checks passed (synthetic fixtures + live-record bit test)");
    }

    static int count(byte[] r, int w, int h) {
        int n = 0, cols = w / 64, rows = h / 64, st = Reveal.strideFor(w);
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) if (Reveal.cell(r, x, y, cols, rows, st)) n++;
        return n;
    }
}
