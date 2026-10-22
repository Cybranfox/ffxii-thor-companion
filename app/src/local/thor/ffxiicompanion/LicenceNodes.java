package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * The live licence-node table the game script writes to live/licence_nodes.json (contract ffxii.licnodes.v1): per node
 * the LP cost (+0x04), type (+0x05) and up to eight granted IDs (+0x08, 0xFFFF = none). Types 0x09-0x0D grant magicks
 * (grant = magick index = gambit action 0x00-0x50), type 0x0E grants technicks (grant = action 0x9E-0xB5).
 * Runtime-confirmed: Penelo's "Time Magick" purchase set node 218 (type 0x0D, 100 LP, Reflectga + Slowga).
 */
final class LicenceNodes {
    static final String CONTRACT = "ffxii.licnodes.v1";
    static final int MAX_BYTES = 64 * 1024;
    final int count, stride;
    private final byte[] raw;

    private LicenceNodes(int count, int stride, byte[] raw) { this.count = count; this.stride = stride; this.raw = raw; }

    static LicenceNodes parse(Map<String, Object> o) {
        if (o == null || !CONTRACT.equals(Json.str(o, "contract"))) return null;
        Long c = Json.lng(o, "count"), s = Json.lng(o, "stride");
        byte[] raw = Gambits.hexBytes(Json.str(o, "records"));
        if (c == null || s == null || raw == null || c < 1 || c > 1024 || s < 24 || s > 64 || raw.length != c * s) return null;
        return new LicenceNodes(c.intValue(), s.intValue(), raw);
    }

    int type(int node) { return raw[node * stride + 5] & 0xFF; }

    /** (0.23.0) Rule byte +6 (Licence_Rules_Trace): 0 ordinary, 1 party-exclusive, 2 Quickening, 3 Esper. */
    int rule(int node) { return raw[node * stride + 6] & 0xFF; }

    int cost(int node) { return raw[node * stride + 4] & 0xFF; }

    /** (0.19.0) Equipment: any node this member owns grants the ID, whatever its type (Gear_Optimize_Trace: 0x2F8FA0). */
    boolean equipLicensed(Build.Member m, int id) {
        for (int n = 0; n < count; n++) if (m.owns(n) && grants(n, id)) return true;
        return false;
    }

    /** (0.21.0) The node's non-empty grants (up to eight u16 at +8). */
    java.util.List<Integer> grantList(int node) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (int k = 0; k < 8; k++) {
            int o = node * stride + 8 + 2 * k;
            int v = (raw[o] & 0xFF) | (raw[o + 1] & 0xFF) << 8;
            if (v != 0xFFFF) out.add(v);
        }
        return out;
    }

    boolean grants(int node, int id) {
        for (int k = 0; k < 8; k++) {
            int o = node * stride + 8 + 2 * k;
            int v = (raw[o] & 0xFF) | (raw[o + 1] & 0xFF) << 8;
            if (v == id) return true;
        }
        return false;
    }

    /** True if this member owns a licence that lets them use gambit action a (magick or technick). */
    boolean licensed(Build.Member m, int a) {
        boolean magick = a <= 0x50, technick = a >= 0x9E && a <= 0x9E + 0x17;
        if (!magick && !technick) return false;
        for (int n = 0; n < count; n++) {
            int t = type(n);
            if ((magick ? t >= 0x09 && t <= 0x0D : t == 0x0E) && grants(n, a) && m.owns(n)) return true;
        }
        return false;
    }
}
