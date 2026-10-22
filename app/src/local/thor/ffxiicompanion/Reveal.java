package local.thor.ffxiicompanion;

import java.util.List;
import java.util.Map;

/**
 * Map-fog family (data.reveal, contract ffxii.reveal.v1, ThorCompanion.lua 0.4.0+) and the verified-data gate
 * that decides whether the companion may draw the current area's map.
 *
 * Contract, from the PC exe and confirmed on the Thor 2026-10-21 (Thor_Reveal_Runtime_2026-10-21.md): the record
 * is 32 bytes; a 64-px tile (x, y) of the area's menu-map sheet is revealed when bit (x & 7) of byte
 * [y * stride + x / 8] is set, with stride = (width / 64) >> 3. Screen-checked for 512-px (The Undershore) and
 * 1024-px (The Balamka Fault) sheets; 2048-px only statistically, so it stays hidden. Partly fogged maps have
 * not been compared with the game screen yet, and multi-sheet (multi-level) maps have no verified layout.
 *
 * The gate never synthesizes reveal bits and never falls back to showing the whole sheet, except when the
 * observed Bountiful Bundle config says the game itself shows fully revealed maps.
 */
final class Reveal {
    static final String CONTRACT = "ffxii.reveal.v1";
    static final long SUPPORTED_TIME_DATE_STAMP = 1573552087L;
    static final long IMAGE_BASE = 0x120000L;
    static final int RECORD_BYTES = 32;
    static final int TILE = 64;
    /** Compile-time switch for the whole family. */
    static final boolean LIVE_REVEAL_ENABLED = true;

    /** Sheet widths whose row stride has been compared with the game's own map screen. */
    static boolean screenVerifiedWidth(int width) { return width == 512 || width == 1024; }

    enum Mode { HIDDEN, GATED, FULL }

    static final class Decision {
        Mode mode = Mode.HIDDEN;
        String reason;            // why hidden, or a qualifier for what is shown
        Atlas.Sheet sheet;        // set when shown
        boolean[][] show;         // [row][col]: draw this tile (revealed, or FULL mode)
        boolean[][] visible;      // show and the sheet has drawn pixels there (what the view crops to)
        int drawnTiles, shownTiles;
        Integer mapId;

        static Decision hidden(Integer mapId, String reason) {
            Decision d = new Decision();
            d.mapId = mapId;
            d.reason = reason;
            return d;
        }
    }

    static int strideFor(int width) { return (width / TILE) >> 3; }

    /** 64 hex chars to 32 bytes, else null. */
    static byte[] parseRecord(String hex) {
        if (hex == null || hex.length() != RECORD_BYTES * 2) return null;
        byte[] out = new byte[RECORD_BYTES];
        for (int i = 0; i < RECORD_BYTES; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16), lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    /** Mirror of the game's bounds-checked bitmap test. */
    static boolean cell(byte[] record, int x, int y, int cols, int rows, int stride) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return false;
        int i = y * stride + (x >> 3);
        return i < RECORD_BYTES && ((record[i] >> (x & 7)) & 1) != 0;
    }

