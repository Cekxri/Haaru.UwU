package android.app;

import android.content.Context;
import android.os.Looper;

public final class ActivityThread {
    public static ActivityThread current;
    public static Context application, systemContext = new Context();
    public static int systemMainCalls;
    public static boolean nullSystemContext;
    public static RuntimeException initializationFailure;
    public static ActivityThread currentActivityThread() { return current; }
    public static Context currentApplication() { return application; }
    public static ActivityThread systemMain() {
        systemMainCalls++;
        if (initializationFailure != null) throw initializationFailure;
        if (Looper.myLooper() == null) throw new IllegalStateException("Cannot create ActivityThread before preparing a Looper");
        current = new ActivityThread();
        return current;
    }
    public Context getSystemContext() { return nullSystemContext ? null : systemContext; }
    public static void reset() {
        current = null; application = null; systemContext = new Context();
        systemMainCalls = 0; nullSystemContext = false; initializationFailure = null;
    }
}
