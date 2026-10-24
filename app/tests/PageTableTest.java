package local.thor.ffxiicompanion;

import java.util.Arrays;
import java.util.List;

/** 0.16.0: reference pages -> headings, chance rows and prose (synthetic lines shaped like the bestiary's). */
public final class PageTableTest {
    static int n;

    static void check(boolean c, String what) {
        if (!c) throw new AssertionError(what);
        n++;
    }

    public static void main(String[] args) {
        List<PageTable.Block> b = PageTable.parse(Arrays.asList(
                "Rare Game  1/9", "", "", "Some prose line.", "## Drop", "[40%]   Test Crystal", "[2.5%] Odd Chance",
                "[100%]   Sure Thing", "[101%]   Not A Row", "[40%]", "## ", "plain [10%] inside", "", ""));
        check(b.get(0).kind == PageTable.Kind.PROSE && b.get(0).text.equals("Rare Game  1/9"), "prose kept verbatim");
        check(b.get(1).kind == PageTable.Kind.BLANK && b.get(2).kind == PageTable.Kind.PROSE, "blank runs collapse to one");
        check(b.get(3).kind == PageTable.Kind.HEADING && b.get(3).text.equals("Drop"), "## heading");
        check(b.get(4).kind == PageTable.Kind.ROW && b.get(4).text.equals("Test Crystal") && b.get(4).chance.equals("40%"),
                "chance row: item and chance");
        check(b.get(5).kind == PageTable.Kind.ROW && b.get(5).chance.equals("2.5%"), "decimal chance kept as written");
        check(b.get(6).kind == PageTable.Kind.ROW && b.get(6).chance.equals("100%"), "100% is a row");
        check(b.get(7).kind == PageTable.Kind.PROSE, "over 100% stays prose");
        check(b.get(8).kind == PageTable.Kind.PROSE && b.get(9).kind == PageTable.Kind.PROSE
                && b.get(10).kind == PageTable.Kind.PROSE, "a bare chance, an empty heading and mid-line brackets stay prose");
        check(b.size() == 11, "trailing blanks dropped (" + b.size() + ")");
        check(PageTable.parse(null).isEmpty(), "no lines -> nothing");
        System.out.println("PASS PageTableTest " + n + " checks (headings, chance rows, prose kept verbatim)");
    }
}
