package local.thor.ffxiicompanion.data;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Where the data builder's game files come from: the archive's own index (its SRYK header), each entry's stored
 * blocks, and the optional mod configs. Subclasses only hand over bytes; this class inflates and checks every entry
 * against the index (block codes, zlib checksums, exact sizes) and records each one's SHA-256 for BUILD_INFO.
 */
public abstract class Inputs implements AtlasStep.Source {
    /** An entry the archive has, but whose bytes weren't copied: always an error, never a skipped step. */
    public static final class NotCopied extends IOException {
        NotCopied(String path) { super("not copied: " + path); }
    }

    public static final int MAX_CONFIG = 4 * 1024 * 1024;

    private final Map<String, String> hashes = new HashMap<>();
    public long reads, inflatedBytes, peakEntryBytes;

    protected abstract VbfIndex index();

    /** The entry's stored blocks, exactly entry.storedSize bytes, or null when they weren't copied. */
    protected abstract InputStream stored(String path, VbfIndex.Entry entry) throws IOException;

    /** An x64-relative mod config ("scripts/config/..."), or null when the player doesn't have it. */
    public abstract byte[] config(String path) throws IOException;

    public byte[] read(String path) throws IOException {
        VbfIndex.Entry entry = index().find(path);
        if (entry == null) throw new AtlasStep.NoSuchEntry(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(entry.size, 1 << 20));
        try (InputStream in = stored(path, entry)) {
            if (in == null) throw new NotCopied(path);
            VbfEntryReader.inflate(in, entry, out);
        }
        byte[] data = out.toByteArray();
        hashes.put(path, sha256(data));
        reads++;
        inflatedBytes += data.length;
        peakEntryBytes = Math.max(peakEntryBytes, data.length);
        return data;
    }

    /** SHA-256 of an entry this build has read (BUILD_INFO's sources). */
    public String sourceHash(String path) throws IOException {
        String h = hashes.get(path);
        if (h == null) throw new IOException("source not read: " + path);
        return h;
    }

    public Collection<String> names() { return index().names(); }

    public long archiveBytes() { return index().archiveSize; }

    public static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder s = new StringBuilder(64);
            for (byte b : d) s.append(Character.forDigit((b & 255) >> 4, 16)).append(Character.forDigit(b & 15, 16));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
