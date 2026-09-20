package android.os;

/** 試験用の偽物。時計は Clock が進める。 */
public final class SystemClock {
    public static long nowNs;
    public static long elapsedRealtimeNanos() { return nowNs; }
    public static long uptimeMillis() { return nowNs / 1000000L; }
}
