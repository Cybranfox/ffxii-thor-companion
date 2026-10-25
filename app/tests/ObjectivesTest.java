package local.thor.ffxiicompanion;

import java.util.List;
import java.util.Map;

/** The story hint selector (0x3BD950) and the hunt journal (0x37EAA0 / 0x37EF70) on synthetic data. */
public final class ObjectivesTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(String json) { return (Map<String, Object>) Json.parse(json); }

    static Flags flags(int scenario, int... setIds) {
        byte[] b = new byte[64];
        for (int id : setIds) b[id >> 3] |= (byte) (1 << (id & 7));
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return Flags.parse(obj("{\"contract\":\"ffxii.flags.v1\",\"status\":\"ok\",\"generic\":\"" + s
                + "\",\"scenario\":" + scenario + ",\"currentExit\":-1}"));
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    public static void main(String[] args) {
        // phase table: stages 0-4 -> phase 0, 5-9 -> 1, 10-14 -> 2, 15+ -> 3 (nibbles, low first)
        byte[] table = new byte[28];
        table[0] = 0x10;
        table[1] = 0x32;
        for (int i = 2; i < 28; i++) table[i] = 0x33;
        String phases = hex(table);
        Objectives o = Objectives.parse(obj("{\"format\":\"ffxii-companion-objectives-1\",\"thresholds\":[null,[30]],"
                + "\"hints\":[{\"threshold\":0,\"gate\":65535,\"text\":\"Start\"},"
                + "{\"threshold\":100,\"gate\":20,\"text\":\"Needs flag 20\"},"
                + "{\"threshold\":100,\"gate\":65535,\"text\":\"Plain\"},"
                + "{\"threshold\":200,\"gate\":16385,\"text\":\"Needs scenario >= 30 too\"}],"
                + "\"hunts\":[{\"questId\":128,\"title\":\"A\",\"phases\":\"" + phases + "\",\"paragraphs\":[\"bill\",\"accepted\",\"done\"]},"
                + "{\"questId\":130,\"title\":\"B\",\"phases\":\"" + phases + "\",\"paragraphs\":[\"bill\",null,\"done\"]},"
                + "{\"questId\":131,\"title\":\"C\",\"phases\":\"" + phases + "\",\"paragraphs\":[]},"
                + "{\"questId\":132,\"title\":\"D\",\"phases\":\"zz\",\"paragraphs\":[]}]}"));
        check(o != null && o.hints.size() == 4 && o.hunts.size() == 3, "parse; a bad phase table drops that hunt only");
        check("Start".equals(o.hint(flags(50))), "first row");
        check("Plain".equals(o.hint(flags(150))) && "Plain".equals(o.hint(flags(150, 20))), "the LAST eligible row wins");
        check("Needs scenario >= 30 too".equals(o.hint(flags(250))), "bit14 gate through the thresholds");
        check(o.hint(flags(0xFFFF)) == null, "a negative scenario reaches no row");
        check(o.hint(null) == null, "no live flags, no hint");

        byte[] a = new byte[32], b = new byte[32];
        a[16] |= 1;                     // 128 listed by bank A
        b[16] |= 1 << 2;                // 130 listed by bank B
        a[16] |= 1 << 3;                // 131 listed, but still at the bill
        byte[] stages = new byte[45];
        stages[0] = 12;                 // 128 -> phase 2
        stages[2] = 20;                 // 130 -> phase 3
        stages[3] = 7;                  // 131 -> phase 1
        Map<String, Object> q = obj("{\"contract\":\"ffxii.quests.v1\",\"status\":\"ok\",\"first\":128,\"count\":45,\"bankA\":\""
                + hex(a) + "\",\"bankB\":\"" + hex(b) + "\",\"stages\":\"" + hex(stages) + "\"}");
        List<Objectives.Entry> j = o.journal(q);
        check(j.size() == 2 && j.get(0).hunt.questId == 128 && j.get(0).phase == 2 && "accepted".equals(j.get(0).paragraph),
                "accepted hunt with its paragraph");
        check(j.get(1).hunt.questId == 130 && j.get(1).phase == 3 && "done".equals(j.get(1).paragraph), "later phase, its paragraph");
        check(Objectives.phase(table, 4) == 0 && Objectives.phase(table, 9) == 1 && Objectives.phase(table, 14) == 2
                && Objectives.phase(table, 255) == 3, "phase = nibble of stage / 5");
        stages[0] = 0;
        check(o.journal(obj("{\"contract\":\"ffxii.quests.v1\",\"status\":\"ok\",\"first\":128,\"count\":45,\"bankA\":\""
                + hex(new byte[32]) + "\",\"bankB\":\"" + hex(new byte[32]) + "\",\"stages\":\"" + hex(stages) + "\"}")).isEmpty(),
                "unlisted hunts never show");
        check(o.journal(obj("{\"contract\":\"ffxii.quests.v1\",\"status\":\"unstable\"}")).isEmpty() && o.journal(null).isEmpty(),
                "no valid live state, no list");
        check(Objectives.parse(obj("{\"format\":\"other\"}")) == null, "other files refused");
        // 0.17.0 hunt maps: quest 128's two entries (HuntMap_Native_2026-10-25.md): the town at raw 0-29 and 50-255,
        // its field map at 30-49 (k = min(stage / 5, 50) on the 7-byte masks)
        Objectives m = Objectives.parse(obj("{\"format\":\"ffxii-companion-objectives-1\",\"hunts\":[{\"questId\":128,\"phases\":\""
                + hex(new byte[28]) + "\",\"paragraphs\":[],\"mapHints\":{\"kind\":1,\"entries\":["
                + "{\"showMask\":\"3ffcffffffff07\",\"markerMask\":\"3ffcffffffff07\",\"mapId\":291,\"markerIndex\":3},"
                + "{\"showMask\":\"c0030000000000\",\"markerMask\":\"c0030000000000\",\"mapId\":227,\"markerIndex\":1}]}},"
                + "{\"questId\":153,\"phases\":\"" + hex(new byte[28]) + "\",\"paragraphs\":[],\"mapHints\":{\"kind\":0,\"entries\":["
                + "{\"showMask\":\"ffffffffffffff\",\"markerMask\":\"00000000000000\",\"regionId\":1}]}},"
                + "{\"questId\":140,\"phases\":\"" + hex(new byte[28]) + "\",\"paragraphs\":[],\"mapHints\":{\"kind\":1,\"entries\":["
                + "{\"showMask\":\"zz\",\"markerMask\":\"00\",\"mapId\":1,\"markerIndex\":0}]}}],"
                + "\"worldAreas\":[{\"worldId\":1,\"name\":\"Area\",\"regions\":[{\"region\":\"sub00\",\"name\":\"R\"},"
                + "{\"region\":\"bad\",\"name\":\"x\"}]},{\"worldId\":3,\"name\":null,\"regions\":[]}]}"));
        Objectives.Hunt h128 = m.hunts.get(0);
        int[] at = {0, 29, 30, 49, 50, 255}, want = {291, 291, 227, 227, 291, 291};
        boolean all = true;
        for (int i = 0; i < at.length; i++) {
            Objectives.HuntMap hm = Objectives.mapFor(h128, at[i]);
            all &= hm != null && hm.mapId == want[i] && hm.markers.size() == 1 && hm.markers.get(0) == (want[i] == 291 ? 3 : 1);
        }
        check(all, "hunt map follows the stage: town, field map, town; its marker row");
        check(m.hunts.get(1).mapKind == 0 && Objectives.mapFor(m.hunts.get(1), 40) == null, "region-only hunt: no single map");
        java.util.List<Objectives.WorldArea> areas = m.areasFor(m.hunts.get(1), 40);
        check(areas.size() == 1 && areas.get(0).worldId == 1 && areas.get(0).regions.size() == 1
                && "sub00".equals(areas.get(0).regions.get(0)[0]), "region-only hunt: its world areas and their regions (bad keys left out)");
        check(m.hunts.get(2).mapKind == -1 && Objectives.mapFor(m.hunts.get(2), 40) == null, "malformed helper: no map, hunt kept");
        System.out.println("PASS ObjectivesTest 16 checks (hint: last eligible row, gates, signed scenario; hunt journal; hunt maps)");
    }
}
