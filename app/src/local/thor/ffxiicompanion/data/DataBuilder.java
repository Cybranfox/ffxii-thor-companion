package local.thor.ffxiicompanion.data;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The data builder on the phone (0.16.0): the same pack builder/build_data_pack.py writes, from the same game files.
 * reference/*.json, areas and BUILD_INFO come out byte-identical to the PC builder's; the atlas's index.json and
 * regions.json too, its PNGs pixel-identical (atlas format 2 names pictures by pixel hash; files.json lists this
 * writer's own PNG bytes). Order and failure rules follow build(): a broken story-hint source skips objectives, an
 * entry the archive has but that wasn't copied stops the build.
 */
public final class DataBuilder {
    /**
     * The game's own menu labels (an st2e file, like menu_mapname). The licence screens look board j's title up by
     * row (text key 1005 + j: group 1, row 5 + j; Job_Names_2026-10-24.md), and those rows carry ids 10100 + j.
     */
    static final String MENU_COMMAND = "ps2data/image/ff12/test_battle/us/binaryfile/menu_command.bin";
    static final int JOB_ROW = 5, JOB_TEXT_ID = 10100, BOARDS = 12;

    /** One output file: its path inside FFXII_companion/ and its bytes. */
    public static final class Output {
        public final String path;
        public final byte[] data;

        Output(String path, byte[] data) {
            this.path = path;
            this.data = data;
        }
    }

    private DataBuilder() {}

    /** The whole pack, in the PC builder's write order. builderVersion goes into BUILD_INFO.json. */
    @SuppressWarnings("unchecked")
    public static List<Output> build(Inputs in, String builderVersion) throws Exception {
        List<Output> out = new ArrayList<>();
        Map<String, Object> reference = ReferenceStep.build(in);
        Map<String, Object> boards = BoardsStep.build(in, jobNames(in.read(MENU_COMMAND)));
        byte[] areasJson = AreasBuilder.build(in.read(BuildInfoStep.AREAS));
        Map<String, Object> areas = (Map<String, Object>) Json.parse(areasJson);
        out.add(new Output("reference/reference.json", Json.encode(reference)));
        out.add(new Output("reference/boards.json", Json.encode(boards)));
        out.add(new Output("reference/areas.json", areasJson));
        Map<String, Object> objectives;
        try {
            objectives = ObjectivesStep.build(in);
        } catch (Inputs.NotCopied e) {
            throw e;
        } catch (Exception e) {                       // build_data_pack: "Story hint and hunts skipped"
            objectives = null;
        }
        if (objectives != null) out.add(new Output("reference/objectives.json", Json.encode(objectives)));
        int sheets = 0;
        for (AtlasStep.Out o : AtlasStep.build(in)) {
            out.add(new Output("atlas/" + o.name, o.data));
            if (o.name.indexOf('/') < 0 && o.name.endsWith(".png")) sheets++;     // the PC counts atlas/*.png
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("builder", builderVersion);
        info.put("atlasFormat", sheets > 0 ? 2 : null);
        info.putAll(BuildInfoStep.build(in, reference, boards, areas, objectives, sheets));
        out.add(new Output("BUILD_INFO.json", Json.encode(info)));
        return out;
    }

    /**
     * The 12 licence board names, read from the player's menu_command.bin. Rows 5..16 must carry ids 10100..10111 (the
     * layout the game's lookup and this one agree on); a different layout, or a missing or empty name, stops the build.
     */
    static List<String> jobNames(byte[] menuCommand) throws IOException {
        byte[] d = menuCommand;
        if (d.length < 16 || d[0] != 's' || d[1] != 't' || d[2] != '2' || d[3] != 'e') throw new IOException("menu_command.bin: not st2e");
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        long count = Integer.toUnsignedLong(b.getInt(4)), rows = Integer.toUnsignedLong(b.getInt(12));
        if (count < JOB_ROW + BOARDS || b.getInt(8) != 4 || rows + 4 * count > d.length) throw new IOException("menu_command.bin: row table");
        for (int j = 0; j < BOARDS; j++)
            if (Short.toUnsignedInt(b.getShort((int) rows + 4 * (JOB_ROW + j))) != JOB_TEXT_ID + j)
                throw new IOException("menu_command.bin: unexpected layout at row " + (JOB_ROW + j));
        Map<Integer, String> labels = AtlasStep.parseMapnames(d);
        List<String> names = new ArrayList<>(BOARDS);
        for (int j = 0; j < BOARDS; j++) {
            String name = labels.get(JOB_TEXT_ID + j);
            if (name == null || name.isEmpty()) throw new IOException("menu_command.bin: no name for licence board " + (j + 1));
            names.add(name);
        }
        return names;
    }
}
