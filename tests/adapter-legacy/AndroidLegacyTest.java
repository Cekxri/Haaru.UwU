package com.github.haaru.ksu;
import android.os.*;
import com.android.internal.telephony.ICarrierConfigLoader;

public final class AndroidLegacyTest {
    private static void check(boolean condition) { if (!condition) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        android.os.Process.uid = 0; Build.VERSION.SDK_INT = 29;
        ICarrierConfigLoader.State state = new ICarrierConfigLoader.State();
        state.configuration.putBoolean("carrier_config_applied_bool", true);
        state.configuration.putString("sim_country_iso_override_string", "tw");
        ServiceManager.register("carrier_config", ICarrierConfigLoader.Stub.newBinder(state));
        AndroidBackend backend = new AndroidBackend();
        check(backend.read(3).country.equals("tw") && state.reads == 1);
        state.failure = new SecurityException("old API read denial");
        try { backend.read(3); throw new AssertionError(); }
        catch (SecurityException expected) { check(expected.getMessage().equals("old API read denial")); }
        check(state.writes == 0);
        for (java.lang.reflect.Method method : AndroidBackend.class.getDeclaredMethods()) check(!method.getName().equals("write"));
        state.failure = null; Build.VERSION.SDK_INT = 30;
        check(backend.read(3).country.equals("tw") && state.writes == 0);
        System.out.println("AndroidBackend API29 read-only legacy fixture passed (no platform setters)");
    }
}
