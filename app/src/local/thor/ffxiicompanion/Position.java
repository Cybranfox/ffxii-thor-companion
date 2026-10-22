package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * "You are here" marker from data.position (contract ffxii.mappos.v1, ThorCompanion.lua 0.4.2+).
 *
 * The logger reads the menu map's own static copy of the controlled character's position and the current sheet's
 * origin (FFXII_Position_Transform_Static_2026-10-21.md). The game draws its map at 16 world units per 64-px tile
 * from the sheet's NPS2 corner, so sheet px = 4 * (x - x0), 4 * (z - z0). Runtime-verified on the Thor 21 Oct 2026:
 * two points in The Undershore within 0.51 and 0.10 tile of the game's own marker, live origin equal to the atlas.
 *
 * The gate also requires the game's live origin to equal the atlas origin of the sheet being drawn, so a wrong
 * sheet or an atlas from other game files never gets a marker.
 */
final class Position {
    static final String CONTRACT = "ffxii.mappos.v1";
    static final boolean LIVE_POSITION_ENABLED = true;
    static final float PX_PER_UNIT = 4f;
    static final double ORIGIN_TOLERANCE = 0.01;

    static final class Marker {
        float px, py;        // sheet pixels; valid only when reason == null
        String reason;       // why there is no marker
        static Marker none(String reason) {
            Marker m = new Marker();
            m.reason = reason;
            return m;
        }
    }

    static Marker locate(Snapshot s, Atlas.Sheet sheet, boolean fixtureMode) {
        if (!LIVE_POSITION_ENABLED) return Marker.none("position marker switched off in this app");
        if (s == null || sheet == null) return Marker.none("no map shown");
        Map<String, Object> p = Json.obj(s.data, "position");
        if (p == null) return Marker.none("no position data (game script older than 0.4.2)");
        if (s.synthetic && !fixtureMode) return Marker.none("synthetic position outside fixture mode ignored");
        if (!s.synthetic) {
            String cap = s.capability("map.position");
            if (!"probe".equals(cap) && !"on".equals(cap)) return Marker.none("game script does not offer the position");
            if (!Reveal.buildVerified(s.hello())) return Marker.none("game build not verified for the position");
        }
        if (!CONTRACT.equals(Json.str(p, "contract"))) return Marker.none("unknown position format");
        String status = Json.str(p, "status");
        if (!"ok".equals(status)) return Marker.none(statusText(status));
        if (!Boolean.TRUE.equals(Json.bool(p, "navimapActive"))) return Marker.none("game map not active");
        Double x = num(p, "x"), z = num(p, "z"), ox = num(p, "originX"), oz = num(p, "originZ");
        if (x == null || z == null || ox == null || oz == null) return Marker.none("position malformed");
        if (sheet.originX == null || sheet.originZ == null) return Marker.none("map atlas has no sheet origins (needs atlas-3)");
        if (Math.abs(ox - sheet.originX) > ORIGIN_TOLERANCE || Math.abs(oz - sheet.originZ) > ORIGIN_TOLERANCE) {
            return Marker.none("game map origin does not match this map sheet");
        }
        Marker m = new Marker();
        m.px = (float) ((x - sheet.originX) * PX_PER_UNIT);
        m.py = (float) ((z - sheet.originZ) * PX_PER_UNIT);
        if (m.px < 0 || m.py < 0 || m.px >= sheet.width || m.py >= sheet.height) return Marker.none("position outside this map sheet");
        return m;
    }

    static String statusText(String status) {
        if (status == null) return "position status missing";
        switch (status) {
            case "unsupported_build": return "game build not supported for the position";
            case "navimap_inactive": return "game map not active";
            case "unstable": return "position was changing during the read";
            case "bad_w": return "position copy not valid";
            case "read_failed": return "position read failed";
            default: return "position status: " + status;
        }
    }

    private static Double num(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : null;
    }
}
