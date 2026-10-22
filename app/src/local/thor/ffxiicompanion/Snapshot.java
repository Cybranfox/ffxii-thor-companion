package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * One parsed file-ab snapshot (protocol v1, written by ThorCompanion.lua). This is the deployed
 * format; it is not the rev 2 native-bridge wire protocol. Returns null for anything malformed.
 */
final class Snapshot {
    static final int MAX_BYTES = 256 * 1024;

    final long seq;
    final long mtime;
    final Map<String, Object> data;
    final boolean exiting;
    /** Game clock (us) at write time, from data.session.clockUs; null in exit snapshots. */
    final Long clockUs;
    /** Explicit producer session id (proposed field data.session.id); null for the deployed v0.3.x script. */
    final String sessionId;
    /** Top-level "synthetic": true marks fixture data. Never set by the game-side script. */
    final boolean synthetic;
    final String fixture;

    private Snapshot(long seq, long mtime, Map<String, Object> data, boolean synthetic, String fixture) {
        this.seq = seq;
        this.mtime = mtime;
        this.data = data;
        Map<String, Object> sess = Json.obj(data, "session");
        this.exiting = sess != null && Boolean.TRUE.equals(Json.bool(sess, "exiting"));
        this.clockUs = sess != null ? Json.lng(sess, "clockUs") : null;
        this.sessionId = sess != null ? Json.str(sess, "id") : null;
        this.synthetic = synthetic;
        this.fixture = fixture;
    }

    @SuppressWarnings("unchecked")
    static Snapshot parse(String text, long mtime) {
        if (text == null || text.isEmpty() || text.length() > MAX_BYTES) return null;
        Object root;
        try {
            root = Json.parse(text);
        } catch (IllegalArgumentException e) {
            return null;  // half-written or corrupt; the other slot holds the previous good snapshot
        }
        if (!(root instanceof Map)) return null;
        Long v = Json.lng(root, "v");
        if (v == null || v != 1) return null;
        if (!"snapshot".equals(Json.str(root, "type"))) return null;
        Long seq = Json.lng(root, "seq");
        if (seq == null || seq < 1) return null;
        Map<String, Object> data = Json.obj(root, "data");
        if (data == null) return null;
        boolean synthetic = Boolean.TRUE.equals(Json.bool(root, "synthetic"));
        return new Snapshot(seq, mtime, data, synthetic, Json.str(root, "fixture"));
    }

    Map<String, Object> hello() { return Json.obj(data, "hello"); }
    Map<String, Object> map() { return Json.obj(data, "map"); }
    Map<String, Object> party() { return Json.obj(data, "party"); }

    /** Capability value from hello.capabilities ("on", "probe", "off"), or null. */
    String capability(String name) {
        return Json.str(Json.obj(hello(), "capabilities"), name);
    }
}
