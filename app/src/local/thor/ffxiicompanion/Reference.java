package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offline REFERENCE bundle (format ffxii-companion-reference-1, built on the PC by draft/tools/reference_pack.py)
 * from files the user's own mods write: Dynamic Equipment Description's generated gear descriptions (live
 * mod-patched battle pack at the time they were written) and Foedex Clan Primer Text Replacer / More Bestiary Pages
 * bestiary pages. Reference only: never shown as live state. Pure Java so the JVM tests can drive it.
 */
final class Reference {
    static final String FORMAT = "ffxii-companion-reference-1";
    static final int MAX_BYTES = 8 * 1024 * 1024;

    static final class Entry {
        final String name, category;      // category: gear category, or null for bestiary
        final List<String> tags, lines;   // lines: plain text; "## " prefix marks a section heading
        final List<String> locations;     // bestiary only
        Entry(String name, String category, List<String> tags, List<String> lines, List<String> locations) {
            this.name = name; this.category = category; this.tags = tags; this.lines = lines; this.locations = locations;
        }
    }

    final List<Entry> gear, bestiary;
    private final Map<Integer, String> namesById = new java.util.HashMap<>();
    private final Map<Integer, Entry> gearById = new java.util.HashMap<>();
    private final Map<Integer, Entry> bestiaryByKey = new java.util.HashMap<>();

    /** The reference entry for an ID (equipment 0x1000+index, as the game stores it in a member record), or null. */
    Entry gearById(int id) { return gearById.get(id); }

    /** The bestiary entry for the game's own bestiary key (the pack's "entry"; 0.13.0 enemy panel), or null. */
    Entry bestiaryByKey(int key) { return bestiaryByKey.get(key); }

    /** Gear/magick/technick/item name by its battle-pack index (DED id), or null. */
    String nameOf(int id) { return namesById.get(id); }

    private final Map<Integer, String> conditions = new java.util.HashMap<>();

    /** Gambit condition name (0x6000+n) from the modded battle pack, or null (bundle without gambitConditions). */
    String conditionName(int id) { return conditions.get(id); }

    private final Map<Integer, String> actions = new java.util.HashMap<>();

    private final Map<Integer, String> characters = new java.util.HashMap<>();

    /** 0.16.0: a party member's name by member ID from the user's game data (guests too), or null for older packs. */
    String characterName(int id) { return characters.get(id); }

    /** Gambit action name exactly as the game's menu shows it (data pack gambitActions), or null for older packs. */
    String actionName(int id) { return actions.get(id); }

    /** True when the pack lists gambit actions; then only listed IDs are ordinary party actions the editor may write. */
    boolean hasActions() { return !actions.isEmpty(); }

    private Reference(List<Entry> gear, List<Entry> bestiary) {
        this.gear = gear; this.bestiary = bestiary;
    }

    /** Gear categories, most entries first (ties alphabetical). */
    List<String> gearCategories() {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (Entry e : gear) if (e.category != null) n.merge(e.category, 1, Integer::sum);
        List<String> out = new ArrayList<>(n.keySet());
        out.sort((a, b) -> n.get(b).equals(n.get(a)) ? a.compareTo(b) : n.get(b) - n.get(a));
        return out;
    }

    List<Entry> gearIn(String category) {
        if (category == null) return gear;
        List<Entry> out = new ArrayList<>();
        for (Entry e : gear) if (category.equals(e.category)) out.add(e);
        return out;
    }

    /** Bestiary locations in first-seen (game) order. */
    List<String> locations() {
        List<String> out = new ArrayList<>();
        for (Entry e : bestiary) for (String l : e.locations) if (!out.contains(l)) out.add(l);
        return out;
    }

    List<Entry> bestiaryAt(String location) {
        if (location == null) return bestiary;
        List<Entry> out = new ArrayList<>();
        for (Entry e : bestiary) if (e.locations.contains(location)) out.add(e);
        return out;
    }

    static Reference parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_BYTES) return null;
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!FORMAT.equals(Json.str(root, "format")) || !"REFERENCE".equals(Json.str(root, "kind"))) return null;
        List<Entry> gear = new ArrayList<>(), best = new ArrayList<>();
        Map<Integer, String> ids = new java.util.HashMap<>();
        Map<Integer, Entry> byId = new java.util.HashMap<>(), byKey = new java.util.HashMap<>();
        List<Object> g = Json.arr(root, "gear"), b = Json.arr(root, "bestiary");
        if (g != null) {
            for (Object o : g) {
                String name = Json.str(o, "name");
                if (name == null || name.isEmpty()) continue;
                Long id = Json.lng(o, "id");
                Entry e = new Entry(name, Json.str(o, "category"), strings(Json.arr(o, "tags")), strings(Json.arr(o, "lines")),
                        Collections.emptyList());
                if (id != null) { ids.put(id.intValue(), name); byId.put(id.intValue(), e); }
                gear.add(e);
            }
        }
        if (b != null) {
            for (Object o : b) {
                String name = Json.str(o, "name");
                List<Object> pages = Json.arr(o, "pages");
                if (name == null || pages == null) continue;
                List<String> lines = new ArrayList<>();
                for (Object p : pages) {
                    if (!(p instanceof List)) continue;
                    if (!lines.isEmpty()) lines.add("");
                    for (Object l : (List<?>) p) if (l instanceof String && !"Items".equals(l)) lines.add((String) l);
                }
                Entry e = new Entry(name, null, Collections.emptyList(), lines, strings(Json.arr(o, "locations")));
                Long key = Json.lng(o, "entry");
                if (key != null) byKey.putIfAbsent(key.intValue(), e);
                best.add(e);
            }
        }
        if (gear.isEmpty() && best.isEmpty()) return null;
        Reference r = new Reference(Collections.unmodifiableList(gear), Collections.unmodifiableList(best));
        r.namesById.putAll(ids);
        r.gearById.putAll(byId);
        r.bestiaryByKey.putAll(byKey);
        Map<String, Object> ga = Json.obj(root, "gambitActions");
        if (ga != null) {
            for (Map.Entry<String, Object> e : ga.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                try {
                    r.actions.put(Integer.parseInt(e.getKey(), 16), (String) e.getValue());
                } catch (NumberFormatException ignored) {
                    // not an action id
                }
            }
        }
        Map<String, Object> ch = Json.obj(root, "characters");
        if (ch != null) {
            for (Map.Entry<String, Object> e : ch.entrySet()) {
                if (!(e.getValue() instanceof String) || ((String) e.getValue()).isEmpty()) continue;
                try {
                    r.characters.put(Integer.parseInt(e.getKey()), (String) e.getValue());
                } catch (NumberFormatException ignored) {
                    // skip malformed keys
                }
            }
        }
        Map<String, Object> gc = Json.obj(root, "gambitConditions");
        if (gc != null) {
            for (Map.Entry<String, Object> e : gc.entrySet()) {
                if (!(e.getValue() instanceof String)) continue;
                try {
                    r.conditions.put(Integer.parseInt(e.getKey(), 16), (String) e.getValue());
                } catch (NumberFormatException ignored) {
                    // skip malformed keys
                }
            }
        }
        return r;
    }

    private static List<String> strings(List<Object> in) {
        if (in == null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (Object o : in) if (o instanceof String) out.add((String) o);
        return Collections.unmodifiableList(out);
    }
}
