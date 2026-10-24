package local.thor.ffxiicompanion;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import local.thor.ffxiicompanion.data.CopyJob;

/**
 * DataStatus (0.16.0): the Game data card's state, words, progress and action for every situation, from synthetic
 * copy-job states and rawcopy snapshots (no game data).
 *   args: &lt;out dir&gt;
 */
public class DataStatusTest {
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }

    static File out;

    /** A copy job in a given state (its app-job.json written directly). */
    static CopyJob job(String phase, String gen, String error, boolean built) throws Exception {
        File base = new File(out, "j" + System.nanoTime());
        File raw = new File(base, "raw");
        raw.mkdirs();
        String json = "{\"format\":\"ffxii-companion-copyjob-1\",\"gen\":\"" + gen + "\",\"phase\":\"" + phase + "\",\"error\":"
                + (error == null ? "null" : "\"" + error + "\"") + ",\"config\":1,\"built\":" + built + ",\"entries\":[],\"configs\":[]}";
        Files.write(new File(raw, "app-job.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        return CopyJob.load(base);
    }

    static Map<String, Object> live(String job, String status, long item, long items, long bytes, long total, String paused) {
        Map<String, Object> m = new HashMap<>();
        m.put("contract", "ffxii.rawcopy.v1");
        m.put("job", job);
        m.put("status", status);
        m.put("item", item);
        m.put("items", items);
        m.put("bytes", bytes);
        m.put("total", total);
        if (paused != null) m.put("paused", paused);
        return m;
    }

    static DataStatus of(CopyJob j, Map<String, Object> live, boolean sessionLive, String builder) {
        return DataStatus.of(j, live, sessionLive, false, true, builder, null, null);
    }

    public static void main(String[] args) throws Exception {
        out = new File(args[0], "datastatus");
        out.mkdirs();

        DataStatus d = of(null, null, false, null);
        check(d.state == DataStatus.State.NONE && d.action == DataStatus.Action.BUILD && "Build game data".equals(DataStatus.label(d.action)),
                "no pack, no copy: \"" + d.line + "\" + Build game data");
        d = of(null, null, false, "0.15.0-beta");
        check(d.state == DataStatus.State.PC_PACK && d.action == DataStatus.Action.REBUILD, "PC-built pack: \"" + d.line + "\" + Rebuild");
        d = of(job("DONE", "g1", null, true), null, false, "app-1");
        check(d.state == DataStatus.State.READY && d.action == DataStatus.Action.REBUILD, "app-built pack: \"" + d.line + "\"");

        CopyJob h = job("HEADER", "g2", null, false);
        d = of(h, null, false, null);
        check(d.state == DataStatus.State.WAITING_GAME && d.progress == 0 && d.action == DataStatus.Action.NONE,
                "copy started, game not running: \"" + d.line + "\"");
        d = of(h, live("dat-old", "running", 3, 75, 0, 100, null), true, null);
        check(d.state == DataStatus.State.WAITING_GAME, "a live status for another job is ignored (still waiting)");
        d = of(h, live("hdr-g2", "running", 1, 1, 6_000_000, 12_000_000, null), false, null);
        check(d.state == DataStatus.State.WAITING_GAME, "our job's status from a session that isn't live: ignored");
        d = of(h, live("hdr-g2", "running", 1, 1, 6_000_000, 12_000_000, null), true, null);
        check(d.state == DataStatus.State.COPYING && Math.abs(d.progress - 0.25) < 1e-9 && d.line.contains("step 1 of 3"),
                "header half copied: \"" + d.line + "\", progress 0.25");
        d = of(h, live("hdr-g2", "running", 1, 1, 6_000_000, 12_000_000, "fight"), true, null);
        check(d.state == DataStatus.State.PAUSED_FIGHT, "fight: \"" + d.line + "\"");
        d = of(h, live("hdr-g2", "running", 1, 1, 6_000_000, 12_000_000, "app_away"), true, null);
        check(d.state == DataStatus.State.PAUSED_AWAY, "app away (just back): \"" + d.line + "\"");

        CopyJob dj = job("DATA", "g2", null, false);
        d = of(dj, live("dat-g2", "running", 35, 75, 0, 1000, null), true, null);
        check(d.state == DataStatus.State.COPYING && d.line.contains("34") == false && d.line.contains("35 of 75 files")
                && d.progress > 0.5 && d.progress < 0.97, "data job: \"" + d.line + "\", progress " + d.progress);
        double last = -1;
        boolean monotonic = true;
        for (long i = 1; i <= 75; i++)
            for (long b = 0; b <= 1000; b += 250) {
                double pr = DataStatus.progress(dj, live("dat-g2", "running", i, 75, b, 1000, null));
                if (pr < last - 1e-12 && !(b == 0 && Math.abs(pr - last) < 1e-12)) monotonic = false;
                last = Math.max(last, pr);
            }
        check(monotonic && last <= 0.97 + 1e-12, "progress through the data job never goes back and stays within its share");
        CopyJob cj = job("CONFIG", "g2", null, false);
        check(DataStatus.progress(cj, live("cf3-g2", "done", 1, 1, 10, 10, null)) <= 1.0
                && DataStatus.progress(cj, null) >= 0.97, "config jobs: the last 3%, never above 1");

        d = DataStatus.of(job("DONE", "g2", null, false), null, false, true, true, null, null, null);
        check(d.state == DataStatus.State.BUILDING && d.action == DataStatus.Action.NONE, "building: \"" + d.line + "\"");
        d = DataStatus.of(job("DONE", "g2", null, false), null, false, false, false, "app-1", null, null);
        check(d.state == DataStatus.State.HELD, "a swap being finished: \"" + d.line + "\"");

        String[][] reasons = {{"copy stopped: entry_mismatch", "changed"}, {"copy stopped: archive_missing", "couldn't open"},
                {"copy stopped: request_size", "app update"}, {"this game needs 134 files copied, more than one request can ask for", "app update"},
                {"Not enough free space", "free space"}, {"Build check failed: atlas/x.png missing or damaged", "checks"},
                {"copy stopped: write", "storage full"}, {"copy stopped: something_new", "(something_new)"}};
        for (String[] r : reasons) {
            d = of(job("FAILED", "g3", r[0], false), null, false, "app-1");
            check(d.state == DataStatus.State.FAILED && d.action == DataStatus.Action.RETRY && d.line.contains(r[1])
                    && d.line.endsWith("Using your previous data."), "failed \"" + r[0] + "\" -> \"" + d.line + "\"");
        }
        d = DataStatus.of(job("DONE", "g4", null, false), null, false, false, true, "app-1", "g4", "Build failed: x");
        check(d.state == DataStatus.State.FAILED && d.action == DataStatus.Action.RETRY, "build failed for this copy: \"" + d.line + "\" + Try again");
        System.out.println(passed + " card checks passed");
    }
}
