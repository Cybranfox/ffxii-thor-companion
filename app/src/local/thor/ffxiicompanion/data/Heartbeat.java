package local.thor.ffxiicompanion.data;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * raw/alive.txt: the app's "still here" for the game script's raw copy, which pauses ("app_away") once it's more than 30 s
 * old. The app writes it every BEAT_S while a copy job runs and the app is in front; going away pauses the copy, by
 * design, and it carries on from its cursor when the app comes back.
 */
public final class Heartbeat {
    public static final int BEAT_S = 10;

    private Heartbeat() {}

    /** epochSeconds: the device clock (the game script compares it with its own os.time()). */
    public static void beat(File base, long epochSeconds) throws IOException {
        File raw = new File(base, "raw");
        if (!raw.isDirectory() && !raw.mkdirs()) throw new IOException("can't make " + raw);
        CopyJob.writeAtomic(new File(raw, "alive.txt"), Long.toString(epochSeconds).getBytes(StandardCharsets.US_ASCII));
    }
}
