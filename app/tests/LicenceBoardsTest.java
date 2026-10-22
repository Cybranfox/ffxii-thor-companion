package local.thor.ffxiicompanion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/** Licence boards (0.21.0) on the real save_27 dump against gen_boards_expected.py's independent counts. */
public final class LicenceBoardsTest {
    static int passed;

    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    static void ok(String n) { passed++; System.out.println("PASS " + n); }

    static Object read(Path p) throws Exception { return Json.parse(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)); }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Path boardsJson = Paths.get(args[0]), build = Paths.get(args[1]), expected = Paths.get(args[2]);
        if (!Files.isRegularFile(boardsJson) || !Files.isRegularFile(build)) { System.out.println("SKIP LicenceBoardsTest"); return; }
        LicenceBoards lb = LicenceBoards.parse((Map<String, Object>) read(boardsJson));
        check(lb != null && lb.boards.size() == 12, "12 boards");
        check(lb.name(34).equals("Swords 3") && lb.name(218).equals("Time Magick 9") && lb.name(31).equals("Essentials"), "names");
        check(lb.board(11).name.equals("Shikari") && lb.board(11).w == 24 && lb.board(11).h == 24, "Shikari board 24x24");
        ok("boards.json: 12 boards, licence names (Swords 3, Time Magick 9, Essentials), job -> board");

        Build b = Build.parse((Map<String, Object>) read(build));
        int n = 0;
        for (Object e : (List<Object>) read(expected)) {
            int m = Json.lng(e, "member").intValue(), job = Json.lng(e, "job").intValue();
            int[] c = LicenceBoards.counts(LicenceBoards.states(lb.board(job), b.members.get(m)));
            check(c[0] == Json.lng(e, "owned") && c[1] == Json.lng(e, "open") && c[2] == Json.lng(e, "total"),
                    Gambits.MEMBERS[m] + " " + lb.board(job).name + ": " + c[0] + "/" + c[1] + "/" + c[2] + " vs " + e);
            n++;
        }
        check(n == 12, "both boards for all six members: " + n);
        ok("owned / open / total cells on both boards of all six members == independent Python result (real dump)");

        Build.Member vaan = b.members.get(0);
        LicenceBoards.Board sh = lb.board(vaan.job1 == 11 ? vaan.job1 : vaan.job2);
        int[] st = LicenceBoards.states(sh, vaan);
        for (int i = 0; i < st.length; i++) {
            if (st[i] != LicenceBoards.OPEN) continue;
            int x = i % sh.w, y = i / sh.w;
            boolean adj = false;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) { int nn = sh.at(x + d[0], y + d[1]); adj |= nn >= 0 && vaan.owns(nn); }
            check(adj && !vaan.owns(sh.cells[i]), "open cell touches an owned one");
        }
        ok("every OPEN cell is unowned and touches an owned licence on the same board");
        System.out.println(passed + " licence board checks passed (real dump)");
    }
}
