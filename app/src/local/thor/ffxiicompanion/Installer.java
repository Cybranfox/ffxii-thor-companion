package local.thor.ffxiicompanion;

import android.content.res.AssetManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Keeps Download/FFXII_companion/install/ in step with this app: the loader stub (ThorCompanion.lua, installed once with
 * install.cmd), the signed game script (ThorCompanion-core.lua + .sig, which the loader verifies at every game start, so
 * updating the app is the whole update), an optional key-rotation policy, and the container install/uninstall scripts.
 * Only files that differ are rewritten, each through a temporary file and a rename; nothing outside that folder is
 * touched. A game started while the pair is being replaced simply refuses the mismatched pair and works on the next start.
 */
final class Installer {
    static final String[] FILES = {"ThorCompanion.lua", "ThorCompanion-core.lua", "ThorCompanion-core.sig",
            "ThorCompanion-policy.txt", "install.cmd", "uninstall.cmd", "README.txt"};
    private static final long MAX_BYTES = 1 << 20;

    /** Why the last sync failed (shown under Details), or null. */
    static volatile String lastError;

    private Installer() {}

    /** Number of files written (0 = already current); -1 when the folder can't be written (no storage access). */
    static int sync(AssetManager assets, File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) { lastError = "cannot create " + dir; return -1; }
        List<String> bundled;
        try {
            bundled = Arrays.asList(assets.list("install"));
        } catch (IOException e) {
            lastError = "assets: " + e;
            return -1;
        }
        List<File[]> pending = new ArrayList<>();   // {temp, final}, renamed together after every temp is written
        for (String name : FILES) {
            if (!bundled.contains(name)) continue;   // development builds carry no signature / no policy
            try (InputStream in = assets.open("install/" + name)) {
                byte[] want = readAll(in, MAX_BYTES);
                File f = new File(dir, name);
                if (f.isFile() && f.length() == want.length && Arrays.equals(readFile(f), want)) continue;
                File tmp = new File(dir, name + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(want);
                    out.getFD().sync();
                }
                pending.add(new File[]{tmp, f});
            } catch (IOException | RuntimeException e) {
                lastError = name + ": " + e;
                for (File[] p : pending) p[0].delete();
                return -1;
            }
        }
        for (File[] p : pending) {
            if (!p[0].renameTo(p[1]) && !(p[1].delete() && p[0].renameTo(p[1]))) {
                lastError = "cannot replace " + p[1].getName();
                return -1;
            }
        }
        lastError = null;
        return pending.size();
    }

    private static byte[] readFile(File f) throws IOException {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readAll(in, MAX_BYTES);
        }
    }

    private static byte[] readAll(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) {
            b.write(buf, 0, n);
            if (b.size() > cap) throw new IOException("file too large");
        }
        return b.toByteArray();
    }
}
