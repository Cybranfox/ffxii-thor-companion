package local.thor.ffxiicompanion;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Bounded reads, the mtime cache and the Details texts that moved out of MainActivity. */
public final class DataFilesTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void write(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        dir.mkdirs();
        File f = new File(dir, "datafiles.json");
        write(f, "{\"a\":1}");
        check(DataFiles.readBytes(f, 7) != null && DataFiles.readBytes(f, 6) == null, "readBytes cap");
        check(DataFiles.readBytes(new File(dir, "missing.json"), 100) == null, "missing file");
        check(DataFiles.readText(f, 3).equals("{\"a\""), "readText reads max + 1 so callers see it was too long");
        check(DataFiles.readText(new File(dir, "missing.json"), 10) == null, "readText missing");
        check(DataFiles.sha256("abc".getBytes(StandardCharsets.US_ASCII))
                .equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "sha256");
        check(Json.lng(DataFiles.jsonObject(f, 100), "a") == 1L, "jsonObject");
        write(f, "[1,2]");
        check(DataFiles.jsonObject(f, 100) == null, "a JSON array is not an object");
        write(f, "{\"a\":1,\"a\":2}");
        check(DataFiles.jsonObject(f, 100) == null, "duplicate keys refused");

        int[] parses = {0};
        DataFiles.Cached<Long> c = new DataFiles.Cached<>(f, 100, o -> { parses[0]++; return Json.lng(o, "v"); });
        write(f, "{\"v\":5}");
        f.setLastModified(1_000_000_000_000L);
        check(c.get() == 5L && c.get() == 5L && parses[0] == 1, "parsed once while unchanged");
        write(f, "{\"v\":6}");
        f.setLastModified(1_000_000_100_000L);
        check(c.get() == 6L && parses[0] == 2, "re-read after a change");
        check(f.delete() && c.get() == null, "gone when the file goes");

        Map<String, Object> hello = Json.obj(java.util.Collections.singletonMap("x", Json.parse(
                "{\"mods\":[{\"id\":\"ffgriever.efl\",\"file_present\":{\"value\":true}},"
                + "{\"id\":\"insurgents.manifesto\",\"file_present\":{\"value\":false}}],"
                + "\"capabilities\":{\"map\":\"on\",\"battle\":\"off\"}}")), "x");
        check(Details.mods(hello).equals("• External File Loader"), "only installed mods listed");
        check(Details.feeds(hello).equals("Available: map\nNot yet: battle"), "feeds");
        check(Details.build(hello).equals("—") && Details.mods(null).equals("—"), "missing blocks");
        check(Details.secs(59_000).equals("59s") && Details.secs(61_000).equals("1m")
                && Details.secs(3_720_000).equals("1h 2m") && Details.secs(-5).equals("0s"), "secs");
        System.out.println("PASS DataFilesTest 15 checks (bounded reads, mtime cache, Details texts)");
    }
}
