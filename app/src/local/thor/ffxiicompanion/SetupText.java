package local.thor.ffxiicompanion;

import java.util.HashMap;
import java.util.Map;

/**
 * 0.16.0: the setup screen's words, from SetupFlow's fixed message keys and actions. One plain sentence each; never
 * text from a file. Placeholders for Manny's voice (copy table: draft/setup_flow/SETUP_SCREEN.md).
 */
final class SetupText {
    private SetupText() {}

    static final Map<String, String> TITLES = new HashMap<>();
    static final Map<String, String> LINES = new HashMap<>();

    static {
        TITLES.put("storage", "File access");
        TITLES.put("install", "Setup files");
        TITLES.put("loaders", "Game loaders");
        TITLES.put("script", "Game script");
        TITLES.put("data", "Game data");

        LINES.put("storage_ready", "File access allowed.");
        LINES.put("storage_permission_needed", "Allow file access so the companion can read the game's updates.");
        LINES.put("storage_unavailable", "The Download folder can't be read or written right now.");
        LINES.put("storage_not_checked", "File access not checked yet.");
        LINES.put("platform_unsupported", "This Android version isn't supported.");

        LINES.put("install_files_current", "Setup files are in Download/FFXII_companion/install.");
        LINES.put("install_sync_needed", "The setup files couldn't be saved to Download/FFXII_companion/install.");
        LINES.put("install_release_assets_missing", "This app build is missing its setup files. Install an official release.");
        LINES.put("install_not_checked", "Setup files not checked yet.");
        LINES.put("install_wait_storage", "Needs file access first.");

        LINES.put("loaders_reported_ready", "The game reports a supported LUA Script Loader and External File Loader.");
        LINES.put("loaders_not_observed", "Start FFXII in GameNative so the companion can check the game setup.");
        LINES.put("loaders_wait_install", "Needs the setup files first.");
        LINES.put("loader_versions_not_confirmed", "The game didn't report its loader versions yet.");
        LINES.put("loader_file_reported_missing", "The game reports a loader file missing. Run the setup steps again.");
        LINES.put("loader_version_reported_unsupported", "The game's LUA Script Loader version isn't supported. See the setup steps.");
        LINES.put("efl_version_reported_unsupported", "The game's External File Loader version isn't supported. See the setup steps.");
        LINES.put("loader_unsupported_lua", "The game loader needs a supported version. See the setup steps.");

        LINES.put("script_reported_live", "The companion's game script is running.");
        LINES.put("script_waiting_for_game", "Start FFXII to check the game script.");
        LINES.put("script_game_closed", "FFXII is closed. Start it to check the game script.");
        LINES.put("script_no_fresh_signal", "No word from the game right now. Start FFXII (or wait for it to load).");
        LINES.put("script_unaccepted_source", "The game's report couldn't be trusted. Restart FFXII.");
        LINES.put("script_other_generation", "The game's report is from an older start. Restart FFXII.");
        LINES.put("script_game_build_unconfirmed", "Load a save so the companion can confirm the game version.");
        LINES.put("script_game_build_unsupported", "This version of FFXII isn't supported yet.");
        LINES.put("script_install_receipt_unconfirmed", "Restart FFXII so the game script reports in.");
        LINES.put("script_restart_for_update", "Restart FFXII to use the updated game script.");
        LINES.put("script_stub_old", "The small game loader needs a one-time update. See the setup steps.");
        LINES.put("script_unsupported_lua", "The game loader needs a supported version. See the setup steps.");
        LINES.put("script_restore_bundled_copy", "The app put its game script back. Restart FFXII to use it.");
        LINES.put("script_update_required", "Install the latest official companion update.");
        LINES.put("script_safety_record_problem", "The game script's safety record needs repair. See the setup steps.");
        LINES.put("script_reported_start_failure", "The game script didn't start. See the setup steps.");
        LINES.put("script_reported_result_unknown", "The game reported something this app doesn't know. See the setup steps.");
        LINES.put("script_wait_install", "Needs the setup files first.");

        LINES.put("data_ready", "Game data ready.");
        LINES.put("data_ready_legacy", "Game data ready (older format; a rebuild adds the newest map checks).");
        LINES.put("data_pack_missing", "No game data yet: build it from your game (about 5 minutes of play).");
        LINES.put("data_pack_invalid", "Some game data files are missing or don't match. Rebuild them.");
        LINES.put("data_format_unsupported", "This game data is in a format this app doesn't read. Rebuild it.");
        LINES.put("data_other_generation", "The game data is being replaced.");
        LINES.put("data_rebuild_required", "The game data needs rebuilding.");
        LINES.put("data_not_checked", "Game data not checked yet.");
        LINES.put("data_wait_storage", "Needs file access first.");
        LINES.put("build_copying", "Copying the files needed to build your game data.");
        LINES.put("build_paused_fight", "Copying is paused during the fight.");
        LINES.put("build_paused_appaway", "Keep the companion open to carry on copying.");
        LINES.put("build_copied_not_committed", "Copied. Building your game data…");
        LINES.put("build_processing", "Building and checking your game data…");
        LINES.put("build_copy_failed", "The copy stopped. Try again.");
    }

    static String title(String id) { return TITLES.getOrDefault(id, id); }

    static String line(String key) { return LINES.getOrDefault(key, "Check not available."); }

    /** The button for an action, or null for none (waiting, or nothing to do). */
    static String label(SetupFlow.Action a) {
        switch (a) {
            case GRANT_STORAGE: return "Allow file access";
            case SYNC_INSTALL: return "Try again";
            case OPEN_GAME_NATIVE: return "Open GameNative";
            case SHOW_SETUP_STEPS:
            case REINSTALL_BOOTSTRAP: return "Show setup steps";
            case UPDATE_APP: return "Open releases";
            case START_BUILD: return "Build game data";
            case RETRY_BUILD: return "Try again";
            default: return null;            // RESTART_GAME, RESTORE_INSTALL, WAIT_*, RESUME_BUILD: the line says what to do
        }
    }
}
