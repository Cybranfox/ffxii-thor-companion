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
        System.out.println("PASS ObjectivesTest 12 checks (hint: last eligible row, gates, signed scenario; hunt journal)");
    }
}
