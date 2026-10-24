package local.thor.ffxiicompanion.data;

import java.io.*;
import java.util.*;

/** Text-owned BUILD_INFO fields. Caller injects actual atlas sheet count. */
public final class BuildInfoStep {
  public static final String PACK="ps2data/image/ff12/test_battle/us/binaryfile/battle_pack.bin";
  public static final String AREAS="ps2data/image/ff12/us/bin/planmapname.bin";
  public static LinkedHashMap<String,Object> build(Inputs inputs,Map<String,Object> reference,
      Map<String,Object> boards,Map<String,Object> areas,Map<String,Object> objectives,int mapSheets)throws IOException {
    if(mapSheets<0||mapSheets>10000)throw new IOException("atlas count");
    Map<String,Object> r=ReferenceStep.metadata(reference);
    Map<String,Object> included=Json.object("names",r.get("names"),"gambitConditions",r.get("gambitConditions"),
      "licenceBoards",((List<?>)boards.get("boards")).size(),"areas",((Map<?,?>)areas.get("names")).size(),
      "mapSheets",mapSheets,"gearDescriptions",r.get("gearDescriptions"),"bestiary",r.get("bestiary"),"tips",r.get("tips"),
      "storyHints",objectives==null?0:((List<?>)objectives.get("hints")).size(),"hunts",objectives==null?0:((List<?>)objectives.get("hunts")).size());
    Map<String,Object> mods=Json.object("Dynamic Equipment Description / Descriptive Inventory (gear descriptions)",r.get("gearModPresent"),
      "Foedex Clan Primer Text Replacer (bestiary)",r.get("bestiaryModPresent"));
    TreeMap<String,Object> sources=new TreeMap<>();sources.put(PACK,inputs.sourceHash(PACK));sources.put(AREAS,inputs.sourceHash(AREAS));
    for(int n=1;n<=12;n++){String p="ps2data/image/ff12/test_battle/in/binaryfile/board_"+n+".bin";sources.put(p,inputs.sourceHash(p));}
    sources.put(DataBuilder.MENU_COMMAND,inputs.sourceHash(DataBuilder.MENU_COMMAND));   //0.16: licence board names
    return Json.object("included",included,"optionalMods",mods,"sources",sources,
      "note","Made from your own game files for your personal use. Do not share this folder.");
  }
}
