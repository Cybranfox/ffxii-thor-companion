package local.thor.ffxiicompanion;

public class AutoClosePolicyTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        AutoClosePolicy p = new AutoClosePolicy();
        check(!p.update(false,true,false,0)); // old exit on cold launch
        check(!p.update(false,true,false,10000));
        check(!p.update(true,false,false,10001));
        check(!p.update(false,false,false,100000)); // paused/stale or no signal
        check(!p.update(false,true,false,100001));
        check(!p.update(false,true,false,101000));
        check(p.update(false,true,false,101501));
        p = new AutoClosePolicy();
        p.update(true,false,false,0);
        p.update(false,true,false,1);
        check(!p.update(true,false,false,2000)); // new live data cancels close
        check(!p.update(false,true,false,2001));
        check(!p.update(false,true,true,9000)); // fixtures never close
        check(!p.update(false,true,false,9001)); // synthetic cancels pending deadline
        System.out.println("PASS AutoClosePolicy: old exit, stale, delayed exit, restart, synthetic");
    }
}
