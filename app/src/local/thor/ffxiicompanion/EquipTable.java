package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * Live equipment table (logger 0.5.12 live/equipment.json, contract ffxii.equipment.v1): battle-pack section 13 as the game
 * loaded it, 0x34-byte records, ID = 0x1000 + index. Only the fields the game's Optimize reads (Gear_Optimize_Trace_2026-10-21.md):
 * +0x06 tie-break, +0x07 slot (>>5) and noLicense (0x10), +0x09 category, +0x11 required offhand category,
 * +0x18 range / evade / defence, +0x19 formula / magick evade / magick resist, +0x1A attack (weapon, ammo).
 */
final class EquipTable {
    static final String CONTRACT = "ffxii.equipment.v1";
    final int count, stride;
    private final byte[] raw;

    private EquipTable(int count, int stride, byte[] raw) { this.count = count; this.stride = stride; this.raw = raw; }

    static EquipTable parse(Map<String, Object> o) {
        if (o == null || !CONTRACT.equals(Json.str(o, "contract"))) return null;
        Long c = Json.lng(o, "count"), s = Json.lng(o, "stride");
        if (c == null || s == null || c < 1 || c > 1024 || s < 0x2C || s > 64) return null;
        byte[] raw = Gambits.hexBytes(Json.str(o, "records"));
        if (raw == null || raw.length != c * s) return null;
        return new EquipTable(c.intValue(), s.intValue(), raw);
    }

    boolean has(int id) { return id >= 0x1000 && id < 0x1000 + count; }
    private int u8(int id, int off) { return raw[(id - 0x1000) * stride + off] & 0xFF; }

    int slot(int id) { return u8(id, 7) >> 5; }
    boolean noLicense(int id) { return (u8(id, 7) & 0x10) != 0; }
    int tie(int id) { return u8(id, 6); }
    int category(int id) { return u8(id, 9); }
    int offhandReq(int id) { return u8(id, 0x11); }
    int b18(int id) { return u8(id, 0x18); }
    int b19(int id) { return u8(id, 0x19); }
    int attack(int id) { return u8(id, 0x1A); }
}
