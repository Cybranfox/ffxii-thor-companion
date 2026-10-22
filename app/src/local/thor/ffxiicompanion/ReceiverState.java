package local.thor.ffxiicompanion;

import java.util.Map;

/**
 * Pure receiver logic for the file-ab transport (protocol v1): picks the newest valid slot, tracks
 * the game session, and works out freshness per data family. No Android types, so the JVM tests in
 * tests/ drive it directly with a fake clock.
 *
 * Session identity: the deployed script has no session id, so a new session is inferred when the
 * newest file restarts its sequence or a run follows an exit snapshot. If a producer later sends the
 * proposed data.session.id, that wins.
 */
final class ReceiverState {
    /** The script heartbeats every 2 s. */
    static final long STALE_MS = 5000;
    /** A party sample older than this (game clock, relative to its snapshot) is stale. */
    static final long PARTY_STALE_MS = 3000;
    /**
     * 0.17.0: live party data from logger 0.5.9 (contract ffxii.party.v1, Party_Vitals_Trace_2026-10-21.md) is shown when
     * the game build is verified and the capability is "on" or "probe" ("probe" until it has been compared with the game;
     * the card says so). Any other live party block is ignored; synthetic fixtures only in fixture mode.
     */
    static final boolean LIVE_PARTY_ENABLED = true;
    static final String PARTY_CONTRACT = "ffxii.party.v1";

    enum Transport { NO_DATA, LIVE, STALE, NO_SIGNAL, GAME_CLOSED }
    enum FamilyState { LIVE, STALE, CLOSED, MISSING, DISABLED }

    static final class Family {
        final FamilyState state;
        final long ageMs;      // -1 when unknown
        final String reason;
        Family(FamilyState state, long ageMs, String reason) {
            this.state = state; this.ageMs = ageMs; this.reason = reason;
        }
    }

    static final class View {
        Transport transport = Transport.NO_DATA;
        long transportAgeMs = -1;
        Snapshot current;          // newest accepted snapshot of this session
        Snapshot location;         // newest snapshot of this session that carried map data
        Family locationFamily = new Family(FamilyState.MISSING, -1, "no map data yet");
        Party party;               // non-null only when the party family may be shown
        Family partyFamily = new Family(FamilyState.DISABLED, -1, "party feed not available yet");
        boolean partyProbe;        // live party shown under capability "probe" (not yet compared with the game)
        boolean synthetic;         // any shown data came from a fixture
        int session;               // 1-based count of sessions seen by this receiver
        int malformedReads;        // slots that existed but did not parse, this poll
    }

    final boolean fixtureMode;

    private Snapshot current, location, party;
    private long lastChangeMono;
    private int session;

    ReceiverState(boolean fixtureMode) { this.fixtureMode = fixtureMode; }

    /**
     * One poll. slotA/slotB are the parsed files (null when missing or malformed); malformed counts
     * slots that existed but did not parse; filesPresent is false when neither slot file exists.
     */
    View update(Snapshot slotA, Snapshot slotB, int malformed, boolean filesPresent, long nowMono, long nowWall) {
        Snapshot newest = pick(slotA, slotB);
        if (newest != null && (current == null
                || SnapshotOrder.newer(newest.mtime, newest.seq, current.mtime, current.seq))) {
            accept(newest, nowMono, nowWall);
            // An exit snapshot has no map; the other slot usually holds this run's last heartbeat.
            Snapshot other = newest == slotA ? slotB : slotA;
            if (location == null && other != null && other.map() != null && sameRunBefore(other, newest)) location = other;
        }

        View v = new View();
        v.malformedReads = malformed;
        v.session = session;
        if (current == null) return v;  // NO_DATA

        v.current = current;
        v.location = location;
        v.transportAgeMs = Math.max(0, nowMono - lastChangeMono);
        if (current.exiting) v.transport = Transport.GAME_CLOSED;
        else if (!filesPresent) v.transport = Transport.NO_SIGNAL;
        else if (v.transportAgeMs > STALE_MS) v.transport = Transport.STALE;
        else v.transport = Transport.LIVE;

        v.locationFamily = locationFamily(v);
        partyFamily(v);
        v.synthetic = current.synthetic || (v.party != null && party.synthetic);
        return v;
    }

    private static Snapshot pick(Snapshot a, Snapshot b) {
        if (a == null) return b;
        if (b == null) return a;
        return SnapshotOrder.newer(b.mtime, b.seq, a.mtime, a.seq) ? b : a;
    }

