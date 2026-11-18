package android.os;

public final class Looper {
    private static Looper current, main;
    public static int prepareCalls, prepareMainCalls;
    public static Looper myLooper() { return current; }
    public static Looper getMainLooper() { return main; }
    public static void prepare() {
        if (current != null) throw new IllegalStateException("Looper already prepared");
        prepareCalls++; current = new Looper();
    }
    public static void prepareMainLooper() {
        if (main != null) throw new IllegalStateException("Main Looper already prepared");
        prepareMainCalls++; current = main = new Looper();
    }
    public static void reset() { current = main = null; prepareCalls = prepareMainCalls = 0; }
}
