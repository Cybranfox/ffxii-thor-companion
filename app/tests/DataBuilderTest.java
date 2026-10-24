package local.thor.ffxiicompanion.data;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;

/**
 * The phone data builder (DataBuilder over RawInputs).
 *   args: &lt;out dir&gt; [&lt;captured run dir&gt; &lt;PC builder pack dir (FFXII_companion)&gt; &lt;extra entries dir&gt;]
 * 1. Synthetic archive (always, no game data): entries inflate and check against the index; a wrong stored size,
 *    an uncopied entry, an unknown entry, a missing or oversized config are each refused or reported as such.
 * 2. Golden (only with your own private inputs, FFXII_RAW_RUN + FFXII_PY_PACK + FFXII_RAW_EXTRA in tests/local.env;
 *    the extra dir holds entries the app needs beyond the captured run, e.g. menu_command.bin): the whole pack
 *    from the captured raw files, as the app builds it (archive size from the header), against the unchanged PC
 *    builder's pack from the same files. Same file list;
 *    every JSON byte-identical except atlas/files.json (that one lists this writer's own PNG bytes, and is checked
 *    against them); every PNG decodes to the same pixels.
 */
public class DataBuilderTest {
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }

    interface Body { void run() throws Exception; }

    static void refuses(Class<? extends Exception> type, Body body, String what) {
        try {
            body.run();
        } catch (Exception e) {
            check(type.isInstance(e), what + " (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
            return;
        }
        throw new AssertionError("accepted: " + what);
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args[0], "databuilder");
        out.mkdirs();
        synthetic(out);
        if (args.length >= 4 && !args[1].isEmpty() && new File(args[1], "manifest.json").isFile()
                && new File(args[2], "BUILD_INFO.json").isFile() && new File(args[3], "extra.json").isFile()) {
            golden(Paths.get(args[1]), Paths.get(args[2]), Paths.get(args[3]));
        } else {
            System.out.println("SKIP golden pack: FFXII_RAW_RUN / FFXII_PY_PACK / FFXII_RAW_EXTRA not set (private inputs)");
        }
        System.out.println(passed + " checks passed");
    }

    // ---- 1. synthetic archive ----

    /** A SRYK header for the given entries (name, logical size, block codes, archive offset). */
    static byte[] header(String[] names, long[] sizes, int[][] codes, long[] offsets) {
        int n = names.length;
        ByteArrayOutputStream nameTable = new ByteArrayOutputStream();
        long[] nameOff = new long[n];
        for (int i = 0; i < n; i++) {
            nameOff[i] = nameTable.size();
            byte[] b = names[i].getBytes(StandardCharsets.UTF_8);
            nameTable.write(b, 0, b.length);
            nameTable.write(0);
        }
        int blocks = 0;
        for (int[] c : codes) blocks += c.length;
        int namesAt = 16 + 48 * n, end = namesAt + 4 + nameTable.size() + 2 * blocks;
        ByteBuffer b = ByteBuffer.allocate(end).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, 0x4b595253).putInt(4, end).putLong(8, n);
        int first = 0;
        for (int i = 0; i < n; i++) {
            int row = 16 + 16 * n + 32 * i;
            b.putInt(row, first).putLong(row + 8, sizes[i]).putLong(row + 16, offsets[i] + end).putLong(row + 24, nameOff[i]);
            for (int c : codes[i]) b.putShort(namesAt + 4 + nameTable.size() + 2 * first++, (short) c);
        }
        b.putInt(namesAt, 4 + nameTable.size());
        System.arraycopy(nameTable.toByteArray(), 0, b.array(), namesAt + 4, nameTable.size());
        return b.array();
    }

    static byte[] zlib(byte[] data) {
        Deflater d = new Deflater(9);
        d.setInput(data);
        d.finish();
        byte[] buf = new byte[data.length + 64];
        int n = d.deflate(buf);
        d.end();
        return Arrays.copyOf(buf, n);
    }

    static File write(File dir, String name, byte[] data) throws IOException {
        File f = new File(dir, name);
        Files.write(f.toPath(), data);
        return f;
    }

    static void synthetic(File out) throws Exception {
        byte[] text = new byte[5000];
        for (int i = 0; i < text.length; i++) text[i] = (byte) ("companion".charAt(i % 9));
        byte[] packed = zlib(text);
        byte[] raw = "raw block".getBytes(StandardCharsets.US_ASCII);
        String[] names = {"a/x.bin", "b/y.bin", "c/z.bin"};
        byte[] h = header(names, new long[] {text.length, raw.length, 10},
                new int[][] {{packed.length}, {raw.length}, {10}}, new long[] {0, packed.length, packed.length + raw.length});
        long archive = h.length + packed.length + raw.length + 10 + 8192;     // a logical size can't exceed the archive
        File hf = write(out, "header.bin", h);
        Map<String, File> entries = new HashMap<>();
        entries.put("a/x.bin", write(out, "001.bin", packed));
        entries.put("b/y.bin", write(out, "002.bin", raw));
        Map<String, File> configs = new HashMap<>();
        configs.put("scripts/config/small.lua", write(out, "003.bin", "return {}".getBytes(StandardCharsets.US_ASCII)));
        File big = new File(out, "004.bin");
        Files.write(big.toPath(), new byte[Inputs.MAX_CONFIG + 1]);
        configs.put("scripts/config/big.lua", big);
        RawInputs in = new RawInputs(hf, archive, entries, configs);

        check(in.names().size() == 3 && in.archiveBytes() == archive, "index: 3 names, archive size as given");
        check(Arrays.equals(in.read("a/x.bin"), text), "zlib block inflates to the logical bytes");
        check(Arrays.equals(in.read("b/y.bin"), raw), "raw block (code == size) copies through");
        check(in.sourceHash("a/x.bin").equals(Inputs.sha256(text)), "source hash = SHA-256 of the inflated entry");
        refuses(Inputs.NotCopied.class, () -> in.read("c/z.bin"), "entry in the index but not copied: stops");
        refuses(AtlasStep.NoSuchEntry.class, () -> in.read("d/none.bin"), "entry not in the archive: NoSuchEntry");
        refuses(IOException.class, () -> in.sourceHash("c/z.bin"), "source hash of an entry never read");
        check(in.config("scripts/config/missing.lua") == null, "missing config: null (optional mod absent)");
        check(new String(in.config("scripts/config/small.lua"), StandardCharsets.US_ASCII).equals("return {}"),
                "config bytes as copied");
        refuses(IOException.class, () -> in.config("scripts/config/big.lua"), "config over 4 MiB refused");

        Map<String, File> shortCopy = new HashMap<>(entries);
        shortCopy.put("a/x.bin", write(out, "005.bin", Arrays.copyOf(packed, packed.length - 1)));
        RawInputs cut = new RawInputs(hf, archive, shortCopy, configs);
        refuses(IOException.class, () -> cut.read("a/x.bin"), "stored file shorter than the index says: refused");
        byte[] bad = packed.clone();
        bad[bad.length - 1] ^= 1;                                    // adler32 trailer
        Map<String, File> corrupt = new HashMap<>(entries);
        corrupt.put("a/x.bin", write(out, "006.bin", bad));
        RawInputs broken = new RawInputs(hf, archive, corrupt, configs);
        refuses(IOException.class, () -> broken.read("a/x.bin"), "corrupt zlib block refused");
        refuses(IOException.class, () -> new RawInputs(hf, h.length + packed.length, entries, configs),
                "archive size smaller than the index's extents refused");
        refuses(IOException.class, () -> DataBuilder.jobNames(new byte[] {'n', 'o', 'p', 'e'}),
                "job names: a file that isn't st2e refused");
        byte[] empty = ByteBuffer.allocate(40).order(ByteOrder.LITTLE_ENDIAN).put(new byte[] {'s', 't', '2', 'e'})
                .putInt(0).putInt(4).putInt(24).putInt(0).putInt(24).putInt(1).putInt(8).putInt(0).array();
        refuses(IOException.class, () -> DataBuilder.jobNames(empty), "job names: an st2e without the board rows stops the build");
        ByteBuffer moved = ByteBuffer.allocate(24 + 4 * 17).order(ByteOrder.LITTLE_ENDIAN);
        moved.put(new byte[] {'s', 't', '2', 'e'}).putInt(17).putInt(4).putInt(24).putInt(0).putInt(24 + 4 * 17);
        for (int i = 0; i < 17; i++) moved.putShort((short) (i < 5 ? i : 10100 + (i - 5 + 1) % 12)).putShort((short) 0);
        refuses(IOException.class, () -> DataBuilder.jobNames(moved.array()),
                "job names: ids 10100.. present but not at rows 5..16 (reordered table) refused");
        RawInputs canonical = new RawInputs(hf, entries, configs);
        check(canonical.archiveBytes() == h.length + packed.length + raw.length + 10 + VbfIndex.FOOTER,
                "archive size from the header: last stored byte + 16-byte footer");
    }

    // ---- 2. golden pack ----

    @SuppressWarnings("unchecked")
    static void golden(Path run, Path pyPack, Path extra) throws Exception {
        Map<String, Object> manifest = (Map<String, Object>) Json.parse(Files.readAllBytes(run.resolve("manifest.json")));
        long archive = ((Number) ((Map<String, Object>) manifest.get("sourceFingerprint")).get("archiveBytes")).longValue();
        Map<String, File> entries = new HashMap<>(), configs = new HashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) manifest.get("entries")).entrySet()) {
            Map<String, Object> row = (Map<String, Object>) e.getValue();
            File f = run.resolve(e.getKey() + ".PRIVATE.bin").toFile();
            String path = (String) row.get("logicalPath");
            if ("vbf-entry".equals(row.get("kind"))) entries.put(path, f);
            else if ("mod-config".equals(row.get("kind"))) configs.put(path.substring("x64/".length()), f);
        }
        check(entries.size() == 74 && configs.size() == 3, "captured raw set: 74 entries + 3 configs");
        for (Object o : (List<Object>) ((Map<String, Object>) Json.parse(Files.readAllBytes(extra.resolve("extra.json")))).get("entries")) {
            Map<String, Object> row = (Map<String, Object>) o;
            byte[] b = Files.readAllBytes(extra.resolve((String) row.get("file")));
            if (!Inputs.sha256(b).equals(row.get("sha256Stored"))) throw new AssertionError("extra entry hash");
            entries.put((String) row.get("logicalPath"), extra.resolve((String) row.get("file")).toFile());
        }
        check(entries.containsKey(DataBuilder.MENU_COMMAND), "plus menu_command.bin (licence board names)");
        Map<String, Object> pyInfo = (Map<String, Object>) Json.parse(Files.readAllBytes(pyPack.resolve("BUILD_INFO.json")));

        long t0 = System.nanoTime();
        RawInputs in = new RawInputs(run.resolve("header.PRIVATE.bin").toFile(), entries, configs);
        check(in.archiveBytes() == archive, "archive size worked out from the header == the real file's ("
                + archive + ")");
        List<DataBuilder.Output> outs = DataBuilder.build(in, (String) pyInfo.get("builder"));
        long ms = (System.nanoTime() - t0) / 1000000L;
        check(new java.util.TreeSet<>(in.readPaths()).equals(new java.util.TreeSet<>(Recipe.entries(in.index()))),
                "the copy recipe (" + Recipe.entries(in.index()).size() + " entries) is exactly what the build reads");

        Map<String, byte[]> java = new TreeMap<>();
        for (DataBuilder.Output o : outs)
            if (java.put(o.path, o.data) != null) throw new AssertionError("written twice: " + o.path);
        Map<String, byte[]> py = new TreeMap<>();
        try (Stream<Path> s = Files.walk(pyPack)) {
            for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList()))
                py.put(pyPack.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
        }
        check(java.keySet().equals(py.keySet()), "same " + py.size() + " files as the PC builder's pack");

        int identical = 0, pixels = 0;
        for (String name : py.keySet()) {
            byte[] a = java.get(name), b = py.get(name);
            if (name.endsWith(".png")) {
                if (!samePixels(a, b)) throw new AssertionError("pixels differ: " + name);
                pixels++;
            } else if (!name.equals("atlas/files.json")) {
                if (!Arrays.equals(a, b)) throw new AssertionError("bytes differ: " + name);
                identical++;
            }
        }
        check(identical == py.size() - 1 - pixels, identical + " JSON files byte-identical (reference, boards with the decoded job names, areas, objectives, "
                + "BUILD_INFO, atlas index/regions)");
        check(pixels == 419, pixels + " PNGs pixel-identical (390 sheets + 29 symbols)");

        Map<String, Object> files = (Map<String, Object>) Json.parse(java.get("atlas/files.json"));
        Map<String, Object> pyFiles = (Map<String, Object>) Json.parse(py.get("atlas/files.json"));
        List<Object> rows = (List<Object>) files.get("files"), pyRows = (List<Object>) pyFiles.get("files");
        check(rows.size() == pyRows.size() && files.get("format").equals(pyFiles.get("format")),
                "files.json: same format and count as the PC's");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = (Map<String, Object>) rows.get(i), q = (Map<String, Object>) pyRows.get(i);
            byte[] png = java.get("atlas/" + r.get("file"));
            if (!r.get("file").equals(q.get("file")) || png == null || ((Number) r.get("bytes")).intValue() != png.length
                    || !Inputs.sha256(png).equals(r.get("sha256")))
                throw new AssertionError("files.json row " + i);
        }
        check(true, "files.json lists every PNG in the PC's order, with this writer's sizes and SHA-256s");

        long peak = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
            if (pool.getType() == MemoryType.HEAP) peak += pool.getPeakUsage().getUsed();
        System.out.println("INFO build " + ms + " ms, " + in.reads + " entry reads, " + in.inflatedBytes
                + " inflated bytes, largest " + in.peakEntryBytes + ", heap pool peaks " + (peak >> 20) + " MiB");
    }

    static boolean samePixels(byte[] a, byte[] b) throws IOException {
        BufferedImage x = ImageIO.read(new ByteArrayInputStream(a)), y = ImageIO.read(new ByteArrayInputStream(b));
        if (x == null || y == null || x.getWidth() != y.getWidth() || x.getHeight() != y.getHeight()) return false;
        Raster p = x.getRaster(), q = y.getRaster();
        if (p.getNumBands() != q.getNumBands()) return false;
        int w = x.getWidth(), h = x.getHeight();
        return Arrays.equals(p.getPixels(0, 0, w, h, (int[]) null), q.getPixels(0, 0, w, h, (int[]) null));
    }
}
