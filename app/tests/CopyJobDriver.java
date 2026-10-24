package local.thor.ffxiicompanion.data;

import java.io.File;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Command-line side of CopyJob for the game-script harness (game-script/tests/test_rawcopy_app.py drives the real
 * ThorCompanion-core.lua under lupa, and calls this between ticks, a fresh JVM each time, as an app restart would be).
 * Prints one JSON line.
 *   start   &lt;FFXII_companion dir&gt; &lt;gen&gt;
 *   advance &lt;FFXII_companion dir&gt;
 *   beat    &lt;FFXII_companion dir&gt; &lt;epoch seconds&gt;
 *   inputs  &lt;FFXII_companion dir&gt;                       SHA-256 of every inflated entry and config, as DataBuilder sees them
 *   keep    &lt;FFXII_companion dir&gt;                       CopyJob.keepOnlyThis()
 *   build   &lt;FFXII_companion dir&gt; &lt;out dir&gt; &lt;version&gt;   DataBuilder over the copied files; writes the pack
 */
public class CopyJobDriver {
    public static void main(String[] a) throws Exception {
        File base = new File(a[1]);
        Map<String, Object> out = new LinkedHashMap<>();
        CopyJob job;
        switch (a[0]) {
            case "start":
                job = CopyJob.start(base, a[2]);
                break;
            case "beat":
                Heartbeat.beat(base, Long.parseLong(a[2]));
                job = CopyJob.load(base);
                break;
            default:
                job = CopyJob.load(base);
                if (job == null) throw new IllegalStateException("no job");
        }
        if (a[0].equals("advance")) out.put("changed", job.advance());
        if (a[0].equals("keep")) job.keepOnlyThis();
        if (a[0].equals("inputs")) {
            RawInputs in = job.inputs();
            Map<String, Object> entries = new TreeMap<>(), configs = new TreeMap<>();
            for (String p : Recipe.entries(in.index())) entries.put(p, Inputs.sha256(in.read(p)));
            for (String c : Recipe.CONFIGS) {
                byte[] b = in.config(c);
                if (b != null) configs.put(c, Inputs.sha256(b));
            }
            out.put("entries", entries);
            out.put("configs", configs);
            out.put("archiveBytes", in.archiveBytes());
        }
        if (a[0].equals("build")) {
            File dir = new File(a[2]);
            List<DataBuilder.Output> pack = DataBuilder.build(job.inputs(), a[3]);
            for (DataBuilder.Output o : pack) {
                File f = new File(dir, o.path);
                f.getParentFile().mkdirs();
                Files.write(f.toPath(), o.data);
            }
            out.put("files", pack.size());
        }
        if (job != null) {
            out.put("phase", job.phase().name());
            out.put("job", job.jobId());
            out.put("error", job.error());
        }
        System.out.println(new String(Json.encode(out), java.nio.charset.StandardCharsets.UTF_8));
    }
}
