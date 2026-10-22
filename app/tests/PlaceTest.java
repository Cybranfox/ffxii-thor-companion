package local.thor.ffxiicompanion;

import java.util.Map;

/** Loading ids never show as "Map -1"; area names come only from the user's areas.json. */
public final class PlaceTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) {
        check(Place.name(54L).equals("Map 54"), "no names compiled in");
        Map<Integer, String> m = Place.parse("{\"format\":\"ffxii-companion-areas-1\",\"names\":{\"54\":\"Area A\",\"55\":\"Area B\",\"x\":\"bad\"}}");
        check(m != null && m.size() == 2, "parse");
        check(Place.parse("{\"format\":\"other\",\"names\":{}}") == null && Place.parse("nope") == null, "rejects other files");
        Place.setNames(m);
        check(Place.name(-1L).equals("Loading…"), "loading name");
        check(Place.from(-1L, -1L).isEmpty(), "no from while loading");
        check(Place.from(54L, -1L).isEmpty(), "no from a loading id");
        check(Place.from(54L, 54L).isEmpty(), "no from the same map");
        check(Place.from(54L, null).isEmpty(), "no previous");
        check(Place.from(55L, 54L).equals("from Area A"), "named from");
        check(Place.name(9999L).equals("Map 9999") && Place.name(null).equals("Unknown area"), "unnamed id stays honest");
        System.out.println("PASS PlaceTest 10 checks (names only from areas.json; loading ids read \"Loading…\")");
    }
}
