package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * 0.11.0: the loader's receipt, live/loader_status.json (contract ffxii.loader.v1), written at every game start by the
 * signed-script loader. It lives in shared storage, so the app treats it as the game's report, never as proof: wording
 * is "The game reports…", and only fixed messages are shown (never text from the file).
 */
final class LoaderStatus {
    static final String CONTRACT = "ffxii.loader.v1";
    static final int MAX_BYTES = 4096;

    private LoaderStatus() {}

    /** The loader's result code, or null when the file isn't a loader receipt. */
    static String result(String json) {
        if (json == null || json.length() > MAX_BYTES) return null;
        Object o;
        try {
            o = Json.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(o instanceof Map) || !CONTRACT.equals(Json.str(o, "contract"))) return null;
        String r = Json.str(o, "result");
        return r != null && r.matches("[a-z_]{1,32}") ? r : null;
    }

    /** One plain sentence with one next step for a refusal; null for ok (or unknown codes, shown as a generic line). */
    static String message(String result) {
        if (result == null || result.equals("ok") || result.equals("already_loaded")) return null;
        String m;
        switch (result) {
            case "tampered":
            case "bad_format":
                m = "the game script could not be verified, so it did not run. This app has put its own copy back: restart the game.";
                break;
            case "missing":
                m = "the game script was missing. This app has put its own copy back: restart the game.";
                break;
            case "rollback":
                m = "this game script is older than one already installed. Install the latest companion APK from the official releases page, then restart the game.";
                break;
            case "unknown_key":
                m = "this game script isn't signed by a key the loader accepts. Install the latest companion APK from the official releases page.";
                break;
            case "stub_old":
                m = "the small game loader needs an update. Run install.cmd once in the game's container (Download/FFXII_companion/install).";
                break;
            case "state_corrupt":
            case "state_unwritable":
                m = "the loader couldn't read or save its safety record. In the game's container run uninstall.cmd, then install.cmd.";
                break;
            case "unsupported_lua":
                m = "this LUA Script Loader version isn't supported. Use FF12 LUA Script Loader "   // the tested one, as Setup says
                        + SetupInputs.LUA_VERSIONS.get(0) + " with External File Loader " + SetupInputs.EFL_VERSIONS.get(0) + ".";
                break;
            default:
                m = "the verified game script couldn't start. Install the latest companion update and restart the game.";
        }
        return "The game reports: " + m;
    }

    /** Refusals the app fixes itself by restoring its bundled files (the next game start then works). */
    static boolean restorable(String result) {
        return "tampered".equals(result) || "bad_format".equals(result) || "missing".equals(result);
    }

    /**
     * What to say when the running game script isn't this app's version. hasLoader: a loader receipt exists, i.e. the
     * loader is installed (0.11+), so the new script only needs a game restart; without it the player is still on the
     * old single-file script and must run install.cmd once.
     */
    static String updateHint(String running, String bundled, boolean hasLoader) {
        if (running == null || bundled == null || running.isEmpty() || bundled.isEmpty() || running.equals(bundled)) return null;
        return hasLoader ? "Restart the game to finish updating to " + bundled + "."
                : "One last manual step: run install.cmd once in the game's container (Download/FFXII_companion/install)."
                  + " After that, updates install themselves.";
    }
}
