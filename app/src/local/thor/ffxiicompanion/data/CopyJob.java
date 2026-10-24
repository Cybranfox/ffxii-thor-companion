package local.thor.ffxiicompanion.data;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The app's side of the game script's raw copy (ThorCompanion-core.lua rawCopy, contract ffxii.rawcopy.v1), for one
 * build. A request names entries by their row in the archive's directory, which only the header has, and a missing
 * config stops a whole job, so one build is a few small jobs in turn (job id = phase prefix + gen):
 *   HEADER  hdr-: "item=header", copied to raw/hdr-&lt;gen&gt;/001.bin;
 *   DATA    dat-: every Recipe entry, by the row and stored size the copied header gives;
 *   CONFIG  cf1- .. cf3-: one optional mod config each; the game script's "config_missing" means the player doesn't
 *           have that mod, which is fine (the PC builder skips it the same way);
 *   DONE    every file checked against its receipt and the index; the request removed (the next game start copies
 *           nothing); inputs() hands the files to DataBuilder;
 *   FAILED  the game script's error.txt reason, or a check here. A retry is a new gen.
 * The job's own state is raw/app-job.json, so it carries on after the app restarts. Nothing here reads game memory or
 * writes outside raw/.
 */
public final class CopyJob {
    public enum Phase { HEADER, DATA, CONFIG, DONE, FAILED }

    static final String CONTRACT = "ffxii.rawcopy.v1", FORMAT = "ffxii-companion-copyjob-1";
    static final int MAX_STATE = 64 * 1024;
    /** The game script's own request limits (rawCopy MAX_ITEMS, MAX_REQUEST): never send what it would refuse. */
    static final int MAX_ITEMS = 128, MAX_REQUEST = 40 * 1024;
    /** rawCopy parseRequest's reasons: the request was refused before any job started (no job folder, no error.txt). */
    static final List<String> REQUEST_ERRORS = List.of("request_size", "job_id", "too_many_items", "item", "line", "request");

    private final File raw;
    private final String gen;
    private Phase phase;
    private String error;
    private int config;                          // CONFIG: 1..Recipe.CONFIGS.size()
    private boolean built;                       // DONE and the pack from it is in place (PackSwap)
    private final List<String> entries;          // the data job's entries in request order
    private final List<Integer> present;         // configs the player has (1-based)

    private CopyJob(File raw, String gen, Phase phase, String error, int config, boolean built, List<String> entries,
                    List<Integer> present) {
        this.raw = raw;
        this.built = built;
        this.gen = gen;
        this.phase = phase;
        this.error = error;
        this.config = config;
        this.entries = entries;
        this.present = present;
    }

    public Phase phase() { return phase; }

    public String error() { return error; }

    public String gen() { return gen; }

    /** True once a pack built from this job's files is in place. */
    public boolean built() { return built; }

    /** PackBuild, after the swap. */
    public void markBuilt() throws IOException {
        built = true;
        save();
    }

    /** The game script's job id for the current phase (what data.rawcopy.job shows while it copies). */
    public String jobId() {
        return (phase == Phase.HEADER ? "hdr-" : phase == Phase.CONFIG ? "cf" + config + "-" : "dat-") + gen;
    }

    /** Starts a build's copy: asks the game script for the header. base is FFXII_companion/; gen: [A-Za-z0-9_], 1..24. */
    public static CopyJob start(File base, String gen) throws IOException {
        if (gen == null || !gen.matches("[A-Za-z0-9_]{1,24}")) throw new IOException("job generation");
        File raw = new File(base, "raw");
        if (!raw.isDirectory() && !raw.mkdirs()) throw new IOException("can't make " + raw);
        CopyJob j = new CopyJob(raw, gen, Phase.HEADER, null, 0, false, new ArrayList<>(), new ArrayList<>());
        j.request(Collections.singletonList("item=header"));
        j.save();
        return j;
    }

