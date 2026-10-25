package local.thor.ffxiicompanion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Party family as proposed in draft/android/PROPOSAL_party_family.md. No live producer exists yet; only
 * synthetic fixtures use it. Missing or invalid values stay unknown: nothing is defaulted from vanilla data.
 */
final class Party {
    /** The strip shows at most four cards (3 active + guest or 4th member under Insurgent's Companions). */
    static final int MAX_SHOWN = 4;

    static final class Vital {
        final long cur, max;
        Vital(long cur, long max) { this.cur = cur; this.max = max; }
    }

    /** 0.16.0: how worried a bar should look: 0 fine, 1 below half, 2 below a quarter, 3 at zero. */
    static int tone(Vital v) {
        if (v == null || v.max <= 0) return 0;
        if (v.cur <= 0) return 3;
        if (v.cur * 4 < v.max) return 2;
        if (v.cur * 2 < v.max) return 1;
        return 0;
    }

    /**
     * 0.16.0: the name to show. Guests (no name from the game script) are named from the user's game data by member ID;
     * the pack is only asked for when it's needed. Without a name anywhere: "Member N".
     */
    static String displayName(Member m, java.util.function.Supplier<Reference> ref) {
        if (m.named || m.member == null) return m.name;
        Reference r = ref.get();
        String n = r != null ? r.characterName(m.member) : null;
        return n != null ? n : m.name;
    }

    /** Status bits never shown as chips: KO (the row says it), HP Critical (the bar shows it), X-Zone (internal). */
    static final int HIDDEN_STATUS = (1 << 0) | (1 << 29) | (1 << 31);

    static final class Chip {
        final int bit;
        final String name;
        final boolean adverse;
        Chip(int bit, String name, boolean adverse) { this.bit = bit; this.name = name; this.adverse = adverse; }
    }

    /**
     * 0.16.0: a member's status chips, bad effects first, both in the game's bit order. Names and the bad/good split
     * come from the user's game data (reference.json statusNames/statusDisplay); a bit without both is left out, never
     * guessed. No status data (older script or pack): no chips.
     */
    static List<Chip> chips(Long status, java.util.function.Supplier<Reference> ref) {
        List<Chip> bad = new ArrayList<>(), good = new ArrayList<>();
        if (status == null || (status & ~(long) HIDDEN_STATUS & 0xFFFFFFFFL) == 0) return bad;
        Reference r = ref.get();
        if (r == null) return bad;
        for (int bit = 0; bit < 32; bit++) {
            if ((status & (1L << bit)) == 0 || (HIDDEN_STATUS & (1 << bit)) != 0) continue;
            String name = r.statusName(bit);
            Boolean adverse = r.adverse(bit);
            if (name == null || adverse == null) continue;
            (adverse ? bad : good).add(new Chip(bit, name, adverse));
        }
        bad.addAll(good);
        return bad;
    }

    static final class Member {
        int slot;
        String name;      // as reported by the producer (modded text); null = unknown
        Integer member;   // the game's member ID, or null
        boolean named;    // the producer sent a name (the game script names only the six main characters)
        String role;      // "active", "guest", or whatever the producer sent
        boolean leader;
        Vital hp, mp;     // null = unavailable or invalid
        boolean hpInvalid, mpInvalid;
        Boolean ko;       // null = unknown; only an explicit flag sets KO
        Long status;      // 0.16.0: the game's status bits (u32[M+0x3C] | u32[M+0x64]); null = older script (unknown)
    }

    final List<Member> members;
    final int dropped;          // members beyond MAX_SHOWN
    final Long observedClockUs;

    private Party(List<Member> members, int dropped, Long observedClockUs) {
        this.members = members;
        this.dropped = dropped;
        this.observedClockUs = observedClockUs;
    }

    static Party parse(Map<String, Object> p) {
        if (p == null) return null;
        List<Object> raw = Json.arr(p, "members");
        Object m0 = p.get("members");
        if (raw == null && m0 instanceof Map && ((Map<?, ?>) m0).isEmpty()) raw = new ArrayList<>();   // Lua's empty table
        if (raw == null) return null;
        List<Member> out = new ArrayList<>();
        for (Object o : raw) {
            if (!(o instanceof Map)) continue;
            Member m = new Member();
            Long slot = Json.lng(o, "slot");
            m.slot = slot != null ? slot.intValue() : out.size();
            m.name = Json.str(o, "name");
            m.named = m.name != null;
            Long id = Json.lng(o, "member");
            m.member = id != null ? id.intValue() : null;
            if (m.name == null && id != null) m.name = "Member " + id;   // logger 0.5.9 names only the six established IDs
            String role = Json.str(o, "role");
            m.role = role != null ? role : "active";
            m.leader = Boolean.TRUE.equals(Json.bool(o, "leader"));
            m.ko = Json.bool(o, "ko");
            Long st = Json.lng(o, "status");
            m.status = st != null && st >= 0 && st <= 0xFFFFFFFFL ? st : null;
            if (m.ko == null && m.status != null) m.ko = (m.status & 1) != 0;   // bit 0 is the game's own KO
            Object hp = ((Map<?, ?>) o).get("hp"), mp = ((Map<?, ?>) o).get("mp");
            m.hp = vital(hp);
            m.hpInvalid = hp != null && m.hp == null;
            m.mp = vital(mp);
            m.mpInvalid = mp != null && m.mp == null;
            out.add(m);
        }
        int dropped = Math.max(0, out.size() - MAX_SHOWN);
        List<Member> shown = out.size() > MAX_SHOWN ? new ArrayList<>(out.subList(0, MAX_SHOWN)) : out;
        return new Party(Collections.unmodifiableList(shown), dropped, Json.lng(p, "observedClockUs"));
    }

    /** {cur,max} with 0 <= cur <= max and max > 0; anything else is unavailable (never clamped). */
    static Vital vital(Object v) {
        if (!(v instanceof Map)) return null;
        Long cur = Json.lng(v, "cur"), max = Json.lng(v, "max");
        if (cur == null || max == null || max <= 0 || cur < 0 || cur > max) return null;
        return new Vital(cur, max);
    }
}
