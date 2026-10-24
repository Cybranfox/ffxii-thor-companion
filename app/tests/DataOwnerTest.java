package local.thor.ffxiicompanion;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadFactory;

import local.thor.ffxiicompanion.data.CopyJob;
import local.thor.ffxiicompanion.data.PackSwap;

/**
 * DataOwner (0.16.0, review Step3_Review_and_Core7_2026-10-24.md findings 1, 2, 4) on synthetic files, no game data:
 * one owner per process; while a cut-short swap can't be finished the pack is never readable and recovery is
 * retried every tick, then the new pack is whole; a refusal left over from another copy doesn't fail a new one.
 *   args: &lt;out dir&gt;
 */
public class DataOwnerTest {
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }

    static void put(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static String get(File f) throws IOException {
        return f.isFile() ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8) : null;
    }

    static void deleteTree(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteTree(k); else k.delete();
        d.delete();
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0], "dataowner");
        deleteTree(out);
        ThreadFactory threads = r -> {                         // daemon: the JVM can end when the test does
            Thread t = new Thread(r, "test-owner");
            t.setDaemon(true);
            return t;
        };

        DataOwner a = DataOwner.get(new File(out, "shared"), threads), b = DataOwner.get(new File(out, "other"), threads);
        check(a == b, "one owner per process: a re-created activity gets the same one, never a second writer");

        // a swap cut short whose recovery keeps failing: never readable, retried each tick, then whole
        File base = new File(out, "recover");
        for (String tag : new String[] {"old", "new"}) {
            File d = tag.equals("old") ? base : PackSwap.stage(base, "b1");
            put(new File(d, "atlas/index.json"), tag);
            put(new File(d, "reference/reference.json"), tag);
            put(new File(d, "BUILD_INFO.json"), tag);
        }
        put(new File(base, ".swap.json"), "{\"format\":\"ffxii-companion-swap-1\",\"id\":\"b1\"}");
        put(new File(base, ".prev-b1"), "a file where the backup folder must go");          // recovery can't proceed
        DataOwner o = new DataOwner(base, threads);
        check(!o.packReadable(), "before the first tick: the pack isn't readable");
        for (int i = 0; i < 3; i++) o.await(() -> o.step(null));
        check(!o.packReadable() && o.problem() != null && new File(base, ".swap.json").exists(),
                "recovery failing: still not readable after 3 ticks, problem shown (" + o.problem() + "), journal kept");
        new File(base, ".prev-b1").delete();
        o.await(() -> o.step(null));
        check(o.packReadable() && o.problem() == null && "new".equals(get(new File(base, "atlas/index.json")))
                        && "new".equals(get(new File(base, "reference/reference.json"))) && !new File(base, ".swap.json").exists(),
                "obstacle gone: the next tick finishes the swap, the new pack is whole and readable");

        // a request refusal left over from another copy doesn't fail a new one
        File c = new File(out, "stale");
        DataOwner p = new DataOwner(c, threads);
        p.startCopy("newgen").get();
        Map<String, Object> stale = new HashMap<>();
        stale.put("contract", "ffxii.rawcopy.v1");
        stale.put("status", "error");
        stale.put("error", "request_size");
        stale.put("job", "dat-oldgen");
        p.await(() -> p.step(stale));
        check(CopyJob.load(c).phase() == CopyJob.Phase.HEADER, "a refusal from another copy (job dat-oldgen): the new copy carries on");
        stale.put("requestJob", "hdr-newgen");
        p.await(() -> p.step(stale));
        check(CopyJob.load(c).phase() == CopyJob.Phase.FAILED, "a refusal the game script ties to our request (requestJob): FAILED");
        System.out.println(passed + " owner checks passed");
    }
}
