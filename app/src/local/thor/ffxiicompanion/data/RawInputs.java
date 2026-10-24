package local.thor.ffxiicompanion.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Inputs from plain files, the way the game script's raw copy leaves them (raw/&lt;job&gt;/NNN.bin): the archive header,
 * each entry's stored blocks by logical path, and the mod configs by x64-relative path. Nothing here trusts a file
 * name: sizes are checked against the archive's own index before any byte is used.
 */
public final class RawInputs extends Inputs {
    private final VbfIndex index;
    private final Map<String, File> entries, configs;

    /** The archive's size worked out from the header (VbfIndex.parseCanonical): what the app uses. */
    public RawInputs(File header, Map<String, File> entries, Map<String, File> configs) throws IOException {
        this(header, -1, entries, configs);
    }

    /**
     * archiveBytes: the whole FFXII_TZA.vbf's size when known (the atlas records it and the index checks every entry's
     * extent against it), or -1 to work it out from the header.
     */
    public RawInputs(File header, long archiveBytes, Map<String, File> entries, Map<String, File> configs) throws IOException {
        long n = header.length();
        if (n <= 0 || n > VbfIndex.MAX_HEADER) throw new IOException("header size");
        byte[] h = Files.readAllBytes(header.toPath());
        index = archiveBytes < 0 ? VbfIndex.parseCanonical(h) : VbfIndex.parse(h, archiveBytes);
        this.entries = Collections.unmodifiableMap(new HashMap<>(entries));
        this.configs = Collections.unmodifiableMap(new HashMap<>(configs));
    }

    @Override
    protected VbfIndex index() { return index; }

    @Override
    protected InputStream stored(String path, VbfIndex.Entry entry) throws IOException {
        File f = entries.get(path);
        if (f == null) return null;
        if (f.length() != entry.storedSize) throw new IOException("wrong size (" + f.length() + " bytes, the archive says " + entry.storedSize + ")");
        return new FileInputStream(f);
    }

    @Override
    public byte[] config(String path) throws IOException {
        File f = configs.get(path);
        if (f == null || !f.isFile()) return null;
        if (f.length() > MAX_CONFIG) throw new IOException("config too large: " + path);
        return Files.readAllBytes(f.toPath());
    }
}
