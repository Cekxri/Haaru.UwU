package android.os;

import java.util.HashMap;
import java.util.Map;

public final class ServiceManager {
    private static final Map<String, IBinder> SERVICES = new HashMap<String, IBinder>();
    private ServiceManager() {}
    public static IBinder getService(String name) { return SERVICES.get(name); }
    public static void register(String name, IBinder service) { SERVICES.put(name, service); }
    public static void clear() { SERVICES.clear(); }
}

