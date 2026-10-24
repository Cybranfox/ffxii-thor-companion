package local.thor.ffxiicompanion;

import java.util.Map;

import local.thor.ffxiicompanion.data.CopyJob;

/**
 * 0.16.0: what the Status tab's "Game data" card says, from the copy job, the game script's live status (rawcopy,
 * only while the session is LIVE and only for our current job), DataOwner, and the live pack's BUILD_INFO builder.
 * One line, an optional progress value and at most one action. Pure; the words are placeholders for Manny's voice.
 * Message keys follow the setup-flow draft (build_copying, build_paused_fight, ...), so its data row can reuse this.
 */
final class DataStatus {
    enum State { NONE, WAITING_GAME, COPYING, PAUSED_FIGHT, PAUSED_AWAY, BUILDING, READY, PC_PACK, FAILED, HELD }

    enum Action { NONE, BUILD, RETRY, REBUILD }

    final State state;
    final String line;
    final double progress;       // 0..1, or -1 for none
    final Action action;

    private DataStatus(State state, String line, double progress, Action action) {
        this.state = state;
        this.line = line;
        this.progress = progress;
        this.action = action;
    }

    /** The button's words for an action. */
    static String label(Action a) {
        switch (a) {
            case BUILD: return "Build game data";
            case RETRY: return "Try again";
            case REBUILD: return "Rebuild";
            default: return null;
        }
    }

    /**
     * job: the copy job or null. live: the newest snapshot's rawcopy (null when none). sessionLive: that snapshot is
     * from a running game. packBuilder: the live pack's BUILD_INFO builder, null when there's no pack.
     * failedGen / failure: DataOwner's last build failure and which copy it was for.
     */
    static DataStatus of(CopyJob job, Map<String, Object> live, boolean sessionLive, boolean building, boolean readable,
                         String packBuilder, String failedGen, String failure) {
        if (!readable) return new DataStatus(State.HELD, "Finishing a game data update…", -1, Action.NONE);
        if (building) return new DataStatus(State.BUILDING, "Building your game data…", -1, Action.NONE);
        CopyJob.Phase p = job == null ? null : job.phase();
        if (p == CopyJob.Phase.FAILED)
            return new DataStatus(State.FAILED, words(job.error()) + " Using your previous data.", -1, Action.RETRY);
        if (job != null && job.gen().equals(failedGen) && failure != null)
            return new DataStatus(State.FAILED, words(failure) + " Using your previous data.", -1, Action.RETRY);
        if (p == CopyJob.Phase.HEADER || p == CopyJob.Phase.DATA || p == CopyJob.Phase.CONFIG) {
            boolean ours = sessionLive && live != null && job.jobId().equals(live.get("job"));
            if (!ours) return new DataStatus(State.WAITING_GAME,
                    "Start FFXII to copy the game files (about 5 minutes of play; you can keep playing).", progress(job, null), Action.NONE);
            Object paused = live.get("paused");
            if ("fight".equals(paused)) return new DataStatus(State.PAUSED_FIGHT,
                    "Copying paused during the fight; it carries on in the field.", progress(job, live), Action.NONE);
            if ("app_away".equals(paused)) return new DataStatus(State.PAUSED_AWAY, "Carrying on…", progress(job, live), Action.NONE);
            return new DataStatus(State.COPYING, copying(job, live), progress(job, live), Action.NONE);
        }
        if (packBuilder == null) return new DataStatus(State.NONE, "No game data yet.", -1, Action.BUILD);
        if (!packBuilder.startsWith("app-"))
            return new DataStatus(State.PC_PACK, "Game data from the PC builder.", -1, Action.REBUILD);
        return new DataStatus(State.READY, "Game data ready.", -1, Action.REBUILD);
    }

    /** "Copying game files: step 2 of 3, 34 of 75 files." */
    static String copying(CopyJob job, Map<String, Object> live) {
        int step = job.phase() == CopyJob.Phase.HEADER ? 1 : job.phase() == CopyJob.Phase.DATA ? 2 : 3;
        String s = "Copying game files: step " + step + " of 3";
        if (job.phase() == CopyJob.Phase.DATA) {
            Long item = Json.lng(live, "item"), items = Json.lng(live, "items");
            if (item != null && items != null) s += ", " + Math.min(item, items) + " of " + items + " files";
        }
        return s + ".";
    }

    /** Overall 0..1: header 0..0.5, data 0.5..0.97, configs 0.97..1; within a step by items and bytes; never back. */
    static double progress(CopyJob job, Map<String, Object> live) {
        double lo, hi;
        switch (job.phase()) {
            case HEADER: lo = 0; hi = 0.5; break;
            case DATA: lo = 0.5; hi = 0.97; break;
            default: lo = 0.97; hi = 1; break;
        }
        double in = 0;
        if (live != null && job.jobId().equals(live.get("job"))) {
            Long item = Json.lng(live, "item"), items = Json.lng(live, "items"), bytes = Json.lng(live, "bytes"), total = Json.lng(live, "total");
            if (item != null && items != null && items > 0) {
                double part = bytes != null && total != null && total > 0 ? Math.min(1, (double) bytes / total) : 0;
                in = Math.min(1, (Math.max(1, item) - 1 + part) / items);
            }
            if ("done".equals(live.get("status"))) in = 1;
        }
        return lo + (hi - lo) * in;
    }

    /** The game script's and the builder's reasons, in plain words. */
    static String words(String why) {
        if (why == null) return "Something went wrong.";
        String w = why.replace("copy stopped: ", "").trim();
        if (w.startsWith("entry_")) return "The game files changed (an update?). Try again.";
        if (w.equals("archive_missing")) return "The game script couldn't open the game's files.";
        if (w.equals("not_sryk") || w.equals("header_size")) return "The game's files don't look like FFXII's.";
        if (w.equals("request_size") || w.equals("too_many_items") || w.contains("more than one request"))
            return "This game version needs an app update.";
        if (w.startsWith("Not enough free space")) return "Not enough free space.";
        if (w.startsWith("Build check failed")) return "The new data didn't pass its checks.";
        if (w.startsWith("Build failed")) return "Building the data failed.";
        if (w.equals("read") || w.equals("write") || w.equals("write_open") || w.equals("seek"))
            return "The copy couldn't read or write a file (storage full?).";
        return "Copy stopped (" + w + ").";
    }
}
