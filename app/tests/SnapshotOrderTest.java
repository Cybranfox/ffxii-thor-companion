package local.thor.ffxiicompanion;
public final class SnapshotOrderTest {
    static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) {
        check(SnapshotOrder.newer(2000, 1, 1000, 900)); // new game beats previous high seq
        check(!SnapshotOrder.newer(1000, 900, 2000, 1)); // old exit cannot replace new run
        check(SnapshotOrder.newer(2000, 2, 2000, 1)); // same timestamp, same-run order
        check(!SnapshotOrder.newer(2000, 1, 2000, 2));
        check(!SnapshotOrder.newer(2000, 2, 2000, 2)); // identical snapshot
        check(SnapshotOrder.newer(3000, 3, 2000, 2)); // normal heartbeat
        System.out.println("6 snapshot-order cases passed");
    }
}