package local.thor.ffxiicompanion;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * What the Status card says for a map id. Area names come from the user's own data pack (reference/areas.json, built
 * on their PC from their game files); none are compiled into the app. The game reports a negative id while it is
 * loading, which reads "Loading…" with no "from" line; ids without a name keep the honest "Map N".
 */
final class Place {
    static final String FORMAT = "ffxii-companion-areas-1";
    private static volatile Map<Integer, String> names = Collections.emptyMap();

    private Place() {}

    /** Parses areas.json ({"format": FORMAT, "names": {"<id>": "<name>"}}); null when it isn't that. */
    static Map<Integer, String> parse(String json) {
        Object o;
        try {
            o = json == null ? null : Json.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(o instanceof Map) || !FORMAT.equals(Json.str(o, "format"))) return null;
        Object n = ((Map<?, ?>) o).get("names");
        if (!(n instanceof Map)) return null;
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) n).entrySet()) {
            try {
                if (e.getValue() instanceof String) out.put(Integer.parseInt(String.valueOf(e.getKey())), (String) e.getValue());
            } catch (NumberFormatException ignored) {
                // not a map id
            }
        }
        return out;
    }

    static void setNames(Map<Integer, String> m) { names = m == null ? Collections.emptyMap() : m; }

    static boolean loading(Long id) { return id != null && id < 0; }

    /** 0.10.0: map 12 is what the game reports on its title and load screens (seen on the Thor); it has no area name. */
    static boolean title(Long id) { return id != null && id == 12 && !names.containsKey(12); }

    static String name(Long id) {
        if (id == null) return "Unknown area";
        if (id < 0) return "Loading…";
        if (title(id)) return "Title screen";
        String n = names.get(id.intValue());
        return n != null ? n : "Map " + id;
    }

    /** "from X", or "" when there is nothing useful to say (no previous map, loading, or the same map). */
    static String from(Long mapId, Long previousId) {
        if (previousId == null || loading(mapId) || loading(previousId) || title(mapId) || previousId.equals(mapId)) return "";
        return "from " + name(previousId);
    }
}
