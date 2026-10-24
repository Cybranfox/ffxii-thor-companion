package local.thor.ffxiicompanion.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * PackSwap on synthetic packs (no game data): a swap cut short after every possible rename, then recover(), always
 * ends with exactly the new pack live and the old one in .prev; a build that never reached the journal is removed and
 * the live pack is untouched; a first install (no live pack) works; only one .prev is kept.
 *   args: &lt;out dir&gt;
 */
public class PackSwapTest {
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

    static void pack(File dir, String tag) throws IOException {
        put(new File(dir, "atlas/index.json"), tag + " index");
        put(new File(dir, "atlas/icons/type_01.png"), tag + " icon");
        put(new File(dir, "reference/reference.json"), tag + " reference");
        put(new File(dir, "BUILD_INFO.json"), tag + " info");
    }

    static boolean is(File dir, String tag) throws IOException {
        return (tag + " index").equals(get(new File(dir, "atlas/index.json")))
                && (tag + " icon").equals(get(new File(dir, "atlas/icons/type_01.png")))
                && (tag + " reference").equals(get(new File(dir, "reference/reference.json")))
                && (tag + " info").equals(get(new File(dir, "BUILD_INFO.json")));
    }

    static File fresh(File out, String name, boolean live) throws IOException {
        File base = new File(out, name);
        PackSwap.deleteTree(base);
        base.mkdirs();
        if (live) pack(base, "old");
        pack(PackSwap.stage(base, "b1"), "new");
        return base;
    }

    static boolean clean(File base) {
        return !new File(base, ".swap.json").exists() && !PackSwap.stage(base, "b1").exists();
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0], "packswap");
        out.mkdirs();

        File base = fresh(out, "full", true);
        PackSwap.swap(base, "b1");
        check(is(base, "new") && is(new File(base, ".prev-b1"), "old") && clean(base),
                "swap: new pack live, old one in .prev-b1, no staging or journal left");

        for (int k = 0; k < 6; k++) {
            base = fresh(out, "cut" + k, true);
            PackSwap.swap(base, "b1", k);
            check(new File(base, ".swap.json").isFile(), "cut after " + k + " renames: journal still there");
            PackSwap.recover(base);
            check(is(base, "new") && is(new File(base, ".prev-b1"), "old") && clean(base),
                    "cut after " + k + " renames, then recover(): exactly the new pack live, old one in .prev");
        }

        base = fresh(out, "nojournal", true);
        PackSwap.recover(base);
        check(is(base, "old") && clean(base), "build never reached the swap: staging removed, live pack untouched");

        base = fresh(out, "first", false);
        PackSwap.swap(base, "b1");
        check(is(base, "new") && clean(base), "first install (no live pack): new pack live");

        base = fresh(out, "prev", true);
        pack(new File(base, ".prev-b0"), "older");
        PackSwap.swap(base, "b1");
        check(!new File(base, ".prev-b0").exists() && is(new File(base, ".prev-b1"), "old"), "only one .prev kept");

        try {
            PackSwap.swap(base, "b2");
            throw new AssertionError("swapped with nothing staged");
        } catch (IOException e) {
            check(is(base, "new"), "nothing staged: refused, live pack untouched");
        }
        base = fresh(out, "two", true);
        CopyJob.writeAtomic(new File(base, ".swap.json"), "{\"format\":\"ffxii-companion-swap-1\",\"id\":\"b0\"}".getBytes(StandardCharsets.UTF_8));
        try {
            PackSwap.swap(base, "b1");
            throw new AssertionError("second swap over a pending journal");
        } catch (IOException e) {
            check(is(base, "old"), "a swap while another's journal is pending: refused, live pack untouched");
        }

        base = fresh(out, "malformed", true);
        put(new File(base, ".swap.json"), "{\"id\":\"b1\",\"other\":1}");
        PackSwap.recover(base);
        File[] bad = base.listFiles((d, n) -> n.startsWith(".swap.json.bad-"));
        check(is(base, "old") && !new File(base, ".swap.json").exists() && bad != null && bad.length == 1 && !PackSwap.stage(base, "b1").exists(),
                "a journal that isn't ours: not acted on (kept as .swap.json.bad-*), live pack untouched, staging removed");

        base = fresh(out, "leftover", true);
        PackSwap.swap(base, "b1");
        File stale = new File(base, ".prev-b0");
        pack(stale, "older");
        new File(stale, "atlas/index.json").delete();                    // a cleanup the app was killed in
        stale.setLastModified(new File(base, ".prev-b1").lastModified() - 60_000);
        PackSwap.recover(base);
        check(!stale.exists() && is(new File(base, ".prev-b1"), "old") && is(base, "new"),
                "next start: a half-deleted older .prev is removed, the newest rollback copy kept");

        System.out.println(passed + " swap checks passed");
    }
}
