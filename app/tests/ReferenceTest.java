package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/** Reference model tests: a small inline bundle, malformed input, and (optional) the real PC-built bundle. */
public final class ReferenceTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    static final String SMALL = "{\"format\":\"ffxii-companion-reference-1\",\"kind\":\"REFERENCE\",\"note\":\"test\","
            + "\"gear\":[{\"id\":4097,\"name\":\"Broadsword\",\"category\":\"Sword\",\"tags\":[\"License: Swords 1\"],"
            + "\"lines\":[\"Attack Power: 14\"]},{\"id\":4098,\"name\":\"Longsword\",\"category\":\"Sword\",\"tags\":[],"
            + "\"lines\":[]},{\"id\":1,\"name\":\"Hi-Potion\",\"category\":\"Item\",\"tags\":[],\"lines\":[\"Restore HP\"]},"
            + "{\"id\":2,\"name\":\"\",\"category\":\"Item\"}],"
            + "\"bestiary\":[{\"entry\":0,\"name\":\"Cactoid\",\"locations\":[\"Dalmasca Estersand\",\"Dalmasca Westersand\"],"
            + "\"pages\":[[\"Items\",\"\",\"Dalmasca Estersand\",\"## Drop\",\"[40%]   Cactus Fruit\"],[\"Items\",\"## Steal\"]]},"
            + "{\"entry\":2,\"name\":\"Wolf\",\"locations\":[\"Dalmasca Westersand\"],\"pages\":[[\"Items\",\"## Drop\"]]}],"
            + "\"tips\":[]}";

    public static void main(String[] args) throws Exception {
        Reference r = Reference.parse(SMALL);
        check(r != null && r.gear.size() == 3, "unnamed gear dropped");
        check(r.gearCategories().equals(List.of("Sword", "Item")), "categories by count: " + r.gearCategories());
        check(r.gearIn("Sword").size() == 2 && r.gearIn(null).size() == 3, "gear filter");
        check(r.gear.get(0).tags.get(0).equals("License: Swords 1"), "tags kept");
        ok("gear: categories ordered by count, filter, unnamed entries skipped");

        check(r.locations().equals(List.of("Dalmasca Estersand", "Dalmasca Westersand")), "locations in game order");
        check(r.bestiaryAt("Dalmasca Westersand").size() == 2 && r.bestiaryAt("Dalmasca Estersand").size() == 1, "location filter");
        List<String> lines = r.bestiary.get(0).lines;
        check(!lines.contains("Items") && lines.contains("## Drop") && lines.contains("## Steal"), "pages joined, 'Items' title dropped");
        ok("bestiary: locations, filter, pages joined into one detail text");

        check(Reference.parse("{\"format\":\"other\",\"kind\":\"REFERENCE\",\"gear\":[]}") == null, "wrong format");
        check(Reference.parse(SMALL.replace("\"REFERENCE\"", "\"LIVE\"")) == null, "must be labelled REFERENCE");
        check(Reference.parse("{") == null && Reference.parse("") == null, "malformed");
        ok("rejects wrong format, non-REFERENCE kind, malformed text");

        if (args.length > 0 && Files.isRegularFile(Paths.get(args[0]))) {
            Path p = Paths.get(args[0]);
            Reference real = Reference.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            check(real != null && real.gear.size() == 590 && real.bestiary.size() == 384, "real bundle sizes");
            check(real.gearCategories().get(0).equals("Magick") && real.gearIn("Sword").size() == 27, "real categories");
            check(real.bestiary.get(0).name.equals("Cactoid") && real.bestiaryAt("Dalmasca Estersand").size() > 5, "real bestiary");
            ok("real bundle parses (590 gear, 384 bestiary entries)");
        } else {
            System.out.println("SKIP real reference bundle (pass its path)");
        }
        System.out.println(passed + " reference checks passed");
    }
}
