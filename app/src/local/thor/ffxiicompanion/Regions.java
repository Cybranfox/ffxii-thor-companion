package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Region view: the current area drawn among its region's other explored areas, as the game's own map screen does.
 *
 * Layout (atlas regions.json, format ffxii-companion-regions-1): a sheet's top-left corner sits at its lmap point
 * and 1 layout unit = 4 sheet pixels. Overlaying that on the Thor's Zertinan Caverns region screen put every visited
 * neighbour on the game's ink (evidence/map_assets/region_layout). Like the game, unvisited areas are not drawn:
 * a neighbour needs a non-zero record in data.revealTable (ThorCompanion.lua 0.4.1+), and its tiles are gated by
 * those bits exactly like the current area. The current area itself always comes from Reveal.decide.
 *
 * Exit labels (0.12.0, optional "exits" per region, built by the data builder from menu_mapname.bin): the game draws
 * each label at the exit's second point and only dims it while the route is locked, so labels are no spoiler. The
 * companion still shows a label only next to explored ground: a label shows when the area its exit belongs to is drawn.
 * That area is the one whose line out (a link from an area to itself) clearly ends at the exit (97 of the game's 111
 * exits), otherwise the area whose sheet is nearest.
 *
 * 0.13.0 (optional "icons", "symbols" and "thresholds", built from the user's files; Map_Icons_Flags_2026-10-22.md):
 * an exit's label is dimmed while its story gate is closed, as the game does, and the map symbols are drawn where and
 * when the game draws them (Flags.symbolVisible), each at layout (area x + display x, area y + display y). They are
 * the game's own pictures and the app never names them: what each one means is not established.
 *
 * Atlas 2 (0.15.0, format ffxii-companion-regions-2): symbols carry their pixel hash instead of the PNG's sha256,
 * which comes from atlas/files.json (AtlasFiles), as for the sheets in Atlas.
 */
final class Regions {
    static final String FORMAT = "ffxii-companion-regions-1";
    static final String FORMAT_2 = "ffxii-companion-regions-2";
    static final int MAX_BYTES = 1024 * 1024;

    static final class Placed {
        final int mapId, sheet, x, y;
        final String file;
        Placed(int mapId, int sheet, String file, int x, int y) {
            this.mapId = mapId; this.sheet = sheet; this.file = file; this.x = x; this.y = y;
        }
    }

    static final class Link {
        final int a, b, ax, ay, bx, by;
        Link(int a, int b, int ax, int ay, int bx, int by) {
            this.a = a; this.b = b; this.ax = ax; this.ay = ay; this.bx = bx; this.by = by;
        }
    }

    /** An exit: its point (x, y), where its label goes (lx, ly), all in layout units, the label and its story gate. */
    static final class Exit {
        final int x, y, lx, ly, gate;
        final String text;
        Exit(int x, int y, int lx, int ly, String text, int gate) {
            this.x = x; this.y = y; this.lx = lx; this.ly = ly; this.text = text; this.gate = gate;
        }
    }

    /** A map symbol row (NAVIICN2): its map, group-local index, type, source point (fog), display point, gate. */
    static final class Icon {
        final int mapId, group, index, type, sx, sy, dx, dy, gate;
        Icon(int mapId, int group, int index, int type, int sx, int sy, int dx, int dy, int gate) {
            this.mapId = mapId; this.group = group; this.index = index; this.type = type;
            this.sx = sx; this.sy = sy; this.dx = dx; this.dy = dy; this.gate = gate;
        }
    }

    /**
     * A symbol to draw: its picture type, where (x, y) and the spot it marks (sx, sy), in layout units. The game draws
     * some symbols beside their spot and, in at least one map mode, a line back to it (0x404B7D..0x404BA1).
     */
    static final class Shown {
        final int type;
        final float x, y, sx, sy;
        Shown(int type, float x, float y, float sx, float sy) { this.type = type; this.x = x; this.y = y; this.sx = sx; this.sy = sy; }
    }

    /** A symbol picture in the atlas (icons/type_NN.png). */
    static final class Symbol {
        final int type, width, height;
        final String file, sha256;
        Symbol(int type, String file, int width, int height, String sha256) {
            this.type = type; this.file = file; this.width = width; this.height = height; this.sha256 = sha256;
        }
    }

    static final class Region {
        final String name;
        final List<Placed> sheets;
        final List<Link> links;
        final List<Exit> exits;
        final List<Icon> icons;
        Region(String name, List<Placed> sheets, List<Link> links, List<Exit> exits, List<Icon> icons) {
            this.name = name; this.sheets = sheets; this.links = links; this.exits = exits; this.icons = icons;
        }
    }

    /** One drawable area: atlas sheet, layout position, which tiles to draw. */
    static final class Part {
        Atlas.Sheet sheet;
        int x, y;               // layout units
        boolean[][] visible;    // explored and drawn tiles
        boolean[][] revealed;   // explored tiles, drawn or not (the game's fog cells), for the symbols
        boolean focus;
    }

    /**
     * 0.16.0: the game's fog edge (VA 0x3C4810, Legacy_Audit_2026-10-24.md): an unexplored cell next to explored ones is
     * drawn in the corners that touch them. Bits: 1 top-left, 2 top-right, 4 bottom-left, 8 bottom-right; a corner counts
     * when any of the three cells around it (side, side, diagonal) is explored. 0 = not an edge.
     */
    static int edgeMask(boolean[][] cells, int x, int y) {
        return (cell(cells, x - 1, y - 1) || cell(cells, x, y - 1) || cell(cells, x - 1, y) ? 1 : 0)
                | (cell(cells, x + 1, y - 1) || cell(cells, x + 1, y) || cell(cells, x, y - 1) ? 2 : 0)
                | (cell(cells, x - 1, y + 1) || cell(cells, x, y + 1) || cell(cells, x - 1, y) ? 4 : 0)
                | (cell(cells, x + 1, y + 1) || cell(cells, x, y + 1) || cell(cells, x + 1, y) ? 8 : 0);
    }

    private static boolean cell(boolean[][] c, int x, int y) {
        return y >= 0 && y < c.length && x >= 0 && x < c[y].length && c[y][x];
    }

    /** Alpha of explored quarters and of the game's edge corners, for a soft boundary once smoothly scaled up. */
    static final int FOG_SOLID = 255, FOG_EDGE = 120;

    /**
     * 0.16.0: a part's fog as alpha per quarter tile (2 x 2 per cell, row-major, width 2 x columns): explored cells solid,
     * the edge corners of their unexplored neighbours half, the rest clear. Scaled up with filtering, the explored ground
     * fades out at its edges like the game's map instead of stopping in hard tile steps. Null when the part has no cells.
     */
    static int[] fogAlpha(boolean[][] cells) {
        if (cells == null || cells.length == 0 || cells[0].length == 0) return null;
        int rows = cells.length, cols = cells[0].length, w = cols * 2;
        int[] a = new int[w * rows * 2];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                int m = cell(cells, x, y) ? 15 : edgeMask(cells, x, y);
                int v = cell(cells, x, y) ? FOG_SOLID : FOG_EDGE;
                if ((m & 1) != 0) a[(2 * y) * w + 2 * x] = v;
                if ((m & 2) != 0) a[(2 * y) * w + 2 * x + 1] = v;
                if ((m & 4) != 0) a[(2 * y + 1) * w + 2 * x] = v;
                if ((m & 8) != 0) a[(2 * y + 1) * w + 2 * x + 1] = v;
            }
        }
        return a;
    }

    static final class View {
        final List<Part> parts = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        final List<Exit> exits = new ArrayList<>();
        final java.util.Set<Exit> dimmed = new java.util.HashSet<>();   // labels of locked exits (0.13.0)
        final List<Shown> symbols = new ArrayList<>();
        final List<Shown> targets = new ArrayList<>();       // 0.17.0: a hunt map's markers (group 9), drawn ringed
        boolean targetExplored;                              // the hunt view's map has explored ground
        String region;          // region file name, or null when unknown
        String note;            // why neighbours are missing, or null
        int neighbours;
    }

    final double unitsPerSheetPx;
    private final Map<String, Region> byFile = new HashMap<>();
    final Map<Integer, Symbol> symbols = new HashMap<>();
    int[][] thresholds;
    int version = 1;        // 1 or 2: must match the atlas index

    private Regions(double unitsPerSheetPx) { this.unitsPerSheetPx = unitsPerSheetPx; }

    /** Regions 1 only (no receipt). */
    static Regions parse(String text) { return parse(text, null); }

    /** Null for anything malformed. A v2 file needs the receipt (files.json) for its symbol pictures. */
    static Regions parse(String text, AtlasFiles files) {
        if (text == null || text.isEmpty() || text.length() > MAX_BYTES) return null;
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String format = Json.str(root, "format");
        int version = FORMAT.equals(format) ? 1 : FORMAT_2.equals(format) ? 2 : 0;
        if (version == 0 || (version == 2 && files == null)) return null;
        Object u = root instanceof Map ? ((Map<?, ?>) root).get("unitsPerSheetPx") : null;
        if (!(u instanceof Number) || ((Number) u).doubleValue() != 0.25) return null;   // the verified rule only
        List<Object> raw = Json.arr(root, "regions");
        if (raw == null) return null;
        Regions out = new Regions(0.25);
        out.version = version;
        for (Object r : raw) {
            String name = Json.str(r, "region");
            List<Object> sh = Json.arr(r, "sheets"), ln = Json.arr(r, "links");
            if (name == null || sh == null || ln == null) continue;
            List<Placed> placed = new ArrayList<>();
            for (Object s : sh) {
                Long id = Json.lng(s, "mapId"), idx = Json.lng(s, "sheet"), x = Json.lng(s, "x"), y = Json.lng(s, "y");
                String file = Json.str(s, "file");
                if (id == null || idx == null || x == null || y == null || file == null) continue;
                placed.add(new Placed(id.intValue(), idx.intValue(), file, x.intValue(), y.intValue()));
            }
            List<Link> links = new ArrayList<>();
            for (Object l : ln) {
                Long a = Json.lng(l, "a"), b = Json.lng(l, "b"), ax = Json.lng(l, "ax"), ay = Json.lng(l, "ay");
                Long bx = Json.lng(l, "bx"), by = Json.lng(l, "by");
                if (a == null || b == null || ax == null || ay == null || bx == null || by == null) continue;
                links.add(new Link(a.intValue(), b.intValue(), ax.intValue(), ay.intValue(), bx.intValue(), by.intValue()));
            }
            List<Exit> exits = new ArrayList<>();
            List<Object> ex = Json.arr(r, "exits");   // absent in data packs built before 0.12.0
            if (ex != null) for (Object e : ex) {
                Long x = Json.lng(e, "x"), y = Json.lng(e, "y"), lx = Json.lng(e, "lx"), ly = Json.lng(e, "ly");
                String label = Json.str(e, "text");
                Long gate = Json.lng(e, "gate");
                if (x == null || y == null || lx == null || ly == null || label == null || label.isEmpty() || label.length() > 64) continue;
                exits.add(new Exit(x.intValue(), y.intValue(), lx.intValue(), ly.intValue(), label,
                        gate != null && gate >= 0 && gate <= 0xFFFF ? gate.intValue() : 0xFFFF));
            }
            List<Icon> icons = new ArrayList<>();
            List<Object> ic = Json.arr(r, "icons");   // absent before 0.13.0
            if (ic != null) for (Object o : ic) {
                Long m = Json.lng(o, "mapId"), g = Json.lng(o, "group"), i = Json.lng(o, "index"), ty = Json.lng(o, "type");
                Long sx = Json.lng(o, "sx"), sy = Json.lng(o, "sy"), dx = Json.lng(o, "dx"), dy = Json.lng(o, "dy");
                Long gate = Json.lng(o, "gate");
                if (m == null || g == null || i == null || ty == null || sx == null || sy == null || dx == null || dy == null
                        || gate == null || ty < 1 || ty > 28 || gate < 0 || gate > 0xFFFF) continue;
                icons.add(new Icon(m.intValue(), g.intValue(), i.intValue(), ty.intValue(), sx.intValue(), sy.intValue(),
                        dx.intValue(), dy.intValue(), gate.intValue()));
            }
            Region reg = new Region(name, Collections.unmodifiableList(placed), Collections.unmodifiableList(links),
                    Collections.unmodifiableList(exits), Collections.unmodifiableList(icons));
            for (Placed p : placed) out.byFile.put(p.file, reg);
        }
        if (out.byFile.isEmpty()) return null;
        List<Object> sy = Json.arr(root, "symbols"), th = Json.arr(root, "thresholds");
        if (sy != null && th != null) {
            for (Object o : sy) {
                Long ty = Json.lng(o, "type"), w = Json.lng(o, "width"), h = Json.lng(o, "height");
                String file = Json.str(o, "file"), sha = Json.str(o, "sha256");
                if (version == 2) {
                    String px = Json.str(o, "pixelSha256");
                    if (!"RGBA8".equals(Json.str(o, "pixelEncoding")) || px == null || !px.matches("[0-9a-f]{64}")) continue;
                    sha = file == null ? null : files.sha256(file);
                }
                if (ty == null || w == null || h == null || file == null || sha == null || !file.matches("icons/type_[0-9]{2}\\.png")
                        || w < 1 || h < 1 || w > 256 || h > 256) continue;
                out.symbols.put(ty.intValue(), new Symbol(ty.intValue(), file, w.intValue(), h.intValue(), sha));
            }
            int[][] rows = new int[Math.min(th.size(), 4096)][];
            for (int i = 0; i < rows.length; i++) {
                if (!(th.get(i) instanceof List)) continue;
                List<?> row = (List<?>) th.get(i);
                if (row.size() > 64) continue;
                rows[i] = new int[row.size()];
                for (int k = 0; k < row.size(); k++) {
                    Object v = row.get(k);
                    rows[i][k] = v instanceof Long && (Long) v >= 0 && (Long) v <= 0xFFFF ? ((Long) v).intValue() : 0xFFFF;
                }
            }
            out.thresholds = rows;
        }
        return out;
    }

    Region regionOf(String sheetFile) { return byFile.get(sheetFile); }

    /** 0.17.0: a region by its key ("subKK", the regions.json "region" value), or null. */
    Region regionByKey(String key) {
        for (Region r : byFile.values()) if (r.name.equals(key)) return r;
        return null;
    }

    /** Non-zero records by slot from data.revealTable, or null unless it is an ok, stable ffxii.reveal.v1 table. */
    static Map<Integer, byte[]> table(Snapshot s) {
        Map<String, Object> t = Json.obj(s.data, "revealTable");
        if (t == null || !Reveal.CONTRACT.equals(Json.str(t, "contract")) || !"ok".equals(Json.str(t, "status"))
                || !Boolean.TRUE.equals(Json.bool(t, "stable"))) return null;
        String recs = Json.str(t, "records");
        if (recs == null) return null;
        Map<Integer, byte[]> out = new HashMap<>();
        if (recs.isEmpty()) return out;
        for (String item : recs.split(",")) {
            int c = item.indexOf(':');
            if (c <= 0) return null;
            int slot;
            try {
                slot = Integer.parseInt(item.substring(0, c));
            } catch (NumberFormatException e) {
                return null;
            }
            byte[] r = Reveal.parseRecord(item.substring(c + 1));
            if (r == null || slot < 0 || slot > 0x180) return null;
            out.put(slot, r);
        }
        return out;
    }

    /**
     * Region view for a shown current-area decision. Neighbours obey the same per-sheet rules as Reveal (single
     * sheet, screen-verified width, game stride) plus: visited per the live table, and the atlas slot of the current
     * area must equal the live slot the game reported. Anything unproven is left out, never drawn whole.
     */
    View build(Reveal.Decision current, Snapshot s, Atlas atlas) {
        View v = new View();
        Part focus = new Part();
        focus.sheet = current.sheet;
        focus.visible = current.visible;
        focus.revealed = current.show;
        focus.focus = true;
        Region reg = current.sheet != null ? regionOf(current.sheet.file) : null;
        if (reg == null) {
            v.parts.add(focus);
            v.note = "region layout unknown for this area";
            return v;
        }
        for (Placed p : reg.sheets) if (p.file.equals(current.sheet.file)) { focus.x = p.x; focus.y = p.y; }
        v.parts.add(focus);
        v.region = reg.name;
        boolean full = current.mode == Reveal.Mode.FULL;
        Map<Integer, byte[]> table = full ? null : table(s);
        Long liveSlot = Json.lng(Json.obj(s.data, "reveal"), "slot");
        Integer atlasSlot = atlas.slot(current.sheet);
        if (!full && table == null) { v.note = "other explored areas need game script 0.4.1"; return v; }
        if (!full && (liveSlot == null || atlasSlot == null || liveSlot.intValue() != atlasSlot)) {
            v.note = "map atlas does not match the game's area table";
            return v;
        }
        List<Integer> shownMaps = new ArrayList<>();
        shownMaps.add(current.sheet.mapId);
        for (Placed p : reg.sheets) {
            if (p.file.equals(current.sheet.file)) continue;
            Atlas.Sheet sh = atlas.sheet(p.file);
            if (sh == null || sh.sheetsForMap > 1 || !Reveal.screenVerifiedWidth(sh.width)
                    || sh.stride != Reveal.strideFor(sh.width)) continue;
            byte[] rec = null;
            if (!full) {
                Integer slot = atlas.slot(sh);
                rec = slot == null ? null : table.get(slot);
                if (rec == null) continue;   // unvisited: the game does not draw it either
            }
            int cols = sh.width / Reveal.TILE, rows = sh.height / Reveal.TILE;
            if (rows * sh.stride > Reveal.RECORD_BYTES) continue;
            Part part = new Part();
            part.sheet = sh;
            part.x = p.x;
            part.y = p.y;
            part.visible = new boolean[rows][cols];
            part.revealed = new boolean[rows][cols];
            boolean any = false;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    boolean show = full || Reveal.cell(rec, x, y, cols, rows, sh.stride);
                    part.revealed[y][x] = show;
                    part.visible[y][x] = show && sh.drawn(x, y);
                    any |= part.visible[y][x];
                }
            }
            if (!any) continue;
            v.parts.add(part);
            v.neighbours++;
            shownMaps.add(sh.mapId);
        }
        decorate(v, reg, shownMaps, s, atlas, false);
        return v;
    }

    /**
     * 0.17.0: the region of a hunt's map, as the game's hunt map shows it: that map is the focus (framed even when you
     * haven't explored it, but only explored ground is drawn), the region's other explored areas around it, and the
     * game's hunt marker (NAVIICN2 group 9, rows `markers`) only once that map has explored ground. The saved table is
     * used only when the current area's atlas slot matches the game's, as in build(); the area you're in uses `current`.
     */
    View buildHunt(Reveal.Decision current, int mapId, List<Integer> markers, Snapshot s, Atlas atlas) {
        View v = new View();
        List<Atlas.Sheet> sheets = atlas.sheets(mapId);
        if (sheets.size() != 1 || sheets.get(0).sheetsForMap > 1) { v.note = "This hunt's map isn't in your map data."; return v; }
        Atlas.Sheet target = sheets.get(0);
        Region reg = regionOf(target.file);
        if (reg == null) { v.note = "This hunt's map isn't in your map data."; return v; }
        return huntRegion(v, reg, target, mapId, markers, current, s, atlas);
    }

    /**
     * 0.17.0: a whole region for a hunt whose map names areas, not a point (the region-only hunt): its explored areas,
     * framed together, with no marker and nothing unexplored. The first drawn area carries the focus the view needs.
     */
    View buildOverview(String regionKey, Reveal.Decision current, Snapshot s, Atlas atlas) {
        View v = new View();
        Region reg = regionByKey(regionKey);
        if (reg == null) { v.note = "This region isn't in your map data."; return v; }
        v = huntRegion(v, reg, null, -1, java.util.Collections.<Integer>emptyList(), current, s, atlas);
        if (v.note == null && v.parts.isEmpty()) v.note = "You haven't explored this region yet.";
        return v;
    }

    private View huntRegion(View v, Region reg, Atlas.Sheet target, int mapId, List<Integer> markers, Reveal.Decision current,
                            Snapshot s, Atlas atlas) {
        boolean full = current != null && current.mode == Reveal.Mode.FULL;
        Map<Integer, byte[]> table = full ? null : table(s);
        if (!full) {
            Long liveSlot = Json.lng(Json.obj(s.data, "reveal"), "slot");
            Integer slot = current != null && current.sheet != null ? atlas.slot(current.sheet) : null;
            if (table == null || liveSlot == null || slot == null || liveSlot.intValue() != slot) {
                v.note = "Needs the live map from the game.";
                return v;
            }
        }
        v.region = reg.name;
        List<Integer> shownMaps = new ArrayList<>();
        for (Placed p : reg.sheets) {
            Atlas.Sheet sh = atlas.sheet(p.file);
            if (sh == null || sh.sheetsForMap > 1 || !Reveal.screenVerifiedWidth(sh.width)
                    || sh.stride != Reveal.strideFor(sh.width)) continue;
            boolean isTarget = target != null && sh.file.equals(target.file);
            boolean isCurrent = current != null && current.sheet != null && sh.file.equals(current.sheet.file);
            int cols = sh.width / Reveal.TILE, rows = sh.height / Reveal.TILE;
            if (rows * sh.stride > Reveal.RECORD_BYTES) continue;
            Part part = new Part();
            part.sheet = sh;
            part.x = p.x;
            part.y = p.y;
            part.focus = isTarget;
            if (isCurrent) {
                part.visible = current.visible;
                part.revealed = current.show;
            } else {
                Integer slot = full ? null : atlas.slot(sh);
                byte[] rec = full || slot == null ? null : table.get(slot);
                part.visible = new boolean[rows][cols];
                part.revealed = new boolean[rows][cols];
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        boolean show = full || (rec != null && Reveal.cell(rec, x, y, cols, rows, sh.stride));
                        part.revealed[y][x] = show;
                        part.visible[y][x] = show && sh.drawn(x, y);
                    }
                }
            }
            boolean any = false;
            for (boolean[] row : part.visible) for (boolean c : row) any |= c;
            if (isTarget) v.targetExplored = any;
            else if (!any) continue;
            else v.neighbours++;
            v.parts.add(isTarget ? 0 : v.parts.size(), part);
            if (any) shownMaps.add(sh.mapId);
        }
        if (target == null) {                              // overview: the first explored area holds the focus
            if (v.parts.isEmpty()) return v;
            v.parts.get(0).focus = true;
            decorate(v, reg, shownMaps, s, atlas, true);
            return v;
        }
        if (v.parts.isEmpty() || !v.parts.get(0).focus) { v.parts.clear(); v.note = "This hunt's map isn't in your map data."; return v; }
        decorate(v, reg, shownMaps, s, atlas, true);
        if (v.targetExplored) {
            Flags flags = Flags.parse(Json.obj(s.data, "flags"));
            Part t = v.parts.get(0);
            for (Icon i : reg.icons)
                if (i.mapId == mapId && i.group == 9 && markers.contains(i.index) && exploredIcon(t, i)
                        && Flags.symbolVisible(flags, thresholds, i.group, i.index, i.type, i.sx, i.sy, i.gate, t.revealed))
                    v.targets.add(new Shown(i.type, t.x + i.dx, t.y + i.dy, t.x + i.dx, t.y + i.dy));
        }
        return v;
    }

    // 0.17.0: hunt browsing keeps even fog-bypassing symbols off unexplored ground.
    private static boolean exploredIcon(Part p, Icon i) {
        int x = i.dx / 16, y = i.dy / 16;
        return p.visible != null && cell(p.visible, x, y);
    }

    /** Links, exit labels and symbols for the drawn areas (shared by build and buildHunt). */
    private void decorate(View v, Region reg, List<Integer> shownMaps, Snapshot s, Atlas atlas, boolean hunt) {
        // A link from an area to itself is the line out to one of its exits (all 99 in the game end at an exit point).
        for (Link l : reg.links) if (shownMaps.contains(l.a) && shownMaps.contains(l.b)) v.links.add(l);
        for (Exit e : reg.exits) {
            Integer owner = lineOwner(reg.links, e.x, e.y);
            if (owner == null) owner = nearestMap(reg.sheets, atlas, e.x, e.y);
            if (owner != null && shownMaps.contains(owner)) v.exits.add(e);
        }
        Flags flags = Flags.parse(Json.obj(s.data, "flags"));
        for (Exit e : v.exits) if (Flags.exitDimmed(flags, e.gate, thresholds)) v.dimmed.add(e);
        if (!symbols.isEmpty()) {
            for (Part p : v.parts) {
                if (p.sheet.sheetsForMap > 1) continue;     // a symbol row names a map, not one of its sheets
                for (Icon i : reg.icons) {
                    if (i.mapId != p.sheet.mapId || !symbols.containsKey(i.type) || (hunt && !exploredIcon(p, i))) continue;
                    if (Flags.symbolVisible(flags, thresholds, i.group, i.index, i.type, i.sx, i.sy, i.gate, p.revealed))
                        v.symbols.add(i.group == 9 ? new Shown(i.type, p.x + i.dx, p.y + i.dy, p.x + i.dx, p.y + i.dy)   // no line
                                : new Shown(i.type, p.x + i.dx, p.y + i.dy, p.x + i.sx, p.y + i.sy));
                }
            }
        }
    }

    /** The area whose exit line ends within 30 units of (x, y), when no other exit line ends even half as close. */
    static Integer lineOwner(List<Link> links, int x, int y) {
        double best = Double.MAX_VALUE, second = Double.MAX_VALUE;
        Integer owner = null;
        for (Link l : links) {
            if (l.a != l.b) continue;
            double d = Math.min(Math.hypot(l.ax - x, l.ay - y), Math.hypot(l.bx - x, l.by - y));
            if (d < best) { second = best; best = d; owner = l.a; }
            else if (d < second) second = d;
        }
        return best <= 30 && second > 2 * best ? owner : null;
    }

    /** The map ID whose sheet rectangle (layout units) is nearest to point (x, y); null when no sheet is in the atlas. */
    static Integer nearestMap(List<Placed> sheets, Atlas atlas, int x, int y) {
        Integer best = null;
        double bestD = Double.MAX_VALUE;
        for (Placed p : sheets) {
            Atlas.Sheet sh = atlas.sheet(p.file);
            if (sh == null) continue;
            double w = sh.width / 4.0, h = sh.height / 4.0;
            double dx = Math.max(0, Math.max(p.x - x, x - (p.x + w))), dy = Math.max(0, Math.max(p.y - y, y - (p.y + h)));
            double d = dx * dx + dy * dy;
            if (d < bestD) { bestD = d; best = p.mapId; }
        }
        return best;
    }

    /** Layout-unit bounds {x0, y0, x1, y1} of the drawn tiles of the given parts (all, or only the focus), or null. */
    static float[] bounds(List<Part> parts, boolean focusOnly) {
        float tu = Reveal.TILE / 4f;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (Part p : parts) {
            if (p.visible == null || (focusOnly && !p.focus)) continue;
            for (int y = 0; y < p.visible.length; y++) {
                for (int x = 0; x < p.visible[y].length; x++) {
                    if (!p.visible[y][x]) continue;
                    x0 = Math.min(x0, p.x + x * tu); y0 = Math.min(y0, p.y + y * tu);
                    x1 = Math.max(x1, p.x + (x + 1) * tu); y1 = Math.max(y1, p.y + (y + 1) * tu);
                }
            }
        }
        return x1 < x0 ? null : new float[] { x0, y0, x1, y1 };
    }

    /**
     * 0.16.0: what the map fits. The game saves an area's exploration when you leave it, so the area you're in can have
     * nothing explored yet: an area fit then frames the area's whole sheet (where it will be drawn, with the marker in
     * it and neighbours at the edges) instead of drawing nothing. Null only when nothing at all can be drawn.
     */
    static float[] fit(List<Part> parts, boolean focusOnly, Float markerPx, Float markerPy) {
        float[] b = bounds(parts, focusOnly);
        if (b != null) return b;
        Part focus = null;
        for (Part p : parts) if (p.focus) focus = p;
        if (focusOnly && focus != null && focus.visible != null && focus.visible.length > 0) {
            float tu = Reveal.TILE / 4f;
            b = new float[] { focus.x, focus.y, focus.x + focus.visible[0].length * tu, focus.y + focus.visible.length * tu };
            if (markerPx != null && markerPy != null) {           // a marker off the sheet still shows
                float mx = focus.x + markerPx / 4f, my = focus.y + markerPy / 4f;   // RevealMapView.UNITS_PER_PX
                b = new float[] { Math.min(b[0], mx), Math.min(b[1], my), Math.max(b[2], mx), Math.max(b[3], my) };
            }
        }
        return b;
    }
}