    private void accept(Snapshot s, long nowMono, long nowWall) {
        if (isNewSession(s)) {
            session++;
            location = null;
            party = null;
        }
        // On first sight, age the snapshot by its file time so an old exit/heartbeat is not shown as live.
        long wallAge = current == null ? Math.max(0, nowWall - s.mtime) : 0;
        lastChangeMono = nowMono - wallAge;
        current = s;
        if (s.map() != null) location = s;
        if (s.party() != null) party = s;
    }

    /** True when older is an earlier snapshot of the same run as newer (inferred without a session id). */
    private static boolean sameRunBefore(Snapshot older, Snapshot newer) {
        if (older.sessionId != null && newer.sessionId != null) return older.sessionId.equals(newer.sessionId);
        return !older.exiting && older.seq < newer.seq && older.mtime <= newer.mtime;
    }

    private boolean isNewSession(Snapshot s) {
        if (current == null) return true;
        if (s.sessionId != null && current.sessionId != null) return !s.sessionId.equals(current.sessionId);
        if (s.seq < current.seq) return true;          // the script restarts seq at 1 per game start
        return current.exiting && !s.exiting;          // a run after a clean exit
    }

    private Family locationFamily(View v) {
        if (location == null) return new Family(FamilyState.MISSING, -1, "no map data yet");
        if (v.transport == Transport.GAME_CLOSED) return new Family(FamilyState.CLOSED, -1, "last known before exit");
        if (location != current) return new Family(FamilyState.STALE, v.transportAgeMs, "newest snapshot has no map data");
        if (v.transport == Transport.LIVE) return new Family(FamilyState.LIVE, v.transportAgeMs, null);
        return new Family(FamilyState.STALE, v.transportAgeMs, v.transport == Transport.NO_SIGNAL ? "no signal" : "no updates");
    }

    private void partyFamily(View v) {
        if (v.transport == Transport.GAME_CLOSED) {
            v.partyFamily = new Family(FamilyState.CLOSED, -1, "game closed; party values cleared");
            return;
        }
        if (party == null) {
            v.partyFamily = new Family(FamilyState.DISABLED, -1, "party feed not available yet");
            return;
        }
        String cap = party.capability("party");
        boolean allowed = party.synthetic ? fixtureMode
                : LIVE_PARTY_ENABLED && ("on".equals(cap) || "probe".equals(cap))
                  && PARTY_CONTRACT.equals(Json.str(party.party(), "contract")) && Reveal.buildVerified(party.hello());
        if (!allowed) {
            v.partyFamily = new Family(FamilyState.DISABLED, -1, party.synthetic
                    ? "synthetic party data outside fixture mode ignored"
                    : "party data not verified; ignored");
            return;
        }
        Map<String, Object> raw = party.party();
        String status = Json.str(raw, "status");
        if (!party.synthetic && !"ok".equals(status)) {
            v.partyFamily = new Family(FamilyState.MISSING, -1, "save_not_ready".equals(status) ? "no save loaded" : "party " + status);
            return;
        }
        v.partyProbe = !party.synthetic && "probe".equals(cap);
        Party p = Party.parse(raw);
        if (p == null) {
            v.partyFamily = new Family(FamilyState.MISSING, -1, "party data malformed");
            return;
        }
        v.party = p;
        long sampleAge = -1;
        if (p.observedClockUs != null && party.clockUs != null && party.clockUs >= p.observedClockUs) {
            sampleAge = (party.clockUs - p.observedClockUs) / 1000;
        }
        long age = sampleAge < 0 ? -1 : sampleAge + (party == current ? v.transportAgeMs : 0);
        if (party != current) {
            v.partyFamily = new Family(FamilyState.STALE, age, "newest snapshot has no party data");
        } else if (sampleAge < 0) {
            v.partyFamily = new Family(FamilyState.STALE, -1, "party sample time unknown");
        } else if (sampleAge > PARTY_STALE_MS) {
            v.partyFamily = new Family(FamilyState.STALE, age, "party sampler not updating");
        } else if (v.transport != Transport.LIVE) {
            v.partyFamily = new Family(FamilyState.STALE, age, v.transport == Transport.NO_SIGNAL ? "no signal" : "no updates");
        } else {
            v.partyFamily = new Family(FamilyState.LIVE, age, null);
        }
    }
}
