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

    static final class Member {
        int slot;
        String name;      // as reported by the producer (modded text); null = unknown
        String role;      // "active", "guest", or whatever the producer sent
        boolean leader;
        Vital hp, mp;     // null = unavailable or invalid
        boolean hpInvalid, mpInvalid;
        Boolean ko;       // null = unknown; only an explicit flag sets KO
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
            Long id = Json.lng(o, "member");
            if (m.name == null && id != null) m.name = "Member " + id;   // logger 0.5.9 names only the six established IDs
            String role = Json.str(o, "role");
            m.role = role != null ? role : "active";
            m.leader = Boolean.TRUE.equals(Json.bool(o, "leader"));
            m.ko = Json.bool(o, "ko");
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
