package local.thor.ffxiicompanion;

import java.util.List;
import java.util.Map;

/** The text of the four Details cards (Session, Data feeds, Mods, Game build), from the game script's hello block. */
final class Details {
    private Details() {}

    static String session(Snapshot s, Map<String, Object> hello) {
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
        return b.append("Snapshot #").append(s.seq).toString();
    }

    static String build(Map<String, Object> hello) {
        Map<String, Object> bi = Json.obj(hello, "build");
        if (bi == null) return "—";
        StringBuilder b = new StringBuilder();
        b.append(Boolean.TRUE.equals(Json.bool(bi, "exeLengthSupported")) ? "Supported Steam exe" : "Unknown exe size").append('\n');
        b.append(Json.obj(bi, "pe") != null ? "Image at 0x120000 (verified)" : "PE header not readable").append('\n');
        Map<String, Object> patches = Json.obj(bi, "patches");
        if (patches != null) {
            String gn = Json.str(patches, "gn.595520.displaymodes");
            b.append("GameNative display fix: ").append(gn != null ? gn : "?");
        }
        return b.toString().trim();
    }

    static String mods(Map<String, Object> hello) {
        List<Object> arr = Json.arr(hello, "mods");
        if (arr == null) return "—";
        StringBuilder b = new StringBuilder();
        for (Object m : arr) {
            Map<String, Object> fp = Json.obj(m, "file_present");
            if (fp == null || !Boolean.TRUE.equals(Json.bool(fp, "value"))) continue;  // only list what is installed
            b.append("• ").append(modName(Json.str(m, "id")));
            if (Json.obj(m, "config_observed") != null) b.append("  (config)");
            b.append('\n');
        }
        return b.length() == 0 ? "None found" : b.toString().trim();
    }

    static String feeds(Map<String, Object> hello) {
        Map<String, Object> c = Json.obj(hello, "capabilities");
        if (c == null) return "—";
        StringBuilder on = new StringBuilder(), off = new StringBuilder();
        for (Map.Entry<String, Object> e : c.entrySet()) {
            String k = e.getKey();
            // The producer may claim party data before this app accepts it (ReceiverState.LIVE_PARTY_ENABLED).
            if ("party".equals(k) && !"off".equals(e.getValue()) && !ReceiverState.LIVE_PARTY_ENABLED) k = "party (not enabled in this app)";
            ("off".equals(e.getValue()) || k.startsWith("party (") ? off : on).append(k).append("  ");
        }
        String text = "Available: " + (on.length() == 0 ? "none" : on.toString().trim());
        if (off.length() > 0) text += "\nNot yet: " + off.toString().trim();
        return text;
    }

    static String modName(String id) {
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

    static String secs(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        return (s / 3600) + "h " + ((s / 60) % 60) + "m";
    }
}
