/* SPDX-License-Identifier: Apache-2.0 */
package org.haaru.smoke;

import java.lang.reflect.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.haaru.hook.GlobalHooks;

/** Synthetic app_process target. Never invokes the phone or a SIM API. */
public class EngineSmoke {
    static native boolean init();
    static native boolean hook(Method target, Object callback);
    static native boolean deoptimize(Method method);
    static int checks;
    public static class Fixture { public int read(int input) { return input; } }
    public static int caller(Fixture fixture, int value) { return fixture.read(value); }
    public static class Callback {
        public volatile Method backup;
        final boolean change;
        final AtomicInteger calls = new AtomicInteger();
        Callback(boolean change) { this.change = change; }
        public Object callback(Object[] args) throws Throwable {
            Method original;
            synchronized (this) { original = backup; }
            calls.incrementAndGet();
            try {
                Object result = original.invoke(args[0], Arrays.copyOfRange(args, 1, args.length));
                return change ? ((Integer)result) + 100 : result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
    static void check(boolean ok, String label) { checks++; if (!ok) throw new AssertionError(label); }
    public static void main(String[] args) throws Throwable {
        if (args.length != 1) throw new IllegalArgumentException("Expected synthetic test library");
        System.load(args[0]);
        check(init(), "real ART symbol resolution and LSPlant initialisation");
        Fixture fixture = new Fixture();
        int sum = 0; for (int i = 0; i < 50000; i++) sum += caller(fixture, 1);
        check(sum == 50000, "unhooked synthetic original");
        Callback reader = new Callback(true);
        check(hook(Fixture.class.getMethod("read", int.class), reader), "install actual LSPlant reader hook");
        check(deoptimize(EngineSmoke.class.getMethod("caller", Fixture.class, int.class)), "deoptimise caller of inlinable method");
        check(caller(fixture, 7) == 107 && reader.calls.get() > 0, "compiled caller dispatches hook and preserved backup");
        Thread[] threads = new Thread[8];
        final AtomicInteger errors = new AtomicInteger();
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                public void run() { for (int n = 0; n < 1000; n++) if (caller(fixture, n) != n + 100) errors.incrementAndGet(); }
            });
            threads[i].start();
        }
        for (Thread thread : threads) thread.join();
        check(errors.get() == 0, "concurrent real ART callback and backup publication");
        Class<?> looper = Class.forName("android.os.Looper");
        if (looper.getMethod("myLooper").invoke(null) == null) looper.getMethod("prepareMainLooper").invoke(null);
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method systemMain = activityThread.getDeclaredMethod("systemMain"); systemMain.setAccessible(true);
        Object thread = systemMain.invoke(null);
        Method contextGetter = activityThread.getDeclaredMethod("getSystemContext"); contextGetter.setAccessible(true);
        Object context = contextGetter.invoke(thread);
        Class<?> appType = Class.forName("android.app.Application");
        Method attach = appType.getDeclaredMethod("attach", Class.forName("android.content.Context")); attach.setAccessible(true);
        Callback callback = new Callback(false);
        check(hook(attach, callback), "install actual framework Application.attach hook");
        int callers = 0;
        for (Method caller : GlobalHooks.applicationCallers()) { if (deoptimize(caller)) callers++; }
        check(callers > 0, "deoptimise actual LoadedApk and Instrumentation callers");
        Object application = appType.getConstructor().newInstance();
        attach.invoke(application, context);
        check(callback.calls.get() == 1, "actual framework attach callback fires once");
        Field initial = activityThread.getDeclaredField("mInitialApplication"); initial.setAccessible(true);
        initial.set(thread, null);
        check(GlobalHooks.findApplicationContext() == null, "Context fallback safely waits before application publication");
        initial.set(thread, application);
        check(GlobalHooks.findApplicationContext() == application, "production Context fallback works without an attach event");
        System.out.println("ART_SMOKE_PASS " + checks + " checks; synthetic process only; no phone/SIM calls");
        System.exit(0);
    }
}
