package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Index of the local map atlas (format ffxii-companion-atlas-1, built on the PC by draft/tools/mapatlas_pack.py
 * from the user's own game files and pushed to Download/FFXII_companion/atlas). The PNGs are coverage masks
 * only; the game's tint is unknown. Pure Java: the Android side checks each PNG's sha256 and size before use.
 */
final class Atlas {
    static final String FORMAT = "ffxii-companion-atlas-1";
    static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
    static final int MAX_DIM = 4096;

    static final class Sheet {
        final int mapId, width, height, stride, sheetsForMap;
        final String file, sha256, region;
        final String[] tiles;   // rows of '#' (drawn) / '.' (blank), one char per 64-px tile
        Integer slot;           // reveal record slot from the game's navimapdata.bin; null = none or older atlas
        Double originX, originZ; // NPS2 corner in world units (atlas-3+); null = older atlas

        Sheet(int mapId, String region, String file, int width, int height, int stride, int sheetsForMap,
              String sha256, String[] tiles) {
            this.mapId = mapId; this.region = region; this.file = file; this.width = width; this.height = height;
            this.stride = stride; this.sheetsForMap = sheetsForMap; this.sha256 = sha256; this.tiles = tiles;
        }

        boolean drawn(int x, int y) { return y < tiles.length && x < tiles[y].length() && tiles[y].charAt(x) == '#'; }
    }

    private final Map<Integer, List<Sheet>> byMap;
    private final Map<String, Sheet> byFile = new HashMap<>();
    final int sheetCount;

    private Atlas(Map<Integer, List<Sheet>> byMap, int sheetCount) {
        this.byMap = byMap;
        this.sheetCount = sheetCount;
        for (List<Sheet> l : byMap.values()) for (Sheet s : l) byFile.put(s.file, s);
    }

    Sheet sheet(String file) { return byFile.get(file); }

    Integer slot(Sheet s) { return s == null ? null : s.slot; }

    List<Sheet> sheets(int mapId) {
        List<Sheet> l = byMap.get(mapId);
        return l == null ? Collections.emptyList() : l;
    }

    /** Null for anything malformed; individual bad entries are skipped. */
    static Atlas parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_INDEX_BYTES) return null;
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!FORMAT.equals(Json.str(root, "format"))) return null;
        List<Object> raw = Json.arr(root, "sheets");
        if (raw == null) return null;
        Map<Integer, List<Sheet>> byMap = new HashMap<>();
        int n = 0;
        for (Object o : raw) {
            Sheet s = sheet(o);
            if (s == null) continue;
            byMap.computeIfAbsent(s.mapId, k -> new ArrayList<>()).add(s);
            n++;
        }
        return n == 0 ? null : new Atlas(byMap, n);
    }

    private static Sheet sheet(Object o) {
        Long id = Json.lng(o, "mapId"), w = Json.lng(o, "width"), h = Json.lng(o, "height");
        Long stride = Json.lng(o, "stride"), per = Json.lng(o, "sheetsForMap");
        String file = Json.str(o, "file"), sha = Json.str(o, "sha256");
        List<Object> rows = Json.arr(o, "tiles");
        if (id == null || w == null || h == null || stride == null || per == null || file == null || sha == null || rows == null) return null;
        if (w <= 0 || h <= 0 || w > MAX_DIM || h > MAX_DIM || w % 64 != 0 || h % 64 != 0) return null;
        if (!file.matches("[A-Za-z0-9_]+\\.png") || !sha.matches("[0-9a-f]{64}")) return null;
        if (rows.size() != h / 64) return null;
        String[] tiles = new String[rows.size()];
        for (int i = 0; i < tiles.length; i++) {
            Object r = rows.get(i);
            if (!(r instanceof String) || ((String) r).length() != w / 64) return null;
            tiles[i] = (String) r;
        }
        Sheet s = new Sheet(id.intValue(), Json.str(o, "region"), file, w.intValue(), h.intValue(), stride.intValue(),
                per.intValue(), sha, tiles);
        Long slot = Json.lng(o, "slot");
        if (slot != null && slot >= 0 && slot <= 0x180) s.slot = slot.intValue();
        Map<String, Object> origin = Json.obj(o, "origin");
        Object x0 = origin != null ? origin.get("x0") : null, z0 = origin != null ? origin.get("z0") : null;
        if (x0 instanceof Number && z0 instanceof Number) {
            s.originX = ((Number) x0).doubleValue();
            s.originZ = ((Number) z0).doubleValue();
        }
        return s;
    }
}
