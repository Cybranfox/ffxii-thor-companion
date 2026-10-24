package local.thor.ffxiicompanion;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Atlas 2 (0.15.0): atlas/files.json, format ffxii-companion-atlas-files-1. The size and sha256 of every PNG as the
 * builder that made this atlas wrote it. index.json and regions.json name each picture by its pixel hash, which is
 * the same whichever builder (PC or phone) encoded it; this receipt is what the app checks the files against.
 */
final class AtlasFiles {
    static final String FORMAT = "ffxii-companion-atlas-files-1";
    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_FILES = 4096;

    private final Map<String, String> sha256;

    private AtlasFiles(Map<String, String> sha256) { this.sha256 = Collections.unmodifiableMap(sha256); }

    /** The expected sha256 of this file, or null when the receipt doesn't list it. */
    String sha256(String file) { return sha256.get(file); }

    int size() { return sha256.size(); }

    /** Every listed file and its sha256 (PackCheck hashes each one before a new pack is used). */
    Map<String, String> all() { return sha256; }

    /** Null for anything malformed. A file listed twice or a bad row rejects the whole receipt. */
    static AtlasFiles parse(String text) {
        if (text == null || text.isEmpty() || text.length() > MAX_BYTES) return null;
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!FORMAT.equals(Json.str(root, "format"))) return null;
        List<Object> rows = Json.arr(root, "files");
        if (rows == null || rows.isEmpty() || rows.size() > MAX_FILES) return null;
        Map<String, String> out = new HashMap<>();
        for (Object o : rows) {
            String file = Json.str(o, "file"), sha = Json.str(o, "sha256");
            Long bytes = Json.lng(o, "bytes");
            if (file == null || sha == null || bytes == null || bytes < 1 || !sha.matches("[0-9a-f]{64}")) return null;
            if (!file.matches("[A-Za-z0-9_]+\\.png") && !file.matches("icons/type_[0-9]{2}\\.png")) return null;
            if (out.put(file, sha) != null) return null;
        }
        return new AtlasFiles(out);
    }
}
