package compat;
import android.os.PersistableBundle;
import java.util.List;
public final class LegacyServices {
    public interface CarrierAidl { PersistableBundle getConfigForSubId(int id, String caller); }
    public interface SubAidl {
        LegacyInfo getActiveSubscriptionInfo(int id, String caller);
        List<LegacyInfo> getActiveSubscriptionInfoList(String caller);
        List<LegacyInfo> getAllSubInfoList(String caller);
    }
    public static class Carrier implements CarrierAidl {
        public PersistableBundle result;
        public PersistableBundle getConfigForSubId(int id, String caller) { return result; }
    }
    public static class SubscriptionController implements SubAidl {
        public LegacyInfo info;
        public List<LegacyInfo> list;
        public LegacyInfo getActiveSubscriptionInfo(int id, String caller) { return info; }
        public List<LegacyInfo> getActiveSubscriptionInfoList(String caller) { return list; }
        public List<LegacyInfo> getAllSubInfoList(String caller) { return list; }
    }
}
