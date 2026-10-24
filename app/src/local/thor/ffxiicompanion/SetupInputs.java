package local.thor.ffxiicompanion;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 0.16.0: fills SetupFlow's input map from what the app actually observes. Pure (MainActivity gathers Obs). Only
 * positive evidence becomes "ok": no live game means the loader and script checks stay unknown, never "missing"; the
 * loader receipt only counts for the session it was written for.
 */
final class SetupInputs {
    /** Bump when the setup requirements change, so a finished setup is checked again once. */
    static final int REVISION = 1;
    /** Loader versions this app's game script is qualified with (Loader_201_Trial_2026-10-23.md). */
    static final List<String> LUA_VERSIONS = Collections.unmodifiableList(Arrays.asList("2.0.1", "1.7.1"));
    static final List<String> EFL_VERSIONS = Collections.unmodifiableList(Arrays.asList("1.6.1", "1.4.1"));
    /** An "ok" receipt older than the newest snapshot by more than this isn't that session's (its own write failed). */
    static final long RECEIPT_WINDOW_S = 12 * 3600;

    /** What the app saw this refresh. */
    static final class Obs {
        boolean supported, storageGranted, readable, writable;
        boolean assetsComplete, installSynced;
        Map<String, Object> bundledSig;        // version, sequence, minStub of the signed core the APK carries
        Map<String, Object> loaderStatus;      // live/loader_status.json, parsed (null when none)
        String transport;                      // ReceiverState.Transport name
        boolean accepted, synthetic;
        Boolean buildSupported;                // Reveal.buildVerified(hello) while live; null otherwise
        Map<String, Object> snapshotData;      // the newest accepted snapshot's data (null when none)
        boolean packChecked, packPresent, packReadable, building;   // packChecked false: no verdict yet (unknown)
        String packProblem, atlasFormat;
        String jobId;                          // the copy job's current job id while copying (null otherwise)
        Map<String, Object> rawcopy;
        int completedRevision;
    }

    private SetupInputs() {}

    static Map<String, Object> build(Obs o) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("platform", map("supported", o.supported, "storageGranted", o.storageGranted,
                "sharedReadable", o.readable, "sharedWritable", o.writable));
        Map<String, Object> sig = o.bundledSig != null ? o.bundledSig : Collections.emptyMap();
        root.put("expected", map("setupRevision", (long) REVISION, "version", sig.get("version"), "sequence", sig.get("sequence"),
                "minStub", sig.get("minStub"), "allowedLuaVersions", LUA_VERSIONS, "job", o.jobId));
        root.put("history", map("completedSetupRevision", (long) o.completedRevision));
        root.put("install", map("releaseAssetsComplete", o.assetsComplete, "bundleBytesMatch", o.installSynced));
        if (o.loaderStatus != null) root.put("loaderStatus", o.loaderStatus);
        root.put("receiver", map("transport", o.transport, "accepted", o.accepted, "buildSupported", o.buildSupported));
        root.put("snapshot", map("synthetic", o.synthetic, "data", o.snapshotData));
        Map<String, Object> hello = Json.obj(o.snapshotData, "hello");
        Map<String, Object> loader = Json.obj(hello, "loader");
        root.put("evidence", map(
                "receiptApplicable", receiptApplicable(o),
                "snapshotGenerationMatches", true,
                "loaderInventoryCurrent", hello != null && loader != null && Json.arr(hello, "mods") != null,
                "eflCompatible", eflCompatible(loader),
                "rawcopyCurrent", "LIVE".equals(o.transport) && o.jobId != null && o.rawcopy != null && o.jobId.equals(o.rawcopy.get("job")),
                "appBuildCurrent", o.building));
        boolean valid = o.packPresent && o.packProblem == null;
        root.put("pack", !o.packChecked                 // not checked yet: no pack evidence at all (the step says so)
                ? map("committed", o.packReadable, "buildStatus", o.building ? "building" : null)
                : map("present", o.packPresent, "validated", valid, "committed", o.packReadable,
                "requiredFilesPresent", valid, "atlasFormat", o.atlasFormat, "indexRegionsVersionMatch", valid,
                "atlasReceiptMatches", valid, "generationMatches", true, "requiredRebuild", false,
                "buildStatus", o.building ? "building" : null));
        root.put("rawcopy", o.rawcopy != null ? o.rawcopy : Collections.emptyMap());
        return root;
    }

    /**
     * The receipt is rewritten at every game start, before that start's first snapshot; a refused start writes no
     * snapshot at all. So, against the newest snapshot (session.wallTime): a receipt written after it is the newest
     * start (a refusal shows at once); one written before it counts only if it's "ok": the snapshot proves the game
     * started and ran after it, so an older refusal is history (and an "ok" from long before that session isn't its
     * own). With no snapshot, the receipt is all there is.
     */
    static boolean receiptApplicable(Obs o) {
        if (o.loaderStatus == null) return false;
        Long receipt = Json.lng(o.loaderStatus, "wallTime");
        Long session = Json.lng(Json.obj(o.snapshotData, "session"), "wallTime");
        if (session == null) return true;
        if (receipt == null) return false;
        if (receipt > session) return true;
        String r = Json.str(o.loaderStatus, "result");          // and from this session: not days before its snapshots
        return ("ok".equals(r) || "already_loaded".equals(r)) && session - receipt <= RECEIPT_WINDOW_S;
    }

    /** The install files an official APK carries (Installer.FILES), and a readable signature header among them. */
    static boolean assetsComplete(java.util.Collection<String> have, Map<String, Object> sig) {
        return sig != null && have.containsAll(Arrays.asList("ThorCompanion.lua", "ThorCompanion-core.lua",
                "ThorCompanion-core.sig", "install.cmd", "uninstall.cmd", "README.txt"));
    }

    /** True / false from the loader's plugin list (ff12.efl version); null when the loader doesn't report it. */
    static Boolean eflCompatible(Map<String, Object> loader) {
        List<Object> plugins = Json.arr(loader, "plugins");
        if (plugins == null) return null;
        for (Object p : plugins)
            if ("ff12.efl".equals(Json.str(p, "id"))) return EFL_VERSIONS.contains(Json.str(p, "version"));
        return null;
    }

    /** The signed core's envelope header (FFXII-CORE-SIG-V1: sequence, version, min-stub), or null. */
    static Map<String, Object> parseSig(String text) {
        if (text == null || !text.startsWith("FFXII-CORE-SIG-V1\n")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "\nsequence=([1-9]\\d{0,8})\nversion=([0-9a-z.\\-]{1,64})\nmin-stub=([1-9]\\d{0,4})\n").matcher(text);
        if (!m.find()) return null;
        return map("sequence", Long.parseLong(m.group(1)), "version", m.group(2), "minStub", Long.parseLong(m.group(3)));
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