    static Decision decide(Snapshot s, Atlas atlas, boolean fixtureMode) {
        if (!LIVE_REVEAL_ENABLED) return Decision.hidden(null, "map fog feed is switched off in this app");
        if (s == null || s.map() == null) return Decision.hidden(null, "no map data yet");
        Integer mapId = toInt(Json.lng(s.map(), "mapId"));
        Map<String, Object> r = Json.obj(s.data, "reveal");
        if (r == null) return Decision.hidden(mapId, "no map fog data (game script older than 0.4.0)");
        if (s.synthetic && !fixtureMode) return Decision.hidden(mapId, "synthetic fog data outside fixture mode ignored");
        if (!s.synthetic) {
            if (!"on".equals(s.capability("map.reveal"))) return Decision.hidden(mapId, "game script does not offer map fog");
            if (!buildVerified(s.hello())) return Decision.hidden(mapId, "game build not verified for map fog");
        }
        if (!CONTRACT.equals(Json.str(r, "contract"))) return Decision.hidden(mapId, "unknown map fog format");
        String status = Json.str(r, "status");
        if (!"ok".equals(status)) return Decision.hidden(mapId, statusText(status));
        if (!Boolean.TRUE.equals(Json.bool(r, "stable"))) return Decision.hidden(mapId, "map fog read not stable");
        Integer rid = toInt(Json.lng(r, "mapId"));
        if (mapId == null || !mapId.equals(rid)) return Decision.hidden(mapId, "map fog is for a different area");
        byte[] record = parseRecord(Json.str(r, "record"));
        if (record == null) return Decision.hidden(mapId, "map fog record malformed");

        if (atlas == null) return Decision.hidden(mapId, "map atlas not installed on this device");
        List<Atlas.Sheet> sheets = atlas.sheets(mapId);
        if (sheets.isEmpty()) return Decision.hidden(mapId, "no menu map for this area");
        if (sheets.size() > 1 || sheets.get(0).sheetsForMap > 1) return Decision.hidden(mapId, "multi-level map: fog layout not verified");
        Atlas.Sheet sh = sheets.get(0);
        int cols = sh.width / TILE, rows = sh.height / TILE, stride = strideFor(sh.width);
        if (sh.stride != stride) return Decision.hidden(mapId, "map atlas stride disagrees with the game rule");
        if (!screenVerifiedWidth(sh.width)) return Decision.hidden(mapId, "fog layout for " + sh.width + "-px maps not verified yet");
        if (stride < 1 || rows * stride > RECORD_BYTES) return Decision.hidden(mapId, "map size does not fit the fog record");

        boolean full = bundleFullyRevealed(s.hello());
        Decision d = new Decision();
        d.mapId = mapId;
        d.sheet = sh;
        d.show = new boolean[rows][cols];
        d.visible = new boolean[rows][cols];
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < cols; x++) {
                boolean drawn = sh.drawn(x, y);
                boolean show = full || cell(record, x, y, cols, rows, stride);
                d.show[y][x] = show;
                d.visible[y][x] = show && drawn;
                if (drawn) d.drawnTiles++;
                if (drawn && show) d.shownTiles++;
            }
        }
        d.mode = full ? Mode.FULL : Mode.GATED;
        if (full) d.reason = "Bountiful Bundle: fully revealed maps (config file)";
        else if (d.shownTiles == 0) d.reason = "no explored areas recorded for this map yet";
        return d;
    }

    static boolean buildVerified(Map<String, Object> hello) {
        Map<String, Object> b = Json.obj(hello, "build");
        Long stamp = Json.lng(Json.obj(b, "pe"), "timeDateStamp");
        Long base = Json.lng(b, "imageBase");
        return stamp != null && stamp == SUPPORTED_TIME_DATE_STAMP && base != null && base == IMAGE_BASE;
    }

    /** True only when the Bundle is present and its observed config file sets fullyRevealedMaps = true. */
    static boolean bundleFullyRevealed(Map<String, Object> hello) {
        List<Object> mods = Json.arr(hello, "mods");
        if (mods == null) return false;
        for (Object m : mods) {
            if (!"insurgents.bundle".equals(Json.str(m, "id"))) continue;
            if (!Boolean.TRUE.equals(Json.bool(Json.obj(m, "file_present"), "value"))) return false;
            return Boolean.TRUE.equals(Json.bool(Json.obj(Json.obj(m, "config_observed"), "states"), "fullyRevealedMaps"));
        }
        return false;
    }

    static String statusText(String status) {
        if (status == null) return "map fog status missing";
        switch (status) {
            case "unsupported_build": return "game build not supported for map fog";
            case "no_map": return "no current map";
            case "nav_not_loaded": return "map data not loaded yet";
            case "no_slot": return "this area has no map fog record";
            case "root_unexpected": return "save data not ready";
            case "unstable": return "map fog was changing during the read";
            case "read_failed": return "map fog read failed";
            default: return "map fog status: " + status;
        }
    }

    private static Integer toInt(Long v) { return v == null ? null : v.intValue(); }
}
