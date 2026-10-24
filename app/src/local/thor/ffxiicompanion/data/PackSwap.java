package local.thor.ffxiicompanion.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * Puts a new, already checked pack in place of the live one (FFXII_companion/atlas, reference, BUILD_INFO.json) without
 * ever leaving a mix. The staged pack is .build-&lt;id&gt;/; the live one moves to .prev-&lt;id&gt;/ (one kept, for rollback).
 * A journal (.swap.json) is written first and removed last; every step is a same-folder rename that's skipped when
 * already done, so a swap cut short (the app killed) is finished by recover() on the next start: the staged pack
 * was already checked, so rolling forward is always right. A .build-* without a journal is a build that never
 * finished: deleted.
 */
public final class PackSwap {
    static final List<String> ITEMS = List.of("atlas", "reference", "BUILD_INFO.json");
    static final String JOURNAL = ".swap.json", FORMAT = "ffxii-companion-swap-1";

    private PackSwap() {}

    public static File stage(File base, String id) { return new File(base, ".build-" + id); }

    /** Swaps .build-&lt;id&gt; in. id: [A-Za-z0-9_], 1..32, new for every build. */
    public static void swap(File base, String id) throws IOException {
        swap(base, id, Integer.MAX_VALUE);
    }

    /** For tests: stops after `renames` renames, the way a killed app would. */
    static void swap(File base, String id, int renames) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_]{1,32}")) throw new IOException("build id");
        if (!stage(base, id).isDirectory()) throw new IOException("nothing staged");
        if (new File(base, JOURNAL).exists()) throw new IOException("another swap is still pending");   // never two
        CopyJob.writeAtomic(new File(base, JOURNAL), ("{\"format\":\"" + FORMAT + "\",\"id\":\"" + id + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        finish(base, id, renames);
    }

    /** On app start, before anything reads the pack: finishes a cut-short swap, removes unfinished builds. */
    public static void recover(File base) throws IOException {
        File j = new File(base, JOURNAL);
        if (j.isFile()) {
            String text = j.length() < 4096 ? new String(Files.readAllBytes(j.toPath()), StandardCharsets.UTF_8) : "";
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "^\\{\"format\":\"" + FORMAT + "\",\"id\":\"([A-Za-z0-9_]{1,32})\"\\}$").matcher(text.trim());
            if (m.find()) finish(base, m.group(1), Integer.MAX_VALUE);   // items already moved are skipped
            else {                                       // not one of ours: keep it as evidence, don't act on it
                File bad = new File(base, JOURNAL + ".bad-" + System.currentTimeMillis());
                if (!j.renameTo(bad)) throw new IOException("can't set aside " + j);
            }
        }
        File[] all = base.listFiles();
        if (all == null) return;
        File newest = null;                         // the one rollback copy; older ones are leftovers (a cleanup cut short)
        for (File d : all) {
            if (!d.isDirectory()) continue;
            if (d.getName().startsWith(".build-")) deleteTree(d);
            else if (d.getName().startsWith(".prev-") && (newest == null || d.lastModified() > newest.lastModified())) newest = d;
        }
        for (File d : all) if (d.isDirectory() && d.getName().startsWith(".prev-") && !d.equals(newest)) deleteTree(d);
    }

    private static void finish(File base, String id, int renames) throws IOException {
        File stage = stage(base, id), prev = new File(base, ".prev-" + id);
        if (!prev.isDirectory() && !prev.mkdirs()) throw new IOException("can't make " + prev);
        int done = 0;
        for (String item : ITEMS) {
            File live = new File(base, item), staged = new File(stage, item), old = new File(prev, item);
            if (!staged.exists()) continue;                              // this item is already in place
            if (live.exists()) {
                if (old.exists()) deleteTree(old);
                if (done++ >= renames) return;
                rename(live, old);
            }
            if (done++ >= renames) return;
            rename(staged, live);
        }
        deleteTree(stage);
        if (!new File(base, JOURNAL).delete()) throw new IOException("can't remove the swap journal");
        File[] all = base.listFiles();
        if (all != null)
            for (File d : all) if (d.isDirectory() && d.getName().startsWith(".prev-") && !d.equals(prev)) deleteTree(d);
    }

    private static void rename(File from, File to) throws IOException {
        if (!from.renameTo(to)) throw new IOException("can't move " + from.getName() + " to " + to);
    }

    static void deleteTree(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteTree(k); else k.delete();
        d.delete();
    }

    /** Writes a built pack into its staging folder (atlas/, reference/, BUILD_INFO.json, as the live layout). */
    public static void write(List<DataBuilder.Output> pack, File stage) throws IOException {
        for (DataBuilder.Output o : pack) {
            File f = new File(stage, o.path);
            File dir = f.getParentFile();
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("can't make " + dir);
            Files.write(f.toPath(), o.data);
        }
    }
}
