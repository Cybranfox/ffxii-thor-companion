package local.thor.ffxiicompanion;

/** Close only after this Activity observed a real running session and its explicit exit. */
final class AutoClosePolicy {
    static final long EXIT_DELAY_MS = 1500;
    private boolean armed;
    private long exitAt = -1;

    boolean update(boolean running, boolean exited, boolean synthetic, long now) {
        if (synthetic) { exitAt = -1; return false; }
        if (running) { armed = true; exitAt = -1; }
        else if (!exited) exitAt = -1; // missing/stale data is not an exit
        if (armed && exited) {
            if (exitAt < 0) exitAt = now;
            return now - exitAt >= EXIT_DELAY_MS;
        }
        return false;
    }
}
