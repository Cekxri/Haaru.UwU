package android.content;

import android.telephony.TelephonyManager;

/** Models the Context accesses made by the real Android TelephonyManager constructor. */
public class Context {
    public static int applicationReads, phoneServiceReads;
    public boolean phoneServiceAvailable = true;
    public RuntimeException applicationFailure;
    public Context getApplicationContext() {
        applicationReads++;
        if (applicationFailure != null) throw applicationFailure;
        return this;
    }
    public Object getSystemService(String name) {
        if (!"phone".equals(name)) return null;
        phoneServiceReads++;
        return phoneServiceAvailable ? new TelephonyManager(this) : null;
    }
    public android.content.pm.PackageManager getPackageManager() { return android.content.pm.PackageManager.INSTANCE; }
    public static void resetCounters() { applicationReads = 0; phoneServiceReads = 0; }
}
