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
 */
final class Regions {
    static final String FORMAT = "ffxii-companion-regions-1";
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

    static final class Region {
        final String name;
        final List<Placed> sheets;
        final List<Link> links;
        Region(String name, List<Placed> sheets, List<Link> links) { this.name = name; this.sheets = sheets; this.links = links; }
    }

    /** One drawable area: atlas sheet, layout position, which tiles to draw. */
    static final class Part {
        Atlas.Sheet sheet;
        int x, y;               // layout units
        boolean[][] visible;    // explored and drawn tiles
        boolean focus;
    }

    static final class View {
        final List<Part> parts = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        String note;            // why neighbours are missing, or null
        int neighbours;
    }

    final double unitsPerSheetPx;
    private final Map<String, Region> byFile = new HashMap<>();

    private Regions(double unitsPerSheetPx) { this.unitsPerSheetPx = unitsPerSheetPx; }

    static Regions parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_BYTES) return null;
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!FORMAT.equals(Json.str(root, "format"))) return null;
        Object u = root instanceof Map ? ((Map<?, ?>) root).get("unitsPerSheetPx") : null;
        if (!(u instanceof Number) || ((Number) u).doubleValue() != 0.25) return null;   // the verified rule only
        List<Object> raw = Json.arr(root, "regions");
        if (raw == null) return null;
        Regions out = new Regions(0.25);
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
            Region reg = new Region(name, Collections.unmodifiableList(placed), Collections.unmodifiableList(links));
            for (Placed p : placed) out.byFile.put(p.file, reg);
        }
        return out.byFile.isEmpty() ? null : out;
    }

    Region regionOf(String sheetFile) { return byFile.get(sheetFile); }

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
        focus.focus = true;
        Region reg = current.sheet != null ? regionOf(current.sheet.file) : null;
        if (reg == null) {
            v.parts.add(focus);
            v.note = "region layout unknown for this area";
            return v;
        }
        for (Placed p : reg.sheets) if (p.file.equals(current.sheet.file)) { focus.x = p.x; focus.y = p.y; }
        v.parts.add(focus);
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
            boolean any = false;
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    boolean show = full || Reveal.cell(rec, x, y, cols, rows, sh.stride);
                    part.visible[y][x] = show && sh.drawn(x, y);
                    any |= part.visible[y][x];
                }
            }
            if (!any) continue;
            v.parts.add(part);
            v.neighbours++;
            shownMaps.add(sh.mapId);
        }
        for (Link l : reg.links) if (l.a != l.b && shownMaps.contains(l.a) && shownMaps.contains(l.b)) v.links.add(l);
        return v;
    }
}
