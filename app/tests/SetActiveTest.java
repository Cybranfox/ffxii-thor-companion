package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 0.10.0 set switching: data.gambits.active is parsed per member, a switch is only offered for a verified member with
 * a known active set and a different target, and the setactive command carries the expected state the game script
 * re-checks. Writes tests/out/java_setactive_cmd.txt for the Lua side's field check.
 */
public final class SetActiveTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Gambits.MEMBER_BYTES; i++) hex.append(i < 4 ? "01600000" .substring(i * 2, i * 2 + 2) : "ff");
        String member = hex.toString();
        StringBuilder sets = new StringBuilder();
        for (int m = 0; m < 6; m++) sets.append(m == 0 ? "" : ",").append(member);
        String json = "{\"contract\":\"ffxii.gambits.v1\",\"status\":\"ok\",\"stable\":true,\"sets\":\"" + sets
                + "\",\"active\":\"0,1,2,0,1,2\"}";
        List<Gambits.Member> ms = Gambits.parse((Map<String, Object>) Json.parse(json));
        check(ms != null && ms.get(0).active == 0 && ms.get(1).active == 1 && ms.get(2).active == 2, "active parsed");
        check(ms.get(0).storedHex.equals(member), "stored bytes kept");
        String old = "{\"contract\":\"ffxii.gambits.v1\",\"status\":\"ok\",\"stable\":true,\"sets\":\"" + sets + "\"}";
        List<Gambits.Member> older = Gambits.parse((Map<String, Object>) Json.parse(old));
        check(older.get(0).active == -1 && !GambitEdit.canSwitch(older.get(0), 1), "older game script: no switching offered");
        Gambits.Member vaan = ms.get(0);
        check(GambitEdit.canSwitch(vaan, 1) && GambitEdit.canSwitch(vaan, 2) && !GambitEdit.canSwitch(vaan, 0) && !GambitEdit.canSwitch(vaan, 3),
                "only a different, valid set");
        String txt = GambitEdit.setActiveText(vaan, 2, "c1-abcdef", 1700000000L);
        check(txt.equals("ffxii.gambitcmd.v1\nid=c1-abcdef\nissued=1700000000\nmember=0\nset=2\nop=setactive\nfrom=0\nstored=" + member + "\n"), txt);
        Files.createDirectories(Paths.get(args[0]));
        Files.write(Paths.get(args[0], "java_setactive_cmd.txt"), txt.getBytes(StandardCharsets.US_ASCII));
        check(GambitEdit.reason("gambits_menu_open").contains("Gambits menu"), "readable reason");
        System.out.println("PASS SetActiveTest 7 checks (active set parsed, switch rules, exact setactive command)");
    }
}
