package local.thor.ffxiicompanion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import local.thor.ffxiicompanion.data.DataBuilder;
import local.thor.ffxiicompanion.data.Inputs;

/**
 * 0.16.0: a newly built pack is only swapped in if the app itself can use all of it. Read with the same parsers the
 * screens use, eagerly (the screens load lazily): atlas 2 index + regions of one builder run, every picture
 * files.json lists present at its sha256, reference, boards (12), areas, objectives when there, and BUILD_INFO from
 * this builder. Pure: no Android types.
 */
final class PackCheck {
    static final int MAX_PNG = 4 * 1024 * 1024;           // MainActivity.MAX_MASK_BYTES

    private PackCheck() {}

    /** A new build from this app's builder (atlas 2, BUILD_INFO "builder" = DataBuilder.VERSION): null when usable. */
    static String problem(File dir) { return problem(dir, true); }

    /**
     * Null when the pack in dir (atlas/, reference/, BUILD_INFO.json) is usable; otherwise the first problem.
     * newBuild: this app's own fresh build (atlas 2 and its builder only); false for the live pack the setup screen
     * checks, which may also come from the PC builder or be an older atlas 1 pack (no picture receipt to check).
     */
    static String problem(File dir, boolean newBuild) {
        File atlasDir = new File(dir, "atlas"), ref = new File(dir, "reference");
        AtlasFiles files = AtlasFiles.parse(text(new File(atlasDir, "files.json"), AtlasFiles.MAX_BYTES));
        String index = text(new File(atlasDir, "index.json"), Atlas.MAX_INDEX_BYTES);
        boolean v1 = !newBuild && files == null && index != null && index.contains(Atlas.FORMAT);
        if (files == null && !v1) return "atlas/files.json missing or unreadable";
        Atlas atlas = Atlas.parse(index, files);
        if (atlas == null || atlas.version != (v1 ? 1 : 2)) return "atlas/index.json unreadable";
        Regions regions = Regions.parse(text(new File(atlasDir, "regions.json"), Regions.MAX_BYTES), files);
        if (regions == null || regions.version != atlas.version) return "atlas/regions.json unreadable or from another run";
        if (v1) {                                   // atlas 1: no receipt, but its index and regions carry each sha256
            for (Atlas.Sheet s : atlas.allSheets()) {
                String p = picture(atlasDir, s.file, s.sha256);
                if (p != null) return p;
            }
            for (Regions.Symbol s : regions.symbols.values()) {
                String p = picture(atlasDir, s.file, s.sha256);
                if (p != null) return p;
            }
        }
        int sheets = 0;
        if (!v1) for (Map.Entry<String, String> e : files.all().entrySet()) {
            byte[] png = DataFiles.readBytes(new File(atlasDir, e.getKey()), MAX_PNG);
            try {
                if (png == null || !Inputs.sha256(png).equals(e.getValue())) return "atlas/" + e.getKey() + " missing or damaged";
            } catch (java.io.IOException x) {
                return "can't hash atlas/" + e.getKey();
            }
            if (!e.getKey().startsWith("icons/")) sheets++;
        }
        if (!v1 && sheets != atlas.sheetCount) return "atlas/index.json and files.json disagree";
        if (Reference.parse(text(new File(ref, "reference.json"), Reference.MAX_BYTES)) == null) return "reference.json unreadable";
        LicenceBoards boards = LicenceBoards.parse(DataFiles.jsonObject(new File(ref, "boards.json"), 512 * 1024));
        if (boards == null || boards.boards.size() != 12) return "boards.json unreadable";
        Map<Integer, String> areas = Place.parse(text(new File(ref, "areas.json"), 256 * 1024));
        if (areas == null || areas.isEmpty()) return "areas.json unreadable";
        File obj = new File(ref, "objectives.json");
        if (obj.exists() && Objectives.parse(DataFiles.jsonObject(obj, Objectives.MAX_BYTES)) == null) return "objectives.json unreadable";
        Map<String, Object> info = DataFiles.jsonObject(new File(dir, "BUILD_INFO.json"), 64 * 1024);
        if (info == null || newBuild && !DataBuilder.VERSION.equals(info.get("builder"))) return "BUILD_INFO.json missing or from another builder";
        return null;
    }

    /** Null when atlas/file is there and hashes to sha256; else the problem. */
    private static String picture(File atlasDir, String file, String sha256) {
        if (file == null || sha256 == null) return "atlas index names a picture without its hash";
        byte[] png = DataFiles.readBytes(new File(atlasDir, file), MAX_PNG);
        try {
            if (png == null || !Inputs.sha256(png).equals(sha256)) return "atlas/" + file + " missing or damaged";
        } catch (java.io.IOException x) {
            return "can't hash atlas/" + file;
        }
        return null;
    }

    /** BUILD_INFO's builder for the pack in dir, or null. */
    static String builder(File dir) {
        Map<String, Object> info = DataFiles.jsonObject(new File(dir, "BUILD_INFO.json"), 64 * 1024);
        Object b = info == null ? null : info.get("builder");
        return b instanceof String ? (String) b : null;
    }

    private static String text(File f, int max) {
        byte[] b = DataFiles.readBytes(f, max);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
}
