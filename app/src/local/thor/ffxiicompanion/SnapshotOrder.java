package local.thor.ffxiicompanion;

/** File-transport order only. Sequence resets on game launch, so file recency wins.
 * Shared-storage timestamps are a legacy fallback, not protocol rev2 session identity.
 * Equal timestamps use sequence; coarse timestamps can delay detection until the next heartbeat.
 */
final class SnapshotOrder {
    static boolean newer(long candidateTime, long candidateSeq, long retainedTime, long retainedSeq) {
        return candidateTime > retainedTime || (candidateTime == retainedTime && candidateSeq > retainedSeq);
    }
}