    /** The job in progress or last finished, or null when there's none (or its state file is unreadable). */
    @SuppressWarnings("unchecked")
    public static CopyJob load(File base) {
        File raw = new File(base, "raw"), f = new File(raw, "app-job.json");
        try {
            if (!f.isFile() || f.length() > MAX_STATE) return null;
            Map<String, Object> o = (Map<String, Object>) Json.parse(Files.readAllBytes(f.toPath()));
            String gen = (String) o.get("gen");
            if (!FORMAT.equals(o.get("format")) || gen == null || !gen.matches("[A-Za-z0-9_]{1,24}")) return null;
            List<String> entries = new ArrayList<>();
            for (Object x : (List<Object>) o.get("entries")) entries.add((String) x);
            List<Integer> present = new ArrayList<>();
            for (Object x : (List<Object>) o.get("configs")) present.add(((Number) x).intValue());
            return new CopyJob(raw, gen, Phase.valueOf((String) o.get("phase")), (String) o.get("error"),
                    ((Number) o.get("config")).intValue(), Boolean.TRUE.equals(o.get("built")), entries, present);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** advance(null): from the files in raw/ alone. */
    public boolean advance() throws IOException { return advance(null); }

    /**
     * Moves the job on from what the game script has left in raw/ (done.txt / error.txt of the current job): the next
     * job's request, or DONE, or FAILED. True when anything changed. Cheap: a few small files.
     * live: the game script's status from the newest snapshot (data.rawcopy), or null. With it, a job the script
     * reports done whose done.txt never appeared (a failed write) still moves on (its receipts are checked as
     * always), and a request the script refused before starting it fails the build with its reason instead of
     * waiting for files that will never come.
     */
    public boolean advance(Map<String, Object> live) throws IOException {
        if (phase == Phase.DONE || phase == Phase.FAILED) return false;
        File dir = new File(raw, jobId());
        String err = small(new File(dir, "error.txt"));
        boolean ours = live != null && jobId().equals(live.get("job"));
        Object liveStatus = live == null ? null : live.get("status"), liveError = live == null ? null : live.get("error");
        if (err == null && "error".equals(liveStatus) && liveError instanceof String
                && (ours || REQUEST_ERRORS.contains(liveError) && refusesOurRequest(live))) err = (String) liveError;
        boolean missingConfig = phase == Phase.CONFIG && err != null && err.trim().equals("config_missing");
        if (err != null && !missingConfig) return fail("copy stopped: " + err.trim());
        boolean done = small(new File(dir, "done.txt")) != null || ours && "done".equals(liveStatus);
        if (err == null && !done) return false;
        try {
            if (phase == Phase.HEADER) {
                File h = new File(dir, "001.bin");
                checkItems(dir, Collections.singletonList(h.length()));
                VbfIndex index = header();
                List<String> lines = new ArrayList<>();
                entries.clear();
                for (String path : Recipe.entries(index)) {
                    VbfIndex.Entry e = index.find(path);
                    lines.add("item=entry " + e.ordinal + " " + e.storedSize + " " + path);
                    entries.add(path);
                }
                int bytes = 64;
                for (String l : lines) bytes += l.length() + 1;
                if (lines.size() > MAX_ITEMS || bytes > MAX_REQUEST)
                    throw new IOException("this game needs " + lines.size() + " files copied, more than one request can ask for");
                phase = Phase.DATA;
                request(lines);
            } else if (phase == Phase.DATA) {
                VbfIndex index = header();
                List<Long> sizes = new ArrayList<>();
                for (String path : entries) sizes.add(index.find(path).storedSize);
                checkItems(dir, sizes);
                nextConfig(1);
            } else {
                if (!missingConfig) {
                    checkItems(dir, Collections.singletonList(-1L));
                    present.add(config);
                }
                nextConfig(config + 1);
            }
        } catch (IOException | RuntimeException e) {
            return fail(e.getMessage());
        }
        save();
        return true;
    }

    /**
     * A request refusal (rawCopy parseRequest) belongs to our current request only if the game script says so
     * (requestJob, from core 7 on), or, on older cores, which keep the last job they ran in status.job, if that last
     * job is this build's previous one (same gen, earlier phase): then the refused request can only be the one this
     * build published after it. A refusal left over from another build is never taken for ours.
     */
    private boolean refusesOurRequest(Map<String, Object> live) {
        Object req = live.get("requestJob"), last = live.get("job");
        if (req instanceof String) return req.equals(jobId());
        return last instanceof String && ((String) last).endsWith("-" + gen) && !last.equals(jobId());
    }

    /** The copied files for DataBuilder (DONE only). */
    public RawInputs inputs() throws IOException {
        if (phase != Phase.DONE) throw new IOException("copy not finished");
        Map<String, File> files = new HashMap<>(), configs = new HashMap<>();
        File dat = new File(raw, "dat-" + gen);
        for (int n = 1; n <= entries.size(); n++) files.put(entries.get(n - 1), new File(dat, String.format(Locale.ROOT, "%03d.bin", n)));
        for (int c : present) configs.put(Recipe.CONFIGS.get(c - 1), new File(new File(raw, "cf" + c + "-" + gen), "001.bin"));
        return new RawInputs(new File(new File(raw, "hdr-" + gen), "001.bin"), files, configs);
    }

    /** Removes every other build's copied files (keeps this gen's, so a builder update can rebuild without copying). */
    public void keepOnlyThis() {
        File[] all = raw.listFiles();
        if (all == null) return;
        for (File d : all)
            if (d.isDirectory() && d.getName().matches("(hdr|dat|cf[0-9])-[A-Za-z0-9_]+") && !d.getName().endsWith("-" + gen))
                deleteTree(d);
    }

    // ---- phases and checks ----

    private void nextConfig(int c) throws IOException {
        if (c <= Recipe.CONFIGS.size()) {
            phase = Phase.CONFIG;
            config = c;
            request(Collections.singletonList("item=config " + c));
        } else {
            phase = Phase.DONE;
            File req = new File(raw, "request.txt");
            if (req.exists() && !req.delete()) throw new IOException("can't remove request.txt");
        }
    }

    private VbfIndex header() throws IOException {
        File h = new File(new File(raw, "hdr-" + gen), "001.bin");
        if (!h.isFile() || h.length() < 16 || h.length() > VbfIndex.MAX_HEADER) throw new IOException("header copy missing");
        return VbfIndex.parseCanonical(Files.readAllBytes(h.toPath()));
    }

    /** Items 1..n of a finished job: present, at their receipt's size, and at the expected size (-1: a config, capped). */
    private static void checkItems(File dir, List<Long> expected) throws IOException {
        Map<Integer, Long> receipts = new HashMap<>();
        String text = small(new File(dir, "receipts.txt"));
        if (text == null) throw new IOException("receipts missing");
        for (String line : text.split("\n")) {
            String[] p = line.trim().split(" ");
            if (p.length == 2) receipts.put(Integer.parseInt(p[0]), Long.parseLong(p[1]));
        }
        for (int n = 1; n <= expected.size(); n++) {
            File f = new File(dir, String.format(Locale.ROOT, "%03d.bin", n));
            Long r = receipts.get(n);
            long want = expected.get(n - 1);
            if (!f.isFile() || r == null || f.length() != r) throw new IOException("item " + n + " incomplete");
            if (want >= 0 ? f.length() != want : f.length() > Inputs.MAX_CONFIG) throw new IOException("item " + n + " size");
        }
    }

    private boolean fail(String why) throws IOException {
        phase = Phase.FAILED;
        error = why;
        File req = new File(raw, "request.txt");
        if (req.exists()) req.delete();                       // nothing more for the game script to do
        save();
        return true;
    }

    // ---- files ----

    private void request(List<String> lines) throws IOException {
        StringBuilder s = new StringBuilder("contract=" + CONTRACT + "\njob=" + jobId() + "\n");
        for (String l : lines) s.append(l).append('\n');
        writeAtomic(new File(raw, "request.txt"), s.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void save() throws IOException {
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("format", FORMAT);
        o.put("gen", gen);
        o.put("phase", phase.name());
        o.put("error", error);
        o.put("config", config);
        o.put("built", built);
        o.put("entries", entries);
        o.put("configs", present);
        writeAtomic(new File(raw, "app-job.json"), Json.encode(o));
    }

    /**
     * Write then rename over, so a reader (the game script, the app) sees the old file or the new one, never half of
     * one. The temporary name is unique, and the replace is one atomic rename (no delete-then-rename fallback).
     */
    static void writeAtomic(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + "." + Thread.currentThread().getId() + "-" + System.nanoTime() + ".tmp");
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    /** A small text file (receipts up to 64 KiB, others 4 KiB), or null when absent. */
    static String small(File f) throws IOException {
        if (!f.isFile()) return null;
        if (f.length() > (f.getName().equals("receipts.txt") ? 64 * 1024 : 4096)) throw new IOException(f.getName() + " too large");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteTree(k); else k.delete();
        d.delete();
    }
}
