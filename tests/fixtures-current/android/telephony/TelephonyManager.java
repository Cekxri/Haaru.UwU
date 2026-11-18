package android.telephony;

import java.util.HashMap;
import java.util.Map;
import android.content.Context;

public final class TelephonyManager {
    public static int activeModems = 2;
    public static final Map<Integer, String> SIM_COUNTRIES = new HashMap<Integer, String>();
    private final int subId;
    private final Context context;
    public static boolean staticCountryUnsupported, staticPhoneUnsupported;
    public static RuntimeException staticCountryFailure;
    public static int defaultCalls, staticCountryCalls, staticPhoneCalls, scopedCalls;
    private TelephonyManager() { subId = -1; context = null; }
    public TelephonyManager(Context value) { this(value, -1); }
    private TelephonyManager(Context value, int id) {
        if (value == null) throw new NullPointerException("Attempt to invoke virtual method 'android.content.Context android.content.Context.getApplicationContext()' on a null object reference");
        Context application = value.getApplicationContext();
        context = application == null ? value : application;
        subId = id;
    }
    public static TelephonyManager getDefault() { defaultCalls++; return new TelephonyManager(); }
    public static String getSimCountryIso(int id) {
        staticCountryCalls++;
        if (staticCountryFailure != null) throw staticCountryFailure;
        if (staticCountryUnsupported) throw new UnsupportedOperationException("Subscription getter unavailable");
        if (SubscriptionManager.invalidPhoneCache) return "";
        return country(id);
    }
    public static String getSimCountryIsoForPhone(int phoneId) {
        staticPhoneCalls++;
        if (staticPhoneUnsupported) throw new UnsupportedOperationException("Phone getter unavailable");
        Integer id = SubscriptionManager.SLOT_IDS.get(phoneId);
        return id == null ? "" : country(id.intValue());
    }
    public TelephonyManager createForSubscriptionId(int id) { scopedCalls++; return new TelephonyManager(context, id); }
    public String getSimCountryIso() {
        return country(subId);
    }
    private static String country(int id) {
        String value = SIM_COUNTRIES.get(id);
        return value == null ? "" : value;
    }
    public int getActiveModemCount() { return activeModems; }
    public int getPhoneCount() { return activeModems; }
    public static void reset() {
        SIM_COUNTRIES.clear(); activeModems = 2;
        staticCountryUnsupported = staticPhoneUnsupported = false; staticCountryFailure = null;
        defaultCalls = staticCountryCalls = staticPhoneCalls = scopedCalls = 0;
    }
}

