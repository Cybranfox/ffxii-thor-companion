package local.thor.ffxiicompanion;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Function;

/**
 * Bounded reads of the files in Download/FFXII_companion (pure Java, so the JVM tests cover it). Every read has a size
 * cap; anything missing, too large, short or unparsable comes back as null.
 */
final class DataFiles {
    private DataFiles() {}

    /** The whole file, or null when it is missing, larger than max or could not be read in full. */
    static byte[] readBytes(File f, int max) {
        if (!f.isFile() || f.length() > max) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return n == buf.length ? buf : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** UTF-8 text of at most max + 1 bytes (one over, so the parser can see it was too long), or null. */
    static String readText(File f, int max) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), max + 1L)];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    static String sha256(byte[] data) {
        try {
            StringBuilder b = new StringBuilder();
            for (byte x : MessageDigest.getInstance("SHA-256").digest(data)) b.append(String.format("%02x", x));
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return "";
        }
    }

    /** The file's top-level JSON object, or null. */
    static Map<String, Object> jsonObject(File f, int max) {
        byte[] data = readBytes(f, max);
        if (data == null) return null;
        Object o;
        try {
            o = Json.parse(new String(data, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return null;
        }
        return o instanceof Map ? Json.obj(java.util.Collections.singletonMap("x", o), "x") : null;
    }

    /** A parsed file, re-read only when its modification time changes (and again once it disappears). */
    static final class Cached<T> {
        private final File file;
        private final int max;
        private final Function<Map<String, Object>, T> parse;
        private long stamp = Long.MIN_VALUE;
        private T value;

        Cached(File file, int max, Function<Map<String, Object>, T> parse) {
            this.file = file;
            this.max = max;
            this.parse = parse;
        }

        T get() {
            long mt = file.isFile() ? file.lastModified() : -1;
            if (mt != stamp) {
                stamp = mt;
                Map<String, Object> o = mt < 0 ? null : jsonObject(file, max);
                value = o == null ? null : parse.apply(o);
            }
            return value;
        }
    }
}
