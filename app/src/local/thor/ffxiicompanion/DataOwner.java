package local.thor.ffxiicompanion;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import local.thor.ffxiicompanion.data.CopyJob;
import local.thor.ffxiicompanion.data.Heartbeat;
import local.thor.ffxiicompanion.data.PackSwap;

/**
 * 0.16.0: the one owner of everything the app writes in FFXII_companion (the pack, the copy job's state and requests,
 * the heartbeat). One per process (get()), so it outlives the activity: a re-created activity finds the same owner,
 * never a second writer. Everything runs on its one thread, in order: finish a cut-short swap (and nothing reads the
 * pack until that has worked), keep a copy going, build, swap, clean up. Screens read the pack only while
 * packReadable(), and learn about new packs and failures from counters they compare on their 2 s refresh.
 * Pure except for the ThreadFactory (MainActivity passes one at background priority).
 */
final class DataOwner {
    private static DataOwner shared;

    private final File base;
    private final ExecutorService thread;
    private final AtomicBoolean tickQueued = new AtomicBoolean();
    private volatile Map<String, Object> latestLive;
    private volatile boolean recovered, swapping, building;
    private volatile int installs, failures;
    private volatile String problem, lastFailure;
    private volatile String failedGen;          // the copy whose build failed (not retried by itself)
    private String unmarkedGen;                 // owner thread only
    private long lastBeat;

    DataOwner(File base, ThreadFactory threads) {
        this.base = base;
        this.thread = Executors.newSingleThreadExecutor(threads);
    }

    /** The process's owner for base (the first caller's base and threads win). */
    static synchronized DataOwner get(File base, ThreadFactory threads) {
        if (shared == null) shared = new DataOwner(base, threads);
        return shared;
    }

    /** False while a swap is unfinished (recovery not done yet, or failed) or running: don't read or cache the pack. */
    boolean packReadable() { return recovered && !swapping; }

    boolean building() { return building; }

    /** A new pack went live this many times (reload when it changes). */
    int installs() { return installs; }

    /** A build failed this many times; lastFailure() says why. */
    int failures() { return failures; }

    String lastFailure() { return lastFailure; }

    String failedGen() { return failedGen; }

    /** Recovery or copy-job trouble that's still there (null when fine). */
    String problem() { return problem; }

    /**
     * From the activity's refresh, only while it's in front (so the heartbeat stops when it isn't): one step on the
     * owner's thread with the newest snapshot's rawcopy. Ticks never pile up; the newest status wins.
     */
    void tick(Map<String, Object> rawcopy) {
        latestLive = rawcopy;
        if (tickQueued.compareAndSet(false, true)) thread.execute(() -> {
            tickQueued.set(false);
            step(latestLive);
        });
    }

    /** Starts a new copy (Build game data / Try again). */
    Future<?> startCopy(String gen) {
        return thread.submit(() -> {
            try {
                CopyJob.start(base, gen);
                failedGen = null;
                problem = null;
            } catch (IOException e) {
                problem = "Couldn't start the copy: " + e.getMessage();
            }
        });
    }

    /** Runs r on the owner's thread and waits (tests). */
    void await(Runnable r) throws Exception { thread.submit(r).get(); }

    void step(Map<String, Object> live) {
        if (!recovered) {
            try {
                PackSwap.recover(base);
                recovered = true;
                problem = null;
            } catch (IOException e) {
                problem = "Finishing a game data update: " + e.getMessage();
                return;                                  // tried again on the next tick; nothing reads the pack meanwhile
            }
        }
        CopyJob job = CopyJob.load(base);
        if (job == null) return;
        try {
            CopyJob.Phase p = job.phase();
            if (p == CopyJob.Phase.HEADER || p == CopyJob.Phase.DATA || p == CopyJob.Phase.CONFIG) {
                long now = System.currentTimeMillis() / 1000;
                if (now - lastBeat >= Heartbeat.BEAT_S) {
                    Heartbeat.beat(base, now);
                    lastBeat = now;
                }
                job.advance(live);
            }
            if (job.gen().equals(unmarkedGen)) {          // installed, but recording it failed: record it, don't rebuild
                job.markBuilt();
                unmarkedGen = null;
                return;
            }
            if (!job.gen().equals(failedGen) && PackBuild.needed(base, job)) build(job);
            problem = null;
        } catch (IOException e) {
            problem = "Copy: " + e.getMessage();
        }
    }

    private void build(CopyJob job) {
        building = true;
        PackBuild.Result r;
        try {
            r = PackBuild.run(base, job, "b" + System.currentTimeMillis(), () -> false, s -> swapping = s);
        } finally {
            building = false;
        }
        if (new File(base, ".swap.json").exists()) recovered = false;   // the swap was cut short: finish it first
        if (r.installed) {
            if (r.problem != null) unmarkedGen = job.gen();
            job.keepOnlyThis();
            installs++;
        } else {
            failedGen = job.gen();
            lastFailure = r.problem;
            failures++;
        }
    }
}
