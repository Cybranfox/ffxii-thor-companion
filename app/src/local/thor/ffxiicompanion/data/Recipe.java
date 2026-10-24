package local.thor.ffxiicompanion.data;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What one build needs copied from the game (US): the archive files DataBuilder reads, and the three optional mod
 * configs. The atlas reads every region file the archive lists, so those come from the copied header's own index
 * (49 in the shipped game); the rest is fixed. DataBuilderTest checks this against what a build actually reads.
 */
public final class Recipe {
    static final List<String> FIXED = Collections.unmodifiableList(Arrays.asList(
            "gamedata/d3d11/artdata/font/us/font00.dat",
            "ps2data/image/ff12/in/bin/navimapdata.bin",
            "ps2data/image/ff12/myoshiok/us/quest/menuquest.dat",
            "ps2data/image/ff12/myoshiok/us/quest/menuquest01.bin",
            "ps2data/image/ff12/myoshiok/us/tm2_menu/loca_8_c.tm2",
            "ps2data/image/ff12/myoshiok/us/worldmap/menumap_common.bin",
            "ps2data/image/ff12/myoshiok/us/worldmap/menumap_guide00.bin",
            "ps2data/image/ff12/myoshiok/us/worldmap/menumap_location.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_1.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_2.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_3.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_4.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_5.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_6.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_7.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_8.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_9.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_10.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_11.bin",
            "ps2data/image/ff12/test_battle/in/binaryfile/board_12.bin",
            "ps2data/image/ff12/test_battle/us/binaryfile/battle_pack.bin",
            "ps2data/image/ff12/test_battle/us/binaryfile/menu_command.bin",
            "ps2data/image/ff12/test_battle/us/binaryfile/menu_mapname.bin",
            "ps2data/image/ff12/test_battle/us/binaryfile/questtitle.bin",
            "ps2data/image/ff12/us/bin/mapjumpgroupflagrom.bin",
            "ps2data/image/ff12/us/bin/planmapname.bin"));

    /** The game script's config numbers (ThorCompanion-core.lua rawCopy CONFIGS, 1-based) and their x64 paths. */
    static final List<String> CONFIGS = Collections.unmodifiableList(Arrays.asList(
            ReferenceStep.GEAR, ReferenceStep.BEST, ReferenceStep.TIPS));

    private Recipe() {}

    /** The archive entries to copy, in index order of name: the fixed ones this archive has, plus its region files. */
    public static List<String> entries(VbfIndex index) {
        List<String> out = new ArrayList<>();
        for (String name : FIXED) if (index.find(name) != null) out.add(name);
        for (String name : index.names())
            if (name.startsWith(AtlasStep.PREFIX) && name.endsWith(".bin")) out.add(name);
        Collections.sort(out);
        return out;
    }
}
