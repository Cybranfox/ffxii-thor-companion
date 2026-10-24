package local.thor.ffxiicompanion;

/** 0.11.0: the loader receipt is read strictly and only fixed messages are shown; the update hint knows about the loader. */
public final class LoaderStatusTest {
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); }

    public static void main(String[] args) {
        check("ok".equals(LoaderStatus.result("{\"contract\":\"ffxii.loader.v1\",\"stub\":1,\"result\":\"ok\"}")), "ok receipt");
        check(LoaderStatus.result("{\"contract\":\"other\",\"result\":\"ok\"}") == null, "other contract ignored");
        check(LoaderStatus.result("{\"contract\":\"ffxii.loader.v1\",\"result\":\"Run evil.exe now\"}") == null, "free text never accepted as a code");
        check(LoaderStatus.result("not json") == null && LoaderStatus.result(null) == null, "garbage ignored");
        check(LoaderStatus.message("ok") == null && LoaderStatus.message(null) == null, "ok: no message");
        for (String r : new String[]{"tampered", "bad_format", "missing", "rollback", "unknown_key", "stub_old", "state_corrupt",
                "state_unwritable", "unsupported_lua", "core_failed", "something_new"}) {
            String m = LoaderStatus.message(r);
            check(m != null && m.startsWith("The game reports: ") && m.endsWith("."), r);
        }
        check(LoaderStatus.message("stub_old").contains("install.cmd") && LoaderStatus.message("tampered").contains("restart the game"), "next steps");
        check(LoaderStatus.message("unsupported_lua").contains("2.0.1") && !LoaderStatus.message("unsupported_lua").contains("1.7.1"),
                "unsupported loader: points to the tested 2.0.1, not the old 1.7.1");
        check(LoaderStatus.restorable("tampered") && LoaderStatus.restorable("missing") && !LoaderStatus.restorable("rollback")
                && !LoaderStatus.restorable("ok"), "the app restores its own files only for tampered/missing scripts");
        check(LoaderStatus.updateHint("0.10.0-beta", "0.11.0-beta", false).contains("install.cmd"), "old single-file script: one install.cmd");
        check(LoaderStatus.updateHint("0.11.0-beta", "0.11.1-beta", true).startsWith("Restart the game"), "with the loader: just restart");
        check(LoaderStatus.updateHint("0.11.0-beta", "0.11.0-beta", true) == null && LoaderStatus.updateHint(null, "x", true) == null, "no hint when current");
        boolean dup = false, inf = false;
        try { Json.parse("{\"a\":1,\"a\":2}"); } catch (IllegalArgumentException e) { dup = true; }
        try { Json.parse("{\"seq\":1e999}"); } catch (IllegalArgumentException e) { inf = true; }
        check(dup && inf, "JSON: duplicate keys and out-of-range numbers are refused");
        check(Json.lng(Json.parse("{\"n\":2.5}"), "n") == null && Json.lng(Json.parse("{\"n\":3.0}"), "n") == 3L, "only whole numbers become longs");
        System.out.println("PASS LoaderStatusTest 25 checks (strict receipt, fixed messages, loader-aware update hint)");
    }
}
