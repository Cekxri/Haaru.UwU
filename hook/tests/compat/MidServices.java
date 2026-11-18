package compat;
import android.os.PersistableBundle;
import android.telephony.SubscriptionInfo;
import java.util.List;
/** Middle generation: feature-aware CarrierConfig, a two-string active list, and an inherited list getter. */
public final class MidServices {
    public interface CarrierAidl extends LegacyServices.CarrierAidl {
        PersistableBundle getConfigForSubIdWithFeature(int id, String caller, String feature);
    }
    public interface SubAidl {
        SubscriptionInfo getActiveSubscriptionInfo(int id, String caller, String feature);
        List<SubscriptionInfo> getActiveSubscriptionInfoList(String caller, String feature);
        List<SubscriptionInfo> getAllSubInfoList(String caller);
    }
    public static class Carrier extends LegacyServices.Carrier implements CarrierAidl {
        public PersistableBundle getConfigForSubIdWithFeature(int id, String caller, String feature) { return result; }
    }
    public static class Parent {
        public List<SubscriptionInfo> list;
        public List<SubscriptionInfo> getAllSubInfoList(String caller) { return list; }
    }
    public static class Service extends Parent implements SubAidl {
        public SubscriptionInfo info;
        public SubscriptionInfo getActiveSubscriptionInfo(int id, String caller, String feature) { return info; }
        public List<SubscriptionInfo> getActiveSubscriptionInfoList(String caller, String feature) { return list; }
    }
}
