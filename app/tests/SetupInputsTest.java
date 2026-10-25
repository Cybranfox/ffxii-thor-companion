package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SetupInputs (0.16.0) through the real SetupFlow: what the setup screen shows in the situations a player actually
 * meets, built from observations shaped like the Thor's (a snapshot's hello section as the 0.15 game script writes
 * it, a loader receipt, the bundled signature header). Synthetic values only.
 */
public class SetupInputsTest {
    static int passed = 0;

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
        System.out.println("PASS " + what);
    }

    static final String SIG = "FFXII-CORE-SIG-V1\nsequence=6\nversion=0.15.0-beta\nmin-stub=1\nbuild-date=2026-10-23\nkey-id=95f31409c38245d1\n";
    static final long SESSION_WALL = 1_800_000_000L;

    static Map<String, Object> m(Object... kv) { return SetupInputs.map(kv); }

    static List<Object> l(Object... xs) { return new ArrayList<>(Arrays.asList(xs)); }

    /** A snapshot's data as the 0.15 game script writes it at the title screen (hello, session, rawcopy). */
    static Map<String, Object> snapshot(String efl, String lua, String producer) {
        Map<String, Object> hello = m(
                "producer", m("name", "ThorCompanion-lua", "version", producer),
                "loader", m("lua", lua, "plugins", l(m("id", "ff12.efl", "version", efl), m("id", "ff12.loader", "version", lua))),
                "build", m("imageBase", 0x120000L, "pe", m("timeDateStamp", Reveal.SUPPORTED_TIME_DATE_STAMP)),
                "mods", l(m("id", "ffgriever.efl", "file_present", m("value", true)), m("id", "ffgriever.lualoader", "file_present", m("value", true))));
        return m("hello", hello, "session", m("wallTime", SESSION_WALL));
    }

    /** A healthy Thor: file access, setup files synced, a live session, a fresh ok receipt, a good atlas-2 pack. */
    static SetupInputs.Obs healthy() {
        SetupInputs.Obs o = new SetupInputs.Obs();
        o.supported = o.storageGranted = o.readable = o.writable = true;
        o.assetsComplete = o.installSynced = true;
        o.bundledSig = SetupInputs.parseSig(SIG);
        o.loaderStatus = m("contract", "ffxii.loader.v1", "stub", 2L, "result", "ok", "version", "0.15.0-beta", "sequence", 6L,
                "wallTime", SESSION_WALL - 5);
        o.transport = "LIVE";
        o.accepted = true;
        o.buildSupported = true;
        o.snapshotData = snapshot("1.6.1", "2.0.1", "0.15.0-beta");
        o.packChecked = o.packPresent = o.packReadable = true;
        o.atlasFormat = Atlas.FORMAT_2;
        return o;
    }

    static SetupFlow.View run(SetupInputs.Obs o) { return SetupFlow.evaluate(SetupInputs.build(o)); }

    static SetupFlow.Step step(SetupFlow.View v, String id) {
        for (SetupFlow.Step s : v.steps) if (s.id.equals(id)) return s;
        throw new AssertionError(id);
    }

    public static void main(String[] args) {
        Map<String, Object> sig = SetupInputs.parseSig(SIG);
        check(sig != null && Long.valueOf(6).equals(sig.get("sequence")) && "0.15.0-beta".equals(sig.get("version"))
                && Long.valueOf(1).equals(sig.get("minStub")), "bundled signature header read: sequence 6, 0.15.0-beta, min-stub 1");
        check(SetupInputs.parseSig("garbage") == null && SetupInputs.parseSig(null) == null, "a file that isn't a signature: null");
        List<Object> all = l("ThorCompanion.lua", "ThorCompanion-core.lua", "ThorCompanion-core.sig", "install.cmd", "uninstall.cmd", "README.txt");
        @SuppressWarnings("unchecked") List<String> names = (List<String>) (List<?>) all;
        check(SetupInputs.assetsComplete(names, sig) && !SetupInputs.assetsComplete(names.subList(0, 3), sig)
                && !SetupInputs.assetsComplete(names, null), "setup files complete only with every install file and a readable signature");

        String ok = "FFXII Thor Companion install \r\nINSTALLED: A:\\x64\\scripts\\ThorCompanion.lua (loader).\r\n";
        check("installed".equals(SetupInputs.bootstrap(ok))
                && "installed_warning".equals(SetupInputs.bootstrap("WARNING: no msvcp140.dll in x64.\nINSTALLED: x\n"))
                && "failed".equals(SetupInputs.bootstrap("FAILED: game not found at A:\\x64\n"))
                && SetupInputs.bootstrap("REMOVED: the game runs exactly as before.") == null && SetupInputs.bootstrap(null) == null,
                "install.cmd result.txt: installed / warning / failed / an uninstall or nothing");
        SetupFlow.View v = run(healthy());
        check(v.complete && v.firstBlocker == null && v.shouldPersistCompletion, "healthy Thor: all five checks OK, setup complete, remembered");
        for (String id : new String[] {"storage", "install", "loaders", "script", "data"})
            check(step(v, id).state == SetupFlow.State.OK && SetupText.line(step(v, id).messageKey).length() > 3,
                    "  " + id + ": " + SetupText.line(step(v, id).messageKey));
        SetupInputs.Obs o = healthy();
        o.completedRevision = SetupInputs.REVISION;
        check(!run(o).needsOnboarding && !run(o).shouldPersistCompletion, "after setup was completed once: the card stays hidden");

        o = new SetupInputs.Obs();
        o.supported = true;
        v = run(o);
        check(v.firstBlocker.id.equals("storage") && v.firstBlocker.action == SetupFlow.Action.GRANT_STORAGE
                && "Allow file access".equals(SetupText.label(v.firstBlocker.action)), "fresh install, no file access: first step Allow file access");
        check(step(v, "install").state == SetupFlow.State.UNKNOWN && step(v, "data").state == SetupFlow.State.UNKNOWN,
                "  nothing else is called missing before access is granted");

        o = healthy();
        o.transport = "NO_DATA";
        o.accepted = false;
        o.buildSupported = null;
        o.snapshotData = null;
        o.loaderStatus = null;
        o.packPresent = false;
        o.packProblem = "no game data";
        v = run(o);
        check(step(v, "loaders").state == SetupFlow.State.UNKNOWN && step(v, "loaders").messageKey.equals("loader_not_seen")
                && "Show setup steps".equals(SetupText.label(step(v, "loaders").action)),
                "new user, no loader receipt ever: loaders unknown (not missing), \"" + SetupText.line("loader_not_seen") + "\"");
        check(v.firstBlocker.id.equals("loaders") && step(v, "data").messageKey.equals("data_wait_script")
                && step(v, "data").state == SetupFlow.State.UNKNOWN,
                "  game data that can't be built yet waits (\"" + SetupText.line("data_wait_script") + "\"): the loader step has the button");
        // 0.16.1: started from the app, nothing back after 90 s: the card points at the mod loaders
        o.launchedNoLoader = true;
        v = run(o);
        check(v.firstBlocker.id.equals("loaders") && step(v, "loaders").state == SetupFlow.State.MISSING
                && step(v, "loaders").messageKey.equals("loader_not_loading") && SetupText.line("loader_not_loading").contains("Enable mod loaders"),
                "game started but no loader: \"" + SetupText.line("loader_not_loading").substring(0, 60) + "...\"");
        o.launchedNoLoader = false;

        o.loaderStatus = m("contract", "ffxii.loader.v1", "stub", 2L, "result", "ok", "version", "0.15.0-beta", "sequence", 6L,
                "wallTime", SESSION_WALL - 5);
        v = run(o);
        check(v.firstBlocker.id.equals("data") && v.firstBlocker.action == SetupFlow.Action.START_BUILD,
                "game closed after a good start of this release: Build game data offered on the setup card");
        o.jobId = "dat-g1";
        v = run(o);
        check(step(v, "data").messageKey.equals("build_waiting_for_game") && SetupText.inProgress("build_waiting_for_game"),
                "build requested, game closed: \"" + SetupText.line("build_waiting_for_game") + "\" (shown as progress)");

        o = healthy();
        o.buildSupported = false;
        check(step(run(o), "script").messageKey.equals("script_game_build_unsupported"), "a different FFXII build: script missing, unsupported build");

        o = healthy();
        o.transport = "NO_DATA";
        o.accepted = false;
        o.buildSupported = null;
        o.snapshotData = null;
        o.loaderStatus.put("result", "stub_old");
        v = run(o);
        check(step(v, "script").state == SetupFlow.State.MISSING && step(v, "script").messageKey.equals("script_stub_old")
                && "Show setup steps".equals(SetupText.label(step(v, "script").action)), "a refused start (stub_old, no snapshot): \"" + SetupText.line("script_stub_old") + "\"");

        o = healthy();
        o.loaderStatus.put("wallTime", SESSION_WALL - 3 * 86400);
        check(!SetupInputs.receiptApplicable(o) && step(run(o), "script").state != SetupFlow.State.OK,
                "a loader receipt from days before this session doesn't vouch for it");

        o = healthy();
        o.snapshotData = snapshot("1.5.0", "2.0.1", "0.15.0-beta");
        check(step(run(o), "loaders").messageKey.equals("efl_version_reported_unsupported"), "EFL 1.5.0 reported: loaders missing, unsupported EFL");
        o.snapshotData = snapshot("1.6.1", "1.9.9", "0.15.0-beta");
        check(step(run(o), "loaders").messageKey.equals("loader_version_reported_unsupported"), "LUA Script Loader 1.9.9 reported: unsupported");
        o.snapshotData = snapshot("1.6.1", "1.7.1", "0.15.0-beta");
        check(step(run(o), "loaders").state == SetupFlow.State.OK, "LUA Script Loader 1.7.1 + EFL 1.6.1: still supported");

        o = healthy();
        o.packPresent = false;
        o.packProblem = "no game data";
        o.jobId = "dat-g1";
        o.rawcopy = m("contract", "ffxii.rawcopy.v1", "job", "dat-g1", "status", "running", "item", 12L, "items", 75L);
        check(step(run(o), "data").messageKey.equals("build_copying"), "copying: \"" + SetupText.line("build_copying") + "\"");
        o.rawcopy.put("paused", "fight");
        check(step(run(o), "data").messageKey.equals("build_paused_fight"), "fight: \"" + SetupText.line("build_paused_fight") + "\"");
        o.rawcopy.put("job", "dat-other");
        check(step(run(o), "data").messageKey.equals("build_starting"),
                "a copy status for another job is ignored: ours is still starting");

        o = healthy();
        o.completedRevision = SetupInputs.REVISION;
        o.packChecked = false;
        v = run(o);
        check(step(v, "data").state == SetupFlow.State.UNKNOWN && !v.needsOnboarding,
                "pack not checked yet (first refresh): data unknown, a finished setup doesn't reopen (review 4)");

        o = healthy();
        o.transport = "STALE";
        o.loaderStatus.put("result", "stub_old");
        o.loaderStatus.put("wallTime", SESSION_WALL + 120);
        o.completedRevision = SetupInputs.REVISION;
        v = run(o);
        check(SetupInputs.receiptApplicable(o) && step(v, "script").messageKey.equals("script_stub_old") && v.needsOnboarding,
                "a refused start after the last snapshot: shown at once, the setup card reopens (review 3)");
        o = healthy();
        o.transport = "STALE";
        o.loaderStatus.put("result", "stub_old");
        o.loaderStatus.put("wallTime", SESSION_WALL - 600);
        check(!SetupInputs.receiptApplicable(o) && !step(run(o), "script").messageKey.equals("script_stub_old"),
                "a refusal from before a session that then ran: history, not shown (review 3)");

        o = healthy();
        o.packProblem = "atlas/x.png missing or damaged";
        check(step(run(o), "data").messageKey.equals("data_pack_invalid"), "damaged pack: \"" + SetupText.line("data_pack_invalid") + "\"");
        o = healthy();
        o.atlasFormat = Atlas.FORMAT;
        check(step(run(o), "data").messageKey.equals("data_ready_legacy"), "older atlas-1 pack: usable, \"" + SetupText.line("data_ready_legacy") + "\"");
        System.out.println(passed + " setup adapter checks passed");
    }
}
