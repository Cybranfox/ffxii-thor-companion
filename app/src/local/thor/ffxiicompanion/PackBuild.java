package local.thor.ffxiicompanion;

import java.io.File;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import local.thor.ffxiicompanion.data.CopyJob;
import local.thor.ffxiicompanion.data.DataBuilder;
import local.thor.ffxiicompanion.data.PackSwap;

/**
 * 0.16.0: one build, from the copied game files to the pack the app uses. DataBuilder -> staged in .build-&lt;id&gt;/ ->
 * PackCheck -> PackSwap. Any failure before the swap leaves the current pack as it was. Pure; DataOwner runs it on its
 * one thread, the only writer of the pack and the copy job's state.
 */
final class PackBuild {
    /** Room for the staged pack, the live one and the previous one, with a margin (the pack is about 15 MB). */
    static final long MIN_FREE = 128L * 1024 * 1024;

    /** installed: the new pack is live (reload, even with a warning); otherwise problem says why not. */
    static final class Result {
        final boolean installed;
        final String problem;     // failed: why; installed: a bookkeeping warning, or null

        private Result(boolean installed, String problem) {
            this.installed = installed;
            this.problem = problem;
        }

        static Result installed(String warning) { return new Result(true, warning); }

        static Result failed(String why) { return new Result(false, why); }
    }

    private PackBuild() {}

    /** True when this copy job's files should be (re)built: copied but not built yet, or built by an older app builder. */
    static boolean needed(File base, CopyJob job) {
        if (job == null || job.phase() != CopyJob.Phase.DONE) return false;
        if (!job.built()) return true;
        String b = PackCheck.builder(base);
        return b != null && b.startsWith("app-") && !b.equals(DataBuilder.VERSION);
    }

    /**
     * Builds, checks and swaps in. swapping(true/false) brackets the swap itself (a few renames), so screens can hold
     * off reading the pack while it changes. After a swap the job is marked built; if only that fails, the pack is
     * still reported installed (DataOwner marks it again later).
     */
    static Result run(File base, CopyJob job, String id, BooleanSupplier cancelled, Consumer<Boolean> swapping) {
        File stage = PackSwap.stage(base, id);
        try {
            if (base.getUsableSpace() < MIN_FREE) return Result.failed("Not enough free space");
            List<DataBuilder.Output> pack = DataBuilder.build(job.inputs(), DataBuilder.VERSION);
            if (cancelled.getAsBoolean()) return Result.failed("Stopped");
            PackSwap.write(pack, stage);
            pack = null;
            String problem = PackCheck.problem(stage);
            if (problem != null) return Result.failed("Build check failed: " + problem);
            if (cancelled.getAsBoolean()) return Result.failed("Stopped");
            swapping.accept(true);
            try {
                PackSwap.swap(base, id);
            } finally {
                swapping.accept(false);
            }
        } catch (Exception | OutOfMemoryError e) {
            return Result.failed("Build failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        } finally {
            if (stage.exists() && !new File(base, ".swap.json").exists()) deleteTree(stage);
        }
        try {
            job.markBuilt();
            return Result.installed(null);
        } catch (Exception e) {
            return Result.installed("couldn't record the build: " + e.getMessage());
        }
    }

    private static void deleteTree(File d) {
        File[] kids = d.listFiles();
        if (kids != null) for (File k : kids) if (k.isDirectory()) deleteTree(k); else k.delete();
        d.delete();
    }
}
