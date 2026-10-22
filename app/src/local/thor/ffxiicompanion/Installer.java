package local.thor.ffxiicompanion;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Keeps Download/FFXII_companion/install/ in step with this app: the game script (ThorCompanion.lua) of the same release
 * and the container install/uninstall scripts, so updating the app also brings the matching script. Only files that
 * differ are rewritten; nothing outside that folder is touched. The user runs install.cmd in the game's container.
 */
final class Installer {
    static final String[] FILES = {"ThorCompanion.lua", "install.cmd", "uninstall.cmd", "README.txt"};

    /** Why the last sync failed (shown under Details), or null. */
    static volatile String lastError;

    private Installer() {}

    /** Number of files written (0 = already current); -1 when the folder can't be written (no storage access). */
    static int sync(AssetManager assets, File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) { lastError = "cannot create " + dir; return -1; }
        int written = 0;
        for (String name : FILES) {
            try (InputStream in = assets.open("install/" + name)) {
                byte[] want = readAll(in);
                File f = new File(dir, name);
                if (f.isFile() && f.length() == want.length && Arrays.equals(readFile(f), want)) continue;
                try (FileOutputStream out = new FileOutputStream(f)) {
                    out.write(want);
                }
                written++;
            } catch (IOException | RuntimeException e) {
                lastError = name + ": " + e;
                return -1;
            }
        }
        lastError = null;
        return written;
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toByteArray();
    }
}
