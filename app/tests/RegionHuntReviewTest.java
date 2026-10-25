package local.thor.ffxiicompanion;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Synthetic fixtures only. Drop into app/tests; pass tests/fixtures. */
public final class RegionHuntReviewTest {
    static int checks;
    static void check(boolean yes, String label) {
        if (!yes) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    public static void main(String[] args) throws Exception {
        Path f = Paths.get(args.length == 0 ? "tests/fixtures" : args[0]);
        Atlas atlas = Atlas.parse(Files.readString(f.resolve("atlas/index.json"), StandardCharsets.UTF_8));
        Snapshot s = Snapshot.parse(Files.readString(f.resolve("region_table.json"), StandardCharsets.UTF_8), 1000);
        String raw = Files.readString(f.resolve("atlas/regions.json"), StandardCharsets.UTF_8);
        String extra = ",{\"mapId\":581,\"group\":9,\"index\":99,\"type\":1,\"sx\":8,\"sy\":8,\"dx\":8,\"dy\":8,\"gate\":0}"
                + ",{\"mapId\":581,\"group\":9,\"index\":100,\"type\":1,\"sx\":112,\"sy\":112,\"dx\":112,\"dy\":112,\"gate\":0}";
        int at = raw.indexOf(']', raw.indexOf("\"icons\""));
        raw = raw.substring(0, at) + extra + raw.substring(at);
        Regions regions = Regions.parse(raw);
        Reveal.Decision current = Reveal.decide(s, atlas, true);
        current.show = new boolean[8][8]; current.visible = new boolean[8][8];
        Map<String,Object> table = Json.obj(s.data, "revealTable");
        table.put("records", "");
        Regions.View empty = regions.buildOverview("synthetic", current, s, atlas);
        check(empty.parts.isEmpty() && empty.targets.isEmpty() && empty.symbols.isEmpty()
                && empty.links.isEmpty() && empty.exits.isEmpty() && empty.note.contains("haven't explored"), "empty overview hides everything");
        current.show[0][0] = true; current.visible[0][0] = true;
        table.put("records", "43:" + "ff".repeat(32));
        Regions.View two = regions.buildOverview("synthetic", current, s, atlas);
        check(two.parts.size() == 2 && two.parts.get(0).focus && two.parts.get(0).sheet.mapId == 581
                && !two.parts.get(1).focus && two.parts.get(1).sheet.mapId == 578, "two areas: only first explored area is focus");
        check(two.targets.isEmpty(), "overview has no hunt target ring");
        boolean near = false, hidden = false;
        for (Regions.Shown icon : two.symbols) {
            near |= icon.x == 328 && icon.y == 425;
            hidden |= icon.x == 432 && icon.y == 529;
        }
        check(near && !hidden, "ordinary group9 icons require explored displayed tiles");
        float[] all = Regions.fit(two.parts, false, null, null), focus = Regions.fit(two.parts, true, null, null);
        check(all != null && focus != null && !Arrays.equals(all, focus), "overview fit includes both areas, focus fit does not");
        Regions.View point = regions.buildHunt(current, 581, Arrays.asList(99, 100), s, atlas);
        check(point.parts.get(0).focus && point.targetExplored && point.targets.size() == 1, "point hunt keeps its focused map and explored target");
        check(regions.buildOverview("sub99", current, s, atlas).parts.isEmpty(), "unknown region key is safe");
        String missing = raw;
        for (int id : new int[]{585,581,578,588}) missing = missing.replace(id + "_synthetic_0.png", id + "_missing_0.png");
        Regions.View unavailable = Regions.parse(missing).buildOverview("synthetic", current, s, atlas);
        check(unavailable.parts.isEmpty() && unavailable.note != null && unavailable.targets.isEmpty(), "region key present but atlas files absent is safe and explained");
        String doc = "{\"format\":\"ffxii-companion-objectives-1\",\"hints\":[],\"hunts\":[{\"questId\":153,\"phases\":\"" + "00".repeat(28)
                + "\",\"paragraphs\":[],\"mapHints\":{\"kind\":0,\"entries\":[{\"regionId\":1,\"showMask\":\"01000000000000\",\"markerMask\":\"00000000000000\"}]}}],"
                + "\"worldAreas\":[{\"worldId\":1,\"name\":\"Synthetic\",\"regions\":[{\"region\":\"sub00\",\"name\":\"Synthetic child\"}]}]}";
        @SuppressWarnings("unchecked") Map<String,Object> map = (Map<String,Object>) Json.parse(doc);
        Objectives objectives = Objectives.parse(map);
        check(objectives.areasFor(objectives.hunts.get(0), 0).size() == 1 && objectives.areasFor(objectives.hunts.get(0), 5).isEmpty(), "areasFor obeys show mask");
        check(objectives.areasFor(objectives.hunts.get(0), -1).isEmpty() && objectives.areasFor(objectives.hunts.get(0), 256).isEmpty(), "areasFor rejects out-of-range stages");
        objectives.hunts.get(0).map.get(0).show = new byte[]{0,0,0,0,0,0,4};
        check(objectives.areasFor(objectives.hunts.get(0), 255).size() == 1, "areasFor clamps to bucket 50");
        map.remove("worldAreas"); objectives = Objectives.parse(map);
        check(objectives.areasFor(objectives.hunts.get(0), 0).isEmpty(), "old objectives pack has no fabricated region choices");
        System.out.println("PASS RegionHuntReviewTest " + checks + " checks");
    }
}
