package local.thor.ffxiicompanion;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.imageio.ImageIO;

import local.thor.ffxiicompanion.data.CopyJob;
import local.thor.ffxiicompanion.data.DataBuilder;
import local.thor.ffxiicompanion.data.PackSwap;

/**
 * Step 3 (0.16.0): PackBuild + PackCheck + PackSwap.
 *   args: &lt;out dir&gt; [&lt;captured run dir&gt; &lt;extra entries dir&gt; &lt;PC builder pack dir&gt; &lt;synthetic v1 regions.json&gt;]
 * Always: PackCheck refuses an empty or garbage pack. With your own private inputs (FFXII_RAW_RUN, FFXII_RAW_EXTRA,
 * FFXII_PY_PACK in tests/local.env): a stand-in for the game script answers each copy job from the captured files,
 * then a whole build -> check -> swap; the live pack equals the PC builder's (builder name aside); PackCheck refuses
 * each kind of damage; a failed or cancelled build leaves the live pack exactly as it was; the rebuild rule.
 */
public class PackBuildTest {
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0], "packbuild");
        deleteTree(out);
        out.mkdirs();
        check(PackCheck.problem(out) != null, "empty folder: refused (" + PackCheck.problem(out) + ")");
        File junk = new File(out, "junk");
        for (String f : new String[] {"atlas/files.json", "atlas/index.json", "atlas/regions.json", "reference/reference.json", "BUILD_INFO.json"})
            put(new File(junk, f), "{not json");
        check(PackCheck.problem(junk) != null, "garbage files: refused (" + PackCheck.problem(junk) + ")");
        File legacy = new File(out, "legacy");                   // a synthetic atlas-1 index + regions, no pictures
        Files.createDirectories(new File(legacy, "atlas").toPath());
        Files.copy(new File("tests/fixtures/atlas/index.json").toPath(), new File(legacy, "atlas/index.json").toPath());
        Files.copy(new File("tests/fixtures/atlas/regions.json").toPath(), new File(legacy, "atlas/regions.json").toPath());
        String lp = PackCheck.problem(legacy, false);
        check(lp != null && lp.contains("missing or damaged"), "atlas-1 pack without its pictures: refused (" + lp + ") (review 2)");
        if (args.length >= 5 && new File(args[1], "manifest.json").isFile() && new File(args[2], "extra.json").isFile()
                && new File(args[3], "BUILD_INFO.json").isFile()) {
            golden(out, Paths(args[1]), Paths(args[2]), Paths(args[3]), new File(args[4]));
        } else {
            System.out.println("SKIP private build: FFXII_RAW_RUN / FFXII_RAW_EXTRA / FFXII_PY_PACK not set");
        }
        System.out.println(passed + " build checks passed");
    }

    static Path Paths(String s) { return java.nio.file.Paths.get(s); }

    // ---- a stand-in for the game script's raw copy, answering from the captured files ----

    @SuppressWarnings("unchecked")
    static Map<String, File> captured(Path run, Path extra) throws IOException {
        Map<String, File> byPath = new HashMap<>();
        Map<String, Object> m = (Map<String, Object>) local.thor.ffxiicompanion.data.Json.parse(Files.readAllBytes(run.resolve("manifest.json")));
        for (Map.Entry<String, Object> e : ((Map<String, Object>) m.get("entries")).entrySet()) {
            Map<String, Object> row = (Map<String, Object>) e.getValue();
            String p = (String) row.get("logicalPath");
            if (p != null) byPath.put(p.startsWith("x64/") ? p.substring(4) : p, run.resolve(e.getKey() + ".PRIVATE.bin").toFile());
        }
        Map<String, Object> x = (Map<String, Object>) local.thor.ffxiicompanion.data.Json.parse(Files.readAllBytes(extra.resolve("extra.json")));
        for (Object o : (List<Object>) x.get("entries")) {
            Map<String, Object> row = (Map<String, Object>) o;
            byPath.put((String) row.get("logicalPath"), extra.resolve((String) row.get("file")).toFile());
        }
        byPath.put("header", run.resolve("header.PRIVATE.bin").toFile());
        return byPath;
    }

    static final String[] CONFIGS = {"scripts/config/TheInsurgentsDescriptiveInventoryConfig/us.lua",
            "scripts/config/FoedexClanPrimerTextReplacerConfig/Bestiary/us.lua",
            "scripts/config/FoedexClanPrimerTextReplacerConfig/TravelersTips/us.lua"};

    /** Does what the game script would for the request in raw/: copies each item, writes receipts and done.txt. */
    static void answer(File base, Map<String, File> files) throws IOException {
        File raw = new File(base, "raw");
        List<String> lines = Files.readAllLines(new File(raw, "request.txt").toPath());
        String job = lines.get(1).substring("job=".length());
        File dir = new File(raw, job);
        dir.mkdirs();
        StringBuilder receipts = new StringBuilder();
        int n = 0;
        for (String l : lines.subList(2, lines.size())) {
            String item = l.substring("item=".length());
            File src = item.equals("header") ? files.get("header")
                    : item.startsWith("config ") ? files.get(CONFIGS[Integer.parseInt(item.substring(7)) - 1])
                    : files.get(item.substring(item.lastIndexOf(' ') + 1));
            n++;
            File dst = new File(dir, String.format("%03d.bin", n));
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            receipts.append(String.format("%03d %d\n", n, dst.length()));
        }
        put(new File(dir, "receipts.txt"), receipts.toString());
        put(new File(dir, "done.txt"), "done\n");
    }

    static CopyJob copy(File base, Map<String, File> files, String gen) throws IOException {
        CopyJob job = CopyJob.start(base, gen);
        for (int i = 0; i < 10 && job.phase() != CopyJob.Phase.DONE; i++) {
            answer(base, files);
            job.advance();
        }
        return job;
    }

    // ---- the private build ----

    static void golden(File out, Path run, Path extra, Path pyPack, File v1Regions) throws Exception {
        File base = new File(out, "FFXII_companion");
        Map<String, File> files = captured(run, extra);
        CopyJob job = copy(base, files, "g1");
        check(job.phase() == CopyJob.Phase.DONE && PackBuild.needed(base, job), "copy DONE, not built yet: a build is needed");
        long t0 = System.nanoTime();
        java.util.List<Boolean> swapping = new java.util.ArrayList<>();
        PackBuild.Result r = PackBuild.run(base, job, "b1", () -> false, swapping::add);
        long ms = (System.nanoTime() - t0) / 1000000L;
        check(r.installed && r.problem == null, "build -> check -> swap: new pack live (" + ms + " ms)");
        check(swapping.equals(java.util.Arrays.asList(true, false)), "the swap itself is bracketed (screens hold off reading)");
        check(!PackSwap.stage(base, "b1").exists() && !new File(base, ".swap.json").exists(), "no staging or journal left");
        CopyJob again = CopyJob.load(base);
        check(again.built() && !PackBuild.needed(base, again), "job marked built: no rebuild");

        Map<String, byte[]> live = files(base, "atlas", "reference", "BUILD_INFO.json");
        Map<String, byte[]> py = files(pyPack.toFile(), "atlas", "reference", "BUILD_INFO.json");
        check(live.keySet().equals(py.keySet()), "live pack has the PC builder's " + py.size() + " files");
        String pyBuilder = (String) ((Map<?, ?>) local.thor.ffxiicompanion.data.Json.parse(py.get("BUILD_INFO.json"))).get("builder");
        int same = 0, pixels = 0;
        for (String name : py.keySet()) {
            byte[] a = live.get(name), b = py.get(name);
            if (name.endsWith(".png")) {
                check2(samePixels(a, b), "pixels " + name);
                pixels++;
            } else if (name.equals("BUILD_INFO.json")) {
                String fixed = new String(b, StandardCharsets.UTF_8).replace("\"builder\":\"" + pyBuilder + "\"", "\"builder\":\"" + DataBuilder.VERSION + "\"");
                check2(Arrays.equals(a, fixed.getBytes(StandardCharsets.UTF_8)), "BUILD_INFO");
                same++;
            } else if (!name.equals("atlas/files.json")) {
                check2(Arrays.equals(a, b), "bytes " + name);
                same++;
            }
        }
        check(true, same + " JSON files byte-identical to the PC builder's (BUILD_INFO: builder \"" + DataBuilder.VERSION
                + "\"), " + pixels + " PNGs pixel-identical");

        // rebuild rule: an older app builder -> rebuild; a PC-built pack -> left alone
        File info = new File(base, "BUILD_INFO.json");
        byte[] keep = Files.readAllBytes(info.toPath());
        put(info, new String(keep, StandardCharsets.UTF_8).replace(DataBuilder.VERSION, "app-0"));
        check(PackBuild.needed(base, again), "pack from an older app builder: rebuilt from the kept copy");
        put(info, new String(keep, StandardCharsets.UTF_8).replace(DataBuilder.VERSION, pyBuilder));
        check(!PackBuild.needed(base, again), "PC-built pack: left alone");
        Files.write(info.toPath(), keep);

        // PackCheck refuses each kind of damage (on copies of the good pack)
        File good = new File(out, "good");
        copyPack(base, good);
        check(PackCheck.problem(good) == null, "PackCheck: the built pack passes");
        check(PackCheck.problem(pyPack.toFile()) != null && PackCheck.problem(pyPack.toFile(), false) == null,
                "PackCheck: the PC builder's pack isn't a new build of this app's, but is a usable live pack (setup screen)");
        damage(good, out, "png", d -> { File f = firstPng(new File(d, "atlas")); byte[] b = Files.readAllBytes(f.toPath()); b[b.length / 2] ^= 1; Files.write(f.toPath(), b); });
        damage(good, out, "files", d -> new File(d, "atlas/files.json").delete());
        damage(good, out, "regions", d -> Files.copy(v1Regions.toPath(), new File(d, "atlas/regions.json").toPath(), StandardCopyOption.REPLACE_EXISTING));
        damage(good, out, "reference", d -> { File f = new File(d, "reference/reference.json"); Files.write(f.toPath(), Arrays.copyOf(Files.readAllBytes(f.toPath()), 1000)); });
        damage(good, out, "boards", d -> new File(d, "reference/boards.json").delete());
        damage(good, out, "builder", d -> { File f = new File(d, "BUILD_INFO.json"); put(f, new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace(DataBuilder.VERSION, "app-0")); });

        // a failed or cancelled build leaves the live pack exactly as it was
        Map<String, byte[]> before = files(base, "atlas", "reference", "BUILD_INFO.json");
        CopyJob j2 = copy(base, files, "g2");
        new File(base, "raw/dat-g2/003.bin").delete();
        PackBuild.Result fail = PackBuild.run(base, j2, "b2", () -> false, s -> {});
        String why = fail.problem;
        check(!fail.installed && same(before, files(base, "atlas", "reference", "BUILD_INFO.json")) && !PackSwap.stage(base, "b2").exists(),
                "a copied file missing: build fails (" + why + "), live pack unchanged, staging removed");
        CopyJob j3 = copy(base, files, "g3");
        PackBuild.Result stop = PackBuild.run(base, j3, "b3", () -> true, s -> {});
        check(!stop.installed && "Stopped".equals(stop.problem) && same(before, files(base, "atlas", "reference", "BUILD_INFO.json")) && !PackSwap.stage(base, "b3").exists(),
                "cancelled build: live pack unchanged, staging removed");
        if (System.getProperty("os.name").startsWith("Windows")) {
            CopyJob j4 = copy(base, files, "g4");
            PackBuild.Result r4;
            try (java.io.FileInputStream hold = new java.io.FileInputStream(new File(base, "raw/app-job.json"))) {
                r4 = PackBuild.run(base, j4, "b4", () -> false, s -> {});
            }
            check(r4.installed && r4.problem != null && PackCheck.problem(base) == null && !CopyJob.load(base).built(),
                    "swap done but recording it failed: still reported installed (reload), with a warning (" + r4.problem + ")");
            j3 = j4;
        }
        j3.keepOnlyThis();
        check(new File(base, "raw/dat-" + j3.gen()).isDirectory() && !new File(base, "raw/dat-g1").exists(), "keepOnlyThis after a build: older copies removed");
    }

    interface Damage { void apply(File d) throws IOException; }

    static void damage(File good, File out, String what, Damage d) throws IOException {
        File copy = new File(out, "damaged-" + what);
        copyPack(good, copy);
        d.apply(copy);
        String p = PackCheck.problem(copy);
        check(p != null, "PackCheck refuses damage '" + what + "': " + p);
    }

    static File firstPng(File dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir.toPath())) {
            return s.filter(p -> p.toString().endsWith(".png")).sorted().findFirst().get().toFile();
        }
    }

    static void check2(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    static boolean same(Map<String, byte[]> a, Map<String, byte[]> b) {
        if (!a.keySet().equals(b.keySet())) return false;
        for (String k : a.keySet()) if (!Arrays.equals(a.get(k), b.get(k))) return false;
        return true;
    }

    static Map<String, byte[]> files(File base, String... items) throws IOException {
        Map<String, byte[]> m = new TreeMap<>();
        for (String it : items) {
            File f = new File(base, it);
            if (!f.exists()) continue;
            try (Stream<Path> s = Files.walk(f.toPath())) {
                for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList()))
                    m.put(base.toPath().relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
            }
        }
        return m;
    }

    static void copyPack(File from, File to) throws IOException {
        deleteTree(to);
        for (Map.Entry<String, byte[]> e : files(from, "atlas", "reference", "BUILD_INFO.json").entrySet()) {
            File f = new File(to, e.getKey());
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), e.getValue());
        }
    }

    static boolean samePixels(byte[] a, byte[] b) throws IOException {
        BufferedImage x = ImageIO.read(new ByteArrayInputStream(a)), y = ImageIO.read(new ByteArrayInputStream(b));
        if (x == null || y == null || x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()
                || x.getRaster().getNumBands() != y.getRaster().getNumBands()) return false;
        int w = x.getWidth(), h = x.getHeight();
        return Arrays.equals(x.getRaster().getPixels(0, 0, w, h, (int[]) null), y.getRaster().getPixels(0, 0, w, h, (int[]) null));
    }

    static void put(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    static void deleteTree(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteTree(k); else k.delete();
        d.delete();
    }
}
