package local.thor.ffxiicompanion;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Atlas 2 (0.15.0): pixel hashes in index.json/regions.json, PNG file hashes in files.json. Synthetic checks, plus an
 * optional real check: pass a PC-built atlas folder and every PNG is checked against files.json and decoded to its
 * pixel hash (the data builder's Python and this Java must agree on all of them).
 */
public class AtlasTest {
    static int passed = 0;

    static void check(boolean c, String what) {
        if (!c) throw new AssertionError(what);
    }

    static void ok(String what) {
        passed++;
        System.out.println("ok " + what);
    }

    // The same vectors are in builder/tests/test_mapatlas_pixels.py.
    static final String L8_2X1 = "ff6104d9f6277429db12e85861f759425d3e954e8fcfb40d79c6b476fda37f52";
    static final String RGBA_1X2 = "0dd3a1419891c20d56adda387ea74bb2690ecdfbf1ac0f84b11fcc8daaaa1849";
    static final String A = "a".repeat(64), B = "b".repeat(64), C = "c".repeat(64);

    static String tiles() { return "[\"########\",\"########\",\"########\",\"########\",\"########\",\"########\",\"########\",\"########\"]"; }

    static String index(String format, String sheetHashes) {
        return "{\"format\":\"" + format + "\",\"sheets\":[{\"mapId\":7,\"region\":\"r\",\"sheet\":0,\"file\":\"7_r_0.png\","
                + "\"width\":512,\"height\":512,\"stride\":1,\"sheetsForMap\":1,\"tiles\":" + tiles() + "," + sheetHashes + "}]}";
    }

    static String regions(String format, String symbolHashes) {
        return "{\"format\":\"" + format + "\",\"unitsPerSheetPx\":0.25,\"regions\":[{\"region\":\"r\",\"sheets\":"
                + "[{\"mapId\":7,\"sheet\":0,\"file\":\"7_r_0.png\",\"x\":0,\"y\":0}],\"links\":[]}],"
                + "\"symbols\":[{\"type\":3,\"file\":\"icons/type_03.png\",\"width\":16,\"height\":16," + symbolHashes + "}],"
                + "\"thresholds\":[[65535]]}";
    }

    static String files(String rows) { return "{\"format\":\"ffxii-companion-atlas-files-1\",\"files\":[" + rows + "]}"; }

    static String row(String file, String sha) { return "{\"file\":\"" + file + "\",\"bytes\":10,\"sha256\":\"" + sha + "\"}"; }

    public static void main(String[] args) throws Exception {
        // 1. the shared pixel-hash vectors
        check(L8_2X1.equals(Atlas.pixelSha256(1, 2, 1, new byte[] {0, (byte) 255})), "L8 vector");
        check(RGBA_1X2.equals(Atlas.pixelSha256(4, 1, 2, new byte[] {10, 20, 30, 0, (byte) 255, 0, (byte) 128, (byte) 255})),
                "RGBA vector, RGB kept under alpha 0");
        check(!RGBA_1X2.equals(Atlas.pixelSha256(4, 1, 2, new byte[] {0, 0, 0, 0, (byte) 255, 0, (byte) 128, (byte) 255})),
                "hidden RGB is part of the hash");
        check(!L8_2X1.equals(Atlas.pixelSha256(1, 1, 2, new byte[] {0, (byte) 255})), "size is part of the hash");
        check(Atlas.pixelSha256(1, 2, 2, new byte[3]) == null && Atlas.pixelSha256(3, 1, 1, new byte[3]) == null,
                "bad buffers refused");
        ok("pixel hash matches the builder's vectors");

        // 2. atlas 1 still reads as before, with or without a receipt lying around
        String v1 = index(Atlas.FORMAT, "\"bytes\":10,\"sha256\":\"" + A + "\"");
        Atlas a1 = Atlas.parse(v1);
        check(a1 != null && a1.version == 1 && A.equals(a1.sheets(7).get(0).sha256), "v1 index");
        check(Atlas.parse(v1, AtlasFiles.parse(files(row("7_r_0.png", B)))).sheets(7).get(0).sha256.equals(A),
                "v1 keeps its own hash even if a receipt exists");
        Regions r1 = Regions.parse(regions(Regions.FORMAT, "\"sha256\":\"" + C + "\""));
        check(r1 != null && r1.version == 1 && C.equals(r1.symbols.get(3).sha256), "v1 regions");
        ok("atlas 1 and regions 1 unchanged");

        // 3. atlas 2 takes file hashes from the receipt
        String px = "\"pixelEncoding\":\"L8\",\"pixelSha256\":\"" + L8_2X1 + "\"";
        String v2 = index(Atlas.FORMAT_2, px);
        AtlasFiles rec = AtlasFiles.parse(files(row("7_r_0.png", B) + "," + row("icons/type_03.png", C)));
        check(rec != null && rec.size() == 2, "receipt parses");
        Atlas a2 = Atlas.parse(v2, rec);
        check(a2 != null && a2.version == 2 && B.equals(a2.sheets(7).get(0).sha256), "v2 sheet takes the receipt hash");
        Regions r2 = Regions.parse(regions(Regions.FORMAT_2, "\"pixelEncoding\":\"RGBA8\",\"pixelSha256\":\"" + RGBA_1X2 + "\""), rec);
        check(r2 != null && r2.version == 2 && C.equals(r2.symbols.get(3).sha256), "v2 symbol takes the receipt hash");
        ok("atlas 2 and regions 2 read with files.json");

        // 4. refusals: no receipt, sheet missing from it, wrong encoding, bad receipt rows
        check(Atlas.parse(v2) == null && Atlas.parse(v2, null) == null, "v2 without receipt refused");
        check(Atlas.parse(v2, AtlasFiles.parse(files(row("other_0.png", B)))) == null, "sheet without receipt row skipped");
        check(Atlas.parse(index(Atlas.FORMAT_2, "\"pixelEncoding\":\"RGBA8\",\"pixelSha256\":\"" + L8_2X1 + "\""), rec) == null,
                "mask must be L8");
        check(Atlas.parse(index(Atlas.FORMAT_2, "\"pixelEncoding\":\"L8\",\"pixelSha256\":\"xyz\""), rec) == null, "bad pixel hash");
        check(Regions.parse(regions(Regions.FORMAT_2, "\"pixelEncoding\":\"RGBA8\",\"pixelSha256\":\"" + RGBA_1X2 + "\""), null) == null,
                "regions 2 without receipt refused");
        Regions noArt = Regions.parse(regions(Regions.FORMAT_2, "\"pixelEncoding\":\"L8\",\"pixelSha256\":\"" + RGBA_1X2 + "\""), rec);
        check(noArt != null && noArt.symbols.isEmpty(), "symbol with the wrong encoding dropped, layout kept");
        check(AtlasFiles.parse(files(row("7_r_0.png", B) + "," + row("7_r_0.png", B))) == null, "duplicate receipt row");
        check(AtlasFiles.parse(files(row("../x.png", B))) == null, "path outside the atlas");
        check(AtlasFiles.parse(files(row("7_r_0.png", "AB"))) == null, "bad receipt hash");
        check(AtlasFiles.parse(files("")) == null && AtlasFiles.parse("{\"format\":\"x\",\"files\":[]}") == null, "empty/foreign receipt");
        ok("v2 without a receipt, unlisted files, wrong encodings and bad receipts refused");

        // 5. mixed versions are detectable (MainActivity refuses the pair)
        check(a2.version != r1.version && a1.version != r2.version, "versions differ across builder runs");
        ok("index and regions versions are compared");

        // 6. optional: a real PC-built atlas 2 folder: every PNG against files.json and decoded to its pixel hash
        if (args.length > 0 && Files.isRegularFile(Paths.get(args[0], "files.json"))) {
            Path dir = Paths.get(args[0]);
            AtlasFiles real = AtlasFiles.parse(read(dir.resolve("files.json")));
            Object idx = Json.parse(read(dir.resolve("index.json")));
            Object reg = Json.parse(read(dir.resolve("regions.json")));
            check(real != null && Atlas.parse(read(dir.resolve("index.json")), real) != null, "real atlas 2 parses");
            int masks = 0, symbols = 0;
            for (Object o : Json.arr(idx, "sheets")) {
                verify(dir, real, Json.str(o, "file"), 1, Json.str(o, "pixelSha256"));
                masks++;
            }
            List<Object> sy = Json.arr(reg, "symbols");
            if (sy != null) for (Object o : sy) {
                verify(dir, real, Json.str(o, "file"), 4, Json.str(o, "pixelSha256"));
                symbols++;
            }
            check(masks == 390 && symbols == 29, "390 masks + 29 symbols, got " + masks + " + " + symbols);
            ok("real atlas 2: " + masks + " masks + " + symbols + " symbols match files.json and their pixel hashes");
        } else {
            System.out.println("SKIP real atlas 2 check (pass a PC-built atlas folder as the 1st argument)");
        }
        System.out.println(passed + " atlas checks passed");
    }

    static String read(Path p) throws Exception { return new String(Files.readAllBytes(p), StandardCharsets.UTF_8); }

    static void verify(Path dir, AtlasFiles real, String file, int channels, String pixelSha) throws Exception {
        byte[] data = Files.readAllBytes(dir.resolve(file));
        check(DataFiles.sha256(data).equals(real.sha256(file)), file + ": file hash vs files.json");
        BufferedImage im = ImageIO.read(new java.io.ByteArrayInputStream(data));
        Raster r = im.getRaster();
        int w = im.getWidth(), h = im.getHeight();
        check(r.getNumBands() == channels, file + ": channels");
        int[] px = r.getPixels(0, 0, w, h, (int[]) null);
        byte[] samples = new byte[px.length];
        for (int i = 0; i < px.length; i++) samples[i] = (byte) px[i];
        check(pixelSha.equals(Atlas.pixelSha256(channels, w, h, samples)), file + ": pixel hash");
    }
}
